package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.fulfillment.api.PickupReservationOperations
import io.github.kdh949.beanflow.inventory.api.StockReservationOperations
import io.github.kdh949.beanflow.loyalty.api.PointReservationOperations
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.ordering.api.ReservationExpiryUseCase
import io.github.kdh949.beanflow.ordering.api.StoredHttpResponse
import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.payment.api.ApplyExternalPaymentResultCommand
import io.github.kdh949.beanflow.payment.api.ExternalPaymentOperations
import io.github.kdh949.beanflow.payment.api.ExternalPaymentView
import io.github.kdh949.beanflow.payment.api.PaymentPreparation
import io.github.kdh949.beanflow.payment.api.PaymentPreparationState
import io.github.kdh949.beanflow.payment.api.PaymentReconciliationOperations
import io.github.kdh949.beanflow.payment.api.ClaimedPaymentReconciliation
import io.github.kdh949.beanflow.payment.api.PrepareExternalPaymentCommand
import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import io.github.kdh949.beanflow.payment.api.ProviderTransportFailure
import io.github.kdh949.beanflow.promotion.api.CouponReservationOperations
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.ReservationTransitionReport
import io.github.kdh949.beanflow.shared.api.ReservationTransitionResult
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class PaymentConfirmationService(
	private val preparationTransaction: PaymentPreparationTransaction,
	private val resultTransaction: PaymentResultTransaction,
	private val paymentOperations: ExternalPaymentOperations,
	private val correlationIdSource: CorrelationIdSource,
	private val objectMapper: ObjectMapper,
	private val meterRegistry: MeterRegistry,
	private val clock: Clock,
) {
	private val logger = LoggerFactory.getLogger(javaClass)

	fun confirm(
		customerId: UUID,
		orderId: UUID,
		paymentMethodId: UUID,
		idempotencyKey: String,
	): StoredHttpResponse {
		val now = clock.instant()
		val command = PrepareExternalPaymentCommand(
			actorId = customerId,
			orderId = orderId,
			paymentMethodId = paymentMethodId,
			requestedAmountKrw = preparationTransaction.requestedAmount(customerId, orderId),
			idempotencyKey = idempotencyKey,
			payloadHash = CanonicalPaymentPayload.hash(orderId, paymentMethodId),
			correlationId = correlationIdSource.currentOrCreate(),
			now = now,
		)
		return when (val preparation = preparationTransaction.prepare(command)) {
			is OrderPaymentPreparation.Expired ->
				throw DomainFailure(FailureCode.RESERVATION_EXPIRED, "Order reservation lease has expired")
			is OrderPaymentPreparation.Ready -> handle(preparation.payment, customerId, orderId)
		}
	}

	private fun handle(
		preparation: PaymentPreparation,
		customerId: UUID,
		orderId: UUID,
	): StoredHttpResponse =
		when (preparation.state) {
			PaymentPreparationState.IN_PROGRESS ->
				error(
					FailureCode.IDEMPOTENCY_REQUEST_IN_PROGRESS,
					"An identical payment request is still processing",
					preparation.current?.correlationId ?: correlationIdSource.currentOrCreate(),
				)
			PaymentPreparationState.CURRENT ->
				preparation.responseStatus?.let { status ->
					preparation.responseBody?.let { body ->
						StoredHttpResponse(status, body, replay = true)
					}
				} ?: currentResponse(requireNotNull(preparation.current), replay = true)
			PaymentPreparationState.ACQUIRED -> {
				val sample = Timer.start(meterRegistry)
				val result = try {
					paymentOperations.requestProviderApproval(preparation.paymentId)
				} catch (failure: ProviderTransportFailure) {
					logger.warn(
						"payment_approval paymentId={} outcome=UNKNOWN reason=PROVIDER_CALL_FAILED",
						preparation.paymentId,
					)
					ProviderPaymentResult.Unknown("PROVIDER_CALL_FAILED")
				} catch (failure: DataAccessException) {
					throw DomainFailure(
						FailureCode.DEPENDENCY_UNAVAILABLE,
						"Payment approval request could not be prepared",
					)
				} finally {
					sample.stop(meterRegistry.timer("beanflow.payment.approval.duration"))
				}
				val outcome = when (result) {
					is ProviderPaymentResult.Approved -> "approved"
					is ProviderPaymentResult.Declined -> "declined"
					is ProviderPaymentResult.Unknown -> "unknown"
				}
				meterRegistry.counter("beanflow.payment.approval.attempts", "outcome", outcome).increment()
				try {
					resultTransaction.apply(customerId, orderId, preparation.paymentId, result, clock.instant())
				} catch (failure: DataAccessException) {
					throw DomainFailure(
						FailureCode.DEPENDENCY_UNAVAILABLE,
						"Payment result could not be committed and will be reconciled",
					)
				}
			}
		}

	private fun currentResponse(view: ExternalPaymentView, replay: Boolean): StoredHttpResponse {
		val status = when (view.approvalState) {
			"APPROVED" -> 200
			"FAILED" -> 422
			"UNKNOWN", "RECONCILING", "MANUAL_REVIEW" -> 202
			else -> 409
		}
		if (status == 422) {
			return error(
				FailureCode.PAYMENT_DECLINED,
				"Provider declined the payment",
				view.correlationId,
				replay,
			)
		}
		return StoredHttpResponse(
			status = status,
			body = objectMapper.writeValueAsString(view.toResponse()),
			replay = replay,
		)
	}

	private fun error(
		code: FailureCode,
		message: String,
		correlationId: String,
		replay: Boolean = false,
	): StoredHttpResponse =
		StoredHttpResponse(
			status = when (code) {
				FailureCode.PAYMENT_DECLINED -> 422
				else -> 409
			},
			body = objectMapper.writeValueAsString(ErrorResponse(code.name, message, correlationId)),
			replay = replay,
		)
}

internal sealed interface OrderPaymentPreparation {
	data class Ready(val payment: PaymentPreparation) : OrderPaymentPreparation
	data object Expired : OrderPaymentPreparation
}

@Service
internal class PaymentPreparationTransaction(
	private val orderRepository: OrderJpaRepository,
	private val expiryUseCase: ReservationExpiryUseCase,
	private val paymentOperations: ExternalPaymentOperations,
) {
	@Transactional(readOnly = true)
	fun requestedAmount(customerId: UUID, orderId: UUID): Long {
		val order = orderRepository.findById(orderId).orElse(null)
			?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Order was not found")
		if (order.customerId != customerId) {
			throw DomainFailure(FailureCode.ACCESS_DENIED, "Order belongs to another customer")
		}
		return order.payableKrw
	}

	@Transactional
	fun prepare(command: PrepareExternalPaymentCommand): OrderPaymentPreparation {
		val order = orderRepository.findLockedById(command.orderId)
			?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Order was not found")
		if (order.customerId != command.actorId) {
			throw DomainFailure(FailureCode.ACCESS_DENIED, "Order belongs to another customer")
		}
		if (order.payableKrw <= 0) {
			throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, "Order has no external payable amount")
		}
		val existing = paymentOperations.existing(command)
		val deadline = order.reservationExpiresAt
		if (order.state == OrderState.PENDING_PAYMENT && deadline != null && !command.now.isBefore(deadline)) {
			expiryUseCase.expireIfDue(command.orderId, command.now)
			if (existing != null) {
				return OrderPaymentPreparation.Ready(existing)
			}
			return OrderPaymentPreparation.Expired
		}
		if (existing != null) {
			return OrderPaymentPreparation.Ready(existing)
		}
		if (order.state != OrderState.PENDING_PAYMENT || order.payableKrw <= 0 || deadline == null) {
			throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, "Order is not eligible for external payment")
		}
		if (command.requestedAmountKrw != order.payableKrw) {
			throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, "Order payment amount changed")
		}
		return OrderPaymentPreparation.Ready(paymentOperations.prepare(command))
	}
}

@Service
internal class PaymentResultTransaction(
	private val orderRepository: OrderJpaRepository,
	private val expiryUseCase: ReservationExpiryUseCase,
	private val pickupOperations: PickupReservationOperations,
	private val stockOperations: StockReservationOperations,
	private val couponOperations: CouponReservationOperations,
	private val pointOperations: PointReservationOperations,
	private val paymentOperations: ExternalPaymentOperations,
	private val reconciliationOperations: PaymentReconciliationOperations,
	private val auditOperations: AuditRecordOperations,
	private val objectMapper: ObjectMapper,
	private val meterRegistry: MeterRegistry,
) {
	@Transactional
	fun apply(
		customerId: UUID,
		orderId: UUID,
		paymentId: UUID,
		result: ProviderPaymentResult,
		now: Instant,
	): StoredHttpResponse =
		when (result) {
			is ProviderPaymentResult.Unknown -> unknown(paymentId, orderId, result, now)
			is ProviderPaymentResult.Declined -> decline(customerId, orderId, paymentId, result, now)
			is ProviderPaymentResult.Approved -> approve(customerId, orderId, paymentId, result, now)
		}

	@Transactional
	fun reconcileUnknown(
		work: ClaimedPaymentReconciliation,
		result: ProviderPaymentResult.Unknown,
		now: Instant,
	) {
		val body = confirmationBody(
			work.paymentId,
			work.orderId,
			"UNKNOWN",
			null,
			work.currency,
			"RECONCILING",
			now,
		)
		reconciliationOperations.recordUnknown(
			work = work,
			responseStatus = 202,
			responseBody = body,
			code = result.code,
			now = now,
		)
	}

	@Transactional
	fun reconcileMismatch(
		work: ClaimedPaymentReconciliation,
		result: ProviderPaymentResult.Approved,
		now: Instant,
	) {
		val body = confirmationBody(
			work.paymentId,
			work.orderId,
			"RECONCILING",
			null,
			work.currency,
			"RECONCILING",
			now,
		)
		reconciliationOperations.recordUnknown(
			work = work,
			responseStatus = 202,
			responseBody = body,
			code = if (result.currency != work.currency) "CURRENCY_MISMATCH" else "AMOUNT_MISMATCH",
			now = now,
		)
	}

	private fun unknown(
		paymentId: UUID,
		orderId: UUID,
		result: ProviderPaymentResult.Unknown,
		now: Instant,
	): StoredHttpResponse {
		val response = PaymentConfirmationResponse(
			paymentId,
			orderId,
			"EXTERNAL",
			"UNKNOWN",
			null,
			"KRW",
			PaymentRecoveryResponse("REQUESTED", now),
			now,
			correlation(paymentId),
		)
		val body = objectMapper.writeValueAsString(response)
		paymentOperations.applyResult(
			ApplyExternalPaymentResultCommand(paymentId, result, 202, body, now),
		)
		meterRegistry.counter("beanflow.payment.unknown.count").increment()
		return StoredHttpResponse(202, body)
	}

	private fun decline(
		customerId: UUID,
		orderId: UUID,
		paymentId: UUID,
		result: ProviderPaymentResult.Declined,
		now: Instant,
	): StoredHttpResponse {
		val order = lockOwned(customerId, orderId)
		expireIfDue(order, now)
		if (order.state == OrderState.PENDING_PAYMENT) {
			val reports = release(order, now)
			order.state = OrderState.CANCELLED
			order.reservationExpiresAt = null
			order.updatedAt = now
			appendAudits(customerId, orderId, paymentId, now, "PAYMENT_DECLINED", reports)
		}
		val correlationId = correlation(paymentId)
		val body = objectMapper.writeValueAsString(
			ErrorResponse(
				FailureCode.PAYMENT_DECLINED.name,
				"Provider declined the payment",
				correlationId,
			),
		)
		paymentOperations.applyResult(
			ApplyExternalPaymentResultCommand(paymentId, result, 422, body, now),
		)
		return StoredHttpResponse(422, body)
	}

	private fun approve(
		customerId: UUID,
		orderId: UUID,
		paymentId: UUID,
		result: ProviderPaymentResult.Approved,
		now: Instant,
	): StoredHttpResponse {
		val order = lockOwned(customerId, orderId)
		expireIfDue(order, now)
		val exact = result.amountKrw == order.payableKrw && result.currency == order.currency
		val late = order.state == OrderState.EXPIRED || order.state == OrderState.CANCELLED
		if (late) {
			val body = confirmationBody(
				paymentId,
				orderId,
				"RECONCILING",
				result.amountKrw,
				result.currency,
				"REQUESTED",
				now,
			)
			paymentOperations.applyResult(
				ApplyExternalPaymentResultCommand(paymentId, result, 202, body, now, lateApproval = true),
			)
			meterRegistry.counter("beanflow.payment.late_approval.count").increment()
			return StoredHttpResponse(202, body)
		}
		if (!exact) {
			val body = confirmationBody(
				paymentId,
				orderId,
				"RECONCILING",
				null,
				order.currency,
				"REQUESTED",
				now,
			)
			paymentOperations.applyResult(
				ApplyExternalPaymentResultCommand(paymentId, result, 202, body, now),
			)
			return StoredHttpResponse(202, body)
		}
		if (order.state != OrderState.PENDING_PAYMENT) {
			throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, "Order is not eligible for approval")
		}
		val reports = confirm(order)
		order.state = OrderState.PAID
		order.reservationExpiresAt = null
		order.updatedAt = now
		val body = confirmationBody(
			paymentId,
			orderId,
			"APPROVED",
			result.amountKrw,
			result.currency,
			"NOT_REQUIRED",
			now,
		)
		paymentOperations.applyResult(
			ApplyExternalPaymentResultCommand(paymentId, result, 200, body, now),
		)
		appendAudits(customerId, orderId, paymentId, now, "PAYMENT_APPROVED", reports)
		return StoredHttpResponse(200, body)
	}

	private fun expireIfDue(order: OrderEntity, now: Instant) {
		val deadline = order.reservationExpiresAt
		if (order.state == OrderState.PENDING_PAYMENT && deadline != null && !now.isBefore(deadline)) {
			expiryUseCase.expireIfDue(order.id, now)
		}
	}

	private fun confirm(order: OrderEntity): List<Pair<String, ReservationTransitionReport>> {
		val reports = mutableListOf<Pair<String, ReservationTransitionReport>>()
		reports += "PICKUP" to requireApplied(
			"PICKUP",
			pickupOperations.confirm(order.id, OrderCreationTransaction.pickupSource(order.id)),
		)
		reports += "STOCK" to requireApplied(
			"STOCK",
			stockOperations.confirm(order.id, OrderCreationTransaction.stockSource(order.id)),
		)
		if (order.couponDiscountKrw > 0) {
			reports += "COUPON" to requireApplied(
				"COUPON",
				couponOperations.confirm(order.id, OrderCreationTransaction.couponSource(order.id)),
			)
		}
		if (order.pointsAppliedKrw > 0) {
			reports += "POINTS" to requireApplied(
				"POINTS",
				pointOperations.confirm(order.id, OrderCreationTransaction.pointsSource(order.id)),
			)
		}
		return reports
	}

	private fun release(order: OrderEntity, now: Instant): List<Pair<String, ReservationTransitionReport>> {
		val reports = mutableListOf<Pair<String, ReservationTransitionReport>>()
		reports += "PICKUP" to requireApplied(
			"PICKUP",
			pickupOperations.release(order.id, now, OrderCreationTransaction.pickupSource(order.id)),
		)
		reports += "STOCK" to requireApplied(
			"STOCK",
			stockOperations.release(order.id, now, OrderCreationTransaction.stockSource(order.id)),
		)
		if (order.couponDiscountKrw > 0) {
			reports += "COUPON" to requireApplied(
				"COUPON",
				couponOperations.release(order.id, now, OrderCreationTransaction.couponSource(order.id)),
			)
		}
		if (order.pointsAppliedKrw > 0) {
			reports += "POINTS" to requireApplied(
				"POINTS",
				pointOperations.release(order.id, now, OrderCreationTransaction.pointsSource(order.id)),
			)
		}
		return reports
	}

	private fun requireApplied(owner: String, report: ReservationTransitionReport): ReservationTransitionReport {
		if (report.result != ReservationTransitionResult.APPLIED || report.targetIds.isEmpty()) {
			throw DomainFailure(
				FailureCode.DEPENDENCY_UNAVAILABLE,
				"$owner reservation was not eligible for atomic payment transition",
			)
		}
		return report
	}

	private fun lockOwned(customerId: UUID, orderId: UUID): OrderEntity {
		val order = orderRepository.findLockedById(orderId)
			?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Order was not found")
		if (order.customerId != customerId) {
			throw DomainFailure(FailureCode.ACCESS_DENIED, "Order belongs to another customer")
		}
		return order
	}

	private fun confirmationBody(
		paymentId: UUID,
		orderId: UUID,
		approvalState: String,
		approvedAmountKrw: Long?,
		currency: String,
		recoveryState: String,
		now: Instant,
	): String =
		objectMapper.writeValueAsString(
			PaymentConfirmationResponse(
				paymentId,
				orderId,
				"EXTERNAL",
				approvalState,
				approvedAmountKrw,
				currency,
				PaymentRecoveryResponse(recoveryState, now).takeUnless { recoveryState == "NOT_REQUIRED" },
				now,
				correlation(paymentId),
			),
		)

	private fun correlation(paymentId: UUID): String =
		paymentOperations.current(paymentId).correlationId

	private fun appendAudits(
		customerId: UUID,
		orderId: UUID,
		paymentId: UUID,
		now: Instant,
		action: String,
		reports: List<Pair<String, ReservationTransitionReport>>,
	) {
		val source = "payment:$paymentId:tx2"
		val correlationId = correlation(paymentId)
		val terminal = if (action == "PAYMENT_APPROVED") "CONFIRMED" else "RELEASED"
		val commands = mutableListOf(
			audit(
				customerId,
				action,
				"PAYMENT",
				paymentId,
				now,
				source,
				"APPROVING",
				action.removePrefix("PAYMENT_"),
				correlationId,
			),
			audit(
				customerId,
				if (action == "PAYMENT_APPROVED") "ORDER_PAID" else "ORDER_CANCELLED",
				"ORDER",
				orderId,
				now,
				source,
				"PENDING_PAYMENT",
				if (action == "PAYMENT_APPROVED") "PAID" else "CANCELLED",
				correlationId,
			),
		)
		reports.forEach { (owner, report) ->
			report.targetIds.forEach { targetId ->
				commands += audit(
					customerId,
					"${owner}_${terminal}",
					"${owner}_RESERVATION",
					targetId,
					now,
					source,
					"RESERVED",
					terminal,
					correlationId,
				)
			}
		}
		auditOperations.appendAll(commands)
	}

	private fun audit(
		customerId: UUID,
		action: String,
		targetType: String,
		targetId: UUID,
		now: Instant,
		source: String,
		before: String,
		after: String,
		correlationId: String,
	) = AppendAuditRecordCommand(
		actorId = customerId.toString(),
		actorType = AuditActorType.CUSTOMER,
		action = action,
		targetType = targetType,
		targetId = targetId,
		occurredAt = now,
		reason = action,
		beforeSummary = mapOf("state" to before),
		afterSummary = mapOf("state" to after),
		correlationId = correlationId,
		sourceReference = source,
	)
}

private fun ExternalPaymentView.toResponse(): PaymentConfirmationResponse =
	PaymentConfirmationResponse(
		paymentId = paymentId,
		orderId = orderId,
		type = type,
		approvalState = approvalState,
		approvedAmountKrw = approvedAmountKrw,
		currency = currency,
		recovery = PaymentRecoveryResponse(recoveryState, updatedAt).takeUnless {
			recoveryState == "NOT_REQUIRED"
		},
		updatedAt = updatedAt,
		correlationId = correlationId,
	)

package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.payment.api.ApplyExternalPaymentResultCommand
import io.github.kdh949.beanflow.payment.api.ExternalPaymentOperations
import io.github.kdh949.beanflow.payment.api.ExternalPaymentView
import io.github.kdh949.beanflow.payment.api.PaymentPreparation
import io.github.kdh949.beanflow.payment.api.PaymentPreparationState
import io.github.kdh949.beanflow.payment.api.PrepareExternalPaymentCommand
import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import io.github.kdh949.beanflow.payment.internal.domain.Payment
import io.github.kdh949.beanflow.payment.internal.domain.PaymentApprovalState
import io.github.kdh949.beanflow.payment.internal.domain.PaymentType
import io.github.kdh949.beanflow.payment.internal.domain.ProviderApproval
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Duration
import java.util.UUID

@Service
internal class ExternalPaymentService(
	private val paymentRepository: PaymentJpaRepository,
	private val paymentMethodRepository: PaymentMethodJpaRepository,
	private val providerRequestSnapshots: PaymentProviderRequestSnapshotStore,
	private val idempotencyRepository: PaymentIdempotencyJpaRepository,
	private val reconciliationRepository: PaymentReconciliationJpaRepository,
	private val jdbcTemplate: JdbcTemplate,
	private val identifierSource: IdentifierSource,
	private val providerRequestLoader: PaymentProviderRequestLoader,
	private val gateway: PaymentGateway,
) : ExternalPaymentOperations {

	@Transactional(propagation = Propagation.MANDATORY)
	override fun existing(command: PrepareExternalPaymentCommand): PaymentPreparation? {
		validate(command)
		val existing = idempotencyRepository.findByActorIdAndOperationAndIdempotencyKey(
			command.actorId,
			OPERATION,
			command.idempotencyKey,
		) ?: return null
		return existing.toPreparation(command)
	}

	@Transactional(propagation = Propagation.MANDATORY)
	override fun prepare(command: PrepareExternalPaymentCommand): PaymentPreparation {
		validate(command)
		val paymentId = identifierSource.next()
		val idempotencyRecordId = identifierSource.next()
		val inserted = jdbcTemplate.update(
			"""
			INSERT INTO payment_idempotency_record (
			    id, actor_id, operation, idempotency_key, payload_hash, payment_id,
			    order_id, status, started_at, version
			)
			VALUES (?, ?, ?, ?, ?, ?, ?, 'PROCESSING', ?, 0)
			ON CONFLICT (actor_id, operation, idempotency_key) DO NOTHING
			""".trimIndent(),
			idempotencyRecordId,
			command.actorId,
			OPERATION,
			command.idempotencyKey,
			command.payloadHash,
			paymentId,
			command.orderId,
			Timestamp.from(command.now),
		)
		if (inserted == 0) {
			return existingPreparation(command)
		}

		val paymentMethod = paymentMethodRepository.findLockedById(command.paymentMethodId)
			?: fail(FailureCode.RESOURCE_NOT_FOUND, "Payment method was not found")
		if (paymentMethod.customerId != command.actorId) {
			fail(FailureCode.ACCESS_DENIED, "Payment method belongs to another customer")
		}
		if (paymentMethod.status != PaymentMethodStatus.ACTIVE) {
			fail(FailureCode.PAYMENT_METHOD_STATE_CONFLICT, "Payment method is not active")
		}
		if (paymentRepository.findByOrderId(command.orderId) != null) {
			fail(FailureCode.ORDER_STATE_CONFLICT, "Order already has a payment")
		}

		paymentRepository.saveAndFlush(
			PaymentEntity(
				id = paymentId,
				orderId = command.orderId,
				customerId = command.actorId,
				paymentMethodId = command.paymentMethodId,
				type = PaymentType.EXTERNAL,
				approvalState = PaymentApprovalState.APPROVING,
				requestedAmountKrw = command.requestedAmountKrw,
				approvedAmountKrw = null,
				currency = "KRW",
				benefitSnapshotReference = null,
				sourceReference = "payment:$paymentId:external",
				correlationId = command.correlationId,
				approvedAt = null,
				createdAt = command.now,
				updatedAt = command.now,
			),
		)
		providerRequestSnapshots.create(
			PaymentProviderRequestSnapshotEntity(
				paymentId = paymentId,
				paymentMethodId = paymentMethod.id,
				provider = paymentMethod.provider,
				tokenReference = paymentMethod.tokenReference,
				providerCustomerReference = paymentMethod.providerCustomerReference,
				createdAt = command.now,
			),
		)
		reconciliationRepository.save(
			PaymentReconciliationEntity(
				id = identifierSource.next(),
				paymentId = paymentId,
				kind = ReconciliationKind.APPROVAL_LOOKUP,
				status = ReconciliationStatus.SCHEDULED,
				attemptCount = 0,
				nextAttemptAt = command.now.plus(RECONCILIATION_DELAYS.first()),
				sourceReference = "payment:$paymentId:approval-lookup",
				createdAt = command.now,
				updatedAt = command.now,
			),
		)
		return PaymentPreparation(paymentId, PaymentPreparationState.ACQUIRED)
	}

	override fun requestProviderApproval(paymentId: UUID): ProviderPaymentResult {
		val request = providerRequestLoader.load(paymentId)
		return gateway.approve(request)
	}

	@Transactional(propagation = Propagation.MANDATORY)
	override fun applyResult(command: ApplyExternalPaymentResultCommand): ExternalPaymentView {
		val entity = paymentRepository.findLockedById(command.paymentId)
			?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Payment is missing")
		val payment = entity.toDomain()
		if (command.lateApproval) {
			val approved = command.result as? ProviderPaymentResult.Approved
				?: fail(FailureCode.INVALID_REQUEST, "Only an approval can be marked late")
			payment.markLateApproval(
				ProviderApproval.Approved(
					approved.providerTransactionReference,
					approved.amountKrw,
					approved.currency,
				),
				command.now,
			)
		} else {
			payment.apply(command.result.toDomain(), command.now)
		}
		entity.apply(payment)

		val idempotency = idempotencyRepository.findByIdempotencyPaymentId(command.paymentId)
		val work = reconciliationRepository.findByPaymentIdAndKind(
			command.paymentId,
			ReconciliationKind.APPROVAL_LOOKUP,
		) ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Payment reconciliation is missing")
		when (payment.approvalState) {
			PaymentApprovalState.APPROVED -> {
				idempotency.status = PaymentIdempotencyStatus.COMPLETED
				idempotency.terminalAt = command.now
				work.status = ReconciliationStatus.SUCCEEDED
			}
			PaymentApprovalState.FAILED -> {
				idempotency.status = PaymentIdempotencyStatus.FAILED
				idempotency.terminalAt = command.now
				work.status = ReconciliationStatus.SUCCEEDED
			}
			PaymentApprovalState.UNKNOWN -> {
				idempotency.status = PaymentIdempotencyStatus.UNKNOWN
				work.status = ReconciliationStatus.SCHEDULED
				work.lastFailureCode = (command.result as ProviderPaymentResult.Unknown).code
			}
			PaymentApprovalState.RECONCILING -> {
				idempotency.status = PaymentIdempotencyStatus.RECONCILING
				if (command.lateApproval) {
					work.status = ReconciliationStatus.SUCCEEDED
					work.lastFailureCode = "LATE_APPROVAL"
					scheduleLateVoid(command.paymentId, command.now)
				} else {
					work.status = ReconciliationStatus.SCHEDULED
					work.lastFailureCode = "AMOUNT_OR_CURRENCY_MISMATCH"
				}
			}
			else -> fail(FailureCode.ORDER_STATE_CONFLICT, "Unexpected payment result state")
		}
		idempotency.responseStatus = command.responseStatus
		idempotency.responseBody = command.responseBody
		work.claimToken = null
		work.claimUntil = null
		work.updatedAt = command.now
		return entity.toView(recoveryWork(command.paymentId) ?: work)
	}

	private fun scheduleLateVoid(paymentId: UUID, now: java.time.Instant) {
		val existing = reconciliationRepository.findByPaymentIdAndKind(paymentId, ReconciliationKind.LATE_VOID)
		if (existing != null) return
		reconciliationRepository.save(
			PaymentReconciliationEntity(
				id = identifierSource.next(),
				paymentId = paymentId,
				kind = ReconciliationKind.LATE_VOID,
				status = ReconciliationStatus.SCHEDULED,
				attemptCount = 0,
				nextAttemptAt = now,
				sourceReference = "payment:$paymentId:late-void",
				lastFailureCode = "LATE_APPROVAL",
				createdAt = now,
				updatedAt = now,
			),
		)
	}

	@Transactional(readOnly = true)
	override fun current(paymentId: UUID): ExternalPaymentView {
		val payment = paymentRepository.findById(paymentId).orElse(null)
			?: fail(FailureCode.RESOURCE_NOT_FOUND, "Payment was not found")
		return payment.toView(
			recoveryWork(paymentId)
				?: reconciliationRepository.findByPaymentIdAndKind(paymentId, ReconciliationKind.APPROVAL_LOOKUP),
		)
	}

	private fun recoveryWork(paymentId: UUID): PaymentReconciliationEntity? =
		reconciliationRepository.findByPaymentIdAndKind(paymentId, ReconciliationKind.LATE_REFUND)
			?: reconciliationRepository.findByPaymentIdAndKind(paymentId, ReconciliationKind.LATE_VOID)

	private fun existingPreparation(command: PrepareExternalPaymentCommand): PaymentPreparation {
		val existing = idempotencyRepository.findByActorIdAndOperationAndIdempotencyKey(
			command.actorId,
			OPERATION,
			command.idempotencyKey,
		) ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Payment idempotency record is missing")
		return existing.toPreparation(command)
	}

	private fun PaymentIdempotencyEntity.toPreparation(
		command: PrepareExternalPaymentCommand,
	): PaymentPreparation {
		if (payloadHash != command.payloadHash) {
			fail(FailureCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was reused with another payload")
		}
		if (orderId != command.orderId) {
			fail(FailureCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key belongs to another order")
		}
		val view = current(paymentId)
		val state = if (status == PaymentIdempotencyStatus.PROCESSING) {
			PaymentPreparationState.IN_PROGRESS
		} else {
			PaymentPreparationState.CURRENT
		}
		return PaymentPreparation(paymentId, state, view, responseStatus, responseBody)
	}

	private fun validate(command: PrepareExternalPaymentCommand) {
		if (command.idempotencyKey.length !in 8..128 || command.payloadHash.length != 64) {
			fail(FailureCode.INVALID_REQUEST, "Payment idempotency input is invalid")
		}
		if (command.requestedAmountKrw <= 0 || command.correlationId.isBlank()) {
			fail(FailureCode.INVALID_REQUEST, "External payment amount and correlation are required")
		}
	}

	private fun PaymentEntity.toDomain(): Payment = Payment.restore(
		id = id,
		orderId = orderId,
		customerId = requireNotNull(customerId),
		paymentMethodId = paymentMethodId,
		type = type,
		requestedAmountKrw = requestedAmountKrw,
		currency = currency,
		correlationId = correlationId,
		approvalState = approvalState,
		approvedAmountKrw = approvedAmountKrw,
		providerTransactionReference = providerTransactionReference,
		createdAt = createdAt,
		updatedAt = updatedAt,
	)

	private fun PaymentEntity.apply(payment: Payment) {
		approvalState = payment.approvalState
		approvedAmountKrw = payment.approvedAmountKrw
		providerTransactionReference = payment.providerTransactionReference
		lastFailureCode = when (payment.approvalState) {
			PaymentApprovalState.UNKNOWN -> "PROVIDER_RESULT_UNKNOWN"
			PaymentApprovalState.RECONCILING -> "AMOUNT_OR_CURRENCY_MISMATCH"
			else -> null
		}
		approvedAt = payment.updatedAt.takeIf { payment.approvalState == PaymentApprovalState.APPROVED }
		updatedAt = payment.updatedAt
	}

	private fun ProviderPaymentResult.toDomain(): ProviderApproval =
		when (this) {
			is ProviderPaymentResult.Approved ->
				ProviderApproval.Approved(providerTransactionReference, amountKrw, currency)
			is ProviderPaymentResult.Declined -> ProviderApproval.Declined(code)
			is ProviderPaymentResult.Unknown -> ProviderApproval.Unknown(code)
		}

	private fun PaymentEntity.toView(work: PaymentReconciliationEntity?): ExternalPaymentView =
		ExternalPaymentView(
			paymentId = id,
			orderId = orderId,
			type = type.name,
			approvalState = approvalState.name,
			approvedAmountKrw = approvedAmountKrw,
			currency = currency,
			recoveryState = when {
				work == null -> "NOT_REQUIRED"
				work.kind == ReconciliationKind.APPROVAL_LOOKUP &&
					work.status == ReconciliationStatus.SUCCEEDED -> "NOT_REQUIRED"
				work.status == ReconciliationStatus.SUCCEEDED -> "SUCCEEDED"
				work.status == ReconciliationStatus.SCHEDULED -> "REQUESTED"
				work.status == ReconciliationStatus.RETRY_SCHEDULED -> "RECONCILING"
				else -> work.status.name
			},
			updatedAt = updatedAt,
			correlationId = correlationId,
		)

	private fun fail(code: FailureCode, message: String): Nothing = throw DomainFailure(code, message)

	internal companion object {
		const val OPERATION = "CONFIRM_ORDER_PAYMENT"
		val RECONCILIATION_DELAYS: List<Duration> = listOf(
			Duration.ofSeconds(10),
			Duration.ofSeconds(30),
			Duration.ofMinutes(2),
			Duration.ofMinutes(5),
			Duration.ofMinutes(15),
		)
	}
}

@Service
internal class PaymentProviderRequestLoader(
	private val paymentRepository: PaymentJpaRepository,
	private val snapshots: PaymentProviderRequestSnapshotStore,
) {
	@Transactional(readOnly = true)
	fun load(paymentId: UUID): GatewayApprovalRequest {
		val payment = paymentRepository.findById(paymentId).orElse(null)
			?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Payment was not found")
		if (payment.approvalState != PaymentApprovalState.APPROVING) {
			throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, "Payment is not awaiting Provider approval")
		}
		val snapshot = snapshot(payment)
		return GatewayApprovalRequest(
			paymentId = payment.id,
			provider = snapshot.provider,
			tokenReference = snapshot.tokenReference,
			providerCustomerReference = snapshot.providerCustomerReference,
			amountKrw = payment.requestedAmountKrw,
			currency = payment.currency,
			providerIdempotencyKey = "payment:${payment.id}:approve",
		)
	}

	@Transactional(readOnly = true)
	fun loadLookup(paymentId: UUID): GatewayLookupRequest {
		val payment = paymentRepository.findById(paymentId).orElse(null)
			?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Payment was not found")
		val snapshot = snapshot(payment)
		return GatewayLookupRequest(
			paymentId = payment.id,
			provider = snapshot.provider,
			tokenReference = snapshot.tokenReference,
			providerCustomerReference = snapshot.providerCustomerReference,
			providerTransactionReference = payment.providerTransactionReference,
			amountKrw = payment.requestedAmountKrw,
			currency = payment.currency,
		)
	}

	private fun snapshot(payment: PaymentEntity): PaymentProviderRequestSnapshotEntity {
		val snapshot = snapshots.findByPaymentId(payment.id)
			?: throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Payment provider request snapshot is missing")
		if (
			snapshot.paymentMethodId != payment.paymentMethodId ||
			snapshot.provider.isBlank() ||
			snapshot.tokenReference.isBlank() ||
			(snapshot.provider == "TOSS_PAYMENTS" && snapshot.providerCustomerReference.isNullOrBlank())
		) {
			throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Payment provider request snapshot is invalid")
		}
		return snapshot
	}
}

private fun PaymentIdempotencyJpaRepository.findByIdempotencyPaymentId(
	paymentId: UUID,
): PaymentIdempotencyEntity =
	findByPaymentId(paymentId)
		?: throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Payment idempotency record is missing")

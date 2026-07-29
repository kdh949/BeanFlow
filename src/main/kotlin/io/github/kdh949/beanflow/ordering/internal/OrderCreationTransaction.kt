package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.fulfillment.api.PickupReservationOperations
import io.github.kdh949.beanflow.fulfillment.api.ReservePickupCommand
import io.github.kdh949.beanflow.inventory.api.ReserveStockCommand
import io.github.kdh949.beanflow.inventory.api.StockRequirement
import io.github.kdh949.beanflow.inventory.api.StockReservationOperations
import io.github.kdh949.beanflow.loyalty.api.PointReservationOperations
import io.github.kdh949.beanflow.loyalty.api.ReservePointsCommand
import io.github.kdh949.beanflow.merchant.api.MenuQuoteUseCase
import io.github.kdh949.beanflow.merchant.api.QuoteOrderLine
import io.github.kdh949.beanflow.ordering.api.CreateOrderCommand
import io.github.kdh949.beanflow.ordering.api.StoredHttpResponse
import io.github.kdh949.beanflow.ordering.internal.domain.Krw
import io.github.kdh949.beanflow.ordering.internal.domain.Order
import io.github.kdh949.beanflow.ordering.internal.domain.OrderPricingCalculator
import io.github.kdh949.beanflow.ordering.internal.domain.PricingLine
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.payment.api.ApproveBenefitOnlyPaymentCommand
import io.github.kdh949.beanflow.payment.api.BenefitOnlyPaymentOperations
import io.github.kdh949.beanflow.payment.api.BenefitOnlyPaymentResult
import io.github.kdh949.beanflow.promotion.api.CouponPricingLine
import io.github.kdh949.beanflow.promotion.api.CouponReservationOperations
import io.github.kdh949.beanflow.promotion.api.ReserveCouponCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.ReservationTransitionReport
import io.github.kdh949.beanflow.shared.api.ReservationTransitionResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.util.UUID

@Service
internal class OrderCreationTransaction(
	private val menuQuoteUseCase: MenuQuoteUseCase,
	private val pickupOperations: PickupReservationOperations,
	private val stockOperations: StockReservationOperations,
	private val couponOperations: CouponReservationOperations,
	private val pointOperations: PointReservationOperations,
	private val benefitOnlyPaymentOperations: BenefitOnlyPaymentOperations,
	private val orderRepository: OrderJpaRepository,
	private val orderLineRepository: OrderLineJpaRepository,
	private val idempotencyService: OrderIdempotencyService,
	private val auditRecordOperations: AuditRecordOperations,
	private val identifierSource: IdentifierSource,
	private val correlationIdSource: CorrelationIdSource,
	private val clock: Clock,
	private val objectMapper: ObjectMapper,
) {

	private val pricingCalculator = OrderPricingCalculator()

	@Transactional
	fun create(
		idempotencyRecordId: UUID,
		orderId: UUID,
		command: CreateOrderCommand,
	): StoredHttpResponse {
		validate(command)
		val createdAt = clock.instant()
		val expiresAt = createdAt.plus(RESERVATION_LEASE)
		val quotes = menuQuoteUseCase.quote(
			command.storeId,
			command.lines.map { QuoteOrderLine(it.menuId, it.optionIds, it.quantity) },
		)
		val stockRequirements = aggregateStockRequirements(quotes)

		val pickupReservationId = pickupOperations.reserve(
			ReservePickupCommand(
				orderId = orderId,
				storeId = command.storeId,
				pickupSlotId = command.pickupSlotId,
				expiresAt = expiresAt,
				sourceReference = pickupSource(orderId),
			),
		)
		val stockReservationIds = stockOperations.reserve(
			ReserveStockCommand(
				orderId = orderId,
				storeId = command.storeId,
				requirements = stockRequirements,
				expiresAt = expiresAt,
				sourceReference = stockSource(orderId),
			),
		)

		val grossLines = quotes.mapIndexed { sequence, quote ->
			CouponPricingLine(
				lineSequence = sequence,
				menuId = quote.menuId,
				grossKrw = Krw.of(quote.unitPriceKrw).multiply(quote.quantity).value,
			)
		}
		val couponQuote = command.couponIssuanceId?.let { couponIssuanceId ->
			couponOperations.reserve(
				ReserveCouponCommand(
					orderId = orderId,
					customerId = command.customerId,
					storeId = command.storeId,
					couponIssuanceId = couponIssuanceId,
					lines = grossLines,
					reservationExpiresAt = expiresAt,
					sourceReference = couponSource(orderId),
				),
			)
		}
		val pricing = pricingCalculator.calculate(
			lines = quotes.mapIndexed { sequence, quote ->
				PricingLine(
					lineSequence = sequence,
					menuId = quote.menuId,
					unitPrice = Krw.of(quote.unitPriceKrw),
					quantity = quote.quantity,
					couponEligible = couponQuote?.eligibleLineSequences?.contains(sequence) ?: false,
				)
			},
			couponDiscount = Krw.of(couponQuote?.discountKrw ?: 0),
			pointsToUse = Krw.of(command.pointsToUseKrw),
		)
		val pointReservation = if (command.pointsToUseKrw > 0) {
			pointOperations.reserve(
				ReservePointsCommand(
					orderId = orderId,
					customerId = command.customerId,
					amountKrw = command.pointsToUseKrw,
					reservationExpiresAt = expiresAt,
					sourceReference = pointsSource(orderId),
				),
			)
		} else {
			null
		}

		val lineIds = quotes.map { identifierSource.next() }
		val correlationId = correlationIdSource.currentOrCreate()
		val benefitConfirmation = if (pricing.payable == Krw.ZERO) {
			val payment = benefitOnlyPaymentOperations.approve(
				ApproveBenefitOnlyPaymentCommand(
					paymentId = identifierSource.next(),
					orderId = orderId,
					approvedAmountKrw = pricing.payable.value,
					currency = "KRW",
					benefitSnapshotReference = benefitSnapshotSource(orderId),
					sourceReference = paymentSource(orderId),
					correlationId = correlationId,
					approvedAt = createdAt,
				),
			)
			val pickup = requireApplied(
				"PICKUP",
				pickupOperations.confirm(orderId, pickupSource(orderId)),
			)
			val stock = requireApplied(
				"STOCK",
				stockOperations.confirm(orderId, stockSource(orderId)),
			)
			val coupon = couponQuote?.let {
				requireApplied(
					"COUPON",
					couponOperations.confirm(orderId, couponSource(orderId)),
				)
			}
			val points = requireApplied(
				"POINTS",
				pointOperations.confirm(orderId, pointsSource(orderId)),
			)
			BenefitOnlyConfirmation(payment, pickup, stock, coupon, points)
		} else {
			null
		}
		val order = if (benefitConfirmation == null) {
			Order.pendingPayment(
				id = orderId,
				customerId = command.customerId,
				storeId = command.storeId,
				pickupSlotId = command.pickupSlotId,
				lineIds = lineIds,
				quotes = quotes,
				pricing = pricing,
				createdAt = createdAt,
			)
		} else {
			Order.benefitOnlyPaid(
				id = orderId,
				customerId = command.customerId,
				storeId = command.storeId,
				pickupSlotId = command.pickupSlotId,
				lineIds = lineIds,
				quotes = quotes,
				pricing = pricing,
				createdAt = createdAt,
			)
		}
		orderRepository.save(
			OrderEntity(
				id = order.id,
				customerId = order.customerId,
				storeId = order.storeId,
				pickupSlotId = order.pickupSlotId,
				state = order.state,
				subtotalKrw = order.subtotalKrw,
				couponDiscountKrw = order.couponDiscountKrw,
				pointsAppliedKrw = order.pointsAppliedKrw,
				payableKrw = order.payableKrw,
				reservationExpiresAt = order.reservationExpiresAt,
				createdAt = order.createdAt,
				updatedAt = order.createdAt,
			),
		)
		orderLineRepository.saveAll(
			order.lines.map { line ->
				OrderLineEntity(
					id = line.id,
					orderId = order.id,
					lineSequence = line.lineSequence,
					menuId = line.menuId,
					menuName = line.menuName,
					optionNamesJson = objectMapper.writeValueAsString(line.options.map { it.name }),
					sellableRequirementsJson = objectMapper.writeValueAsString(line.sellableUnitRequirements),
					unitPriceKrw = line.unitPriceKrw,
					quantity = line.quantity,
					grossKrw = line.grossKrw,
					couponDiscountKrw = line.couponDiscountKrw,
					pointsAppliedKrw = line.pointsAppliedKrw,
					cashPayableKrw = line.cashPayableKrw,
				)
			},
		)
		val auditSource = createAuditSource(order.id)
		val auditRecords = mutableListOf(
			auditCommand(
				command = command,
				action = "ORDER_CREATED",
				targetType = "ORDER",
				targetId = order.id,
				occurredAt = createdAt,
				correlationId = correlationId,
				sourceReference = auditSource,
				after = mapOf("state" to order.state.name, "payableKrw" to order.payableKrw.toString()),
			),
			auditCommand(
				command = command,
				action = "PICKUP_RESERVED",
				targetType = "PICKUP_RESERVATION",
				targetId = pickupReservationId,
				occurredAt = createdAt,
				correlationId = correlationId,
				sourceReference = auditSource,
				after = mapOf("state" to "RESERVED"),
			),
		)
		stockReservationIds.forEach { reservationId ->
			auditRecords += auditCommand(
				command = command,
				action = "STOCK_RESERVED",
				targetType = "STOCK_RESERVATION",
				targetId = reservationId,
				occurredAt = createdAt,
				correlationId = correlationId,
				sourceReference = auditSource,
				after = mapOf("state" to "RESERVED"),
			)
		}
		couponQuote?.let {
			auditRecords += auditCommand(
				command = command,
				action = "COUPON_RESERVED",
				targetType = "COUPON_RESERVATION",
				targetId = it.reservationId,
				occurredAt = createdAt,
				correlationId = correlationId,
				sourceReference = auditSource,
				after = mapOf("state" to "RESERVED", "discountKrw" to it.discountKrw.toString()),
			)
		}
		pointReservation?.let {
			auditRecords += auditCommand(
				command = command,
				action = "POINTS_RESERVED",
				targetType = "POINT_RESERVATION",
				targetId = it.reservationId,
				occurredAt = createdAt,
				correlationId = correlationId,
				sourceReference = auditSource,
				after = mapOf("state" to "RESERVED", "amountKrw" to command.pointsToUseKrw.toString()),
			)
		}
		benefitConfirmation?.let { confirmation ->
			auditRecords += auditCommand(
				command = command,
				action = "BENEFIT_ONLY_PAYMENT_APPROVED",
				targetType = "PAYMENT",
				targetId = confirmation.payment.paymentId,
				occurredAt = createdAt,
				correlationId = correlationId,
				sourceReference = auditSource,
				after = mapOf("approvalState" to "APPROVED", "approvedAmountKrw" to "0"),
			)
			confirmation.pickup.targetIds.forEach { reservationId ->
				auditRecords += confirmationAudit(
					command,
					"PICKUP_CONFIRMED",
					"PICKUP_RESERVATION",
					reservationId,
					createdAt,
					correlationId,
					auditSource,
				)
			}
			confirmation.stock.targetIds.forEach { reservationId ->
				auditRecords += confirmationAudit(
					command,
					"STOCK_CONFIRMED",
					"STOCK_RESERVATION",
					reservationId,
					createdAt,
					correlationId,
					auditSource,
				)
			}
			confirmation.coupon?.targetIds?.forEach { reservationId ->
				auditRecords += confirmationAudit(
					command,
					"COUPON_CONFIRMED",
					"COUPON_RESERVATION",
					reservationId,
					createdAt,
					correlationId,
					auditSource,
					"USED",
				)
			}
			confirmation.points.targetIds.forEach { reservationId ->
				auditRecords += confirmationAudit(
					command,
					"POINTS_CONFIRMED",
					"POINT_RESERVATION",
					reservationId,
					createdAt,
					correlationId,
					auditSource,
					"USED",
				)
			}
		}
		auditRecordOperations.appendAll(auditRecords)
		val response = StoredHttpResponse(
			status = 201,
			body = objectMapper.writeValueAsString(
				if (benefitConfirmation == null) {
					pendingPaymentResponse(order)
				} else {
					benefitOnlyResponse(order, benefitConfirmation.payment)
				},
			),
		)
		idempotencyService.complete(idempotencyRecordId, order.id, response)
		return response
	}

	private fun requireApplied(
		owner: String,
		report: ReservationTransitionReport,
	): ReservationTransitionReport {
		if (report.result != ReservationTransitionResult.APPLIED || report.targetIds.isEmpty()) {
			throw DomainFailure(
				FailureCode.DEPENDENCY_UNAVAILABLE,
				"$owner reservation was not eligible for BENEFIT_ONLY confirmation",
			)
		}
		return report
	}

	private fun pendingPaymentResponse(order: Order) =
		PendingPaymentOrderCreationResponse(
			order = OrderResponse(
				orderId = order.id,
				storeId = order.storeId,
				state = order.state.name,
				reservationExpiresAt = order.reservationExpiresAt,
				lines = orderLineResponses(order),
				subtotalKrw = order.subtotalKrw,
				couponDiscountKrw = order.couponDiscountKrw,
				pointsAppliedKrw = order.pointsAppliedKrw,
				payableKrw = order.payableKrw,
				currency = "KRW",
				createdAt = order.createdAt,
				updatedAt = order.createdAt,
			),
		)

	private fun benefitOnlyResponse(order: Order, payment: BenefitOnlyPaymentResult) =
		BenefitOnlyOrderCreationResponse(
			order = BenefitOnlyOrderResponse(
				orderId = order.id,
				storeId = order.storeId,
				state = order.state.name,
				lines = orderLineResponses(order),
				subtotalKrw = order.subtotalKrw,
				couponDiscountKrw = order.couponDiscountKrw,
				pointsAppliedKrw = order.pointsAppliedKrw,
				payableKrw = order.payableKrw,
				currency = "KRW",
				createdAt = order.createdAt,
				updatedAt = order.createdAt,
			),
			payment = BenefitOnlyPaymentResponse(
				paymentId = payment.paymentId,
				orderId = payment.orderId,
				type = payment.type,
				approvalState = payment.approvalState,
				approvedAmountKrw = payment.approvedAmountKrw,
				currency = payment.currency,
				updatedAt = payment.updatedAt,
				correlationId = payment.correlationId,
			),
		)

	private fun orderLineResponses(order: Order): List<OrderLineResponse> =
		order.lines.map { line ->
			OrderLineResponse(
				orderLineId = line.id,
				menuId = line.menuId,
				menuName = line.menuName,
				optionNames = line.options.map { it.name },
				unitPriceKrw = line.unitPriceKrw,
				quantity = line.quantity,
				couponDiscountKrw = line.couponDiscountKrw,
				pointsAppliedKrw = line.pointsAppliedKrw,
				cashPaidKrw = line.cashPayableKrw,
			)
		}

	private fun validate(command: CreateOrderCommand) {
		if (command.lines.isEmpty() || command.pointsToUseKrw < 0) {
			throw DomainFailure(FailureCode.INVALID_REQUEST, "Order lines and non-negative points are required")
		}
		if (command.lines.any { it.quantity < 1 || it.optionIds.size != it.optionIds.toSet().size }) {
			throw DomainFailure(FailureCode.INVALID_REQUEST, "Line quantity and option IDs are invalid")
		}
	}

	private fun auditCommand(
		command: CreateOrderCommand,
		action: String,
		targetType: String,
		targetId: UUID,
		occurredAt: java.time.Instant,
		correlationId: String,
		sourceReference: String,
		after: Map<String, String>,
		before: Map<String, String> = emptyMap(),
	) = AppendAuditRecordCommand(
		actorId = command.customerId.toString(),
		actorType = AuditActorType.CUSTOMER,
		action = action,
		targetType = targetType,
		targetId = targetId,
		occurredAt = occurredAt,
		reason = "CUSTOMER_ORDER_CREATION",
		beforeSummary = before,
		afterSummary = after,
		correlationId = correlationId,
		sourceReference = sourceReference,
	)

	private fun confirmationAudit(
		command: CreateOrderCommand,
		action: String,
		targetType: String,
		targetId: UUID,
		occurredAt: java.time.Instant,
		correlationId: String,
		sourceReference: String,
		afterState: String = "CONFIRMED",
	) = auditCommand(
		command = command,
		action = action,
		targetType = targetType,
		targetId = targetId,
		occurredAt = occurredAt,
		correlationId = correlationId,
		sourceReference = sourceReference,
		before = mapOf("state" to "RESERVED"),
		after = mapOf("state" to afterState),
	)

	private fun aggregateStockRequirements(
		quotes: List<io.github.kdh949.beanflow.merchant.api.MenuLineQuote>,
	): List<StockRequirement> =
		quotes.flatMap { quote ->
			quote.sellableUnitRequirements.map { requirement ->
				val quantity = try {
					Math.multiplyExact(requirement.quantityPerLineUnit, quote.quantity)
				} catch (_: ArithmeticException) {
					throw DomainFailure(
						FailureCode.INVALID_REQUEST,
						"Sellable unit requirement exceeds supported range",
					)
				}
				StockRequirement(requirement.sellableUnitId, quantity)
			}
		}.groupBy(StockRequirement::sellableUnitId)
			.map { (id, requirements) ->
				val quantity = requirements.fold(0L) { total, requirement ->
					try {
						Math.addExact(total, requirement.quantity)
					} catch (_: ArithmeticException) {
						throw DomainFailure(
							FailureCode.INVALID_REQUEST,
							"Aggregated sellable unit requirement exceeds supported range",
						)
					}
				}
				StockRequirement(id, quantity)
			}
			.sortedBy(StockRequirement::sellableUnitId)

	internal companion object {
		private val RESERVATION_LEASE: Duration = Duration.ofMinutes(5)

		fun pickupSource(orderId: UUID) = "order:$orderId:pickup"
		fun stockSource(orderId: UUID) = "order:$orderId:stock"
		fun couponSource(orderId: UUID) = "order:$orderId:coupon"
		fun pointsSource(orderId: UUID) = "order:$orderId:points"
		fun paymentSource(orderId: UUID) = "order:$orderId:benefit-only-payment"
		fun benefitSnapshotSource(orderId: UUID) = "order:$orderId:benefit-snapshot"
		fun createAuditSource(orderId: UUID) = "order:$orderId:create"
	}
}

private data class BenefitOnlyConfirmation(
	val payment: BenefitOnlyPaymentResult,
	val pickup: ReservationTransitionReport,
	val stock: ReservationTransitionReport,
	val coupon: ReservationTransitionReport?,
	val points: ReservationTransitionReport,
)

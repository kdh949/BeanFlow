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
import io.github.kdh949.beanflow.promotion.api.CouponPricingLine
import io.github.kdh949.beanflow.promotion.api.CouponReservationOperations
import io.github.kdh949.beanflow.promotion.api.ReserveCouponCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.IdentifierSource
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
		val order = Order.pendingPayment(
			id = orderId,
			customerId = command.customerId,
			storeId = command.storeId,
			pickupSlotId = command.pickupSlotId,
			lineIds = lineIds,
			quotes = quotes,
			pricing = pricing,
			createdAt = createdAt,
		)
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
		val correlationId = correlationIdSource.currentOrCreate()
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
		auditRecordOperations.appendAll(auditRecords)
		val response = StoredHttpResponse(
			status = 201,
			body = objectMapper.writeValueAsString(
				PendingPaymentOrderCreationResponse(
					order = OrderResponse(
						orderId = order.id,
						storeId = order.storeId,
						state = order.state.name,
						reservationExpiresAt = order.reservationExpiresAt,
						lines = order.lines.map { line ->
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
						},
						subtotalKrw = order.subtotalKrw,
						couponDiscountKrw = order.couponDiscountKrw,
						pointsAppliedKrw = order.pointsAppliedKrw,
						payableKrw = order.payableKrw,
						currency = "KRW",
						createdAt = order.createdAt,
						updatedAt = order.createdAt,
					),
				),
			),
		)
		idempotencyService.complete(idempotencyRecordId, order.id, response)
		return response
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
	) = AppendAuditRecordCommand(
		actorId = command.customerId.toString(),
		actorType = AuditActorType.CUSTOMER,
		action = action,
		targetType = targetType,
		targetId = targetId,
		occurredAt = occurredAt,
		reason = "CUSTOMER_ORDER_CREATION",
		afterSummary = after,
		correlationId = correlationId,
		sourceReference = sourceReference,
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
		fun createAuditSource(orderId: UUID) = "order:$orderId:create"
	}
}

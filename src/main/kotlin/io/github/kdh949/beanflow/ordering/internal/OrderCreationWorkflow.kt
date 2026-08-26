package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.fulfillment.api.PickupReservationOperations
import io.github.kdh949.beanflow.fulfillment.api.ReservePickupCommand
import io.github.kdh949.beanflow.inventory.api.ReserveStockCommand
import io.github.kdh949.beanflow.inventory.api.StockRequirement
import io.github.kdh949.beanflow.inventory.api.StockReservationOperations
import io.github.kdh949.beanflow.loyalty.api.PointReservationOperations
import io.github.kdh949.beanflow.loyalty.api.ReservePointsCommand
import io.github.kdh949.beanflow.merchant.api.MenuLineQuote
import io.github.kdh949.beanflow.merchant.api.MenuQuoteUseCase
import io.github.kdh949.beanflow.merchant.api.QuoteOrderLine
import io.github.kdh949.beanflow.merchant.api.StoreDisplaySnapshotOperations
import io.github.kdh949.beanflow.merchant.api.StoreSettlementTermsOperations
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyOperations
import io.github.kdh949.beanflow.ordering.api.CreateOrderCommand
import io.github.kdh949.beanflow.ordering.internal.domain.Krw
import io.github.kdh949.beanflow.ordering.internal.domain.Order
import io.github.kdh949.beanflow.ordering.internal.domain.OrderDisplayIdentitySnapshot
import io.github.kdh949.beanflow.ordering.internal.domain.OrderPricingCalculator
import io.github.kdh949.beanflow.ordering.internal.domain.PricingLine
import io.github.kdh949.beanflow.payment.api.ApproveBenefitOnlyPaymentCommand
import io.github.kdh949.beanflow.payment.api.BenefitOnlyPaymentOperations
import io.github.kdh949.beanflow.payment.api.BenefitOnlyPaymentResult
import io.github.kdh949.beanflow.promotion.api.CouponPricingLine
import io.github.kdh949.beanflow.promotion.api.CouponReservationOperations
import io.github.kdh949.beanflow.promotion.api.ReserveCouponCommand
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.ReservationTransitionReport
import io.github.kdh949.beanflow.shared.api.ReservationTransitionResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.UUID

internal data class OrderCreationOutcome(
    val order: Order,
    val benefitOnlyPayment: BenefitOnlyPaymentResult?,
)

@Service
internal class OrderCreationWorkflow(
    private val menuQuoteUseCase: MenuQuoteUseCase,
    private val storeSettlementTermsOperations: StoreSettlementTermsOperations,
    private val storeDisplaySnapshotOperations: StoreDisplaySnapshotOperations,
    private val pickupOperations: PickupReservationOperations,
    private val stockOperations: StockReservationOperations,
    private val couponOperations: CouponReservationOperations,
    private val pointOperations: PointReservationOperations,
    private val benefitOnlyPaymentOperations: BenefitOnlyPaymentOperations,
    private val orderRepository: OrderJpaRepository,
    private val orderLineRepository: OrderLineJpaRepository,
    private val auditRecordOperations: AuditRecordOperations,
    private val pointAccrualPolicyOperations: OrdinaryPointAccrualPolicyOperations,
    private val pointAccrualSnapshotService: OrderPointAccrualSnapshotService,
    private val settlementInputSnapshotService: OrderSettlementInputSnapshotService,
    private val displayIdentityAllocator: OrderDisplayIdentityAllocator,
    private val identifierSource: IdentifierSource,
    private val correlationIdSource: CorrelationIdSource,
    private val clock: Clock,
    private val snapshotAssembler: OrderSnapshotAssembler,
    private val auditFactory: OrderCreationAuditFactory,
) {
    private val pricingCalculator = OrderPricingCalculator()
    private val pointAccrualCalculator = OrderPointAccrualCalculator()

    @Transactional(propagation = Propagation.MANDATORY)
    fun create(
        orderId: UUID,
        command: CreateOrderCommand,
        prevalidatedQuotes: List<MenuLineQuote>? = null,
        preparedQuote: OrderQuoteCalculation? = null,
    ): OrderCreationOutcome {
        validate(command)
        if (prevalidatedQuotes != null && preparedQuote != null) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Only one prevalidated order quote may be supplied")
        }
        val createdAt = preparedQuote?.response?.quotedAt ?: clock.instant()
        val requestedExpiresAt = createdAt.plus(RESERVATION_LEASE)
        val storeDisplaySnapshot = preparedQuote?.storeDisplay ?: storeDisplaySnapshotOperations.require(command.storeId)
        val settlementTerms = preparedQuote?.settlementTerms ?: storeSettlementTermsOperations.findApplicable(command.storeId, createdAt)
        val quotes = preparedQuote?.menu?.lines ?: prevalidatedQuotes?.also { validatePrevalidatedQuotes(command, it) } ?: quote(command)
        val stockRequirements = aggregateStockRequirements(quotes)

        val pickupReservation =
            pickupOperations.reserve(
                ReservePickupCommand(
                    orderId = orderId,
                    storeId = command.storeId,
                    pickupSlotId = command.pickupSlotId,
                    expiresAt = requestedExpiresAt,
                    sourceReference = OrderCreationTransaction.pickupSource(orderId),
                ),
            )
        val reservationExpiresAt = pickupReservation.expiresAt
        val stockReservationIds =
            stockOperations.reserve(
                ReserveStockCommand(
                    orderId = orderId,
                    storeId = command.storeId,
                    requirements = stockRequirements,
                    expiresAt = reservationExpiresAt,
                    sourceReference = OrderCreationTransaction.stockSource(orderId),
                ),
            )

        val grossLines =
            quotes.mapIndexed { sequence, quote ->
                CouponPricingLine(
                    lineSequence = sequence,
                    menuId = quote.menuId,
                    grossKrw = Krw.of(quote.unitPriceKrw).multiply(quote.quantity).value,
                )
            }
        val couponQuote =
            command.couponIssuanceId?.let { couponIssuanceId ->
                couponOperations.reserve(
                    ReserveCouponCommand(
                        orderId = orderId,
                        customerId = command.customerId,
                        storeId = command.storeId,
                        couponIssuanceId = couponIssuanceId,
                        lines = grossLines,
                        reservationExpiresAt = reservationExpiresAt,
                        sourceReference = OrderCreationTransaction.couponSource(orderId),
                    ),
                )
            }
        val pricing =
            preparedQuote?.pricing ?: pricingCalculator.calculate(
                lines =
                    quotes.mapIndexed { sequence, quote ->
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
        val pointReservation =
            if (command.pointsToUseKrw > 0) {
                pointOperations.reserve(
                    ReservePointsCommand(
                        orderId = orderId,
                        customerId = command.customerId,
                        amountKrw = command.pointsToUseKrw,
                        reservationExpiresAt = reservationExpiresAt,
                        sourceReference = OrderCreationTransaction.pointsSource(orderId),
                    ),
                )
            } else {
                null
            }

        val lineIds = quotes.map { identifierSource.next() }
        val allocatedDisplayIdentity = displayIdentityAllocator.allocate(command.storeId, pickupReservation.startsAt)
        val displayIdentity =
            OrderDisplayIdentitySnapshot(
                publicReference = allocatedDisplayIdentity.publicReference.value,
                pickupBusinessDate = allocatedDisplayIdentity.pickupBusinessDate,
                pickupSequence = allocatedDisplayIdentity.pickupSequence,
                storeName = storeDisplaySnapshot.name,
                pickupWindowStart = pickupReservation.startsAt,
                pickupWindowEnd = pickupReservation.endsAt,
            )
        val correlationId = correlationIdSource.currentOrCreate()
        val benefitConfirmation =
            if (pricing.payable == Krw.ZERO) {
                val payment =
                    benefitOnlyPaymentOperations.approve(
                        ApproveBenefitOnlyPaymentCommand(
                            paymentId = identifierSource.next(),
                            orderId = orderId,
                            approvedAmountKrw = pricing.payable.value,
                            currency = "KRW",
                            benefitSnapshotReference = OrderCreationTransaction.benefitSnapshotSource(orderId),
                            sourceReference = OrderCreationTransaction.paymentSource(orderId),
                            correlationId = correlationId,
                            approvedAt = createdAt,
                        ),
                    )
                val pickup =
                    requireApplied(
                        "PICKUP",
                        pickupOperations.confirm(
                            orderId,
                            clock.instant(),
                            OrderCreationTransaction.pickupSource(orderId),
                        ),
                    )
                val stock =
                    requireApplied(
                        "STOCK",
                        stockOperations.confirm(orderId, OrderCreationTransaction.stockSource(orderId)),
                    )
                val coupon =
                    couponQuote?.let {
                        requireApplied(
                            "COUPON",
                            couponOperations.confirm(orderId, OrderCreationTransaction.couponSource(orderId)),
                        )
                    }
                val points =
                    requireApplied(
                        "POINTS",
                        pointOperations.confirm(orderId, OrderCreationTransaction.pointsSource(orderId)),
                    )
                BenefitOnlyConfirmation(payment, pickup, stock, coupon, points)
            } else {
                null
            }
        val order =
            if (benefitConfirmation == null) {
                Order.pendingPayment(
                    id = orderId,
                    customerId = command.customerId,
                    storeId = command.storeId,
                    pickupSlotId = command.pickupSlotId,
                    displayIdentity = displayIdentity,
                    lineIds = lineIds,
                    quotes = quotes,
                    pricing = pricing,
                    createdAt = createdAt,
                    reservationExpiresAt = reservationExpiresAt,
                )
            } else {
                Order.benefitOnlyPaid(
                    id = orderId,
                    customerId = command.customerId,
                    storeId = command.storeId,
                    pickupSlotId = command.pickupSlotId,
                    displayIdentity = displayIdentity,
                    lineIds = lineIds,
                    quotes = quotes,
                    pricing = pricing,
                    createdAt = createdAt,
                )
            }
        orderRepository.save(snapshotAssembler.order(order))
        orderLineRepository.saveAll(snapshotAssembler.lines(order))
        orderLineRepository.flush()
        settlementInputSnapshotService.materialize(
            order = order,
            terms = settlementTerms,
            coupon = couponQuote,
            points = pointReservation,
            createdAt = createdAt,
        )
        val selectedPointAccrualPolicy =
            preparedQuote?.pointAccrualPolicy ?: pointAccrualPolicyOperations.selectForOrder(order.storeId)
        val pointAccrualCalculation =
            pointAccrualCalculator.calculate(
                selectedPointAccrualPolicy.policy,
                snapshotAssembler.pointAccrualLines(order),
            )
        pointAccrualSnapshotService.save(
            orderId = order.id,
            orderPayableKrw = order.payableKrw,
            selected = selectedPointAccrualPolicy,
            calculation = pointAccrualCalculation,
            createdAt = createdAt,
        )
        auditRecordOperations.appendAll(
            auditFactory.create(
                command = command,
                order = order,
                pickupReservationId = pickupReservation.reservationId,
                stockReservationIds = stockReservationIds,
                coupon = couponQuote,
                points = pointReservation,
                benefit = benefitConfirmation,
                occurredAt = createdAt,
                correlationId = correlationId,
            ),
        )
        return OrderCreationOutcome(order, benefitConfirmation?.payment)
    }

    private fun quote(command: CreateOrderCommand): List<MenuLineQuote> =
        menuQuoteUseCase.quote(
            command.storeId,
            command.lines.map { QuoteOrderLine(it.menuId, it.optionIds, it.quantity) },
        )

    private fun validatePrevalidatedQuotes(
        command: CreateOrderCommand,
        quotes: List<MenuLineQuote>,
    ) {
        if (quotes.size != command.lines.size) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Merchant quote count does not match order lines")
        }
        command.lines.zip(quotes).forEach { (line, quote) ->
            if (
                line.menuId != quote.menuId ||
                line.quantity != quote.quantity ||
                line.optionIds.distinct().sorted() != quote.optionSnapshots.map { it.optionId }
            ) {
                throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Merchant quote identity does not match order lines")
            }
        }
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

    private fun validate(command: CreateOrderCommand) {
        if (command.lines.isEmpty() || command.pointsToUseKrw < 0) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Order lines and non-negative points are required")
        }
        if (command.lines.any { it.quantity < 1 || it.optionIds.size != it.optionIds.toSet().size }) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Line quantity and option IDs are invalid")
        }
    }

    private fun aggregateStockRequirements(quotes: List<MenuLineQuote>): List<StockRequirement> =
        quotes
            .flatMap { quote ->
                quote.sellableUnitRequirements.map { requirement ->
                    val quantity =
                        try {
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
                val quantity =
                    requirements.fold(0L) { total, requirement ->
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
            }.sortedBy(StockRequirement::sellableUnitId)

    private companion object {
        val RESERVATION_LEASE: Duration = Duration.ofMinutes(5)
    }
}

internal data class BenefitOnlyConfirmation(
    val payment: BenefitOnlyPaymentResult,
    val pickup: ReservationTransitionReport,
    val stock: ReservationTransitionReport,
    val coupon: ReservationTransitionReport?,
    val points: ReservationTransitionReport,
)

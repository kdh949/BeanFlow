package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.fulfillment.api.PickupQuoteOperations
import io.github.kdh949.beanflow.fulfillment.api.PickupQuoteSnapshot
import io.github.kdh949.beanflow.inventory.api.StockQuoteItem
import io.github.kdh949.beanflow.inventory.api.StockQuoteOperations
import io.github.kdh949.beanflow.inventory.api.StockRequirement
import io.github.kdh949.beanflow.loyalty.api.PointQuoteOperations
import io.github.kdh949.beanflow.loyalty.api.PointQuoteSnapshot
import io.github.kdh949.beanflow.merchant.api.MenuLineQuote
import io.github.kdh949.beanflow.merchant.api.MerchantOrderQuoteOperations
import io.github.kdh949.beanflow.merchant.api.MerchantOrderQuoteSnapshot
import io.github.kdh949.beanflow.merchant.api.QuoteOrderLine
import io.github.kdh949.beanflow.merchant.api.StoreDisplaySnapshot
import io.github.kdh949.beanflow.merchant.api.StoreDisplaySnapshotOperations
import io.github.kdh949.beanflow.merchant.api.StoreSettlementTermsOperations
import io.github.kdh949.beanflow.merchant.api.StoreSettlementTermsSnapshot
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyQuoteOperations
import io.github.kdh949.beanflow.operations.api.SelectedOrdinaryPointAccrualPolicy
import io.github.kdh949.beanflow.ordering.api.CreateOrderCommand
import io.github.kdh949.beanflow.ordering.api.OrderQuoteCommand
import io.github.kdh949.beanflow.ordering.api.OrderQuoteLine
import io.github.kdh949.beanflow.ordering.api.OrderQuotePickupWindow
import io.github.kdh949.beanflow.ordering.api.OrderQuotePricing
import io.github.kdh949.beanflow.ordering.api.OrderQuoteResponse
import io.github.kdh949.beanflow.ordering.api.OrderQuoteStore
import io.github.kdh949.beanflow.ordering.internal.domain.Krw
import io.github.kdh949.beanflow.ordering.internal.domain.OrderPricing
import io.github.kdh949.beanflow.ordering.internal.domain.OrderPricingCalculator
import io.github.kdh949.beanflow.ordering.internal.domain.PricingLine
import io.github.kdh949.beanflow.promotion.api.CouponPricingLine
import io.github.kdh949.beanflow.promotion.api.CouponQuoteCommand
import io.github.kdh949.beanflow.promotion.api.CouponQuoteOperations
import io.github.kdh949.beanflow.promotion.api.CouponQuoteSnapshot
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock

internal data class OrderQuoteCalculation(
    val response: OrderQuoteResponse,
    val menu: MerchantOrderQuoteSnapshot,
    val storeDisplay: StoreDisplaySnapshot,
    val pickup: PickupQuoteSnapshot,
    val stock: List<StockQuoteItem>,
    val coupon: CouponQuoteSnapshot?,
    val points: PointQuoteSnapshot?,
    val pricing: OrderPricing,
    val settlementTerms: StoreSettlementTermsSnapshot,
    val pointAccrualPolicy: SelectedOrdinaryPointAccrualPolicy,
)

@Component
internal class OrderQuoteCoordinator(
    private val merchantQuoteOperations: MerchantOrderQuoteOperations,
    private val storeDisplayOperations: StoreDisplaySnapshotOperations,
    private val settlementTermsOperations: StoreSettlementTermsOperations,
    private val pickupQuoteOperations: PickupQuoteOperations,
    private val stockQuoteOperations: StockQuoteOperations,
    private val couponQuoteOperations: CouponQuoteOperations,
    private val pointQuoteOperations: PointQuoteOperations,
    private val pointAccrualPolicyOperations: OrdinaryPointAccrualPolicyQuoteOperations,
    private val clock: Clock,
) {
    private val pricingCalculator = OrderPricingCalculator()

    fun inspect(command: OrderQuoteCommand): OrderQuoteCalculation = calculate(command, lock = false)

    fun lockForOrderCreation(command: CreateOrderCommand): OrderQuoteCalculation =
        calculate(
            OrderQuoteCommand(
                customerId = command.customerId,
                storeId = command.storeId,
                pickupSlotId = command.pickupSlotId,
                lines = command.lines,
                couponIssuanceId = command.couponIssuanceId,
                pointsToUseKrw = command.pointsToUseKrw,
            ),
            lock = true,
        )

    private fun calculate(
        command: OrderQuoteCommand,
        lock: Boolean,
    ): OrderQuoteCalculation {
        validate(command)
        val quotedAt = clock.instant()
        val quoteLines = command.lines.map { QuoteOrderLine(it.menuId, it.optionIds, it.quantity) }
        val menu =
            if (lock) {
                merchantQuoteOperations.lockForOrderCreation(command.storeId, quoteLines)
            } else {
                merchantQuoteOperations.inspectForQuote(command.storeId, quoteLines)
            }
        requireMenuIdentity(command, menu)
        val storeDisplay = storeDisplayOperations.require(command.storeId)
        val settlementTerms = settlementTermsOperations.findApplicable(command.storeId, quotedAt)
        val pointAccrualPolicy =
            if (lock) {
                pointAccrualPolicyOperations.lockForOrderCreation(command.storeId)
            } else {
                pointAccrualPolicyOperations.inspectForQuote(command.storeId)
            }
        val stockRequirements = aggregateStockRequirements(menu.lines)
        val pickup =
            if (lock) {
                pickupQuoteOperations.lockForOrderCreation(command.storeId, command.pickupSlotId)
            } else {
                pickupQuoteOperations.inspect(command.storeId, command.pickupSlotId)
            }
        val stock =
            if (lock) {
                stockQuoteOperations.lockForOrderCreation(command.storeId, stockRequirements)
            } else {
                stockQuoteOperations.inspect(command.storeId, stockRequirements)
            }
        val grossLines = grossLines(menu.lines)
        val coupon =
            command.couponIssuanceId?.let { issuanceId ->
                val couponCommand =
                    CouponQuoteCommand(
                        customerId = command.customerId,
                        storeId = command.storeId,
                        couponIssuanceId = issuanceId,
                        lines = grossLines,
                    )
                if (lock) couponQuoteOperations.lockForOrderCreation(couponCommand) else couponQuoteOperations.inspect(couponCommand)
            }
        val pricing =
            pricingCalculator.calculate(
                lines =
                    menu.lines.mapIndexed { sequence, line ->
                        PricingLine(
                            lineSequence = sequence,
                            menuId = line.menuId,
                            unitPrice = Krw.of(line.unitPriceKrw),
                            quantity = line.quantity,
                            couponEligible = coupon?.eligibleLineSequences?.contains(sequence) ?: false,
                        )
                    },
                couponDiscount = Krw.of(coupon?.discountKrw ?: 0),
                pointsToUse = Krw.of(command.pointsToUseKrw),
            )
        val points =
            if (lock) {
                pointQuoteOperations.lockForOrderCreation(command.customerId, command.pointsToUseKrw)
            } else {
                pointQuoteOperations.inspect(command.customerId, command.pointsToUseKrw)
            }
        val publicLines =
            menu.lines.mapIndexed { sequence, line ->
                OrderQuoteLine(
                    menuId = line.menuId,
                    menuName = line.menuName,
                    quantity = line.quantity,
                    optionNames = line.optionSnapshots.map { it.name },
                    lineTotalKrw =
                        pricing.lines
                            .single { it.lineSequence == sequence }
                            .gross.value,
                )
            }
        val publicPricing =
            OrderQuotePricing(
                subtotalKrw = pricing.subtotal.value,
                couponDiscountKrw = pricing.couponDiscount.value,
                pointsAppliedKrw = pricing.pointsApplied.value,
                payableKrw = pricing.payable.value,
            )
        val fingerprint =
            OrderQuoteFingerprint.calculate(
                command = command,
                menu = menu,
                storeDisplay = storeDisplay,
                pickup = pickup,
                stock = stock,
                coupon = coupon,
                points = points,
                pricing = pricing,
                settlementTerms = settlementTerms,
                pointAccrualPolicy = pointAccrualPolicy,
                publicLines = publicLines,
                publicPricing = publicPricing,
            )
        return OrderQuoteCalculation(
            response =
                OrderQuoteResponse(
                    quotedAt = quotedAt,
                    quoteFingerprint = fingerprint,
                    store = OrderQuoteStore(command.storeId, storeDisplay.name),
                    pickupWindow = OrderQuotePickupWindow(pickup.startsAt, pickup.endsAt),
                    lines = publicLines,
                    pricing = publicPricing,
                ),
            menu = menu,
            storeDisplay = storeDisplay,
            pickup = pickup,
            stock = stock,
            coupon = coupon,
            points = points,
            pricing = pricing,
            settlementTerms = settlementTerms,
            pointAccrualPolicy = pointAccrualPolicy,
        )
    }

    private fun validate(command: OrderQuoteCommand) {
        if (command.lines.isEmpty() || command.pointsToUseKrw < 0) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Order lines and non-negative points are required")
        }
        if (command.lines.any { it.quantity < 1 || it.optionIds.size != it.optionIds.toSet().size }) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Line quantity and option IDs are invalid")
        }
    }

    private fun requireMenuIdentity(
        command: OrderQuoteCommand,
        menu: MerchantOrderQuoteSnapshot,
    ) {
        if (menu.lines.size != command.lines.size) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Merchant quote count does not match order lines")
        }
        command.lines.zip(menu.lines).forEach { (requested, quoted) ->
            if (requested.menuId != quoted.menuId || requested.quantity != quoted.quantity ||
                requested.optionIds.sortedBy { it.toString() } != quoted.optionSnapshots.map { it.optionId }
            ) {
                throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Merchant quote identity does not match order lines")
            }
        }
    }

    private fun grossLines(lines: List<MenuLineQuote>): List<CouponPricingLine> =
        lines.mapIndexed { sequence, line ->
            CouponPricingLine(sequence, line.menuId, Krw.of(line.unitPriceKrw).multiply(line.quantity).value)
        }

    private fun aggregateStockRequirements(quotes: List<MenuLineQuote>): List<StockRequirement> =
        quotes
            .flatMap { quote ->
                quote.sellableUnitRequirements.map { requirement ->
                    val quantity =
                        try {
                            Math.multiplyExact(requirement.quantityPerLineUnit, quote.quantity)
                        } catch (_: ArithmeticException) {
                            throw DomainFailure(FailureCode.INVALID_REQUEST, "Sellable unit requirement exceeds supported range")
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
                            throw DomainFailure(FailureCode.INVALID_REQUEST, "Aggregated sellable unit requirement exceeds supported range")
                        }
                    }
                StockRequirement(id, quantity)
            }.sortedBy { it.sellableUnitId.toString() }
}

internal object OrderQuoteFingerprint {
    private const val VERSION = "order-quote-fingerprint/v2"

    fun calculate(
        command: OrderQuoteCommand,
        menu: MerchantOrderQuoteSnapshot,
        storeDisplay: StoreDisplaySnapshot,
        pickup: PickupQuoteSnapshot,
        stock: List<StockQuoteItem>,
        coupon: CouponQuoteSnapshot?,
        points: PointQuoteSnapshot?,
        pricing: OrderPricing,
        settlementTerms: StoreSettlementTermsSnapshot,
        pointAccrualPolicy: SelectedOrdinaryPointAccrualPolicy,
        publicLines: List<OrderQuoteLine>,
        publicPricing: OrderQuotePricing,
    ): String {
        val canonical =
            CanonicalFields()
                .apply {
                    value(VERSION)
                    value(command.customerId)
                    value(command.storeId)
                    value(command.pickupSlotId)
                    value(command.couponIssuanceId)
                    value(command.pointsToUseKrw)
                    size(command.lines.size)
                    command.lines.forEach { line ->
                        value(line.menuId)
                        values(line.optionIds.sortedBy { it.toString() })
                        value(line.quantity)
                    }
                    value(menu.storeAcceptingOrders)
                    value(menu.storePickupEnabled)
                    size(menu.lines.size)
                    menu.lines.forEach { line ->
                        value(line.menuId)
                        value(line.menuName)
                        value(line.unitPriceKrw)
                        value(line.quantity)
                        size(line.optionSnapshots.size)
                        line.optionSnapshots.forEach { option ->
                            value(option.optionId)
                            value(option.name)
                            value(option.additionalPriceKrw)
                        }
                        size(line.sellableUnitRequirements.size)
                        line.sellableUnitRequirements.sortedBy { it.sellableUnitId.toString() }.forEach { requirement ->
                            value(requirement.sellableUnitId)
                            value(requirement.quantityPerLineUnit)
                        }
                    }
                    value(storeDisplay.storeId)
                    value(storeDisplay.name)
                    value(pickup.pickupSlotId)
                    value(pickup.storeId)
                    value(pickup.startsAt)
                    value(pickup.endsAt)
                    value(pickup.capacity)
                    value(pickup.reservedCount)
                    value(pickup.confirmedCount)
                    value(pickup.version)
                    size(stock.size)
                    stock.sortedBy { it.sellableUnitId.toString() }.forEach { item ->
                        value(item.sellableUnitId)
                        value(item.storeId)
                        value(item.requiredQuantity)
                        value(item.availableQuantity)
                        value(item.reservedQuantity)
                        value(item.confirmedQuantity)
                        value(item.version)
                    }
                    nullable(coupon) { current ->
                        value(current.couponIssuanceId)
                        value(current.issuanceVersion)
                        value(current.issuanceState)
                        value(current.couponExpiresAt)
                        value(current.originalIssuanceId)
                        value(current.discountKrw)
                        values(current.eligibleLineSequences.sorted())
                        value(current.allMenusEligible)
                        values(current.eligibleMenuIds.sortedBy { it.toString() })
                        value(current.discountType)
                        value(current.fixedAmountKrw)
                        value(current.rateBps)
                        value(current.minimumEligibleSubtotalKrw)
                        value(current.maximumDiscountKrw)
                        value(current.campaignId)
                        value(current.campaignVersion)
                        value(current.costBearer)
                        value(current.platformShareBps)
                        value(current.storeShareBps)
                        value(current.platformCouponCostKrw)
                        value(current.storeCouponCostKrw)
                    }
                    nullable(points) { current ->
                        value(current.pointAccountId)
                        value(current.accountVersion)
                        value(current.availablePointsKrw)
                        value(current.requestedPointsKrw)
                        size(current.allocations.size)
                        current.allocations.forEach { allocation ->
                            value(allocation.pointLotId)
                            value(allocation.lotVersion)
                            value(allocation.expiresAt)
                            value(allocation.issuerType)
                            value(allocation.issuerReference)
                            value(allocation.availableAmountKrw)
                            value(allocation.allocationKrw)
                        }
                    }
                    value(settlementTerms.termsVersionId)
                    value(settlementTerms.storeId)
                    value(settlementTerms.sourceReference)
                    value(settlementTerms.feeRateBps)
                    value(settlementTerms.effectiveFrom)
                    value(settlementTerms.effectiveTo)
                    value(pointAccrualPolicy.selectionSource)
                    with(pointAccrualPolicy.policy) {
                        value(policyVersionId)
                        value(scopeType)
                        value(scopeReference)
                        value(accrualRateBps)
                        value(roundingMode)
                        value(issuerType)
                        value(issuerReference)
                        value(expiryRule)
                        value(validityDays)
                        value(canonicalPolicyHash)
                    }
                    size(pricing.lines.size)
                    pricing.lines.forEach { line ->
                        value(line.lineSequence)
                        value(line.menuId)
                        value(line.unitPrice.value)
                        value(line.quantity)
                        value(line.gross.value)
                        value(line.couponDiscount.value)
                        value(line.pointsApplied.value)
                        value(line.cashPayable.value)
                    }
                    size(publicLines.size)
                    publicLines.forEach { line ->
                        value(line.menuId)
                        value(line.menuName)
                        value(line.quantity)
                        values(line.optionNames)
                        value(line.lineTotalKrw)
                    }
                    value(publicPricing.subtotalKrw)
                    value(publicPricing.couponDiscountKrw)
                    value(publicPricing.pointsAppliedKrw)
                    value(publicPricing.payableKrw)
                    value(publicPricing.currency)
                    value("NONE")
                }.toString()
        return MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private class CanonicalFields {
        private val target = StringBuilder()

        fun value(value: Any?) {
            val text = value?.toString()
            if (text == null) target.append("-1:") else target.append(text.length).append(':').append(text)
            target.append('|')
        }

        fun size(size: Int) = value(size)

        fun values(values: Collection<*>) {
            size(values.size)
            values.forEach(::value)
        }

        fun <T : Any> nullable(
            current: T?,
            block: CanonicalFields.(T) -> Unit,
        ) {
            if (current == null) {
                value(null)
            } else {
                value("present")
                block(current)
            }
        }

        override fun toString(): String = target.toString()
    }
}

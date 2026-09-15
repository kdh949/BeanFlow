package io.github.kdh949.beanflow.ordering.internal.domain

import io.github.kdh949.beanflow.merchant.api.MenuLineQuote
import io.github.kdh949.beanflow.merchant.api.OptionSnapshot
import io.github.kdh949.beanflow.merchant.api.StoreSettlementTermsSnapshot
import io.github.kdh949.beanflow.promotion.api.CouponQuoteSnapshot
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class OrderState {
    PENDING_PAYMENT,
    PAID,
    ACCEPTED,
    PREPARING,
    READY,
    COMPLETED,
    REJECTED,
    EXPIRED,
    CANCELLED,
}

enum class CheckoutMode {
    LEGACY_RESERVED,
    IMMEDIATE,
}

data class CheckoutInputSnapshot(
    val schemaVersion: Int,
    val couponIssuanceId: UUID?,
    val pointsToUseKrw: Long,
    val quoteFingerprint: String,
    val cartRevision: Long?,
    val subtotalKrw: Long,
    val couponDiscountKrw: Long,
    val pointsAppliedKrw: Long,
    val payableKrw: Long,
    val settlementTerms: StoreSettlementTermsSnapshot,
    val couponQuote: CouponQuoteSnapshot?,
)

data class OrderLineSnapshot(
    val id: UUID,
    val lineSequence: Int,
    val menuId: UUID,
    val menuName: String,
    val options: List<OptionSnapshot>,
    val unitPriceKrw: Long,
    val quantity: Long,
    val grossKrw: Long,
    val couponDiscountKrw: Long,
    val pointsAppliedKrw: Long,
    val cashPayableKrw: Long,
)

data class OrderDisplayIdentitySnapshot(
    val publicReference: String,
    val pickupBusinessDate: LocalDate,
    val pickupSequence: Long,
    val storeName: String,
    val pickupWindowStart: Instant?,
    val pickupWindowEnd: Instant?,
) {
    init {
        require(PUBLIC_REFERENCE_FORMAT.matches(publicReference)) { "Public order reference format is invalid" }
        require(pickupSequence > 0) { "Pickup sequence must be positive" }
        require(storeName == storeName.trim() && storeName.length in 1..200) { "Store display name is invalid" }
        require(
            (pickupWindowStart == null && pickupWindowEnd == null) ||
                (pickupWindowStart != null && pickupWindowEnd != null && pickupWindowEnd.isAfter(pickupWindowStart)),
        ) { "Pickup window is invalid" }
    }

    val pickupNumber: String
        get() = "A-$pickupSequence"

    private companion object {
        val PUBLIC_REFERENCE_FORMAT = Regex("^BF-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$")
    }
}

class Order private constructor(
    val id: UUID,
    val customerId: UUID,
    val storeId: UUID,
    val pickupSlotId: UUID?,
    val displayIdentity: OrderDisplayIdentitySnapshot,
    val state: OrderState,
    val lines: List<OrderLineSnapshot>,
    val subtotalKrw: Long,
    val couponDiscountKrw: Long,
    val pointsAppliedKrw: Long,
    val payableKrw: Long,
    val createdAt: Instant,
    val reservationExpiresAt: Instant?,
    val paidAt: Instant?,
    val acceptanceWarningAt: Instant?,
    val acceptanceDeadlineAt: Instant?,
    val checkoutMode: CheckoutMode,
    val orderingWindowClosesAt: Instant?,
    val checkoutInputSnapshot: CheckoutInputSnapshot?,
) {
    companion object {
        private val ACCEPTANCE_WARNING_DELAY: Duration = Duration.ofMinutes(2)
        private val ACCEPTANCE_DEADLINE_DELAY: Duration = Duration.ofMinutes(3)

        fun pendingPayment(
            id: UUID,
            customerId: UUID,
            storeId: UUID,
            pickupSlotId: UUID,
            displayIdentity: OrderDisplayIdentitySnapshot,
            lineIds: List<UUID>,
            quotes: List<MenuLineQuote>,
            pricing: OrderPricing,
            createdAt: Instant,
            reservationExpiresAt: Instant,
        ): Order {
            if (quotes.size != pricing.lines.size || lineIds.size != quotes.size) {
                invalid("Quote, pricing and line identifiers must have the same size")
            }
            if (pricing.payable == Krw.ZERO) {
                invalid("A pending-payment order must have a positive payable amount")
            }
            if (!reservationExpiresAt.isAfter(createdAt)) {
                invalid("A reservation deadline must be after order creation")
            }
            val snapshots = snapshots(lineIds, quotes, pricing)
            return Order(
                id = id,
                customerId = customerId,
                storeId = storeId,
                pickupSlotId = pickupSlotId,
                displayIdentity = displayIdentity,
                state = OrderState.PENDING_PAYMENT,
                lines = snapshots,
                subtotalKrw = pricing.subtotal.value,
                couponDiscountKrw = pricing.couponDiscount.value,
                pointsAppliedKrw = pricing.pointsApplied.value,
                payableKrw = pricing.payable.value,
                createdAt = createdAt,
                reservationExpiresAt = reservationExpiresAt,
                paidAt = null,
                acceptanceWarningAt = null,
                acceptanceDeadlineAt = null,
                checkoutMode = CheckoutMode.LEGACY_RESERVED,
                orderingWindowClosesAt = null,
                checkoutInputSnapshot = null,
            )
        }

        fun benefitOnlyPaid(
            id: UUID,
            customerId: UUID,
            storeId: UUID,
            pickupSlotId: UUID,
            displayIdentity: OrderDisplayIdentitySnapshot,
            lineIds: List<UUID>,
            quotes: List<MenuLineQuote>,
            pricing: OrderPricing,
            createdAt: Instant,
        ): Order {
            if (quotes.size != pricing.lines.size || lineIds.size != quotes.size) {
                invalid("Quote, pricing and line identifiers must have the same size")
            }
            if (pricing.payable != Krw.ZERO || pricing.pointsApplied == Krw.ZERO) {
                invalid("A BENEFIT_ONLY order requires zero payable and positive applied points")
            }
            val snapshots = snapshots(lineIds, quotes, pricing)
            return Order(
                id = id,
                customerId = customerId,
                storeId = storeId,
                pickupSlotId = pickupSlotId,
                displayIdentity = displayIdentity,
                state = OrderState.PAID,
                lines = snapshots,
                subtotalKrw = pricing.subtotal.value,
                couponDiscountKrw = pricing.couponDiscount.value,
                pointsAppliedKrw = pricing.pointsApplied.value,
                payableKrw = pricing.payable.value,
                createdAt = createdAt,
                reservationExpiresAt = null,
                paidAt = createdAt,
                acceptanceWarningAt = createdAt.plus(ACCEPTANCE_WARNING_DELAY),
                acceptanceDeadlineAt = createdAt.plus(ACCEPTANCE_DEADLINE_DELAY),
                checkoutMode = CheckoutMode.LEGACY_RESERVED,
                orderingWindowClosesAt = null,
                checkoutInputSnapshot = null,
            )
        }

        fun pendingImmediate(
            id: UUID,
            customerId: UUID,
            storeId: UUID,
            displayIdentity: OrderDisplayIdentitySnapshot,
            lineIds: List<UUID>,
            quotes: List<MenuLineQuote>,
            pricing: OrderPricing,
            createdAt: Instant,
            orderingWindowClosesAt: Instant,
            checkoutInputSnapshot: CheckoutInputSnapshot,
        ): Order =
            immediate(
                id,
                customerId,
                storeId,
                displayIdentity,
                lineIds,
                quotes,
                pricing,
                createdAt,
                orderingWindowClosesAt,
                checkoutInputSnapshot,
                paid = false,
            )

        fun benefitOnlyImmediatePaid(
            id: UUID,
            customerId: UUID,
            storeId: UUID,
            displayIdentity: OrderDisplayIdentitySnapshot,
            lineIds: List<UUID>,
            quotes: List<MenuLineQuote>,
            pricing: OrderPricing,
            createdAt: Instant,
            orderingWindowClosesAt: Instant,
            checkoutInputSnapshot: CheckoutInputSnapshot,
        ): Order =
            immediate(
                id,
                customerId,
                storeId,
                displayIdentity,
                lineIds,
                quotes,
                pricing,
                createdAt,
                orderingWindowClosesAt,
                checkoutInputSnapshot,
                paid = true,
            )

        private fun immediate(
            id: UUID,
            customerId: UUID,
            storeId: UUID,
            displayIdentity: OrderDisplayIdentitySnapshot,
            lineIds: List<UUID>,
            quotes: List<MenuLineQuote>,
            pricing: OrderPricing,
            createdAt: Instant,
            orderingWindowClosesAt: Instant,
            checkoutInputSnapshot: CheckoutInputSnapshot,
            paid: Boolean,
        ): Order {
            if (quotes.size != pricing.lines.size || lineIds.size != quotes.size) {
                invalid("Quote, pricing and line identifiers must have the same size")
            }
            if (!orderingWindowClosesAt.isAfter(createdAt)) invalid("Store ordering window has closed")
            if (paid && (pricing.payable != Krw.ZERO || pricing.pointsApplied == Krw.ZERO)) {
                invalid("A BENEFIT_ONLY order requires zero payable and positive applied points")
            }
            if (!paid && pricing.payable == Krw.ZERO) invalid("A pending-payment order must have a positive payable amount")
            if (displayIdentity.pickupWindowStart != null || displayIdentity.pickupWindowEnd != null) {
                invalid("An immediate order must not contain a pickup window")
            }
            if (checkoutInputSnapshot.subtotalKrw != pricing.subtotal.value ||
                checkoutInputSnapshot.couponDiscountKrw != pricing.couponDiscount.value ||
                checkoutInputSnapshot.pointsAppliedKrw != pricing.pointsApplied.value ||
                checkoutInputSnapshot.payableKrw != pricing.payable.value
            ) {
                invalid("Immediate checkout input does not match pricing")
            }
            val paidAt = createdAt.takeIf { paid }
            val deadline = paidAt?.let { minOf(it.plus(ACCEPTANCE_DEADLINE_DELAY), orderingWindowClosesAt) }
            if (deadline != null && !deadline.isAfter(createdAt)) invalid("Store ordering window has closed")
            val warningAt =
                deadline?.let { finalDeadline ->
                    paidAt?.plus(ACCEPTANCE_WARNING_DELAY)?.takeIf { it.isBefore(finalDeadline) }
                }
            return Order(
                id = id,
                customerId = customerId,
                storeId = storeId,
                pickupSlotId = null,
                displayIdentity = displayIdentity,
                state = if (paid) OrderState.PAID else OrderState.PENDING_PAYMENT,
                lines = snapshots(lineIds, quotes, pricing),
                subtotalKrw = pricing.subtotal.value,
                couponDiscountKrw = pricing.couponDiscount.value,
                pointsAppliedKrw = pricing.pointsApplied.value,
                payableKrw = pricing.payable.value,
                createdAt = createdAt,
                reservationExpiresAt = null,
                paidAt = paidAt,
                acceptanceWarningAt = warningAt,
                acceptanceDeadlineAt = deadline,
                checkoutMode = CheckoutMode.IMMEDIATE,
                orderingWindowClosesAt = orderingWindowClosesAt,
                checkoutInputSnapshot = checkoutInputSnapshot,
            )
        }

        private fun snapshots(
            lineIds: List<UUID>,
            quotes: List<MenuLineQuote>,
            pricing: OrderPricing,
        ): List<OrderLineSnapshot> =
            quotes.indices.map { index ->
                val quote = quotes[index]
                val priced = pricing.lines[index]
                if (priced.lineSequence != index || quote.menuId != priced.menuId) {
                    invalid("Quote and pricing line order must match")
                }
                OrderLineSnapshot(
                    id = lineIds[index],
                    lineSequence = index,
                    menuId = quote.menuId,
                    menuName = quote.menuName,
                    options = quote.optionSnapshots.toList(),
                    unitPriceKrw = quote.unitPriceKrw,
                    quantity = quote.quantity,
                    grossKrw = priced.gross.value,
                    couponDiscountKrw = priced.couponDiscount.value,
                    pointsAppliedKrw = priced.pointsApplied.value,
                    cashPayableKrw = priced.cashPayable.value,
                )
            }

        private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)
    }
}

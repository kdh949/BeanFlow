package io.github.kdh949.beanflow.shared.api

import java.time.Instant
import java.util.UUID

enum class SupportTimelineSource(
    val rank: Int,
) {
    SUPPORT(0),
    ORDERING(10),
    PAYMENT(20),
    LOYALTY(30),
    PROMOTION(40),
    FULFILLMENT(50),
    SETTLEMENT(60),
    NOTIFICATION(70),
    OPERATIONS(80),
}

enum class SupportTimelineType {
    CASE_STATE,
    CASE_ASSIGNMENT,
    CASE_INTERACTION,
    CASE_NOTE,
    SUBJECT_LINK,
    ORDER_STATE,
    PAYMENT_STATE,
    REFUND_STATE,
    POINT_RESERVATION,
    COUPON_RESERVATION,
    PICKUP_RESERVATION,
    SETTLEMENT_ITEM,
    SETTLEMENT_ADJUSTMENT,
    NOTIFICATION_DELIVERY,
    OPERATION_AUDIT,
}

enum class SupportTimelineState {
    OPEN,
    PENDING_CUSTOMER,
    PENDING_STORE,
    ESCALATED,
    RESOLVED,
    CLOSED,
    ASSIGNED,
    INBOUND,
    OUTBOUND,
    INTERNAL,
    RECORDED,
    LINKED,
    UNLINKED,
    PENDING_PAYMENT,
    PAID,
    ACCEPTED,
    PREPARING,
    READY,
    COMPLETED,
    REJECTED,
    EXPIRED,
    CANCELLED,
    APPROVING,
    APPROVED,
    FAILED,
    UNKNOWN,
    RECONCILING,
    MANUAL_REVIEW,
    REQUESTED,
    PROCESSING,
    SUCCEEDED,
    RESERVED,
    USED,
    RELEASED,
    CONFIRMED,
    ITEM_CREATED,
    ADJUSTMENT_RECORDED,
    PENDING,
    RETRY_SCHEDULED,
}

data class SupportOwnerTimelineFact(
    val source: SupportTimelineSource,
    val type: SupportTimelineType,
    val itemId: UUID,
    val state: SupportTimelineState,
    val occurredAt: Instant,
    val amountKrw: Long?,
)

data class SupportTimelineBoundary(
    val occurredAt: Instant,
    val source: SupportTimelineSource,
    val itemId: UUID,
) {
    fun isBefore(fact: SupportOwnerTimelineFact): Boolean = SUPPORT_TIMELINE_COMPARATOR.compare(fact, toFact()) > 0

    private fun toFact(): SupportOwnerTimelineFact =
        SupportOwnerTimelineFact(
            source = source,
            type = SupportTimelineType.ORDER_STATE,
            itemId = itemId,
            state = SupportTimelineState.RECORDED,
            occurredAt = occurredAt,
            amountKrw = null,
        )
}

data class SupportOwnerTimelineQuery(
    val orderIds: Set<UUID>,
    val after: SupportTimelineBoundary?,
    val limit: Int,
) {
    init {
        require(orderIds.isNotEmpty() && orderIds.size <= MAX_ORDER_IDS) { "Timeline query requires one to 100 Order IDs" }
        require(limit in 1..MAX_OWNER_FACTS) { "Timeline owner page must contain one to 101 facts" }
    }

    companion object {
        const val MAX_ORDER_IDS = 100
        const val MAX_OWNER_FACTS = 101
    }
}

val SUPPORT_TIMELINE_COMPARATOR: Comparator<SupportOwnerTimelineFact> =
    Comparator { left, right ->
        val time = right.occurredAt.compareTo(left.occurredAt)
        if (time != 0) {
            time
        } else {
            val source = left.source.rank.compareTo(right.source.rank)
            if (source != 0) source else right.itemId.toString().compareTo(left.itemId.toString())
        }
    }

fun SupportOwnerTimelineQuery.accepts(fact: SupportOwnerTimelineFact): Boolean = after?.isBefore(fact) ?: true

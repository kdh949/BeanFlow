package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.fulfillment.api.FulfillmentSupportTimelineOperations
import io.github.kdh949.beanflow.loyalty.api.LoyaltySupportTimelineOperations
import io.github.kdh949.beanflow.notification.api.NotificationSupportTimelineOperations
import io.github.kdh949.beanflow.operations.api.OperationsSupportTimelineOperations
import io.github.kdh949.beanflow.ordering.api.OrderingSupportTimelineOperations
import io.github.kdh949.beanflow.payment.api.PaymentSupportTimelineOperations
import io.github.kdh949.beanflow.promotion.api.PromotionSupportTimelineOperations
import io.github.kdh949.beanflow.settlement.api.SettlementSupportTimelineOperations
import io.github.kdh949.beanflow.shared.api.SUPPORT_TIMELINE_COMPARATOR
import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineFact
import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineQuery
import io.github.kdh949.beanflow.shared.api.SupportTimelineSource
import io.github.kdh949.beanflow.shared.api.SupportTimelineType
import org.springframework.stereotype.Component

@Component
internal class SupportTimelineOwnerQueries(
    private val ordering: OrderingSupportTimelineOperations,
    private val payment: PaymentSupportTimelineOperations,
    private val loyalty: LoyaltySupportTimelineOperations,
    private val promotion: PromotionSupportTimelineOperations,
    private val fulfillment: FulfillmentSupportTimelineOperations,
    private val settlement: SettlementSupportTimelineOperations,
    private val notification: NotificationSupportTimelineOperations,
    private val operations: OperationsSupportTimelineOperations,
) {
    fun findFacts(
        query: SupportOwnerTimelineQuery,
        sources: Set<SupportTimelineSource>,
    ): List<SupportOwnerTimelineFact> =
        buildList {
            if (selected(SupportTimelineSource.ORDERING, query.types, sources)) addAll(ordering.findTimelineFacts(query))
            if (selected(SupportTimelineSource.PAYMENT, query.types, sources)) addAll(payment.findTimelineFacts(query))
            if (selected(SupportTimelineSource.LOYALTY, query.types, sources)) addAll(loyalty.findTimelineFacts(query))
            if (selected(SupportTimelineSource.PROMOTION, query.types, sources)) addAll(promotion.findTimelineFacts(query))
            if (selected(SupportTimelineSource.FULFILLMENT, query.types, sources)) addAll(fulfillment.findTimelineFacts(query))
            if (selected(SupportTimelineSource.SETTLEMENT, query.types, sources)) addAll(settlement.findTimelineFacts(query))
            if (selected(SupportTimelineSource.NOTIFICATION, query.types, sources)) addAll(notification.findTimelineFacts(query))
            if (selected(SupportTimelineSource.OPERATIONS, query.types, sources)) addAll(operations.findTimelineFacts(query))
        }.sortedWith(SUPPORT_TIMELINE_COMPARATOR)
            .take(query.limit)

    private fun selected(
        source: SupportTimelineSource,
        types: Set<SupportTimelineType>,
        sources: Set<SupportTimelineSource>,
    ): Boolean =
        (sources.isEmpty() || source in sources) &&
            (types.isEmpty() || types.any { TYPE_SOURCE[it] == source })

    companion object {
        private val TYPE_SOURCE =
            mapOf(
                SupportTimelineType.ORDER_STATE to SupportTimelineSource.ORDERING,
                SupportTimelineType.PAYMENT_STATE to SupportTimelineSource.PAYMENT,
                SupportTimelineType.REFUND_STATE to SupportTimelineSource.PAYMENT,
                SupportTimelineType.POINT_RESERVATION to SupportTimelineSource.LOYALTY,
                SupportTimelineType.COUPON_RESERVATION to SupportTimelineSource.PROMOTION,
                SupportTimelineType.PICKUP_RESERVATION to SupportTimelineSource.FULFILLMENT,
                SupportTimelineType.SETTLEMENT_ITEM to SupportTimelineSource.SETTLEMENT,
                SupportTimelineType.SETTLEMENT_ADJUSTMENT to SupportTimelineSource.SETTLEMENT,
                SupportTimelineType.NOTIFICATION_DELIVERY to SupportTimelineSource.NOTIFICATION,
                SupportTimelineType.OPERATION_AUDIT to SupportTimelineSource.OPERATIONS,
            )
    }
}

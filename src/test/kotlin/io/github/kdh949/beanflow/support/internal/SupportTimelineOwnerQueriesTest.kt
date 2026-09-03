package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.fulfillment.api.FulfillmentSupportTimelineOperations
import io.github.kdh949.beanflow.loyalty.api.LoyaltySupportTimelineOperations
import io.github.kdh949.beanflow.notification.api.NotificationSupportTimelineOperations
import io.github.kdh949.beanflow.operations.api.OperationsSupportTimelineOperations
import io.github.kdh949.beanflow.ordering.api.OrderingSupportTimelineOperations
import io.github.kdh949.beanflow.ordering.api.SupportOrderOverviewSnapshot
import io.github.kdh949.beanflow.ordering.api.SupportOrderSnapshot
import io.github.kdh949.beanflow.payment.api.PaymentSupportTimelineOperations
import io.github.kdh949.beanflow.promotion.api.PromotionSupportTimelineOperations
import io.github.kdh949.beanflow.settlement.api.SettlementSupportTimelineOperations
import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineFact
import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineQuery
import io.github.kdh949.beanflow.shared.api.SupportTimelineSource
import io.github.kdh949.beanflow.shared.api.SupportTimelineState
import io.github.kdh949.beanflow.shared.api.SupportTimelineType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class SupportTimelineOwnerQueriesTest {
    @Test
    fun `multi order case calls every selected owner exactly once`() {
        val owners = SupportTimelineSource.entries.filterNot { it == SupportTimelineSource.SUPPORT }.associateWith(::CountingOwner)
        val queries =
            SupportTimelineOwnerQueries(
                owners.getValue(SupportTimelineSource.ORDERING),
                owners.getValue(SupportTimelineSource.PAYMENT),
                owners.getValue(SupportTimelineSource.LOYALTY),
                owners.getValue(SupportTimelineSource.PROMOTION),
                owners.getValue(SupportTimelineSource.FULFILLMENT),
                owners.getValue(SupportTimelineSource.SETTLEMENT),
                owners.getValue(SupportTimelineSource.NOTIFICATION),
                owners.getValue(SupportTimelineSource.OPERATIONS),
            )
        val orderIds = (1..100).map { UUID.randomUUID() }.toSet()

        val facts = queries.findFacts(SupportOwnerTimelineQuery(orderIds, null, 101), emptySet())

        assertThat(facts.map { it.source }).containsExactlyElementsOf(owners.keys)
        assertThat(owners.values).allSatisfy { owner ->
            assertThat(owner.invocations).hasValue(1)
            assertThat(owner.observedOrderCount).isEqualTo(100)
        }
    }

    @Test
    fun `source and type filters skip unrelated owner queries`() {
        val owners = SupportTimelineSource.entries.filterNot { it == SupportTimelineSource.SUPPORT }.associateWith(::CountingOwner)
        val queries =
            SupportTimelineOwnerQueries(
                owners.getValue(SupportTimelineSource.ORDERING),
                owners.getValue(SupportTimelineSource.PAYMENT),
                owners.getValue(SupportTimelineSource.LOYALTY),
                owners.getValue(SupportTimelineSource.PROMOTION),
                owners.getValue(SupportTimelineSource.FULFILLMENT),
                owners.getValue(SupportTimelineSource.SETTLEMENT),
                owners.getValue(SupportTimelineSource.NOTIFICATION),
                owners.getValue(SupportTimelineSource.OPERATIONS),
            )

        queries.findFacts(
            SupportOwnerTimelineQuery(
                setOf(UUID.randomUUID()),
                null,
                20,
                setOf(SupportTimelineType.REFUND_STATE),
            ),
            setOf(SupportTimelineSource.PAYMENT, SupportTimelineSource.NOTIFICATION),
        )

        assertThat(owners.getValue(SupportTimelineSource.PAYMENT).invocations).hasValue(1)
        assertThat(owners.filterKeys { it != SupportTimelineSource.PAYMENT }.values)
            .allSatisfy { assertThat(it.invocations).hasValue(0) }
    }

    private class CountingOwner(
        private val source: SupportTimelineSource,
    ) : OrderingSupportTimelineOperations,
        PaymentSupportTimelineOperations,
        LoyaltySupportTimelineOperations,
        PromotionSupportTimelineOperations,
        FulfillmentSupportTimelineOperations,
        SettlementSupportTimelineOperations,
        NotificationSupportTimelineOperations,
        OperationsSupportTimelineOperations {
        val invocations = AtomicInteger()
        var observedOrderCount = 0

        override fun findTimelineFacts(query: SupportOwnerTimelineQuery): List<SupportOwnerTimelineFact> {
            invocations.incrementAndGet()
            observedOrderCount = query.orderIds.size
            return listOf(
                SupportOwnerTimelineFact(
                    source = source,
                    type = typeFor(source),
                    itemId = UUID.nameUUIDFromBytes(source.name.toByteArray()),
                    state = SupportTimelineState.RECORDED,
                    occurredAt = Instant.parse("2026-08-12T06:00:00Z").minusSeconds(source.rank.toLong()),
                    amountKrw = null,
                ),
            )
        }

        override fun findOrderSnapshots(orderIds: Set<UUID>): List<SupportOrderSnapshot> = emptyList()

        override fun findOrderOverviews(orderIds: Set<UUID>): List<SupportOrderOverviewSnapshot> = emptyList()

        private fun typeFor(source: SupportTimelineSource): SupportTimelineType =
            when (source) {
                SupportTimelineSource.ORDERING -> SupportTimelineType.ORDER_STATE
                SupportTimelineSource.PAYMENT -> SupportTimelineType.REFUND_STATE
                SupportTimelineSource.LOYALTY -> SupportTimelineType.POINT_RESERVATION
                SupportTimelineSource.PROMOTION -> SupportTimelineType.COUPON_RESERVATION
                SupportTimelineSource.FULFILLMENT -> SupportTimelineType.PICKUP_RESERVATION
                SupportTimelineSource.SETTLEMENT -> SupportTimelineType.SETTLEMENT_ITEM
                SupportTimelineSource.NOTIFICATION -> SupportTimelineType.NOTIFICATION_DELIVERY
                SupportTimelineSource.OPERATIONS -> SupportTimelineType.OPERATION_AUDIT
                SupportTimelineSource.SUPPORT -> error("Support has no owner query")
            }
    }
}

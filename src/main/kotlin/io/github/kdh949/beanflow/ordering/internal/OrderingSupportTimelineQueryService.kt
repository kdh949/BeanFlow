package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.OrderingSupportTimelineOperations
import io.github.kdh949.beanflow.ordering.api.SupportOrderSnapshot
import io.github.kdh949.beanflow.ordering.api.SupportOrderState
import io.github.kdh949.beanflow.shared.api.SUPPORT_TIMELINE_COMPARATOR
import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineFact
import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineQuery
import io.github.kdh949.beanflow.shared.api.SupportTimelineSource
import io.github.kdh949.beanflow.shared.api.SupportTimelineState
import io.github.kdh949.beanflow.shared.api.SupportTimelineType
import io.github.kdh949.beanflow.shared.api.accepts
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

@Service
internal class OrderingSupportTimelineQueryService(
    private val jdbcTemplate: JdbcTemplate,
) : OrderingSupportTimelineOperations {
    override fun findTimelineFacts(query: SupportOwnerTimelineQuery): List<SupportOwnerTimelineFact> =
        findOrders(query.orderIds)
            .flatMap { it.timelineFacts() }
            .asSequence()
            .filter(query::accepts)
            .sortedWith(SUPPORT_TIMELINE_COMPARATOR)
            .take(query.limit)
            .toList()

    override fun findOrderSnapshots(orderIds: Set<UUID>): List<SupportOrderSnapshot> {
        require(orderIds.isNotEmpty() && orderIds.size <= SupportOwnerTimelineQuery.MAX_ORDER_IDS)
        return findOrders(orderIds).map {
            SupportOrderSnapshot(it.id, it.customerId, it.storeId, SupportOrderState.valueOf(it.state), it.version)
        }
    }

    private fun findOrders(orderIds: Set<UUID>): List<OrderTimelineRow> {
        val ids = orderIds.sortedBy(UUID::toString)
        val placeholders = ids.joinToString(",") { "?" }
        return jdbcTemplate.query(
            """
            SELECT id, customer_id, store_id, state, payable_krw, version,
                   created_at, updated_at, paid_at, accepted_at, rejected_at,
                   preparing_at, ready_at, completed_at, cancelled_at
              FROM ordering_order
             WHERE id IN ($placeholders)
            """.trimIndent(),
            { resultSet, _ -> resultSet.toTimelineRow() },
            *ids.toTypedArray(),
        )
    }

    private fun ResultSet.toTimelineRow(): OrderTimelineRow =
        OrderTimelineRow(
            id = getObject("id", UUID::class.java),
            customerId = getObject("customer_id", UUID::class.java),
            storeId = getObject("store_id", UUID::class.java),
            state = getString("state"),
            payableKrw = getLong("payable_krw"),
            version = getLong("version"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
            paidAt = getTimestamp("paid_at")?.toInstant(),
            acceptedAt = getTimestamp("accepted_at")?.toInstant(),
            rejectedAt = getTimestamp("rejected_at")?.toInstant(),
            preparingAt = getTimestamp("preparing_at")?.toInstant(),
            readyAt = getTimestamp("ready_at")?.toInstant(),
            completedAt = getTimestamp("completed_at")?.toInstant(),
            cancelledAt = getTimestamp("cancelled_at")?.toInstant(),
        )

    private data class OrderTimelineRow(
        val id: UUID,
        val customerId: UUID,
        val storeId: UUID,
        val state: String,
        val payableKrw: Long,
        val version: Long,
        val createdAt: Instant,
        val updatedAt: Instant,
        val paidAt: Instant?,
        val acceptedAt: Instant?,
        val rejectedAt: Instant?,
        val preparingAt: Instant?,
        val readyAt: Instant?,
        val completedAt: Instant?,
        val cancelledAt: Instant?,
    ) {
        fun timelineFacts(): List<SupportOwnerTimelineFact> {
            val occurrences =
                buildList {
                    add(SupportTimelineState.PENDING_PAYMENT to createdAt)
                    paidAt?.let { add(SupportTimelineState.PAID to it) }
                    acceptedAt?.let { add(SupportTimelineState.ACCEPTED to it) }
                    rejectedAt?.let { add(SupportTimelineState.REJECTED to it) }
                    preparingAt?.let { add(SupportTimelineState.PREPARING to it) }
                    readyAt?.let { add(SupportTimelineState.READY to it) }
                    completedAt?.let { add(SupportTimelineState.COMPLETED to it) }
                    cancelledAt?.let { add(SupportTimelineState.CANCELLED to it) }
                }.toMutableList()
            val currentState = SupportTimelineState.valueOf(state)
            if (occurrences.none { it.first == currentState }) occurrences += currentState to updatedAt
            return occurrences.map { (eventState, occurredAt) ->
                SupportOwnerTimelineFact(
                    source = SupportTimelineSource.ORDERING,
                    type = SupportTimelineType.ORDER_STATE,
                    itemId = eventId(eventState),
                    state = eventState,
                    occurredAt = occurredAt,
                    amountKrw = payableKrw,
                )
            }
        }

        private fun eventId(state: SupportTimelineState): UUID =
            UUID.nameUUIDFromBytes(
                "support-timeline:ordering:$id:${state.name}".toByteArray(StandardCharsets.UTF_8),
            )
    }
}

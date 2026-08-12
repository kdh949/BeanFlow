package io.github.kdh949.beanflow.fulfillment.internal

import io.github.kdh949.beanflow.fulfillment.api.FulfillmentSupportTimelineOperations
import io.github.kdh949.beanflow.shared.api.SUPPORT_TIMELINE_COMPARATOR
import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineFact
import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineQuery
import io.github.kdh949.beanflow.shared.api.SupportTimelineSource
import io.github.kdh949.beanflow.shared.api.SupportTimelineState
import io.github.kdh949.beanflow.shared.api.SupportTimelineType
import io.github.kdh949.beanflow.shared.api.accepts
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

@Service
internal class FulfillmentSupportTimelineQueryService(
    private val jdbcTemplate: JdbcTemplate,
) : FulfillmentSupportTimelineOperations {
    override fun findTimelineFacts(query: SupportOwnerTimelineQuery): List<SupportOwnerTimelineFact> {
        val ids = query.orderIds.sortedBy(UUID::toString)
        val placeholders = ids.joinToString(",") { "?" }
        return jdbcTemplate
            .query(
                """
                SELECT id, state, updated_at
                  FROM fulfillment_pickup_reservation
                 WHERE order_id IN ($placeholders)
                """.trimIndent(),
                { resultSet, _ ->
                    SupportOwnerTimelineFact(
                        source = SupportTimelineSource.FULFILLMENT,
                        type = SupportTimelineType.PICKUP_RESERVATION,
                        itemId = resultSet.getObject("id", UUID::class.java),
                        state = SupportTimelineState.valueOf(resultSet.getString("state")),
                        occurredAt = resultSet.getTimestamp("updated_at").toInstant(),
                        amountKrw = null,
                    )
                },
                *ids.toTypedArray(),
            ).asSequence()
            .filter(query::accepts)
            .sortedWith(SUPPORT_TIMELINE_COMPARATOR)
            .take(query.limit)
            .toList()
    }
}

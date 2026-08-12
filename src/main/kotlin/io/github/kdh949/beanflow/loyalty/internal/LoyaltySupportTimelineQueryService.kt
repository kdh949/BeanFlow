package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.loyalty.api.LoyaltySupportTimelineOperations
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
internal class LoyaltySupportTimelineQueryService(
    private val jdbcTemplate: JdbcTemplate,
) : LoyaltySupportTimelineOperations {
    override fun findTimelineFacts(query: SupportOwnerTimelineQuery): List<SupportOwnerTimelineFact> {
        val ids = query.orderIds.sortedBy(UUID::toString)
        val placeholders = ids.joinToString(",") { "?" }
        return jdbcTemplate
            .query(
                """
                SELECT id, state, amount_krw, updated_at
                  FROM loyalty_point_reservation
                 WHERE order_id IN ($placeholders)
                """.trimIndent(),
                { resultSet, _ ->
                    SupportOwnerTimelineFact(
                        source = SupportTimelineSource.LOYALTY,
                        type = SupportTimelineType.POINT_RESERVATION,
                        itemId = resultSet.getObject("id", UUID::class.java),
                        state = SupportTimelineState.valueOf(resultSet.getString("state")),
                        occurredAt = resultSet.getTimestamp("updated_at").toInstant(),
                        amountKrw = resultSet.getLong("amount_krw"),
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

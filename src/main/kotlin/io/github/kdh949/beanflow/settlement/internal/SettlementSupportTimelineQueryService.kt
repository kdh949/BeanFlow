package io.github.kdh949.beanflow.settlement.internal

import io.github.kdh949.beanflow.settlement.api.SettlementSupportTimelineOperations
import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineFact
import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineQuery
import io.github.kdh949.beanflow.shared.api.SupportTimelineSource
import io.github.kdh949.beanflow.shared.api.SupportTimelineState
import io.github.kdh949.beanflow.shared.api.SupportTimelineType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.util.UUID

@Service
internal class SettlementSupportTimelineQueryService(
    private val jdbcTemplate: JdbcTemplate,
) : SettlementSupportTimelineOperations {
    override fun findTimelineFacts(query: SupportOwnerTimelineQuery): List<SupportOwnerTimelineFact> {
        val ids = query.orderIds.sortedBy(UUID::toString)
        val placeholders = ids.joinToString(",") { "?" }
        val arguments = mutableListOf<Any>()
        arguments.addAll(ids)
        arguments.addAll(ids)
        val boundary = boundaryClause(query, arguments)
        arguments += query.limit
        return jdbcTemplate.query(
            """
            SELECT item_id, fact_type, state, occurred_at, amount_krw
              FROM (
                    SELECT id AS item_id, 'SETTLEMENT_ITEM' AS fact_type, 'ITEM_CREATED' AS state,
                           created_at AS occurred_at, net_settlement_krw AS amount_krw
                      FROM settlement_item
                     WHERE order_id IN ($placeholders)
                    UNION ALL
                    SELECT adjustment.id AS item_id, 'SETTLEMENT_ADJUSTMENT' AS fact_type,
                           'ADJUSTMENT_RECORDED' AS state, adjustment.created_at AS occurred_at,
                           adjustment.amount_krw
                      FROM settlement_adjustment adjustment
                      JOIN settlement_item item ON item.id = adjustment.settlement_item_id
                     WHERE item.order_id IN ($placeholders)
                   ) facts
             $boundary
             ORDER BY occurred_at DESC, item_id DESC
             LIMIT ?
            """.trimIndent(),
            { resultSet, _ ->
                SupportOwnerTimelineFact(
                    source = SupportTimelineSource.SETTLEMENT,
                    type = SupportTimelineType.valueOf(resultSet.getString("fact_type")),
                    itemId = resultSet.getObject("item_id", UUID::class.java),
                    state = SupportTimelineState.valueOf(resultSet.getString("state")),
                    occurredAt = resultSet.getTimestamp("occurred_at").toInstant(),
                    amountKrw = resultSet.getLong("amount_krw"),
                )
            },
            *arguments.toTypedArray(),
        )
    }

    private fun boundaryClause(
        query: SupportOwnerTimelineQuery,
        arguments: MutableList<Any>,
    ): String {
        val after = query.after ?: return ""
        val time = Timestamp.from(after.occurredAt)
        return when {
            SupportTimelineSource.SETTLEMENT.rank > after.source.rank -> {
                arguments += time
                "WHERE occurred_at <= ?"
            }

            SupportTimelineSource.SETTLEMENT.rank < after.source.rank -> {
                arguments += time
                "WHERE occurred_at < ?"
            }

            else -> {
                arguments += time
                arguments += time
                arguments += after.itemId
                "WHERE (occurred_at < ? OR (occurred_at = ? AND item_id < ?))"
            }
        }
    }
}

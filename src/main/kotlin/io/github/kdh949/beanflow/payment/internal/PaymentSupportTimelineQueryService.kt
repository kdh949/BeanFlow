package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.payment.api.PaymentSupportTimelineOperations
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
internal class PaymentSupportTimelineQueryService(
    private val jdbcTemplate: JdbcTemplate,
) : PaymentSupportTimelineOperations {
    override fun findTimelineFacts(query: SupportOwnerTimelineQuery): List<SupportOwnerTimelineFact> {
        val supportedTypes =
            query.types.intersect(setOf(SupportTimelineType.PAYMENT_STATE, SupportTimelineType.REFUND_STATE))
        if (query.types.isNotEmpty() && supportedTypes.isEmpty()) return emptyList()
        val ids = query.orderIds.sortedBy(UUID::toString)
        val placeholders = ids.joinToString(",") { "?" }
        val arguments = mutableListOf<Any>()
        arguments.addAll(ids)
        arguments.addAll(ids)
        val conditions = mutableListOf<String>()
        if (supportedTypes.isNotEmpty()) {
            conditions += "fact_type IN (${supportedTypes.joinToString(",") { "?" }})"
            arguments.addAll(supportedTypes.sortedBy { it.name }.map { it.name })
        }
        boundaryCondition(query, arguments)?.let(conditions::add)
        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        arguments += query.limit
        return jdbcTemplate.query(
            """
            SELECT item_id, fact_type, state, occurred_at, amount_krw
              FROM (
                    SELECT id AS item_id, 'PAYMENT_STATE' AS fact_type, approval_state AS state,
                           updated_at AS occurred_at, COALESCE(approved_amount_krw, requested_amount_krw) AS amount_krw
                      FROM payment_payment
                     WHERE order_id IN ($placeholders)
                    UNION ALL
                    SELECT id AS item_id, 'REFUND_STATE' AS fact_type, state,
                           updated_at AS occurred_at, requested_amount_krw AS amount_krw
                      FROM payment_refund
                     WHERE order_id IN ($placeholders)
                   ) facts
             $where
             ORDER BY occurred_at DESC, item_id DESC
             LIMIT ?
            """.trimIndent(),
            { resultSet, _ ->
                SupportOwnerTimelineFact(
                    source = SupportTimelineSource.PAYMENT,
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

    private fun boundaryCondition(
        query: SupportOwnerTimelineQuery,
        arguments: MutableList<Any>,
    ): String? {
        val after = query.after ?: return null
        val time = Timestamp.from(after.occurredAt)
        return when {
            SupportTimelineSource.PAYMENT.rank > after.source.rank -> {
                arguments += time
                "occurred_at <= ?"
            }

            SupportTimelineSource.PAYMENT.rank < after.source.rank -> {
                arguments += time
                "occurred_at < ?"
            }

            else -> {
                arguments += time
                arguments += time
                arguments += after.itemId
                "(occurred_at < ? OR (occurred_at = ? AND item_id < ?))"
            }
        }
    }
}

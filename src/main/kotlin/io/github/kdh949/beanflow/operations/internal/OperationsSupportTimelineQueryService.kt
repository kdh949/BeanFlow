package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.OperationsSupportTimelineOperations
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
internal class OperationsSupportTimelineQueryService(
    private val jdbcTemplate: JdbcTemplate,
) : OperationsSupportTimelineOperations {
    override fun findTimelineFacts(query: SupportOwnerTimelineQuery): List<SupportOwnerTimelineFact> {
        val ids = query.orderIds.sortedBy(UUID::toString)
        val placeholders = ids.joinToString(",") { "?" }
        val arguments = ids.mapTo(mutableListOf<Any>()) { it }
        val boundary = boundaryClause(query, arguments)
        arguments += query.limit
        return jdbcTemplate.query(
            """
            SELECT id, occurred_at
              FROM operations_audit_record
             WHERE target_type = 'ORDER'
               AND target_id IN ($placeholders)
               $boundary
             ORDER BY occurred_at DESC, id DESC
             LIMIT ?
            """.trimIndent(),
            { resultSet, _ ->
                SupportOwnerTimelineFact(
                    source = SupportTimelineSource.OPERATIONS,
                    type = SupportTimelineType.OPERATION_AUDIT,
                    itemId = resultSet.getObject("id", UUID::class.java),
                    state = SupportTimelineState.RECORDED,
                    occurredAt = resultSet.getTimestamp("occurred_at").toInstant(),
                    amountKrw = null,
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
            SupportTimelineSource.OPERATIONS.rank > after.source.rank -> {
                arguments += time
                "AND occurred_at <= ?"
            }

            SupportTimelineSource.OPERATIONS.rank < after.source.rank -> {
                arguments += time
                "AND occurred_at < ?"
            }

            else -> {
                arguments += time
                arguments += time
                arguments += after.itemId
                "AND (occurred_at < ? OR (occurred_at = ? AND id < ?))"
            }
        }
    }
}

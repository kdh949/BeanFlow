package io.github.kdh949.beanflow.notification.internal

import io.github.kdh949.beanflow.notification.api.NotificationSupportTimelineOperations
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
internal class NotificationSupportTimelineQueryService(
    private val jdbcTemplate: JdbcTemplate,
) : NotificationSupportTimelineOperations {
    override fun findTimelineFacts(query: SupportOwnerTimelineQuery): List<SupportOwnerTimelineFact> {
        val ids = query.orderIds.sortedBy(UUID::toString)
        val placeholders = ids.joinToString(",") { "?" }
        val arguments = ids.mapTo(mutableListOf<Any>()) { it }
        val boundary = boundaryClause(query, arguments)
        arguments += query.limit
        return jdbcTemplate.query(
            """
            SELECT id, state, updated_at
              FROM notification_delivery
             WHERE order_id IN ($placeholders)
               $boundary
             ORDER BY updated_at DESC, id DESC
             LIMIT ?
            """.trimIndent(),
            { resultSet, _ ->
                SupportOwnerTimelineFact(
                    source = SupportTimelineSource.NOTIFICATION,
                    type = SupportTimelineType.NOTIFICATION_DELIVERY,
                    itemId = resultSet.getObject("id", UUID::class.java),
                    state = SupportTimelineState.valueOf(resultSet.getString("state")),
                    occurredAt = resultSet.getTimestamp("updated_at").toInstant(),
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
            SupportTimelineSource.NOTIFICATION.rank > after.source.rank -> {
                arguments += time
                "AND updated_at <= ?"
            }

            SupportTimelineSource.NOTIFICATION.rank < after.source.rank -> {
                arguments += time
                "AND updated_at < ?"
            }

            else -> {
                arguments += time
                arguments += time
                arguments += after.itemId
                "AND (updated_at < ? OR (updated_at = ? AND id < ?))"
            }
        }
    }
}

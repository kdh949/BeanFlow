package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.support.internal.domain.SupportCasePriority
import io.github.kdh949.beanflow.support.internal.domain.SupportCaseState
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal data class SupportCaseListProjection(
    val caseId: UUID,
    val state: SupportCaseState,
    val priority: SupportCasePriority,
    val assigneeId: UUID,
    val version: Long,
    val openedAt: Instant,
)

internal data class SupportCaseSort(
    val openedAt: Instant,
    val caseId: UUID,
)

@Repository
internal class SupportCaseQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findPage(
        state: SupportCaseState?,
        assigneeId: UUID?,
        after: SupportCaseSort?,
        limit: Int,
    ): List<SupportCaseListProjection> {
        val clauses = mutableListOf<String>()
        val arguments = mutableListOf<Any>()
        state?.let {
            clauses += "state = ?"
            arguments += it.name
        }
        assigneeId?.let {
            clauses += "current_assignee_id = ?"
            arguments += it
        }
        after?.let {
            clauses += "(opened_at < ? OR (opened_at = ? AND id < ?))"
            val openedAt = Timestamp.from(it.openedAt)
            arguments += openedAt
            arguments += openedAt
            arguments += it.caseId
        }
        arguments += limit
        val where = if (clauses.isEmpty()) "" else " WHERE ${clauses.joinToString(" AND ")}"
        return jdbcTemplate.query(
            """
            SELECT id, state, priority, current_assignee_id, version, opened_at
              FROM support_case$where
             ORDER BY opened_at DESC, id DESC
             LIMIT ?
            """.trimIndent(),
            { resultSet, _ ->
                SupportCaseListProjection(
                    caseId = resultSet.getObject("id", UUID::class.java),
                    state = SupportCaseState.valueOf(resultSet.getString("state")),
                    priority = SupportCasePriority.valueOf(resultSet.getString("priority")),
                    assigneeId = resultSet.getObject("current_assignee_id", UUID::class.java),
                    version = resultSet.getLong("version"),
                    openedAt = resultSet.getTimestamp("opened_at").toInstant(),
                )
            },
            *arguments.toTypedArray(),
        )
    }
}

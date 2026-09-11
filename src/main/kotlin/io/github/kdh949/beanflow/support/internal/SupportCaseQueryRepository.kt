package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.support.internal.domain.SupportCasePriority
import io.github.kdh949.beanflow.support.internal.domain.SupportCaseState
import io.github.kdh949.beanflow.support.internal.domain.SupportInquiryCategory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal data class SupportCaseQueueSummaryResource(
    val active: Long,
    val open: Long,
    val inProgress: Long,
    val waiting: Long,
    val urgent: Long,
)

internal data class SupportCaseListProjection(
    val caseId: UUID,
    val state: SupportCaseState,
    val priority: SupportCasePriority,
    val assigneeId: UUID,
    val version: Long,
    val openedAt: Instant,
    val category: SupportInquiryCategory,
)

internal data class SupportCaseSort(
    val openedAt: Instant,
    val caseId: UUID,
)

@Repository
internal class SupportCaseQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun summary(actorId: UUID): SupportCaseQueueSummaryResource =
        jdbcTemplate.queryForObject(
            """SELECT count(*) AS active,
            count(*) FILTER (WHERE state = 'OPEN') AS open,
            count(*) FILTER (WHERE state = 'IN_PROGRESS') AS in_progress,
            count(*) FILTER (WHERE state = 'WAITING') AS waiting,
            count(*) FILTER (WHERE priority = 'URGENT') AS urgent
            FROM support_case WHERE current_assignee_id = ? AND state IN ('OPEN', 'IN_PROGRESS', 'WAITING')""",
            {
                rs,
                _,
                ->
                SupportCaseQueueSummaryResource(
                    rs.getLong("active"),
                    rs.getLong("open"),
                    rs.getLong("in_progress"),
                    rs.getLong("waiting"),
                    rs.getLong("urgent"),
                )
            },
            actorId,
        )

    fun findPage(
        state: SupportCaseState?,
        assigneeId: UUID?,
        after: SupportCaseSort?,
        limit: Int,
        category: SupportInquiryCategory? = null,
        priority: SupportCasePriority? = null,
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
        category?.let {
            clauses += "category = ?"
            arguments += it.name
        }
        priority?.let {
            clauses += "priority = ?"
            arguments += it.name
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
            SELECT id, state, priority, current_assignee_id, version, opened_at, category
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
                    category = SupportInquiryCategory.valueOf(resultSet.getString("category")),
                )
            },
            *arguments.toTypedArray(),
        )
    }
}

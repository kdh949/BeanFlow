package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineFact
import io.github.kdh949.beanflow.shared.api.SupportTimelineBoundary
import io.github.kdh949.beanflow.shared.api.SupportTimelineSource
import io.github.kdh949.beanflow.shared.api.SupportTimelineState
import io.github.kdh949.beanflow.shared.api.SupportTimelineType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.util.UUID

@Repository
internal class SupportTimelineQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findLocalFacts(
        caseId: UUID,
        after: SupportTimelineBoundary?,
        types: Set<SupportTimelineType>,
        limit: Int,
    ): List<SupportOwnerTimelineFact> {
        val localTypes = types.intersect(LOCAL_TYPES)
        if (types.isNotEmpty() && localTypes.isEmpty()) return emptyList()
        val arguments = mutableListOf<Any>(caseId)
        val conditions = mutableListOf<String>()
        if (localTypes.isNotEmpty()) {
            conditions += "fact_type IN (${localTypes.joinToString(",") { "?" }})"
            arguments.addAll(localTypes.sortedBy { it.name }.map { it.name })
        }
        boundaryCondition(after, arguments)?.let(conditions::add)
        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        arguments += limit
        return jdbcTemplate.query(
            """
            WITH case_scope AS (SELECT ?::uuid AS case_id)
            SELECT item_id, fact_type, state, occurred_at
              FROM (
                    SELECT id AS item_id, 'CASE_STATE' AS fact_type, current_state AS state, occurred_at
                      FROM support_case_state_history
                     WHERE support_case_id = (SELECT case_id FROM case_scope)
                    UNION ALL
                    SELECT id, 'CASE_ASSIGNMENT', 'ASSIGNED', occurred_at
                      FROM support_case_assignment_history
                     WHERE support_case_id = (SELECT case_id FROM case_scope)
                    UNION ALL
                    SELECT id, 'CASE_INTERACTION', direction, occurred_at
                      FROM support_case_interaction
                     WHERE support_case_id = (SELECT case_id FROM case_scope)
                    UNION ALL
                    SELECT id, 'CASE_NOTE', 'RECORDED', created_at
                      FROM support_case_note
                     WHERE support_case_id = (SELECT case_id FROM case_scope)
                    UNION ALL
                    SELECT md5(id::text || ':linked')::uuid, 'SUBJECT_LINK', 'LINKED', linked_at
                      FROM support_case_subject_link
                     WHERE support_case_id = (SELECT case_id FROM case_scope)
                    UNION ALL
                    SELECT md5(id::text || ':unlinked')::uuid, 'SUBJECT_LINK', 'UNLINKED', unlinked_at
                      FROM support_case_subject_link
                     WHERE support_case_id = (SELECT case_id FROM case_scope)
                       AND unlinked_at IS NOT NULL
                   ) facts(item_id, fact_type, state, occurred_at)
             $where
             ORDER BY occurred_at DESC, item_id DESC
             LIMIT ?
            """.trimIndent(),
            { resultSet, _ ->
                SupportOwnerTimelineFact(
                    source = SupportTimelineSource.SUPPORT,
                    type = SupportTimelineType.valueOf(resultSet.getString("fact_type")),
                    itemId = resultSet.getObject("item_id", UUID::class.java),
                    state = SupportTimelineState.valueOf(resultSet.getString("state")),
                    occurredAt = resultSet.getTimestamp("occurred_at").toInstant(),
                    amountKrw = null,
                )
            },
            *arguments.toTypedArray(),
        )
    }

    fun findActiveOrderIds(
        caseId: UUID,
        limit: Int,
    ): List<UUID> =
        jdbcTemplate
            .queryForList(
                """
                SELECT subject_id
                  FROM support_case_subject_link
                 WHERE support_case_id = ?
                   AND subject_type = 'ORDER'
                   AND unlinked_at IS NULL
                 ORDER BY linked_at, id
                 LIMIT ?
                """.trimIndent(),
                UUID::class.java,
                caseId,
                limit,
            ).map(::requireNotNull)

    fun hasActiveOrderLink(
        caseId: UUID,
        orderId: UUID,
    ): Boolean =
        jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                  FROM support_case_subject_link
                 WHERE support_case_id = ?
                   AND subject_type = 'ORDER'
                   AND subject_id = ?
                   AND unlinked_at IS NULL
            )
            """.trimIndent(),
            Boolean::class.java,
            caseId,
            orderId,
        ) == true

    private fun boundaryCondition(
        after: SupportTimelineBoundary?,
        arguments: MutableList<Any>,
    ): String? {
        after ?: return null
        val time = Timestamp.from(after.occurredAt)
        return when {
            SupportTimelineSource.SUPPORT.rank > after.source.rank -> {
                arguments += time
                "occurred_at <= ?"
            }

            SupportTimelineSource.SUPPORT.rank < after.source.rank -> {
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

    companion object {
        private val LOCAL_TYPES =
            setOf(
                SupportTimelineType.CASE_STATE,
                SupportTimelineType.CASE_ASSIGNMENT,
                SupportTimelineType.CASE_INTERACTION,
                SupportTimelineType.CASE_NOTE,
                SupportTimelineType.SUBJECT_LINK,
            )
    }
}

package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.operations.api.SupportInvestigationDisplay
import io.github.kdh949.beanflow.operations.api.SupportInvestigationDisplayOperations
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class SupportInvestigationDisplayQuery(
    private val jdbc: JdbcTemplate,
) : SupportInvestigationDisplayOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun findDisplays(requestIds: Set<UUID>): Map<UUID, SupportInvestigationDisplay> {
        require(requestIds.size <= 100)
        if (requestIds.isEmpty()) return emptyMap()
        return jdbc
            .query(
                """
                SELECT request.id, request.action, request.current_revision_number, support_case.category, support_case.opened_at
                FROM support_action_request request JOIN support_case ON support_case.id = request.support_case_id
                WHERE request.id IN (${requestIds.joinToString(",") { "?" }})
                """.trimIndent(),
                {
                    rs,
                    _,
                    ->
                    SupportInvestigationDisplay(
                        rs.getObject("id", UUID::class.java),
                        rs.getString("action"),
                        rs.getString("category"),
                        rs.getTimestamp("opened_at").toInstant(),
                        rs.getInt("current_revision_number"),
                    )
                },
                *requestIds.toTypedArray(),
            ).associateBy { it.requestId }
    }
}

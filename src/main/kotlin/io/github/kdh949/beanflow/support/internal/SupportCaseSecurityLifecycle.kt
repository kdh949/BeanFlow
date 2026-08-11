package io.github.kdh949.beanflow.support.internal

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal interface SupportCaseSecurityLifecycle {
    fun revokeForTerminalCase(
        caseId: UUID,
        occurredAt: Instant,
    )
}

@Service
internal class JdbcSupportCaseSecurityLifecycle(
    private val jdbcTemplate: JdbcTemplate,
) : SupportCaseSecurityLifecycle {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun revokeForTerminalCase(
        caseId: UUID,
        occurredAt: Instant,
    ) {
        val timestamp = Timestamp.from(occurredAt)
        jdbcTemplate.update(
            """
            UPDATE support_verification_challenge challenge
               SET state = 'REVOKED', completed_at = ?, version = version + 1
             WHERE challenge.session_id IN (
                       SELECT session.id FROM support_verification_session session WHERE session.support_case_id = ?
                   )
               AND challenge.state IN ('PENDING_ISSUE', 'ISSUED', 'VERIFYING')
            """.trimIndent(),
            timestamp,
            caseId,
        )
        jdbcTemplate.update(
            """
            UPDATE support_verification_session
               SET state = 'REVOKED', revoked_at = ?, version = version + 1
             WHERE support_case_id = ?
               AND state IN ('PENDING', 'VERIFIED')
            """.trimIndent(),
            timestamp,
            caseId,
        )
        jdbcTemplate.update(
            """
            UPDATE support_data_access_grant
               SET state = 'REVOKED', revoked_at = ?, version = version + 1
             WHERE support_case_id = ?
               AND state IN ('REQUESTED', 'APPROVAL_PENDING', 'ACTIVE')
            """.trimIndent(),
            timestamp,
            caseId,
        )
        jdbcTemplate.update(
            """
            UPDATE support_break_glass_request
               SET state = 'REVOKED', revoked_at = ?, version = version + 1
             WHERE support_case_id = ?
               AND state IN ('APPROVAL_PENDING', 'ACTIVE')
            """.trimIndent(),
            timestamp,
            caseId,
        )
    }
}

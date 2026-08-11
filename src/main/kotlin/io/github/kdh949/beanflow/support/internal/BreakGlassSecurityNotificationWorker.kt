package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.notification.api.BreakGlassSecurityNotificationEvent
import io.github.kdh949.beanflow.notification.api.BreakGlassSecurityNotificationOperations
import io.github.kdh949.beanflow.notification.api.BreakGlassSecurityNotificationResult
import io.github.kdh949.beanflow.notification.api.SendBreakGlassSecurityNotificationCommand
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal data class SecurityNotificationWork(
    val intentId: UUID,
    val requestId: UUID,
    val event: BreakGlassSecurityNotificationEvent,
    val attemptCount: Int,
)

@Component
internal class BreakGlassSecurityNotificationWorker(
    private val transactions: BreakGlassSecurityNotificationTransactions,
    private val providers: ObjectProvider<BreakGlassSecurityNotificationOperations>,
) {
    @Scheduled(
        initialDelayString = "\${beanflow.notification.initial-delay-ms:60000}",
        fixedDelayString = "\${beanflow.notification.fixed-delay-ms:10000}",
    )
    fun dispatchDue() {
        val provider = providers.getIfUnique() ?: return
        repeat(MAX_BATCH) {
            val work = transactions.claimDue() ?: return
            val result =
                try {
                    provider.send(
                        SendBreakGlassSecurityNotificationCommand(
                            work.intentId,
                            work.requestId,
                            work.event,
                        ),
                    )
                } catch (_: RuntimeException) {
                    BreakGlassSecurityNotificationResult.UNKNOWN
                }
            transactions.complete(work, result)
        }
    }

    private companion object {
        const val MAX_BATCH = 50
    }
}

@Service
internal class BreakGlassSecurityNotificationTransactions(
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
) {
    @Transactional
    fun claimDue(): SecurityNotificationWork? {
        val now = clock.instant()
        return jdbcTemplate
            .query(
                """
                WITH due AS (
                    SELECT id
                      FROM support_security_notification_intent
                     WHERE (state IN ('PENDING', 'RETRY_SCHEDULED') AND next_attempt_at <= ?)
                        OR (state = 'PROCESSING' AND updated_at <= ?)
                     ORDER BY CASE WHEN state = 'PROCESSING' THEN updated_at ELSE next_attempt_at END, id
                     FOR UPDATE SKIP LOCKED
                     LIMIT 1
                )
                UPDATE support_security_notification_intent intent
                   SET state = 'PROCESSING', attempt_count = attempt_count + 1, updated_at = ?
                  FROM due
                 WHERE intent.id = due.id
                RETURNING intent.id, intent.break_glass_request_id, intent.event_type, intent.attempt_count
                """.trimIndent(),
                { rs, _ ->
                    SecurityNotificationWork(
                        rs.getObject("id", UUID::class.java),
                        rs.getObject("break_glass_request_id", UUID::class.java),
                        BreakGlassSecurityNotificationEvent.valueOf(rs.getString("event_type")),
                        rs.getInt("attempt_count"),
                    )
                },
                Timestamp.from(now),
                Timestamp.from(now.minus(PROCESSING_CLAIM_TIMEOUT)),
                Timestamp.from(now),
            ).singleOrNull()
    }

    @Transactional
    fun complete(
        work: SecurityNotificationWork,
        result: BreakGlassSecurityNotificationResult,
    ) {
        val now = clock.instant()
        val state =
            when (result) {
                BreakGlassSecurityNotificationResult.SENT -> "SENT"

                BreakGlassSecurityNotificationResult.RETRYABLE_FAILURE,
                BreakGlassSecurityNotificationResult.UNKNOWN,
                -> if (work.attemptCount >= MAX_ATTEMPTS) "MANUAL_REVIEW" else "RETRY_SCHEDULED"

                BreakGlassSecurityNotificationResult.PERMANENT_FAILURE -> "MANUAL_REVIEW"
            }
        val nextAttempt =
            if (state == "RETRY_SCHEDULED") {
                now.plus(RETRY_BASE.multipliedBy(1L shl (work.attemptCount - 1).coerceIn(0, 6)))
            } else {
                now
            }
        val updated =
            jdbcTemplate.update(
                """
                UPDATE support_security_notification_intent
                   SET state = ?, next_attempt_at = ?, last_failure_class = ?, updated_at = ?
                 WHERE id = ? AND state = 'PROCESSING' AND attempt_count = ?
                """.trimIndent(),
                state,
                Timestamp.from(nextAttempt),
                if (result == BreakGlassSecurityNotificationResult.SENT) null else result.name,
                Timestamp.from(now),
                work.intentId,
                work.attemptCount,
            )
        check(updated == 1) { "Security notification intent completion is stale" }
    }

    private companion object {
        const val MAX_ATTEMPTS = 8
        val RETRY_BASE: Duration = Duration.ofSeconds(30)
        val PROCESSING_CLAIM_TIMEOUT: Duration = Duration.ofMinutes(5)
    }
}

@Component
@Profile("prod")
internal class BreakGlassSecurityNotificationProductionGuard(
    providers: ObjectProvider<BreakGlassSecurityNotificationOperations>,
) {
    init {
        check(providers.orderedStream().count() == 1L) {
            "Production requires exactly one BreakGlassSecurityNotificationOperations provider"
        }
    }
}

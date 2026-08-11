package io.github.kdh949.beanflow.support.internal

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant

/** Deletes terminal response replay records in bounded, retryable retention-key order. */
@Service
internal class SupportCaseIdempotencyRetentionCleanup(
    private val jdbcTemplate: JdbcTemplate,
) {
    @Transactional
    fun deleteExpired(
        now: Instant,
        batchSize: Int,
    ): Int {
        require(batchSize in 1..MAX_BATCH_SIZE) { "SupportCase idempotency cleanup batch size is invalid" }
        return jdbcTemplate
            .queryForObject(
                """
                WITH candidates AS (
                    SELECT id
                      FROM support_case_command_idempotency
                     WHERE retention_expires_at <= ?
                     ORDER BY retention_expires_at ASC, id ASC
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                ), deleted AS (
                    DELETE FROM support_case_command_idempotency record
                     USING candidates
                     WHERE record.id = candidates.id
                    RETURNING record.id
                )
                SELECT count(*) FROM deleted
                """.trimIndent(),
                Long::class.java,
                Timestamp.from(now),
                batchSize,
            )!!
            .toInt()
    }

    private companion object {
        const val MAX_BATCH_SIZE = 1_000
    }
}

@Component
internal class SupportCaseIdempotencyRetentionWorker(
    private val cleanup: SupportCaseIdempotencyRetentionCleanup,
    private val clock: Clock,
    @Value("\${beanflow.support-case-idempotency.retention.batch-size:100}")
    private val batchSize: Int,
) {
    @Scheduled(
        fixedDelayString = "\${beanflow.support-case-idempotency.retention.fixed-delay-ms:3600000}",
        initialDelayString = "\${beanflow.support-case-idempotency.retention.initial-delay-ms:3600000}",
    )
    fun cleanupExpired() {
        cleanup.deleteExpired(clock.instant(), batchSize)
    }
}

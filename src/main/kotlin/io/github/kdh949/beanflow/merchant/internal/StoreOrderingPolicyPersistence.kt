package io.github.kdh949.beanflow.merchant.internal

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal data class StoreOrderingPolicyCommandRecord(
    val payloadHash: String,
    val responseJson: String,
)

@Repository
internal class StoreOrderingPolicyCommandRepository(
    private val jdbc: JdbcTemplate,
) {
    fun findCommand(
        actorId: UUID,
        idempotencyKey: String,
    ): StoreOrderingPolicyCommandRecord? =
        jdbc
            .query(
                """
                SELECT payload_hash, response_json
                  FROM merchant_store_ordering_policy_command
                 WHERE actor_id = ? AND idempotency_key = ?
                """.trimIndent(),
                { row, _ -> StoreOrderingPolicyCommandRecord(row.getString("payload_hash"), row.getString("response_json")) },
                actorId,
                idempotencyKey,
            ).firstOrNull()

    fun insertCommand(
        id: UUID,
        actorId: UUID,
        idempotencyKey: String,
        payloadHash: String,
        storeId: UUID,
        responseJson: String,
        now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO merchant_store_ordering_policy_command
                (id, actor_id, operation, idempotency_key, payload_hash, store_id, response_json,
                 created_at, retention_expires_at)
            VALUES (?, ?, 'REPLACE_STORE_ORDERING_POLICY_V1', ?, ?, ?, ?, ?, CAST(? AS timestamptz) + interval '90 days')
            """.trimIndent(),
            id,
            actorId,
            idempotencyKey,
            payloadHash,
            storeId,
            responseJson,
            Timestamp.from(now),
            Timestamp.from(now),
        )
    }
}

/** Deletes expired replay rows in bounded, retryable batches. */
@Repository
internal class StoreOrderingPolicyCommandRetentionCleanup(
    private val jdbc: JdbcTemplate,
) {
    @Transactional(propagation = Propagation.REQUIRED)
    fun deleteExpired(
        now: Instant,
        batchSize: Int,
    ): Int {
        require(batchSize in 1..MAX_BATCH_SIZE) { "Store ordering-policy command cleanup batch size is invalid" }
        return jdbc
            .queryForObject(
                """
                WITH candidates AS (
                    SELECT id
                      FROM merchant_store_ordering_policy_command
                     WHERE retention_expires_at <= ?
                     ORDER BY retention_expires_at ASC, id ASC
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                ), deleted AS (
                    DELETE FROM merchant_store_ordering_policy_command record
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

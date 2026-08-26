package io.github.kdh949.beanflow.merchant.internal

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal data class MenuCatalogCommandRecord(
    val payloadHash: String,
    val responseJson: String,
)

@Repository
internal class MenuCatalogCommandRepository(
    private val jdbc: JdbcTemplate,
) {
    fun find(
        actorId: UUID,
        idempotencyKey: String,
    ): MenuCatalogCommandRecord? =
        jdbc
            .query(
                """
                SELECT payload_hash, response_json
                  FROM merchant_menu_catalog_command
                 WHERE actor_id = ? AND idempotency_key = ?
                """.trimIndent(),
                { row, _ -> MenuCatalogCommandRecord(row.getString("payload_hash"), row.getString("response_json")) },
                actorId,
                idempotencyKey,
            ).firstOrNull()

    fun insert(
        id: UUID,
        actorId: UUID,
        operation: String,
        idempotencyKey: String,
        payloadHash: String,
        storeId: UUID,
        menuId: UUID,
        responseJson: String,
        now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO merchant_menu_catalog_command
                (id, actor_id, operation, idempotency_key, payload_hash, store_id, menu_id,
                 response_json, created_at, retention_expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS timestamptz) + interval '90 days')
            """.trimIndent(),
            id,
            actorId,
            operation,
            idempotencyKey,
            payloadHash,
            storeId,
            menuId,
            responseJson,
            Timestamp.from(now),
            Timestamp.from(now),
        )
    }
}

@Repository
internal class MenuCatalogCommandRetentionCleanup(
    private val jdbc: JdbcTemplate,
) {
    @Transactional(propagation = Propagation.REQUIRED)
    fun deleteExpired(
        now: Instant,
        batchSize: Int,
    ): Int {
        require(batchSize in 1..MAX_BATCH_SIZE) { "Menu catalogue command cleanup batch size is invalid" }
        return jdbc
            .queryForObject(
                """
                WITH candidates AS (
                    SELECT id
                      FROM merchant_menu_catalog_command
                     WHERE retention_expires_at <= ?
                     ORDER BY retention_expires_at, id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                ), deleted AS (
                    DELETE FROM merchant_menu_catalog_command record
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

package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.RegionSnapshot
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal data class StoreRegionRow(
    val storeId: UUID,
    val regionCode: String?,
)

internal data class StoreRegionCommandRecord(
    val payloadHash: String,
    val responseJson: String,
)

/**
 * The 법정동 vocabulary, the store's region reference and the region command replay ledger.
 *
 * JdbcTemplate rather than JPA, for the same reason as [BrandRepository]: the write path locks one
 * explicit row and reads a read-only vocabulary, and neither gains anything from a managed
 * persistence context.
 */
@Repository
internal class RegionRepository(
    private val jdbc: JdbcTemplate,
) {
    fun find(code: String): RegionSnapshot? =
        jdbc
            .query("$SELECT_REGION WHERE code = ?", ::mapRegion, code)
            .firstOrNull()

    /**
     * Pages the vocabulary on `(full_name, code)`.
     *
     * The keyset comparison and the `ORDER BY` use the same tuple, so whatever collation the
     * database applies to `full_name` orders both identically and no row can be skipped or
     * repeated between pages.
     */
    fun search(
        tokens: List<String>,
        afterFullName: String?,
        afterCode: String?,
        limit: Int,
    ): List<RegionSnapshot> {
        val conditions = mutableListOf<String>()
        val arguments = mutableListOf<Any>()
        // strpos takes no wildcards, so a caller cannot turn '%' or '_' into a pattern.
        tokens.forEach { token ->
            conditions += "strpos(full_name, ?) > 0"
            arguments += token
        }
        if (afterFullName != null && afterCode != null) {
            conditions += "(full_name, code) > (?, ?)"
            arguments += afterFullName
            arguments += afterCode
        }
        val where = if (conditions.isEmpty()) "" else " WHERE ${conditions.joinToString(" AND ")}"
        arguments += limit
        return jdbc.query("$SELECT_REGION$where ORDER BY full_name ASC, code ASC LIMIT ?", ::mapRegion, *arguments.toTypedArray())
    }

    /**
     * Locks the store's discovery profile and returns its current region.
     *
     * The lock is what makes two concurrent assignments for one store serialise: the second one
     * reads the first one's committed region rather than racing it to the term write.
     */
    fun findStoreRegionLocked(storeId: UUID): StoreRegionRow? =
        jdbc
            .query(
                "SELECT store_id, region_code FROM merchant_store_discovery_profile WHERE store_id = ? FOR UPDATE",
                { row, _ -> StoreRegionRow(row.getObject("store_id", UUID::class.java), row.getString("region_code")) },
                storeId,
            ).firstOrNull()

    fun updateStoreRegion(
        storeId: UUID,
        regionCode: String,
    ) {
        jdbc.update("UPDATE merchant_store_discovery_profile SET region_code = ? WHERE store_id = ?", regionCode, storeId)
    }

    fun findCommand(
        actorId: UUID,
        idempotencyKey: String,
    ): StoreRegionCommandRecord? =
        jdbc
            .query(
                "SELECT payload_hash, response_json FROM merchant_store_region_command WHERE actor_id = ? AND idempotency_key = ?",
                { row, _ -> StoreRegionCommandRecord(row.getString("payload_hash"), row.getString("response_json")) },
                actorId,
                idempotencyKey,
            ).firstOrNull()

    fun insertCommand(
        id: UUID,
        actorId: UUID,
        commandType: String,
        idempotencyKey: String,
        payloadHash: String,
        responseJson: String,
        now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO merchant_store_region_command
                (id, actor_id, command_type, idempotency_key, payload_hash, response_json, created_at, retention_expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS timestamptz) + interval '90 days')
            """.trimIndent(),
            id,
            actorId,
            commandType,
            idempotencyKey,
            payloadHash,
            responseJson,
            Timestamp.from(now),
            Timestamp.from(now),
        )
    }

    private fun mapRegion(
        row: ResultSet,
        rowNumber: Int,
    ): RegionSnapshot =
        RegionSnapshot(
            code = row.getString("code"),
            sido = row.getString("sido"),
            sigungu = row.getString("sigungu"),
            eupmyeondong = row.getString("eupmyeondong"),
            ri = row.getString("ri"),
            fullName = row.getString("full_name"),
        )

    private companion object {
        const val SELECT_REGION = "SELECT code, sido, sigungu, eupmyeondong, ri, full_name FROM merchant_region"
    }
}

/** Deletes replayed region commands past their retention window, in bounded retryable batches. */
@Repository
internal class StoreRegionCommandRetentionCleanup(
    private val jdbc: JdbcTemplate,
) {
    @Transactional(propagation = Propagation.REQUIRED)
    fun deleteExpired(
        now: Instant,
        batchSize: Int,
    ): Int {
        require(batchSize in 1..MAX_BATCH_SIZE) { "Region command cleanup batch size is invalid" }
        return jdbc
            .queryForObject(
                """
                WITH candidates AS (
                    SELECT id
                      FROM merchant_store_region_command
                     WHERE retention_expires_at <= ?
                     ORDER BY retention_expires_at ASC, id ASC
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                ), deleted AS (
                    DELETE FROM merchant_store_region_command record
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

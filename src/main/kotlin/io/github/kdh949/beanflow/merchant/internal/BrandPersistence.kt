package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.BrandStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal data class BrandRow(
    val id: UUID,
    val name: String,
    val normalizedName: String,
    val status: BrandStatus,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

internal data class BrandCommandRecord(
    val payloadHash: String,
    val responseJson: String,
)

/**
 * Brand rows, store brand references and the command replay ledger.
 *
 * Everything here is JdbcTemplate rather than JPA. The brand write path locks explicit rows in a
 * fixed order and reads store id sets in bulk; a persistence-context-managed entity would add a
 * flush ordering question to every one of those steps without removing any SQL.
 */
@Repository
internal class BrandRepository(
    private val jdbc: JdbcTemplate,
) {
    fun find(brandId: UUID): BrandRow? =
        jdbc
            .query("$SELECT_BRAND WHERE id = ?", ::mapBrand, brandId)
            .firstOrNull()

    /**
     * Reads the brand and holds its row until the transaction ends.
     *
     * Every command that touches a brand's store set takes this lock, so a rename cannot run
     * between another command's "which stores belong to this brand" read and its term write.
     */
    fun findLocked(brandId: UUID): BrandRow? =
        jdbc
            .query("$SELECT_BRAND WHERE id = ? FOR UPDATE", ::mapBrand, brandId)
            .firstOrNull()

    fun findActiveByNormalizedName(normalizedName: String): BrandRow? =
        jdbc
            .query("$SELECT_BRAND WHERE normalized_name = ? AND status = 'ACTIVE'", ::mapBrand, normalizedName)
            .firstOrNull()

    fun page(
        afterNormalizedName: String?,
        afterBrandId: UUID?,
        limit: Int,
    ): List<BrandRow> =
        if (afterNormalizedName == null || afterBrandId == null) {
            jdbc.query("$SELECT_BRAND ORDER BY normalized_name ASC, id ASC LIMIT ?", ::mapBrand, limit)
        } else {
            jdbc.query(
                "$SELECT_BRAND WHERE (normalized_name, id) > (?, ?) ORDER BY normalized_name ASC, id ASC LIMIT ?",
                ::mapBrand,
                afterNormalizedName,
                afterBrandId,
                limit,
            )
        }

    fun insert(brand: BrandRow) {
        jdbc.update(
            """
            INSERT INTO merchant_brand (id, name, normalized_name, status, created_at, updated_at, version)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            brand.id,
            brand.name,
            brand.normalizedName,
            brand.status.name,
            Timestamp.from(brand.createdAt),
            Timestamp.from(brand.updatedAt),
            brand.version,
        )
    }

    /** Returns false when another transaction advanced the version first. */
    fun update(
        brand: BrandRow,
        expectedVersion: Long,
    ): Boolean =
        jdbc.update(
            """
            UPDATE merchant_brand
               SET name = ?, normalized_name = ?, status = ?, updated_at = ?, version = version + 1
             WHERE id = ? AND version = ?
            """.trimIndent(),
            brand.name,
            brand.normalizedName,
            brand.status.name,
            Timestamp.from(brand.updatedAt),
            brand.id,
            expectedVersion,
        ) == 1

    fun countAssignedStores(brandId: UUID): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM merchant_store WHERE brand_id = ?",
            Int::class.java,
            brandId,
        ) ?: 0

    fun countAssignedStores(brandIds: Collection<UUID>): Map<UUID, Int> {
        if (brandIds.isEmpty()) return emptyMap()
        val placeholders = brandIds.joinToString(", ") { "?" }
        val counts = mutableMapOf<UUID, Int>()
        jdbc.query(
            "SELECT brand_id, count(*) AS store_count FROM merchant_store WHERE brand_id IN ($placeholders) GROUP BY brand_id",
            { row, _ -> counts[row.getObject("brand_id", UUID::class.java)] = row.getInt("store_count") },
            *brandIds.toTypedArray(),
        )
        return counts
    }

    /**
     * The stores whose `BRAND_NAME` term a rename has to replace.
     *
     * Ordered so that a bounded fan-out reads the same set in the same order every time, which
     * makes the term write deterministic and keeps two concurrent renames from deadlocking.
     */
    fun findAssignedStoreIds(
        brandId: UUID,
        limit: Int,
    ): List<UUID> =
        jdbc.query(
            "SELECT id FROM merchant_store WHERE brand_id = ? ORDER BY id ASC LIMIT ?",
            { row, _ -> row.getObject("id", UUID::class.java) },
            brandId,
            limit,
        )

    /** Locks the store row and returns its current brand, or null when the store does not exist. */
    fun findStoreBrandLocked(storeId: UUID): StoreBrandRow? =
        jdbc
            .query(
                "SELECT id, brand_id FROM merchant_store WHERE id = ? FOR UPDATE",
                { row, _ -> StoreBrandRow(row.getObject("id", UUID::class.java), row.getObject("brand_id", UUID::class.java)) },
                storeId,
            ).firstOrNull()

    fun updateStoreBrand(
        storeId: UUID,
        brandId: UUID?,
    ) {
        jdbc.update("UPDATE merchant_store SET brand_id = ? WHERE id = ?", brandId, storeId)
    }

    fun findCommand(
        actorId: UUID,
        idempotencyKey: String,
    ): BrandCommandRecord? =
        jdbc
            .query(
                "SELECT payload_hash, response_json FROM merchant_brand_command WHERE actor_id = ? AND idempotency_key = ?",
                { row, _ -> BrandCommandRecord(row.getString("payload_hash"), row.getString("response_json")) },
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
            INSERT INTO merchant_brand_command
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

    private fun mapBrand(
        row: ResultSet,
        rowNumber: Int,
    ): BrandRow =
        BrandRow(
            id = row.getObject("id", UUID::class.java),
            name = row.getString("name"),
            normalizedName = row.getString("normalized_name"),
            status = BrandStatus.valueOf(row.getString("status")),
            version = row.getLong("version"),
            createdAt = row.getTimestamp("created_at").toInstant(),
            updatedAt = row.getTimestamp("updated_at").toInstant(),
        )

    private companion object {
        const val SELECT_BRAND = "SELECT id, name, normalized_name, status, version, created_at, updated_at FROM merchant_brand"
    }
}

internal data class StoreBrandRow(
    val storeId: UUID,
    val brandId: UUID?,
)

/** Deletes replayed brand commands past their retention window, in bounded retryable batches. */
@Repository
internal class BrandCommandRetentionCleanup(
    private val jdbc: JdbcTemplate,
) {
    @Transactional(propagation = Propagation.REQUIRED)
    fun deleteExpired(
        now: Instant,
        batchSize: Int,
    ): Int {
        require(batchSize in 1..MAX_BATCH_SIZE) { "Brand command cleanup batch size is invalid" }
        return jdbc
            .queryForObject(
                """
                WITH candidates AS (
                    SELECT id
                      FROM merchant_brand_command
                     WHERE retention_expires_at <= ?
                     ORDER BY retention_expires_at ASC, id ASC
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                ), deleted AS (
                    DELETE FROM merchant_brand_command record
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

package io.github.kdh949.beanflow.settlement.internal

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal data class SettlementItemAmountProjection(
    val completedAt: Instant,
    val settlementItemId: UUID,
    val grossPaidKrw: Long,
    val feeKrw: Long,
    val benefitCostKrw: Long,
    val netSettlementKrw: Long,
)

internal data class SettlementAdjustmentAmountProjection(
    val createdAt: Instant,
    val settlementAdjustmentId: UUID,
    val effectiveAt: Instant,
    val amountKrw: Long,
)

internal data class PreviousSettlementBatchProjection(
    val settlementBatchId: UUID,
    val calculatedAt: Instant,
    val carryForwardOutKrw: Long,
    val adjustmentCursorEffectiveAt: Instant?,
    val adjustmentCursorId: UUID?,
)

@Repository
internal class SettlementBatchLifecycleRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun hasEarlierUnconfirmedBatch(
        storeId: UUID,
        settlementDate: LocalDate,
    ): Boolean =
        jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                  FROM settlement_batch
                 WHERE store_id = ?
                   AND settlement_date < ?
                   AND state <> 'CONFIRMED'
            )
            """.trimIndent(),
            Boolean::class.java,
            storeId,
            settlementDate,
        ) == true

    fun findPreviousConfirmedBatch(
        storeId: UUID,
        settlementDate: LocalDate,
    ): PreviousSettlementBatchProjection? =
        jdbcTemplate
            .query(
                """
                SELECT id, calculated_at, carry_forward_out_krw,
                       adjustment_cursor_effective_at, adjustment_cursor_id
                  FROM settlement_batch
                 WHERE store_id = ?
                   AND settlement_date < ?
                   AND state = 'CONFIRMED'
                 ORDER BY settlement_date DESC, id DESC
                 LIMIT 1
                 FOR SHARE
                """.trimIndent(),
                { resultSet, _ ->
                    PreviousSettlementBatchProjection(
                        settlementBatchId = resultSet.getObject("id", UUID::class.java),
                        calculatedAt = resultSet.getTimestamp("calculated_at").toInstant(),
                        carryForwardOutKrw = resultSet.getLong("carry_forward_out_krw"),
                        adjustmentCursorEffectiveAt =
                            resultSet.getTimestamp("adjustment_cursor_effective_at")?.toInstant(),
                        adjustmentCursorId = resultSet.getObject("adjustment_cursor_id", UUID::class.java),
                    )
                },
                storeId,
                settlementDate,
            ).singleOrNull()

    fun findItemChunk(
        settlementBatchId: UUID,
        afterCompletedAt: Instant?,
        afterItemId: UUID?,
        limit: Int,
    ): List<SettlementItemAmountProjection> {
        val base =
            """
            SELECT completed_at, id, gross_paid_krw, fee_krw,
                   benefit_cost_krw, net_settlement_krw
              FROM settlement_item
             WHERE settlement_batch_id = ?
            """.trimIndent()
        val sql =
            if (afterCompletedAt == null || afterItemId == null) {
                "$base ORDER BY completed_at, id LIMIT ?"
            } else {
                "$base AND (completed_at > ? OR (completed_at = ? AND id > ?)) " +
                    "ORDER BY completed_at, id LIMIT ?"
            }
        val arguments: Array<Any> =
            if (afterCompletedAt == null || afterItemId == null) {
                arrayOf(settlementBatchId, limit)
            } else {
                val completedAt = Timestamp.from(afterCompletedAt)
                arrayOf(settlementBatchId, completedAt, completedAt, afterItemId, limit)
            }
        return jdbcTemplate.query(sql, { resultSet, _ ->
            SettlementItemAmountProjection(
                completedAt = resultSet.getTimestamp("completed_at").toInstant(),
                settlementItemId = resultSet.getObject("id", UUID::class.java),
                grossPaidKrw = resultSet.getLong("gross_paid_krw"),
                feeKrw = resultSet.getLong("fee_krw"),
                benefitCostKrw = resultSet.getLong("benefit_cost_krw"),
                netSettlementKrw = resultSet.getLong("net_settlement_krw"),
            )
        }, *arguments)
    }

    fun findAdjustmentChunk(
        storeId: UUID,
        createdAfter: Instant?,
        createdAtOrBefore: Instant,
        afterCreatedAt: Instant?,
        afterAdjustmentId: UUID?,
        limit: Int,
    ): List<SettlementAdjustmentAmountProjection> {
        val lowerBound = createdAfter ?: Instant.EPOCH
        val pageCreatedAt = afterCreatedAt ?: lowerBound
        val pageId = afterAdjustmentId ?: ZERO_UUID
        return jdbcTemplate.query(
            """
            SELECT id, created_at, effective_at, amount_krw
              FROM settlement_adjustment
             WHERE store_id = ?
               AND created_at > ?
               AND created_at <= ?
               AND (created_at > ? OR (created_at = ? AND id > ?))
             ORDER BY created_at, id
             LIMIT ?
            """.trimIndent(),
            { resultSet, _ ->
                SettlementAdjustmentAmountProjection(
                    createdAt = resultSet.getTimestamp("created_at").toInstant(),
                    settlementAdjustmentId = resultSet.getObject("id", UUID::class.java),
                    effectiveAt = resultSet.getTimestamp("effective_at").toInstant(),
                    amountKrw = resultSet.getLong("amount_krw"),
                )
            },
            storeId,
            Timestamp.from(lowerBound),
            Timestamp.from(createdAtOrBefore),
            Timestamp.from(pageCreatedAt),
            Timestamp.from(pageCreatedAt),
            pageId,
            limit,
        )
    }

    fun findOpenBatchIds(
        beforeDate: LocalDate,
        limit: Int,
    ): List<UUID> =
        jdbcTemplate.query(
            """
            SELECT id
              FROM settlement_batch
             WHERE state = 'OPEN'
               AND settlement_date < ?
             ORDER BY settlement_date, store_id, id
             LIMIT ?
            """.trimIndent(),
            { resultSet, _ -> resultSet.getObject("id", UUID::class.java) },
            beforeDate,
            limit,
        )

    private companion object {
        val ZERO_UUID: UUID = UUID(0, 0)
    }
}

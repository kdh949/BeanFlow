package io.github.kdh949.beanflow.settlement.internal

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal data class SettlementItemProjection(
    val settlementItemId: UUID,
    val settlementBatchId: UUID,
    val orderId: UUID,
    val completedAt: Instant,
    val grossPaidKrw: Long,
    val feeKrw: Long,
    val benefitCostKrw: Long,
    val netSettlementKrw: Long,
    val currency: String,
)

internal data class SettlementItemSort(
    val completedAt: Instant,
    val settlementItemId: UUID,
)

@Repository
internal class SettlementItemQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findBatchStoreId(settlementBatchId: UUID): UUID? =
        jdbcTemplate.query(
            "SELECT store_id FROM settlement_batch WHERE id = ?",
            { resultSet, _ -> resultSet.getObject("store_id", UUID::class.java) },
            settlementBatchId,
        ).singleOrNull()

    fun findPage(
        settlementBatchId: UUID,
        after: SettlementItemSort?,
        limit: Int,
    ): List<SettlementItemProjection> {
        val base =
            """
            SELECT id, settlement_batch_id, order_id, completed_at,
                   gross_paid_krw, fee_krw, benefit_cost_krw, net_settlement_krw, currency
              FROM settlement_item
             WHERE settlement_batch_id = ?
            """.trimIndent()
        val sql =
            if (after == null) {
                "$base ORDER BY completed_at ASC, id ASC LIMIT ?"
            } else {
                "$base AND (completed_at > ? OR (completed_at = ? AND id > ?)) " +
                    "ORDER BY completed_at ASC, id ASC LIMIT ?"
            }
        val arguments: Array<Any> =
            if (after == null) {
                arrayOf(settlementBatchId, limit)
            } else {
                val completedAt = Timestamp.from(after.completedAt)
                arrayOf(settlementBatchId, completedAt, completedAt, after.settlementItemId, limit)
            }
        return jdbcTemplate.query(sql, { resultSet, _ ->
            SettlementItemProjection(
                settlementItemId = resultSet.getObject("id", UUID::class.java),
                settlementBatchId = resultSet.getObject("settlement_batch_id", UUID::class.java),
                orderId = resultSet.getObject("order_id", UUID::class.java),
                completedAt = resultSet.getTimestamp("completed_at").toInstant(),
                grossPaidKrw = resultSet.getLong("gross_paid_krw"),
                feeKrw = resultSet.getLong("fee_krw"),
                benefitCostKrw = resultSet.getLong("benefit_cost_krw"),
                netSettlementKrw = resultSet.getLong("net_settlement_krw"),
                currency = resultSet.getString("currency"),
            )
        }, *arguments)
    }
}

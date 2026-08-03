package io.github.kdh949.beanflow.settlement.internal

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal data class SettlementBatchProjection(
    val settlementBatchId: UUID,
    val storeId: UUID,
    val settlementDate: LocalDate,
    val state: SettlementBatchState,
    val grossPaidKrw: Long,
    val feeKrw: Long,
    val benefitCostKrw: Long,
    val adjustmentKrw: Long,
    val netSettlementKrw: Long,
    val confirmedAt: Instant?,
)

internal data class SettlementBatchSort(
    val settlementDate: LocalDate,
    val settlementBatchId: UUID,
)

@Repository
internal class SettlementBatchQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findPage(
        storeId: UUID,
        after: SettlementBatchSort?,
        limit: Int,
    ): List<SettlementBatchProjection> {
        val base =
            """
            SELECT id, store_id, settlement_date, state,
                   gross_paid_krw, fee_krw, benefit_cost_krw, adjustment_krw,
                   item_net_settlement_krw + adjustment_krw + carry_forward_in_krw AS net_settlement_krw,
                   confirmed_at
              FROM settlement_batch
             WHERE store_id = ?
               AND state IN ('CALCULATED', 'CONFIRMED')
            """.trimIndent()
        val sql =
            if (after == null) {
                "$base ORDER BY settlement_date DESC, id DESC LIMIT ?"
            } else {
                "$base AND (settlement_date < ? OR (settlement_date = ? AND id < ?)) " +
                    "ORDER BY settlement_date DESC, id DESC LIMIT ?"
            }
        val arguments: Array<Any> =
            if (after == null) {
                arrayOf(storeId, limit)
            } else {
                arrayOf(storeId, after.settlementDate, after.settlementDate, after.settlementBatchId, limit)
            }
        return jdbcTemplate.query(sql, { resultSet, _ ->
            SettlementBatchProjection(
                settlementBatchId = resultSet.getObject("id", UUID::class.java),
                storeId = resultSet.getObject("store_id", UUID::class.java),
                settlementDate = resultSet.getObject("settlement_date", LocalDate::class.java),
                state = SettlementBatchState.valueOf(resultSet.getString("state")),
                grossPaidKrw = resultSet.getLong("gross_paid_krw"),
                feeKrw = resultSet.getLong("fee_krw"),
                benefitCostKrw = resultSet.getLong("benefit_cost_krw"),
                adjustmentKrw = resultSet.getLong("adjustment_krw"),
                netSettlementKrw = resultSet.getLong("net_settlement_krw"),
                confirmedAt = resultSet.getTimestamp("confirmed_at")?.toInstant(),
            )
        }, *arguments)
    }
}

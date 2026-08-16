package io.github.kdh949.beanflow.dispute.internal

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal data class SettlementDisputeProjection(
    val disputeId: UUID,
    val settlementItemId: UUID,
    val state: SettlementDisputeState,
    val expectedAdjustmentKrw: Long,
    val heldAmountKrw: Long,
    val filedAt: Instant,
    val decidedAt: Instant?,
)

internal data class SettlementDisputeSort(
    val filedAt: Instant,
    val disputeId: UUID,
)

/**
 * Store-scoped dispute page. The store predicate is in SQL, never a filter
 * applied after reading, and the ordering matches
 * `idx_settlement_dispute_store_filed (store_id, filed_at, id)` scanned
 * backwards.
 */
@Repository
internal class SettlementDisputeQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findPage(
        storeId: UUID,
        state: SettlementDisputeState?,
        after: SettlementDisputeSort?,
        limit: Int,
    ): List<SettlementDisputeProjection> {
        val conditions = StringBuilder("WHERE store_id = ?")
        val arguments = mutableListOf<Any>(storeId)
        state?.let {
            conditions.append(" AND state = ?")
            arguments += it.name
        }
        after?.let {
            conditions.append(" AND (filed_at < ? OR (filed_at = ? AND id < ?))")
            val filedAt = Timestamp.from(it.filedAt)
            arguments += filedAt
            arguments += filedAt
            arguments += it.disputeId
        }
        arguments += limit
        return jdbcTemplate.query(
            """
            SELECT id, settlement_item_id, state, expected_adjustment_krw,
                   held_amount_krw, filed_at, decided_at
              FROM settlement_dispute
             $conditions
             ORDER BY filed_at DESC, id DESC
             LIMIT ?
            """.trimIndent(),
            { resultSet, _ ->
                SettlementDisputeProjection(
                    disputeId = resultSet.getObject("id", UUID::class.java),
                    settlementItemId = resultSet.getObject("settlement_item_id", UUID::class.java),
                    state = SettlementDisputeState.valueOf(resultSet.getString("state")),
                    expectedAdjustmentKrw = resultSet.getLong("expected_adjustment_krw"),
                    heldAmountKrw = resultSet.getLong("held_amount_krw"),
                    filedAt = resultSet.getTimestamp("filed_at").toInstant(),
                    decidedAt = resultSet.getTimestamp("decided_at")?.toInstant(),
                )
            },
            *arguments.toTypedArray(),
        )
    }
}

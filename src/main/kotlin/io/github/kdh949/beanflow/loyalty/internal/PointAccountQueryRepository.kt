package io.github.kdh949.beanflow.loyalty.internal

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal data class PointAccountSummaryProjection(
    val accountId: UUID,
    val customerId: UUID,
    val availablePointsKrw: Long,
    val recoveryPendingKrw: Long,
)

internal data class PointTransactionProjection(
    val transactionId: UUID,
    val type: String,
    val balanceEffect: String,
    val amountKrw: Long,
    val occurredAt: Instant,
    val sourceReference: String,
)

internal data class PointTransactionSort(
    val occurredAt: Instant,
    val transactionId: UUID,
)

@Repository
internal class PointAccountQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findAccount(accountId: UUID): PointAccountSummaryProjection? =
        jdbcTemplate
            .query(
                """
                SELECT id, customer_id, available_points_krw, recovery_pending_krw
                  FROM loyalty_point_account
                 WHERE id = ?
                """.trimIndent(),
                { resultSet, _ ->
                    PointAccountSummaryProjection(
                        accountId = resultSet.getObject("id", UUID::class.java),
                        customerId = resultSet.getObject("customer_id", UUID::class.java),
                        availablePointsKrw = resultSet.getLong("available_points_krw"),
                        recoveryPendingKrw = resultSet.getLong("recovery_pending_krw"),
                    )
                },
                accountId,
            ).singleOrNull()

    fun findTransactions(
        accountId: UUID,
        after: PointTransactionSort?,
        limit: Int,
    ): List<PointTransactionProjection> {
        val base =
            """
            SELECT id, type, balance_effect, amount_krw, occurred_at, source_reference
              FROM loyalty_point_transaction
             WHERE point_account_id = ?
            """.trimIndent()
        val sql =
            if (after == null) {
                "$base ORDER BY occurred_at DESC, id DESC LIMIT ?"
            } else {
                "$base AND (occurred_at < ? OR (occurred_at = ? AND id < ?)) " +
                    "ORDER BY occurred_at DESC, id DESC LIMIT ?"
            }
        val arguments: Array<Any> =
            if (after == null) {
                arrayOf(accountId, limit)
            } else {
                val occurredAt = Timestamp.from(after.occurredAt)
                arrayOf(accountId, occurredAt, occurredAt, after.transactionId, limit)
            }
        return jdbcTemplate.query(sql, { resultSet, _ ->
            PointTransactionProjection(
                transactionId = resultSet.getObject("id", UUID::class.java),
                type = resultSet.getString("type"),
                balanceEffect = resultSet.getString("balance_effect"),
                amountKrw = resultSet.getLong("amount_krw"),
                occurredAt = resultSet.getTimestamp("occurred_at").toInstant(),
                sourceReference = resultSet.getString("source_reference"),
            )
        }, *arguments)
    }
}

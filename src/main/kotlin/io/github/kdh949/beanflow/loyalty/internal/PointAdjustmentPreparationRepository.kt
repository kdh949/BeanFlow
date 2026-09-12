package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal enum class PointAdjustmentPreparationState { PREPARED, APPLIED, CANCELLED }

internal data class PointAdjustmentPreparation(
    val id: UUID,
    val actorId: UUID,
    val accountId: UUID,
    val requestBody: String,
    val payloadHash: String,
    val state: PointAdjustmentPreparationState,
    val responseBody: String?,
    val createdAt: Instant,
    val dismissedAt: Instant?,
)

@Repository
internal class PointAdjustmentPreparationRepository(
    private val jdbc: JdbcTemplate,
) {
    fun findOpen(
        actor: UUID,
        lock: Boolean = false,
    ): PointAdjustmentPreparation? =
        jdbc
            .query(
                "SELECT * FROM loyalty_point_adjustment_preparation WHERE actor_id = ? AND dismissed_at IS NULL${if (lock) " FOR UPDATE" else ""}",
                { rs, _ -> row(rs) },
                actor,
            ).singleOrNull()

    fun find(
        actor: UUID,
        id: UUID,
        lock: Boolean = false,
    ): PointAdjustmentPreparation? =
        jdbc
            .query(
                "SELECT * FROM loyalty_point_adjustment_preparation WHERE actor_id = ? AND id = ?${if (lock) " FOR UPDATE" else ""}",
                { rs, _ -> row(rs) },
                actor,
                id,
            ).singleOrNull()

    fun findByCommandKey(
        actor: UUID,
        key: String,
    ): PointAdjustmentPreparation? {
        val id =
            try {
                UUID.fromString(key)
            } catch (_: IllegalArgumentException) {
                return null
            }
        val owner =
            jdbc
                .query(
                    "SELECT actor_id FROM loyalty_point_adjustment_preparation WHERE id = ?",
                    { row, _ -> row.getObject("actor_id", UUID::class.java) },
                    id,
                ).singleOrNull()
        if (owner != null && owner != actor) throw DomainFailure(FailureCode.ACCESS_DENIED, "Preparation belongs to another operator")
        return find(actor, id, true)
    }

    fun insert(value: PointAdjustmentPreparation) {
        jdbc.update(
            "INSERT INTO loyalty_point_adjustment_preparation " +
                "(id, actor_id, point_account_id, request_body, payload_hash, state, created_at) VALUES (?, ?, ?, ?, ?, 'PREPARED', ?)",
            value.id,
            value.actorId,
            value.accountId,
            value.requestBody,
            value.payloadHash,
            Timestamp.from(value.createdAt),
        )
    }

    fun applied(
        id: UUID,
        response: String,
    ) {
        check(
            jdbc.update(
                "UPDATE loyalty_point_adjustment_preparation SET state = 'APPLIED', response_body = ? " +
                    "WHERE id = ? AND state = 'PREPARED' AND dismissed_at IS NULL",
                response,
                id,
            ) == 1,
        )
    }

    fun dismiss(
        value: PointAdjustmentPreparation,
        now: Instant,
    ) {
        val state = if (value.state == PointAdjustmentPreparationState.PREPARED) "CANCELLED" else value.state.name
        check(
            jdbc.update(
                "UPDATE loyalty_point_adjustment_preparation SET state = ?, dismissed_at = ? " +
                    "WHERE id = ? AND dismissed_at IS NULL",
                state,
                Timestamp.from(now),
                value.id,
            ) == 1,
        )
    }

    fun purgeClosed(
        before: Instant,
        limit: Int,
    ): Int {
        if (limit <= 0) return 0
        return jdbc.update(
            "DELETE FROM loyalty_point_adjustment_preparation WHERE id IN " +
                "(SELECT id FROM loyalty_point_adjustment_preparation WHERE dismissed_at <= ? ORDER BY dismissed_at, id LIMIT ?)",
            Timestamp.from(before),
            limit,
        )
    }

    private fun row(rs: ResultSet) =
        PointAdjustmentPreparation(
            rs.getObject("id", UUID::class.java),
            rs.getObject("actor_id", UUID::class.java),
            rs.getObject("point_account_id", UUID::class.java),
            rs.getString("request_body"),
            rs.getString("payload_hash"),
            PointAdjustmentPreparationState.valueOf(rs.getString("state")),
            rs.getString("response_body"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("dismissed_at")?.toInstant(),
        )
}

package io.github.kdh949.beanflow.fulfillment.internal

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * One aggregate row per candidate store that owns at least one slot inside the window.
 *
 * [corruptedCount] is carried out of SQL rather than filtered away in it. Counting corrupted rows
 * in the same pass keeps the statement count at one while still letting the service fail loudly:
 * a store whose counters are broken must not be reported as merely unavailable.
 */
internal data class PickupAvailabilityRow(
    val storeId: UUID,
    val availableCount: Long,
    val corruptedCount: Long,
)

/**
 * One statement for the whole candidate page.
 *
 * `store_id = ANY(?)` binds the candidate ids as a single `uuid[]`, so the SQL text is constant and
 * the plan is reused no matter how many candidates a page carries. `idx_pickup_slot_store_starts_id`
 * (V35) already covers `(store_id, starts_at, id)` and includes the three counter columns, so the
 * aggregate reads the index rather than the heap.
 *
 * The published 1,000-slot list bound is deliberately not applied here. That bound exists so a
 * catalogue *list* is never silently truncated; this query only asks whether a reservable slot
 * exists, and existence is unaffected by how many further slots follow.
 */
@Repository
internal class PickupAvailabilityQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findAvailability(
        storeIds: Collection<UUID>,
        now: Instant,
        horizonEnd: Instant,
    ): List<PickupAvailabilityRow> =
        jdbcTemplate.query(
            """
            SELECT store_id,
                   count(*) FILTER (
                       WHERE capacity - reserved_count - confirmed_count > 0
                         AND ends_at > starts_at
                   ) AS available_count,
                   count(*) FILTER (
                       WHERE capacity - reserved_count - confirmed_count < 0
                          OR ends_at <= starts_at
                   ) AS corrupted_count
              FROM fulfillment_pickup_slot
             WHERE store_id = ANY(?)
               AND starts_at > ?
               AND starts_at < ?
             GROUP BY store_id
            """.trimIndent(),
            PreparedStatementSetter { statement ->
                statement.setArray(1, statement.connection.createArrayOf("uuid", storeIds.toTypedArray()))
                statement.setTimestamp(2, Timestamp.from(now))
                statement.setTimestamp(3, Timestamp.from(horizonEnd))
            },
            RowMapper { resultSet, _ ->
                PickupAvailabilityRow(
                    storeId = resultSet.getObject("store_id", UUID::class.java),
                    availableCount = resultSet.getLong("available_count"),
                    corruptedCount = resultSet.getLong("corrupted_count"),
                )
            },
        )
}

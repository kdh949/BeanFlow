package io.github.kdh949.beanflow.fulfillment.internal

import io.github.kdh949.beanflow.fulfillment.api.PickupSlotView
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Published slot-list bound for one Store inside the seven-day window (BR-05, ADR-076). */
internal const val MAX_STORE_PICKUP_SLOTS = 1_000

/**
 * One flat DTO query per store. Remaining capacity is the raw owner arithmetic: it is not floored,
 * so a corrupted counter surfaces as a negative number that [PickupSlotQueryService] rejects instead
 * of being silently rendered as a plausible "full" slot.
 */
@Repository
internal class PickupSlotQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findOpenSlots(
        storeId: UUID,
        now: Instant,
        horizonEnd: Instant,
    ): List<PickupSlotView> =
        jdbcTemplate.query(
            """
            SELECT id, starts_at, ends_at,
                   capacity - reserved_count - confirmed_count AS remaining_capacity
              FROM fulfillment_pickup_slot
             WHERE store_id = ?
               AND starts_at > ?
               AND starts_at < ?
             ORDER BY starts_at, id
             LIMIT ${MAX_STORE_PICKUP_SLOTS + 1}
            """.trimIndent(),
            { resultSet, _ ->
                PickupSlotView(
                    pickupSlotId = resultSet.getObject("id", UUID::class.java),
                    startsAt = resultSet.getTimestamp("starts_at").toInstant(),
                    endsAt = resultSet.getTimestamp("ends_at").toInstant(),
                    remainingCapacity = resultSet.getLong("remaining_capacity"),
                )
            },
            storeId,
            Timestamp.from(now),
            Timestamp.from(horizonEnd),
        )
}

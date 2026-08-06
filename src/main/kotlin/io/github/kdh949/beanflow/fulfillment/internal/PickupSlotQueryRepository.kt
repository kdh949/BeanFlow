package io.github.kdh949.beanflow.fulfillment.internal

import io.github.kdh949.beanflow.fulfillment.api.PickupSlotView
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * One flat DTO query per store. Remaining capacity is derived in SQL from the owner counters and
 * floored at zero, so an over-committed slot is reported as full rather than as a negative number.
 */
@Repository
internal class PickupSlotQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findOpenSlots(
        storeId: UUID,
        now: Instant,
    ): List<PickupSlotView> =
        jdbcTemplate.query(
            """
            SELECT id, starts_at, ends_at,
                   GREATEST(capacity - reserved_count - confirmed_count, 0) AS remaining_capacity
              FROM fulfillment_pickup_slot
             WHERE store_id = ?
               AND ends_at > ?
             ORDER BY starts_at, id
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
        )
}

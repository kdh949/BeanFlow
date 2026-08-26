package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.NearbyStoreProfileProjection
import io.github.kdh949.beanflow.merchant.api.NearbyStoreProfileQuery
import io.github.kdh949.beanflow.merchant.api.StoreCustomerDisplayProjection
import io.github.kdh949.beanflow.merchant.api.StoreDiscoveryDisplayProjection
import io.github.kdh949.beanflow.merchant.api.StoreOperatingDay
import io.github.kdh949.beanflow.merchant.api.StoreWeeklyOperatingHours
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID

/**
 * Reads `merchant_store_discovery_profile` and current `merchant_store` state as one PostGIS
 * native projection.
 *
 * The range predicate is the raw `ST_DWithin` geography distance so the GiST index can be used.
 * The sort and cursor predicate use the canonical micrometer expression from the same projection,
 * so the response display value is never reused as a keyset value.
 *
 * The profile is intentionally not mapped as a JPA entity: the store write entity must not grow a
 * search object graph, and a spatial column mapping would require an additional persistence
 * dependency that this query does not need.
 */
@Repository
internal class StoreDiscoveryProfileQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findPickupCapableStoresNear(query: NearbyStoreProfileQuery): List<NearbyStoreProfileProjection> {
        val keysetPredicate = if (query.after == null) "" else " WHERE (candidate.distance_micrometers, candidate.store_id) > (?, ?)"
        val sql =
            """
            WITH candidate AS (
                    SELECT profile.store_id AS store_id,
                           profile.name AS name,
                           floor(ST_Distance(profile.location, $QUERY_POINT) * 1000000)::bigint
                               AS distance_micrometers,
                           (store.accepting_orders AND store.pickup_enabled) AS ordering_available,
                           store.image_thumbnail_key AS image_thumbnail_key
                      FROM merchant_store_discovery_profile profile
                      JOIN merchant_store store ON store.id = profile.store_id
                     WHERE ST_DWithin(profile.location, $QUERY_POINT, ?)
                       AND store.accepting_orders
                       AND store.pickup_enabled
            ),
            page AS (
                SELECT candidate.*
                  FROM candidate
                $keysetPredicate
                 ORDER BY candidate.distance_micrometers, candidate.store_id
                 LIMIT ?
            )
            SELECT page.store_id, page.name, page.distance_micrometers,
                   page.ordering_available, page.image_thumbnail_key,
                   display.address_line, display.directions_hint,
                   hours.day_of_week, hours.closed, hours.opens_at, hours.closes_at
              FROM page
              LEFT JOIN merchant_store_customer_display_profile display ON display.store_id = page.store_id
              LEFT JOIN merchant_store_operating_hours hours ON hours.store_id = page.store_id
             ORDER BY page.distance_micrometers, page.store_id, hours.day_of_week
            """.trimIndent()
        val arguments =
            mutableListOf<Any>(
                query.longitude,
                query.latitude,
                query.longitude,
                query.latitude,
                query.radiusMeters,
            )
        query.after?.let { after ->
            arguments.add(after.distanceMicrometers)
            arguments.add(after.storeId)
        }
        arguments.add(query.limit)
        return jdbcTemplate
            .query(sql, ::nearbyRow, *arguments.toTypedArray())
            .groupBy(NearbyStoreDisplayRow::storeId)
            .values
            .map { rows -> rows.first().toProjection(rows.nearbySchedule()) }
    }

    fun countStores(): Long =
        jdbcTemplate.queryForObject("SELECT count(*) FROM merchant_store", Long::class.java)
            ?: throw IllegalStateException("Store count query returned no row")

    fun findVisibleStores(storeIds: Collection<UUID>): List<StoreDiscoveryDisplayProjection> {
        if (storeIds.isEmpty()) return emptyList()
        return jdbcTemplate
            .query({ connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT profile.store_id,
                               profile.name,
                               (store.accepting_orders AND store.pickup_enabled) AS ordering_available,
                               store.image_thumbnail_key AS image_thumbnail_key,
                               display.address_line, display.directions_hint,
                               hours.day_of_week, hours.closed, hours.opens_at, hours.closes_at
                          FROM merchant_store_discovery_profile profile
                          JOIN merchant_store store ON store.id = profile.store_id
                          LEFT JOIN merchant_store_customer_display_profile display ON display.store_id = profile.store_id
                          LEFT JOIN merchant_store_operating_hours hours ON hours.store_id = profile.store_id
                         WHERE profile.store_id = ANY(?::uuid[])
                         ORDER BY profile.store_id, hours.day_of_week
                        """.trimIndent(),
                    ).also { statement ->
                        statement.setArray(1, connection.createArrayOf("uuid", storeIds.toSet().toTypedArray()))
                    }
            }, ::visibleRow)
            .groupBy(StoreDisplayRow::storeId)
            .values
            .map { rows -> rows.first().toProjection(rows.storeSchedule()) }
    }

    private fun nearbyRow(
        resultSet: java.sql.ResultSet,
        @Suppress("UNUSED_PARAMETER") rowNumber: Int,
    ) = NearbyStoreDisplayRow(
        storeId = resultSet.getObject("store_id", UUID::class.java),
        name = resultSet.getString("name"),
        distanceMicrometers = resultSet.getLong("distance_micrometers"),
        orderingAvailable = resultSet.getBoolean("ordering_available"),
        imageThumbnailKey = resultSet.getString("image_thumbnail_key"),
        addressLine = resultSet.getString("address_line"),
        directionsHint = resultSet.getString("directions_hint"),
        operatingDay = resultSet.operatingDay(),
    )

    private fun visibleRow(
        resultSet: java.sql.ResultSet,
        @Suppress("UNUSED_PARAMETER") rowNumber: Int,
    ) = StoreDisplayRow(
        storeId = resultSet.getObject("store_id", UUID::class.java),
        name = resultSet.getString("name"),
        orderingAvailable = resultSet.getBoolean("ordering_available"),
        imageThumbnailKey = resultSet.getString("image_thumbnail_key"),
        addressLine = resultSet.getString("address_line"),
        directionsHint = resultSet.getString("directions_hint"),
        operatingDay = resultSet.operatingDay(),
    )

    private companion object {
        /**
         * The canonical decimal coordinate is bound as `numeric` and cast in SQL, so the exact
         * request value reaches `ST_MakePoint` instead of a client-side rounded double.
         */
        const val QUERY_POINT =
            "ST_SetSRID(ST_MakePoint(?::double precision, ?::double precision), 4326)::geography"
    }
}

private data class NearbyStoreDisplayRow(
    val storeId: UUID,
    val name: String,
    val distanceMicrometers: Long,
    val orderingAvailable: Boolean,
    val imageThumbnailKey: String?,
    val addressLine: String?,
    val directionsHint: String?,
    val operatingDay: StoreOperatingDay?,
) {
    fun toProjection(operatingHours: StoreWeeklyOperatingHours?) =
        NearbyStoreProfileProjection(
            storeId = storeId,
            name = name,
            distanceMicrometers = distanceMicrometers,
            orderingAvailable = orderingAvailable,
            customerDisplay = StoreCustomerDisplayProjection(addressLine, directionsHint, operatingHours),
            imageThumbnailKey = imageThumbnailKey,
        )
}

private data class StoreDisplayRow(
    val storeId: UUID,
    val name: String,
    val orderingAvailable: Boolean,
    val imageThumbnailKey: String?,
    val addressLine: String?,
    val directionsHint: String?,
    val operatingDay: StoreOperatingDay?,
) {
    fun toProjection(operatingHours: StoreWeeklyOperatingHours?) =
        StoreDiscoveryDisplayProjection(
            storeId = storeId,
            name = name,
            orderingAvailable = orderingAvailable,
            customerDisplay = StoreCustomerDisplayProjection(addressLine, directionsHint, operatingHours),
            imageThumbnailKey = imageThumbnailKey,
        )
}

private fun List<NearbyStoreDisplayRow>.nearbySchedule(): StoreWeeklyOperatingHours? =
    mapNotNull(NearbyStoreDisplayRow::operatingDay).takeIf(List<*>::isNotEmpty)?.let(::StoreWeeklyOperatingHours)

private fun List<StoreDisplayRow>.storeSchedule(): StoreWeeklyOperatingHours? =
    mapNotNull(StoreDisplayRow::operatingDay).takeIf(List<*>::isNotEmpty)?.let(::StoreWeeklyOperatingHours)

private fun java.sql.ResultSet.operatingDay(): StoreOperatingDay? {
    val day = getObject("day_of_week", Int::class.javaObjectType) ?: return null
    return StoreOperatingDay(
        dayOfWeek = DayOfWeek.of(day),
        closed = getBoolean("closed"),
        opensAt = getObject("opens_at", LocalTime::class.java),
        closesAt = getObject("closes_at", LocalTime::class.java),
    )
}

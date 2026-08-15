package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.NearbyStoreProfileProjection
import io.github.kdh949.beanflow.merchant.api.NearbyStoreProfileQuery
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
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
            SELECT candidate.store_id, candidate.name, candidate.distance_micrometers,
                   candidate.accepting_orders, candidate.pickup_enabled
              FROM (
                    SELECT profile.store_id AS store_id,
                           profile.name AS name,
                           floor(ST_Distance(profile.location, $QUERY_POINT) * 1000000)::bigint
                               AS distance_micrometers,
                           store.accepting_orders AS accepting_orders,
                           store.pickup_enabled AS pickup_enabled
                      FROM merchant_store_discovery_profile profile
                      JOIN merchant_store store ON store.id = profile.store_id
                     WHERE ST_DWithin(profile.location, $QUERY_POINT, ?)
                       AND store.accepting_orders
                       AND store.pickup_enabled
                   ) AS candidate
            $keysetPredicate
             ORDER BY candidate.distance_micrometers, candidate.store_id
             LIMIT ?
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
        return jdbcTemplate.query(sql, { resultSet, _ ->
            val acceptingOrders = resultSet.getBoolean("accepting_orders")
            NearbyStoreProfileProjection(
                storeId = resultSet.getObject("store_id", UUID::class.java),
                name = resultSet.getString("name"),
                distanceMicrometers = resultSet.getLong("distance_micrometers"),
                open = acceptingOrders,
                pickupAvailable = acceptingOrders && resultSet.getBoolean("pickup_enabled"),
            )
        }, *arguments.toTypedArray())
    }

    fun countStores(): Long =
        jdbcTemplate.queryForObject("SELECT count(*) FROM merchant_store", Long::class.java)
            ?: throw IllegalStateException("Store count query returned no row")

    private companion object {
        /**
         * The canonical decimal coordinate is bound as `numeric` and cast in SQL, so the exact
         * request value reaches `ST_MakePoint` instead of a client-side rounded double.
         */
        const val QUERY_POINT =
            "ST_SetSRID(ST_MakePoint(?::double precision, ?::double precision), 4326)::geography"
    }
}

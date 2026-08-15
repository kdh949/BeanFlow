package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.discovery.api.CustomerStoreView
import io.github.kdh949.beanflow.discovery.api.FavoriteStoreOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionException
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Customer favorites are owned by Discovery, while a store's public display and owner state remain
 * Merchant-owned. The service keeps those boundaries explicit: favorite ordering comes from the
 * Discovery table, then Merchant hydrates the ids in one bulk query and Fulfillment judges slot
 * availability in one bulk query.
 */
@Service
internal class FavoriteStoreService(
    private val favorites: FavoriteStoreRepository,
    private val hydrator: CustomerStoreHydrator,
) : FavoriteStoreOperations {
    @Transactional(readOnly = true)
    override fun list(
        customerId: UUID,
        now: Instant,
    ): List<CustomerStoreView> {
        val storeIds = persistence { favorites.findStoreIds(customerId) }
        // A stale reference to a store no longer publicly discoverable stays in the customer's
        // source record but is not exposed. It must not change the order of the remaining rows.
        return hydrator.hydrate(storeIds, now)
    }

    @Transactional
    override fun add(
        customerId: UUID,
        storeId: UUID,
        now: Instant,
    ) {
        if (!hydrator.isVisible(storeId)) {
            throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Store was not found")
        }
        // The composite primary key and `ON CONFLICT DO NOTHING` make same-customer retries and
        // simultaneous PUTs converge to one row instead of surfacing a unique-constraint 500.
        persistence { favorites.insertIfAbsent(customerId, storeId, now) }
    }

    @Transactional
    override fun remove(
        customerId: UUID,
        storeId: UUID,
    ) {
        // A missing row is intentionally a successful idempotent DELETE. It does not reveal
        // whether another customer ever favorited the store and does not require a target lookup.
        persistence { favorites.delete(customerId, storeId) }
    }

    private fun <T> persistence(block: () -> T): T =
        try {
            block()
        } catch (failure: DataAccessException) {
            unavailable(failure)
        } catch (failure: TransactionException) {
            unavailable(failure)
        }

    private fun unavailable(cause: Throwable): Nothing =
        throw DomainFailure(
            FailureCode.DEPENDENCY_UNAVAILABLE,
            "Favorite store persistence is unavailable",
        ).also { it.initCause(cause) }
}

@Repository
internal class FavoriteStoreRepository(
    private val jdbc: org.springframework.jdbc.core.JdbcTemplate,
) {
    fun findStoreIds(customerId: UUID): List<UUID> =
        jdbc.query(
            """
            SELECT store_id
              FROM discovery_customer_favorite_store
             WHERE customer_id = ?
             ORDER BY created_at DESC, store_id ASC
            """.trimIndent(),
            { resultSet, _ -> resultSet.getObject("store_id", UUID::class.java) },
            customerId,
        )

    fun insertIfAbsent(
        customerId: UUID,
        storeId: UUID,
        now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO discovery_customer_favorite_store (customer_id, store_id, created_at)
            VALUES (?, ?, ?)
            ON CONFLICT (customer_id, store_id) DO NOTHING
            """.trimIndent(),
            customerId,
            storeId,
            Timestamp.from(now),
        )
    }

    fun delete(
        customerId: UUID,
        storeId: UUID,
    ) {
        jdbc.update(
            "DELETE FROM discovery_customer_favorite_store WHERE customer_id = ? AND store_id = ?",
            customerId,
            storeId,
        )
    }
}

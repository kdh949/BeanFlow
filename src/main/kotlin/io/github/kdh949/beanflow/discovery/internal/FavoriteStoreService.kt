package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.discovery.api.CustomerStoreView
import io.github.kdh949.beanflow.discovery.api.FavoriteStoreOperations
import io.github.kdh949.beanflow.discovery.api.MAX_FAVORITE_STORES
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
        limit: Int,
    ): List<CustomerStoreView> {
        val storeIds = persistence { favorites.findStoreIds(customerId, limit) }
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
        persistence {
            // Counting and then inserting is only correct if nothing else can add a row for this
            // customer in between. Two simultaneous PUTs both reading 199 would otherwise both
            // insert and settle at 201, so the customer is serialized for the rest of the
            // transaction before the count is read.
            favorites.lockCustomer(customerId)
            if (!favorites.exists(customerId, storeId)) {
                if (favorites.count(customerId) >= MAX_FAVORITE_STORES) {
                    throw DomainFailure(
                        FailureCode.FAVORITE_STORE_LIMIT_EXCEEDED,
                        "A customer may hold at most $MAX_FAVORITE_STORES favorite stores",
                    )
                }
                // The composite primary key and `ON CONFLICT DO NOTHING` still make same-customer
                // retries converge to one row rather than surfacing a unique-constraint 500.
                favorites.insertIfAbsent(customerId, storeId, now)
            }
        }
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
    fun findStoreIds(
        customerId: UUID,
        limit: Int,
    ): List<UUID> =
        jdbc.query(
            """
            SELECT store_id
              FROM discovery_customer_favorite_store
             WHERE customer_id = ?
             ORDER BY created_at DESC, store_id ASC
             LIMIT ?
            """.trimIndent(),
            { resultSet, _ -> resultSet.getObject("store_id", UUID::class.java) },
            customerId,
            limit,
        )

    /** Transaction-scoped and per customer, so it never blocks another customer's writes. */
    fun lockCustomer(customerId: UUID) {
        jdbc.queryForObject(
            "select pg_advisory_xact_lock(hashtextextended(?, 0))",
            Any::class.java,
            "discovery-favorite:$customerId",
        )
    }

    fun exists(
        customerId: UUID,
        storeId: UUID,
    ): Boolean =
        jdbc.queryForObject(
            "SELECT exists(SELECT 1 FROM discovery_customer_favorite_store WHERE customer_id = ? AND store_id = ?)",
            Boolean::class.java,
            customerId,
            storeId,
        ) == true

    fun count(customerId: UUID): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM discovery_customer_favorite_store WHERE customer_id = ?",
            Int::class.java,
            customerId,
        ) ?: 0

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

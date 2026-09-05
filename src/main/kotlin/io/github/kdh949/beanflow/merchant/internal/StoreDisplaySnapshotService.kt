package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.StoreDisplaySnapshot
import io.github.kdh949.beanflow.merchant.api.StoreDisplaySnapshotOperations
import io.github.kdh949.beanflow.merchant.api.StoreDisplaySnapshotPage
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class StoreDisplaySnapshotService(
    private val jdbcTemplate: JdbcTemplate,
) : StoreDisplaySnapshotOperations {
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    override fun require(storeId: UUID): StoreDisplaySnapshot {
        val snapshots =
            jdbcTemplate.query(
                """
                SELECT profile.store_id, profile.name, store.version AS store_version
                  FROM merchant_store_discovery_profile profile
                  JOIN merchant_store store ON store.id = profile.store_id
                 WHERE profile.store_id = ?
                """.trimIndent(),
                { resultSet, _ ->
                    StoreDisplaySnapshot(
                        storeId = resultSet.getObject("store_id", UUID::class.java),
                        name = resultSet.getString("name"),
                        storeVersion = resultSet.getLong("store_version"),
                    )
                },
                storeId,
            )
        if (snapshots.size != 1) {
            unavailable("Verified store display profile is missing")
        }
        val snapshot = snapshots.single()
        val normalizedName = snapshot.name.trim()
        if (snapshot.storeId != storeId || normalizedName.isEmpty() || normalizedName.length > 200 || normalizedName != snapshot.name) {
            unavailable("Verified store display profile is invalid")
        }
        return snapshot
    }

    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    override fun list(
        afterName: String?,
        afterStoreId: UUID?,
        limit: Int,
    ): StoreDisplaySnapshotPage {
        if (limit !in 1..100) throw DomainFailure(FailureCode.INVALID_REQUEST, "Store display limit must be between 1 and 100")
        if ((afterName == null) != (afterStoreId == null)) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Store display cursor boundary must be complete")
        }
        return try {
            val boundaryClause = if (afterName == null) "" else "WHERE (profile.name, profile.store_id) > (?, ?)"
            val parameters = mutableListOf<Any>()
            if (afterName != null && afterStoreId != null) {
                parameters.add(afterName)
                parameters.add(afterStoreId)
            }
            parameters.add(limit + 1)
            val snapshots =
                jdbcTemplate
                    .query(
                        """
                        SELECT profile.store_id, profile.name, store.version AS store_version
                          FROM merchant_store_discovery_profile profile
                          JOIN merchant_store store ON store.id = profile.store_id
                         $boundaryClause
                         ORDER BY profile.name, profile.store_id
                         LIMIT ?
                        """.trimIndent(),
                        { resultSet, _ ->
                            StoreDisplaySnapshot(
                                storeId = resultSet.getObject("store_id", UUID::class.java),
                                name = resultSet.getString("name"),
                                storeVersion = resultSet.getLong("store_version"),
                            )
                        },
                        *parameters.toTypedArray(),
                    ).onEach { snapshot ->
                        if (snapshot.name.isBlank() || snapshot.name.length > 200 || snapshot.name != snapshot.name.trim()) {
                            unavailable("Verified store display profile is invalid")
                        }
                    }
            val hasMore = snapshots.size > limit
            val stores = snapshots.take(limit)
            val boundary = stores.lastOrNull().takeIf { hasMore }
            StoreDisplaySnapshotPage(stores, boundary?.name, boundary?.storeId)
        } catch (failure: DataAccessException) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Verified store display profiles are unavailable",
            ).also { it.initCause(failure) }
        }
    }

    private fun unavailable(message: String): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)
}

package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.StoreSearchMenuSource
import io.github.kdh949.beanflow.merchant.api.StoreSearchTermSource
import io.github.kdh949.beanflow.merchant.api.StoreSearchTermSourceQuery
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class StoreSearchTermSourceQueryService(
    private val repository: StoreSearchTermSourceRepository,
) : StoreSearchTermSourceQuery {
    @Transactional(readOnly = true)
    override fun findStoreIdsAfter(
        afterStoreId: UUID?,
        limit: Int,
    ): List<UUID> {
        require(limit in 1..MAX_CHUNK_SIZE) { "Store id page size must be between 1 and $MAX_CHUNK_SIZE" }
        return repository.findStoreIdsAfter(afterStoreId, limit)
    }

    @Transactional(readOnly = true)
    override fun findSearchTermSource(storeId: UUID): StoreSearchTermSource? {
        val storeName = repository.findStoreName(storeId) ?: return null
        if (storeName.isBlank()) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Store has no searchable profile name",
                targetReference = storeId.toString(),
            )
        }
        return StoreSearchTermSource(
            storeId = storeId,
            storeName = storeName,
            availableMenus = repository.findAvailableMenus(storeId),
        )
    }

    private companion object {
        const val MAX_CHUNK_SIZE = 1000
    }
}

@Repository
internal class StoreSearchTermSourceRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findStoreIdsAfter(
        afterStoreId: UUID?,
        limit: Int,
    ): List<UUID> {
        val keysetPredicate = if (afterStoreId == null) "" else " WHERE id > ?"
        val arguments = if (afterStoreId == null) arrayOf<Any>(limit) else arrayOf<Any>(afterStoreId, limit)
        return jdbcTemplate.query(
            "SELECT id FROM merchant_store$keysetPredicate ORDER BY id LIMIT ?",
            { resultSet, _ -> resultSet.getObject("id", UUID::class.java) },
            *arguments,
        )
    }

    /**
     * Returns the profile name, an empty string when the store exists without a profile, and null
     * when the store itself is gone. The caller separates "index this" from "this is broken" from
     * "this disappeared" without a second round trip.
     */
    fun findStoreName(storeId: UUID): String? =
        jdbcTemplate
            .query(
                """
                SELECT COALESCE(profile.name, '') AS name
                  FROM merchant_store store
                  LEFT JOIN merchant_store_discovery_profile profile ON profile.store_id = store.id
                 WHERE store.id = ?
                """.trimIndent(),
                { resultSet, _ -> resultSet.getString("name") },
                storeId,
            ).firstOrNull()

    fun findAvailableMenus(storeId: UUID): List<StoreSearchMenuSource> =
        jdbcTemplate.query(
            """
            SELECT id, name
              FROM merchant_menu
             WHERE store_id = ?
               AND available
             ORDER BY id
            """.trimIndent(),
            { resultSet, _ ->
                StoreSearchMenuSource(
                    menuId = resultSet.getObject("id", UUID::class.java),
                    name = resultSet.getString("name"),
                )
            },
            storeId,
        )
}

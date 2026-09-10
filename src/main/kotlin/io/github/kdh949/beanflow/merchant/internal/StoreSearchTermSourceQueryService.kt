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
    override fun findRebuildTargetStoreIds(): List<UUID> = repository.findStoreIds()

    @Transactional(readOnly = true)
    override fun findAllSearchTermSources(): List<StoreSearchTermSource> =
        repository.findAllSearchTermSources().map(::requireSearchableProfile)

    @Transactional(readOnly = true)
    override fun findSearchTermSource(storeId: UUID): StoreSearchTermSource? {
        val storeName = repository.findStoreName(storeId) ?: return null
        return requireSearchableProfile(
            StoreSearchTermSource(
                storeId = storeId,
                storeName = storeName,
                availableMenus = repository.findAvailableMenus(storeId),
            ),
        )
    }

    private fun requireSearchableProfile(source: StoreSearchTermSource): StoreSearchTermSource {
        if (source.storeName.isBlank()) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Store has no searchable profile name",
                targetReference = source.storeId.toString(),
            )
        }
        return source
    }
}

@Repository
internal class StoreSearchTermSourceRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findStoreIds(): List<UUID> =
        jdbcTemplate.query(
            "SELECT id FROM merchant_store ORDER BY id",
            { resultSet, _ -> resultSet.getObject("id", UUID::class.java) },
        )

    fun findAllSearchTermSources(): List<StoreSearchTermSource> {
        val sources = linkedMapOf<UUID, StoreSearchTermSourceBuilder>()
        jdbcTemplate.query(
            """
            SELECT store.id AS store_id,
                   COALESCE(profile.name, '') AS store_name,
                   menu.id AS menu_id,
                   menu.name AS menu_name
              FROM merchant_store store
              LEFT JOIN merchant_store_discovery_profile profile ON profile.store_id = store.id
              LEFT JOIN merchant_menu menu
                ON menu.store_id = store.id AND menu.available AND menu.lifecycle = 'ACTIVE'
             ORDER BY store.id, menu.id
            """.trimIndent(),
        ) { resultSet ->
            val storeId = resultSet.getObject("store_id", UUID::class.java)
            val source = sources.getOrPut(storeId) { StoreSearchTermSourceBuilder(storeId, resultSet.getString("store_name")) }
            resultSet.getObject("menu_id", UUID::class.java)?.let { menuId ->
                source.availableMenus += StoreSearchMenuSource(menuId, resultSet.getString("menu_name"))
            }
        }
        return sources.values.map { source ->
            StoreSearchTermSource(source.storeId, source.storeName, source.availableMenus)
        }
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
               AND lifecycle = 'ACTIVE'
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

    private data class StoreSearchTermSourceBuilder(
        val storeId: UUID,
        val storeName: String,
        val availableMenus: MutableList<StoreSearchMenuSource> = mutableListOf(),
    )
}

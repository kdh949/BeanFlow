package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.StoreDisplaySnapshot
import io.github.kdh949.beanflow.merchant.api.StoreDisplaySnapshotOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
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

    private fun unavailable(message: String): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)
}

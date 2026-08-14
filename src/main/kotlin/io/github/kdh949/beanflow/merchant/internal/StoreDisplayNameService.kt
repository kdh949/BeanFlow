package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.StoreDisplayNameOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.dao.DataAccessException
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class StoreDisplayNameService(
    private val stores: StoreJpaRepository,
    private val jdbc: JdbcTemplate,
) : StoreDisplayNameOperations {
    @Transactional(readOnly = true)
    override fun requireCurrentName(storeId: UUID): String =
        try {
            jdbc.queryForObject(
                "SELECT name FROM merchant_store_discovery_profile WHERE store_id = ?",
                String::class.java,
                storeId,
            ) ?: missingProfile(storeId)
        } catch (_: EmptyResultDataAccessException) {
            if (stores.existsById(storeId)) missingProfile(storeId)
            throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Store was not found")
        } catch (failure: DataAccessException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Store display name could not be read").also {
                it.initCause(failure)
            }
        }

    private fun missingProfile(storeId: UUID): Nothing =
        throw DomainFailure(
            FailureCode.DEPENDENCY_UNAVAILABLE,
            "Store display profile is unavailable for $storeId",
        )
}

package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.StorePolicyScopeOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class JpaStorePolicyScopeService(
    private val storeRepository: StoreJpaRepository,
) : StorePolicyScopeOperations {
    @Transactional(readOnly = true)
    override fun requireExisting(storeId: UUID) {
        val exists =
            try {
                storeRepository.existsById(storeId)
            } catch (failure: DataAccessException) {
                throw DomainFailure(
                    FailureCode.DEPENDENCY_UNAVAILABLE,
                    "Store policy scope could not be verified",
                )
            }
        if (!exists) {
            throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Store was not found")
        }
    }

    @Transactional(readOnly = true)
    override fun pickupOrderingAvailable(storeId: UUID): Boolean {
        val store =
            try {
                storeRepository.findById(storeId).orElse(null)
            } catch (failure: DataAccessException) {
                throw DomainFailure(
                    FailureCode.DEPENDENCY_UNAVAILABLE,
                    "Store policy scope could not be verified",
                ).also { it.initCause(failure) }
            } ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Store was not found")
        return store.acceptingOrders && store.pickupEnabled
    }
}

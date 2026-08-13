package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.loyalty.api.CustomerPointAccountProvisioningFailed
import io.github.kdh949.beanflow.loyalty.api.CustomerPointAccountProvisioningOperations
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class CustomerPointAccountProvisioningService(
    private val entityManager: EntityManager,
) : CustomerPointAccountProvisioningOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun create(customerId: UUID) {
        try {
            entityManager.persist(
                PointAccountEntity(
                    id = customerId,
                    customerId = customerId,
                    availablePointsKrw = 0,
                    reservedPointsKrw = 0,
                    recoveryPendingKrw = 0,
                ),
            )
            entityManager.flush()
        } catch (failure: RuntimeException) {
            throw CustomerPointAccountProvisioningFailed(failure)
        }
    }
}

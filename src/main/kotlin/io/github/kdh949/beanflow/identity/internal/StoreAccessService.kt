package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.identity.api.StoreAccessOperations
import io.github.kdh949.beanflow.identity.api.StoreActor
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class StoreAccessService(
    private val repository: StoreMembershipJpaRepository,
) : StoreAccessOperations {
    @Transactional(readOnly = true)
    override fun requireOrderManagementAccess(
        actorId: UUID,
        storeId: UUID,
        actorRoles: Set<StoreActorRole>,
    ): StoreActor {
        val membership =
            repository.findByActorIdAndStoreId(actorId, storeId)
                ?: denied("Active store membership is required")
        if (membership.status != StoreMembershipStatus.ACTIVE) {
            denied("Store membership is revoked")
        }
        if (membership.membershipRole !in actorRoles) {
            denied("JWT role and store membership role do not match")
        }
        return StoreActor(actorId, storeId, membership.membershipRole)
    }

    private fun denied(message: String): Nothing = throw DomainFailure(FailureCode.ACCESS_DENIED, message)
}

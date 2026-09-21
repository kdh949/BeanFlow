package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.loyalty.api.DemoPointProvisioning
import io.github.kdh949.beanflow.loyalty.api.PointIssuerType
import jakarta.persistence.EntityManager
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
@ConditionalOnProperty(name = ["beanflow.demo.enabled"], havingValue = "true")
@Transactional(propagation = Propagation.MANDATORY)
internal class DemoPointProvisioningService(
    private val em: EntityManager,
) : DemoPointProvisioning {
    override fun grantSample(
        customerId: UUID,
        storeId: UUID,
        sourceReference: String,
        expiresAt: Instant,
        now: Instant,
    ) {
        val account =
            em.find(PointAccountEntity::class.java, customerId, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
                ?: error("Demo point account missing")
        account.availablePointsKrw = Math.addExact(account.availablePointsKrw, 4500)
        val lotId = UUID.randomUUID()
        em.persist(
            PointLotEntity(
                lotId,
                account.id,
                4500,
                expiresAt = expiresAt,
                issuerType = PointIssuerType.STORE,
                issuerReference = storeId.toString(),
            ),
        )
        em.persist(
            PointTransactionEntity(
                UUID.randomUUID(),
                account.id,
                lotId,
                4500,
                PointTransactionType.ACCRUAL,
                sourceReference = sourceReference,
                occurredAt = now,
            ),
        )
        em.flush()
    }
}

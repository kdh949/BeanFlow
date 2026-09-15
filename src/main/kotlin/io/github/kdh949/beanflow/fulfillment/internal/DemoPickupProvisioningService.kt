package io.github.kdh949.beanflow.fulfillment.internal

import io.github.kdh949.beanflow.fulfillment.api.DemoPickupProvisioning
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
@ConditionalOnProperty(name = ["beanflow.demo.enabled"], havingValue = "true")
@Transactional(propagation = Propagation.MANDATORY)
internal class DemoPickupProvisioningService(
    private val slots: PickupSlotJpaRepository,
) : DemoPickupProvisioning {
    override fun create(
        storeId: UUID,
        expiresAt: Instant,
    ): UUID {
        val ids =
            (1L..3L).map { index ->
                val start = expiresAt.plusSeconds(index * 600)
                val id = UUID.randomUUID()
                slots.saveAndFlush(PickupSlotEntity(id, storeId, start, start.plusSeconds(600), 20))
                id
            }
        return ids.first()
    }
}

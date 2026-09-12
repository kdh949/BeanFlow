package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.CustomerSupportOrderOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class CustomerSupportOrderAdapter(
    private val orders: OrderJpaRepository,
) : CustomerSupportOrderOperations {
    @Transactional(readOnly = true)
    override fun resolveOwned(
        customerId: UUID,
        publicReference: String,
    ): UUID {
        val reference = PublicOrderReference.parse(publicReference)
        return orders.findByPublicReferenceAndCustomerId(reference.value, customerId)?.id
            ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Owned order was not found")
    }
}

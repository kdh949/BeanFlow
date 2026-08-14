package io.github.kdh949.beanflow.loyalty.api

import java.util.UUID

/** Public cross-context port used only inside an existing customer-registration transaction. */
interface CustomerPointAccountProvisioningOperations {
    fun create(customerId: UUID)
}

class CustomerPointAccountProvisioningFailed(
    cause: RuntimeException,
) : RuntimeException("Customer PointAccount provisioning failed", cause)

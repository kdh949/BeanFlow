package io.github.kdh949.beanflow.ordering.api

import java.util.UUID

/** Resolves only an authenticated customer's own opaque order reference for support intake. */
interface CustomerSupportOrderOperations {
    fun resolveOwned(
        customerId: UUID,
        publicReference: String,
    ): UUID
}

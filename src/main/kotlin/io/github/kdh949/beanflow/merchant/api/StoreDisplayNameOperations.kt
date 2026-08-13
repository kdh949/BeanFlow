package io.github.kdh949.beanflow.merchant.api

import java.util.UUID

interface StoreDisplayNameOperations {
    /** Returns the current owner-verified public store name or fails without an empty fallback. */
    fun requireCurrentName(storeId: UUID): String
}

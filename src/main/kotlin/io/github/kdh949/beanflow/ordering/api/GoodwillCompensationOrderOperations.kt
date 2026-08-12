package io.github.kdh949.beanflow.ordering.api

import java.util.UUID

data class GoodwillCompensationOrderFact(
    val orderId: UUID,
    val customerId: UUID,
    val storeId: UUID,
    val payableKrw: Long,
    val currency: String,
    val state: String,
    val version: Long,
)

interface GoodwillCompensationOrderOperations {
    /** Returns the latest owner-context facts without exposing the Ordering write model. */
    fun find(orderId: UUID): GoodwillCompensationOrderFact?
}

package io.github.kdh949.beanflow.merchant.api

import java.time.Instant
import java.util.UUID

data class StoreOrderingPolicySnapshot(
    val storeId: UUID,
    val acceptingOrders: Boolean,
    val pickupEnabled: Boolean,
    val version: Long,
    val updatedAt: Instant,
)

data class ReplaceStoreOrderingPolicyCommand(
    val actorId: UUID,
    val idempotencyKey: String,
    val storeId: UUID,
    val acceptingOrders: Boolean,
    val pickupEnabled: Boolean,
    val expectedVersion: Long,
    val now: Instant,
)

data class StoreOrderingPolicyReplacement(
    val policy: StoreOrderingPolicySnapshot,
    val previous: StoreOrderingPolicySnapshot,
    val changed: Boolean,
    val replayed: Boolean,
)

/** Store ordering-policy owner port. Mutations must join the caller's membership-lock transaction. */
interface StoreOrderingPolicyOperations {
    fun find(storeId: UUID): StoreOrderingPolicySnapshot

    fun replace(command: ReplaceStoreOrderingPolicyCommand): StoreOrderingPolicyReplacement
}

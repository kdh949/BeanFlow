package io.github.kdh949.beanflow.merchant.api

import java.time.Instant
import java.util.UUID

data class StoreIdentitySnapshot(
    val storeId: UUID,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val regionCode: String,
    val version: Long,
    val acceptingOrders: Boolean,
    val pickupEnabled: Boolean,
)

data class StoreIdentityCommand(
    val actorId: UUID,
    val key: String,
    val storeId: UUID?,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val regionCode: String?,
    val expectedVersion: Long?,
    val reason: String,
    val now: Instant,
)

data class StoreIdentityChange(
    val commandId: UUID,
    val snapshot: StoreIdentitySnapshot,
    val previous: StoreIdentitySnapshot?,
    val replayed: Boolean,
)

/** Joins the caller's transaction; caller owns authorization and the corresponding Audit. */
interface StoreIdentityOperations {
    fun get(storeId: UUID): StoreIdentitySnapshot

    fun list(
        query: String?,
        afterId: UUID?,
        limit: Int,
    ): List<StoreIdentitySnapshot>

    fun change(command: StoreIdentityCommand): StoreIdentityChange
}

package io.github.kdh949.beanflow.identity.api

import java.util.UUID

enum class StoreActorRole {
    OWNER,
    STAFF,
}

data class StoreActor(
    val actorId: UUID,
    val storeId: UUID,
    val role: StoreActorRole,
)

interface StoreAccessOperations {
    fun requireStoreAccess(
        actorId: UUID,
        storeId: UUID,
        actorRoles: Set<StoreActorRole>,
    ): StoreActor

    fun requireOrderManagementAccess(
        actorId: UUID,
        storeId: UUID,
        actorRoles: Set<StoreActorRole>,
    ): StoreActor

    /**
     * Revalidates catalogue authoring authority while holding the membership row shared lock.
     * A missing membership is intentionally indistinguishable from a missing target Store.
     */
    fun requireCatalogAccess(
        actorId: UUID,
        storeId: UUID,
        actorRoles: Set<StoreActorRole>,
    ): StoreActor
}

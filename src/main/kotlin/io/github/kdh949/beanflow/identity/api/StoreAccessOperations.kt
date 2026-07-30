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
    fun requireOrderManagementAccess(
        actorId: UUID,
        storeId: UUID,
        actorRoles: Set<StoreActorRole>,
    ): StoreActor
}

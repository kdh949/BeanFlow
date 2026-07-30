package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.identity.api.StoreActorRole
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

internal enum class StoreMembershipStatus {
    ACTIVE,
    REVOKED,
}

@Entity
@Table(name = "identity_store_membership")
internal class StoreMembershipEntity(
    @Id
    val id: UUID,
    @Column(name = "actor_id", nullable = false)
    val actorId: UUID,
    @Column(name = "store_id", nullable = false)
    val storeId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "membership_role", nullable = false)
    val membershipRole: StoreActorRole,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val status: StoreMembershipStatus,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant,
    @Version
    val version: Long = 0,
)

internal interface StoreMembershipJpaRepository : JpaRepository<StoreMembershipEntity, UUID> {
    fun findByActorIdAndStoreId(
        actorId: UUID,
        storeId: UUID,
    ): StoreMembershipEntity?
}

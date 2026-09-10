package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
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
    var membershipRole: StoreActorRole,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: StoreMembershipStatus,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Version
    var version: Long = 0,
) {
    fun replace(
        role: StoreActorRole,
        nextStatus: StoreMembershipStatus,
        now: Instant,
    ) {
        if (role == membershipRole && nextStatus == status) {
            throw DomainFailure(FailureCode.RESOURCE_STATE_CONFLICT, "Membership has no requested change")
        }
        if (nextStatus == StoreMembershipStatus.REVOKED && role != membershipRole) {
            throw DomainFailure(FailureCode.RESOURCE_STATE_CONFLICT, "Revocation preserves the membership role")
        }
        membershipRole = role
        status = nextStatus
        updatedAt = now
    }
}

internal interface StoreMembershipJpaRepository : JpaRepository<StoreMembershipEntity, UUID> {
    fun findByActorIdAndStoreId(
        actorId: UUID,
        storeId: UUID,
    ): StoreMembershipEntity?

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT membership FROM StoreMembershipEntity membership WHERE membership.actorId = :actorId AND membership.storeId = :storeId")
    fun findByActorIdAndStoreIdForShare(
        actorId: UUID,
        storeId: UUID,
    ): StoreMembershipEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT membership FROM StoreMembershipEntity membership WHERE membership.actorId = :actorId AND membership.storeId = :storeId")
    fun findByActorIdAndStoreIdForUpdate(
        actorId: UUID,
        storeId: UUID,
    ): StoreMembershipEntity?

    fun findAllByActorIdAndStatusOrderByStoreIdAsc(
        actorId: UUID,
        status: StoreMembershipStatus,
    ): List<StoreMembershipEntity>

    fun findAllByActorIdOrderByStoreIdAsc(actorId: UUID): List<StoreMembershipEntity>
}

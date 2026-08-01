package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.OperatorPermission
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.io.Serializable
import java.time.Instant
import java.util.UUID

internal enum class OperatorPermissionGrantState {
    ACTIVE,
    REVOKED,
}

internal data class OperatorPermissionGrantId(
    var actorId: UUID = UUID(0, 0),
    var permission: OperatorPermission = OperatorPermission.EXPIRED_BENEFIT_POLICY_READ,
) : Serializable

@Entity
@IdClass(OperatorPermissionGrantId::class)
@Table(name = "operations_operator_permission_grant")
internal class OperatorPermissionGrantEntity(
    @Id
    @Column(name = "actor_id", nullable = false)
    val actorId: UUID,
    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val permission: OperatorPermission,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var state: OperatorPermissionGrantState,
    @Column(name = "granted_at", nullable = false)
    var grantedAt: Instant,
    @Column(name = "revoked_at")
    var revokedAt: Instant?,
    @Column(nullable = false)
    var version: Long,
    @Column(name = "audit_source_reference", nullable = false, length = 200)
    var auditSourceReference: String,
)

internal interface OperatorPermissionGrantJpaRepository : JpaRepository<OperatorPermissionGrantEntity, OperatorPermissionGrantId> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select grant from OperatorPermissionGrantEntity grant " +
            "where grant.actorId = :actorId and grant.permission = :permission",
    )
    fun findLocked(
        @Param("actorId") actorId: UUID,
        @Param("permission") permission: OperatorPermission,
    ): OperatorPermissionGrantEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select grant from OperatorPermissionGrantEntity grant " +
            "where grant.actorId = :actorId and grant.permission = :permission " +
            "and grant.state = io.github.kdh949.beanflow.operations.internal.OperatorPermissionGrantState.ACTIVE",
    )
    fun findActiveLocked(
        @Param("actorId") actorId: UUID,
        @Param("permission") permission: OperatorPermission,
    ): OperatorPermissionGrantEntity?
}

@Repository
internal class DatabaseAdvisoryLock(
    private val entityManager: EntityManager,
) {
    fun lock(key: String) {
        entityManager
            .createNativeQuery(
                "select pg_advisory_xact_lock(hashtextextended(cast(:lockKey as text), 0))",
            ).setParameter("lockKey", key)
            .singleResult
    }
}

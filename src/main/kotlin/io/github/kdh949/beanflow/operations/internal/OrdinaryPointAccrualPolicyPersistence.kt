package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualExpiryRule
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyScopeType
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicySnapshot
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyState
import io.github.kdh949.beanflow.operations.api.PointAccrualIssuerType
import io.github.kdh949.beanflow.operations.api.PointAccrualRoundingMode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.LockModeType
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.Immutable
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.io.Serializable
import java.time.Instant
import java.util.UUID

@Entity
@Immutable
@Table(name = "operations_point_accrual_policy_version")
internal class OrdinaryPointAccrualPolicyVersionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "point_accrual_policy_version_seq")
    @SequenceGenerator(
        name = "point_accrual_policy_version_seq",
        sequenceName = "operations_point_accrual_policy_version_seq",
        allocationSize = 1,
    )
    @Column(name = "policy_version_id")
    val policyVersionId: Long = 0,
    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false)
    val scopeType: OrdinaryPointAccrualPolicyScopeType,
    @Column(name = "scope_reference", nullable = false)
    val scopeReference: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val state: OrdinaryPointAccrualPolicyState,
    @Column(name = "accrual_rate_bps")
    val accrualRateBps: Int?,
    @Enumerated(EnumType.STRING)
    @Column(name = "rounding_mode")
    val roundingMode: PointAccrualRoundingMode?,
    @Enumerated(EnumType.STRING)
    @Column(name = "issuer_type")
    val issuerType: PointAccrualIssuerType?,
    @Column(name = "issuer_reference", length = 240)
    val issuerReference: String?,
    @Enumerated(EnumType.STRING)
    @Column(name = "expiry_rule")
    val expiryRule: OrdinaryPointAccrualExpiryRule?,
    @Column(name = "validity_days")
    val validityDays: Int?,
    @Column(name = "effective_at", nullable = false)
    val effectiveAt: Instant,
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false)
    val actorType: AuditActorType,
    @Column(name = "actor_reference", nullable = false, length = 500)
    val actorReference: String,
    @Column(nullable = false, length = 500)
    val reason: String,
    @Column(name = "idempotency_actor_id")
    val idempotencyActorId: UUID?,
    @Column(name = "idempotency_key", length = 128)
    val idempotencyKey: String?,
    @Column(name = "payload_hash", nullable = false, length = 64)
    val payloadHash: String,
    @Column(name = "source_reference", nullable = false, length = 240)
    val sourceReference: String,
) {
    fun toSnapshot(): OrdinaryPointAccrualPolicySnapshot {
        check(state == OrdinaryPointAccrualPolicyState.OVERRIDE) {
            "INHERIT_GLOBAL is not a complete point accrual policy"
        }
        return OrdinaryPointAccrualPolicySnapshot(
            policyVersionId = policyVersionId,
            scopeType = scopeType,
            scopeReference = scopeReference,
            accrualRateBps = checkNotNull(accrualRateBps),
            roundingMode = checkNotNull(roundingMode),
            issuerType = checkNotNull(issuerType),
            issuerReference = checkNotNull(issuerReference),
            expiryRule = checkNotNull(expiryRule),
            validityDays = checkNotNull(validityDays),
            canonicalPolicyHash = payloadHash,
        )
    }
}

internal data class OrdinaryPointAccrualPolicyHeadId(
    var scopeType: OrdinaryPointAccrualPolicyScopeType = OrdinaryPointAccrualPolicyScopeType.GLOBAL,
    var scopeReference: UUID = OrdinaryPointAccrualPolicySnapshot.GLOBAL_SCOPE_REFERENCE,
) : Serializable

@Entity
@IdClass(OrdinaryPointAccrualPolicyHeadId::class)
@Table(name = "operations_point_accrual_policy_head")
internal class OrdinaryPointAccrualPolicyHeadEntity(
    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false)
    val scopeType: OrdinaryPointAccrualPolicyScopeType,
    @Id
    @Column(name = "scope_reference", nullable = false)
    val scopeReference: UUID,
    @Column(name = "policy_version_id", nullable = false)
    var policyVersionId: Long,
    @Version
    @Column(nullable = false)
    var version: Long = 0,
)

internal interface OrdinaryPointAccrualPolicyVersionJpaRepository : JpaRepository<OrdinaryPointAccrualPolicyVersionEntity, Long> {
    fun findByIdempotencyActorIdAndIdempotencyKey(
        idempotencyActorId: UUID,
        idempotencyKey: String,
    ): OrdinaryPointAccrualPolicyVersionEntity?

    @Query(
        "select policy from OrdinaryPointAccrualPolicyVersionEntity policy " +
            "where policy.scopeType = :scopeType and policy.scopeReference = :scopeReference " +
            "and policy.policyVersionId < :beforePolicyVersionId " +
            "order by policy.policyVersionId desc",
    )
    fun findHistory(
        @Param("scopeType") scopeType: OrdinaryPointAccrualPolicyScopeType,
        @Param("scopeReference") scopeReference: UUID,
        @Param("beforePolicyVersionId") beforePolicyVersionId: Long,
        pageable: Pageable,
    ): List<OrdinaryPointAccrualPolicyVersionEntity>
}

internal interface OrdinaryPointAccrualPolicyHeadJpaRepository :
    JpaRepository<OrdinaryPointAccrualPolicyHeadEntity, OrdinaryPointAccrualPolicyHeadId> {
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query(
        "select head from OrdinaryPointAccrualPolicyHeadEntity head " +
            "where head.scopeType = :scopeType and head.scopeReference = :scopeReference",
    )
    fun findShared(
        @Param("scopeType") scopeType: OrdinaryPointAccrualPolicyScopeType,
        @Param("scopeReference") scopeReference: UUID,
    ): OrdinaryPointAccrualPolicyHeadEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select head from OrdinaryPointAccrualPolicyHeadEntity head " +
            "where head.scopeType = :scopeType and head.scopeReference = :scopeReference",
    )
    fun findLocked(
        @Param("scopeType") scopeType: OrdinaryPointAccrualPolicyScopeType,
        @Param("scopeReference") scopeReference: UUID,
    ): OrdinaryPointAccrualPolicyHeadEntity?
}

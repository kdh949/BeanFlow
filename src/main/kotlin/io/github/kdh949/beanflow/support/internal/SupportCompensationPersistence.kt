package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.support.internal.domain.SupportActionApprovalRoute
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationBand
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationBenefitType
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationCostSnapshot
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationEvidenceBasis
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationLimitRule
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationLimitScope
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationPolicyVersion
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationRequest
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationRequestState
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationResponsibility
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.io.Serializable
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "support_compensation_policy_version")
internal class SupportCompensationPolicyVersionEntity(
    @Id
    val id: UUID,
    @Column(nullable = false, length = 80)
    val code: String,
    @Column(name = "effective_at", nullable = false)
    val effectiveAt: Instant,
    @Column(name = "low_amount_maximum_krw", nullable = false)
    val lowAmountMaximumKrw: Long,
    @Column(name = "high_amount_maximum_krw", nullable = false)
    val highAmountMaximumKrw: Long,
    @Column(name = "supported_amount_maximum_krw", nullable = false)
    val supportedAmountMaximumKrw: Long,
    @Column(name = "low_order_ratio_maximum_bps", nullable = false)
    val lowOrderRatioMaximumBps: Int,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    fun toDomain(rules: List<SupportCompensationLimitRuleEntity>): SupportCompensationPolicyVersion =
        SupportCompensationPolicyVersion(
            id,
            code,
            effectiveAt,
            lowAmountMaximumKrw,
            highAmountMaximumKrw,
            supportedAmountMaximumKrw,
            lowOrderRatioMaximumBps,
            rules.sortedBy { it.scope.ordinal }.map { it.toDomain() },
        )
}

@Entity
@Table(name = "support_compensation_policy_head")
internal class SupportCompensationPolicyHeadEntity(
    @Id
    val name: String,
    @Column(name = "current_version_id", nullable = false)
    val currentVersionId: UUID,
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant,
    @Column(nullable = false)
    val version: Long,
)

@Entity
@Table(name = "support_compensation_limit_rule")
internal class SupportCompensationLimitRuleEntity(
    @Id
    val id: UUID,
    @Column(name = "policy_version_id", nullable = false)
    val policyVersionId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val scope: SupportCompensationLimitScope,
    @Column(name = "window_seconds", nullable = false)
    val windowSeconds: Long,
    @Column(name = "maximum_krw", nullable = false)
    val maximumKrw: Long,
) {
    fun toDomain() = SupportCompensationLimitRule(scope, Duration.ofSeconds(windowSeconds), maximumKrw)
}

@Entity
@Table(name = "support_compensation_request")
internal class SupportCompensationRequestEntity(
    @Id
    val id: UUID,
    @Column(name = "support_case_id", nullable = false)
    val supportCaseId: UUID,
    @Column(name = "customer_id", nullable = false)
    val customerId: UUID,
    @Column(name = "incident_id", nullable = false)
    val incidentId: UUID,
    @Column(name = "order_id")
    val orderId: UUID?,
    @Column(name = "store_id")
    val storeId: UUID?,
    @Column(name = "requester_actor_id", nullable = false)
    val requesterActorId: UUID,
    @Column(name = "executor_actor_id", nullable = false)
    var executorActorId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "benefit_type", nullable = false, length = 16)
    val benefitType: SupportCompensationBenefitType,
    @Column(name = "amount_krw", nullable = false)
    val amountKrw: Long,
    @Column(name = "coupon_template_id")
    val couponTemplateId: UUID?,
    @Column(name = "policy_version_id", nullable = false)
    val policyVersionId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val band: SupportCompensationBand,
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_route", nullable = false, length = 48)
    val approvalRoute: SupportActionApprovalRoute,
    @Column(name = "verification_session_id", nullable = false)
    val verificationSessionId: UUID,
    @Column(name = "target_version", nullable = false)
    val targetVersion: Long,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val responsibility: SupportCompensationResponsibility,
    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_basis", length = 32)
    val evidenceBasis: SupportCompensationEvidenceBasis?,
    @Column(name = "cost_evidence_digest", length = 64)
    val costEvidenceDigest: String?,
    @Column(name = "platform_share_bps", nullable = false)
    val platformShareBps: Int,
    @Column(name = "store_share_bps", nullable = false)
    val storeShareBps: Int,
    @Column(name = "payload_digest", nullable = false, length = 64)
    val payloadDigest: String,
    @Column(name = "evidence_digest", nullable = false, length = 64)
    val evidenceDigest: String,
    @Column(name = "action_request_id")
    val actionRequestId: UUID?,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var state: SupportCompensationRequestState,
    @Column(name = "terminal_benefit_id")
    var terminalBenefitId: UUID?,
    @Column(name = "notification_delivery_id")
    var notificationDeliveryId: UUID?,
    @Column(name = "notification_failure_code", length = 80)
    var notificationFailureCode: String?,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Column(nullable = false)
    var version: Long,
) {
    fun toAggregate(): SupportCompensationRequest =
        SupportCompensationRequest.reconstitute(
            id,
            supportCaseId,
            customerId,
            incidentId,
            orderId,
            storeId,
            requesterActorId,
            executorActorId,
            benefitType,
            amountKrw,
            couponTemplateId,
            policyVersionId,
            band,
            approvalRoute,
            verificationSessionId,
            targetVersion,
            SupportCompensationCostSnapshot(
                responsibility,
                evidenceBasis,
                costEvidenceDigest,
                platformShareBps,
                storeShareBps,
            ),
            payloadDigest,
            evidenceDigest,
            actionRequestId,
            state,
            terminalBenefitId,
            notificationDeliveryId,
            notificationFailureCode,
            version,
            updatedAt,
        )

    fun apply(
        aggregate: SupportCompensationRequest,
        changedAt: Instant,
    ) {
        executorActorId = aggregate.executorActorId
        state = aggregate.state
        terminalBenefitId = aggregate.terminalBenefitId
        notificationDeliveryId = aggregate.notificationDeliveryId
        notificationFailureCode = aggregate.notificationFailureCode
        updatedAt = changedAt
        version = aggregate.version
    }
}

@Entity
@Table(name = "support_compensation_terminal_benefit")
internal class SupportCompensationTerminalBenefitEntity(
    @Id
    val id: UUID,
    @Column(name = "request_id", nullable = false)
    val requestId: UUID,
    @Column(name = "incident_id", nullable = false)
    val incidentId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "benefit_type", nullable = false, length = 16)
    val benefitType: SupportCompensationBenefitType,
    @Column(name = "owner_reference", nullable = false, length = 240)
    val ownerReference: String,
    @Column(name = "amount_krw", nullable = false)
    val amountKrw: Long,
    @Column(name = "policy_version_id", nullable = false)
    val policyVersionId: UUID,
    @Column(name = "issued_at", nullable = false)
    val issuedAt: Instant,
)

internal data class SupportCompensationLimitLockId(
    var scope: SupportCompensationLimitScope = SupportCompensationLimitScope.CUSTOMER,
    var scopeId: UUID = UUID(0, 0),
) : Serializable

@Entity
@IdClass(SupportCompensationLimitLockId::class)
@Table(name = "support_compensation_limit_lock")
internal class SupportCompensationLimitLockEntity(
    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val scope: SupportCompensationLimitScope,
    @Id
    @Column(name = "scope_id", nullable = false)
    val scopeId: UUID,
)

@Entity
@Table(name = "support_compensation_limit_consumption")
internal class SupportCompensationLimitConsumptionEntity(
    @Id
    val id: UUID,
    @Column(name = "request_id", nullable = false)
    val requestId: UUID,
    @Column(name = "policy_version_id", nullable = false)
    val policyVersionId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val scope: SupportCompensationLimitScope,
    @Column(name = "scope_id", nullable = false)
    val scopeId: UUID,
    @Column(name = "amount_krw", nullable = false)
    val amountKrw: Long,
    @Column(name = "issued_at", nullable = false)
    val issuedAt: Instant,
)

internal enum class SupportCompensationCommandOperation {
    CREATE,
    EXECUTE,
    RETRY_NOTIFICATION,
}

@Entity
@Table(name = "support_compensation_command_idempotency")
internal class SupportCompensationCommandIdempotencyEntity(
    @Id
    val id: UUID,
    @Column(name = "actor_id", nullable = false)
    val actorId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    val operation: SupportCompensationCommandOperation,
    @Column(name = "idempotency_key", nullable = false, length = 128)
    val idempotencyKey: String,
    @Column(name = "payload_hash", nullable = false, length = 64)
    val payloadHash: String,
    @Column(name = "compensation_request_id", nullable = false)
    val compensationRequestId: UUID,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "retention_expires_at", nullable = false)
    val retentionExpiresAt: Instant,
)

internal interface SupportCompensationPolicyHeadJpaRepository : JpaRepository<SupportCompensationPolicyHeadEntity, String>

internal interface SupportCompensationPolicyVersionJpaRepository : JpaRepository<SupportCompensationPolicyVersionEntity, UUID>

internal interface SupportCompensationLimitRuleJpaRepository : JpaRepository<SupportCompensationLimitRuleEntity, UUID> {
    fun findAllByPolicyVersionId(policyVersionId: UUID): List<SupportCompensationLimitRuleEntity>
}

internal interface SupportCompensationRequestJpaRepository : JpaRepository<SupportCompensationRequestEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from SupportCompensationRequestEntity request where request.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): SupportCompensationRequestEntity?
}

internal interface SupportCompensationTerminalBenefitJpaRepository : JpaRepository<SupportCompensationTerminalBenefitEntity, UUID> {
    fun findByIncidentId(incidentId: UUID): SupportCompensationTerminalBenefitEntity?

    fun findByRequestId(requestId: UUID): SupportCompensationTerminalBenefitEntity?
}

internal interface SupportCompensationLimitLockJpaRepository :
    JpaRepository<SupportCompensationLimitLockEntity, SupportCompensationLimitLockId> {
    @Modifying
    @Query(
        value = "insert into support_compensation_limit_lock(scope, scope_id) values (:scope, :scopeId) on conflict do nothing",
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("scope") scope: String,
        @Param("scopeId") scopeId: UUID,
    ): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select lock from SupportCompensationLimitLockEntity lock where lock.scope = :scope and lock.scopeId = :scopeId")
    fun findLocked(
        @Param("scope") scope: SupportCompensationLimitScope,
        @Param("scopeId") scopeId: UUID,
    ): SupportCompensationLimitLockEntity?
}

internal interface SupportCompensationLimitConsumptionJpaRepository : JpaRepository<SupportCompensationLimitConsumptionEntity, UUID> {
    @Query(
        "select coalesce(sum(consumption.amountKrw), 0) from SupportCompensationLimitConsumptionEntity consumption " +
            "where consumption.scope = :scope and consumption.scopeId = :scopeId and consumption.issuedAt >= :cutoff",
    )
    fun sumInWindow(
        @Param("scope") scope: SupportCompensationLimitScope,
        @Param("scopeId") scopeId: UUID,
        @Param("cutoff") cutoff: Instant,
    ): Long
}

internal interface SupportCompensationCommandIdempotencyJpaRepository :
    JpaRepository<SupportCompensationCommandIdempotencyEntity, UUID> {
    fun findByActorIdAndOperationAndIdempotencyKey(
        actorId: UUID,
        operation: SupportCompensationCommandOperation,
        idempotencyKey: String,
    ): SupportCompensationCommandIdempotencyEntity?
}

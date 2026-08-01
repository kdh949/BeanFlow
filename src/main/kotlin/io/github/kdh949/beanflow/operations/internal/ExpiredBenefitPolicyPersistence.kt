package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationMode
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationTrigger
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitType
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
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.io.Serializable
import java.time.Instant
import java.util.UUID

@Entity
@Immutable
@Table(name = "operations_expired_benefit_policy_version")
internal class ExpiredBenefitPolicyVersionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "expired_benefit_policy_version_seq")
    @SequenceGenerator(
        name = "expired_benefit_policy_version_seq",
        sequenceName = "operations_expired_benefit_policy_version_seq",
        allocationSize = 1,
    )
    @Column(name = "policy_version")
    val policyVersion: Long = 0,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val trigger: ExpiredBenefitRestorationTrigger,
    @Enumerated(EnumType.STRING)
    @Column(name = "benefit_type", nullable = false)
    val benefitType: ExpiredBenefitType,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val mode: ExpiredBenefitRestorationMode,
    @Column(name = "compensation_validity_days", nullable = false)
    val compensationValidityDays: Int,
    @Column(name = "effective_at", nullable = false)
    val effectiveAt: Instant,
    @Column(name = "updated_by", nullable = false)
    val updatedBy: UUID,
    @Column(nullable = false, length = 500)
    val reason: String,
    @Column(name = "idempotency_actor_id")
    val idempotencyActorId: UUID?,
    @Column(name = "idempotency_key", length = 128)
    val idempotencyKey: String?,
    @Column(name = "payload_hash", length = 64)
    val payloadHash: String?,
)

internal data class ExpiredBenefitPolicyHeadId(
    var trigger: ExpiredBenefitRestorationTrigger = ExpiredBenefitRestorationTrigger.STORE_REJECTION,
    var benefitType: ExpiredBenefitType = ExpiredBenefitType.COUPON,
) : Serializable

@Entity
@IdClass(ExpiredBenefitPolicyHeadId::class)
@Table(name = "operations_expired_benefit_policy_head")
internal class ExpiredBenefitPolicyHeadEntity(
    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val trigger: ExpiredBenefitRestorationTrigger,
    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "benefit_type", nullable = false)
    val benefitType: ExpiredBenefitType,
    @Column(name = "policy_version", nullable = false)
    var policyVersion: Long,
    @Version
    var version: Long = 0,
)

internal interface ExpiredBenefitPolicyVersionJpaRepository : JpaRepository<ExpiredBenefitPolicyVersionEntity, Long> {
    fun findByIdempotencyActorIdAndIdempotencyKey(
        idempotencyActorId: UUID,
        idempotencyKey: String,
    ): ExpiredBenefitPolicyVersionEntity?
}

internal interface ExpiredBenefitPolicyHeadJpaRepository : JpaRepository<ExpiredBenefitPolicyHeadEntity, ExpiredBenefitPolicyHeadId> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select head from ExpiredBenefitPolicyHeadEntity head " +
            "where head.trigger = :trigger and head.benefitType = :benefitType",
    )
    fun findLocked(
        @Param("trigger") trigger: ExpiredBenefitRestorationTrigger,
        @Param("benefitType") benefitType: ExpiredBenefitType,
    ): ExpiredBenefitPolicyHeadEntity?

    @Query("select head from ExpiredBenefitPolicyHeadEntity head order by head.trigger, head.benefitType")
    fun findAllOrdered(): List<ExpiredBenefitPolicyHeadEntity>
}

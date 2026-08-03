package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationMode
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitType
import io.github.kdh949.beanflow.operations.api.OrderCompensationState
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepState
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepType
import io.github.kdh949.beanflow.operations.api.OrderCompensationTrigger
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.io.Serializable
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "operations_order_compensation_case")
internal class OrderCompensationCaseEntity(
    @Id
    val id: UUID,
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,
    @Column(name = "terminal_order_version", nullable = false)
    val terminalOrderVersion: Long,
    @Column(name = "customer_id", nullable = false)
    val customerId: UUID,
    @Column(name = "store_id", nullable = false)
    val storeId: UUID,
    @Column(name = "event_id", nullable = false)
    val eventId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val trigger: OrderCompensationTrigger,
    @Column(name = "source_reference", nullable = false, length = 240)
    val sourceReference: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var state: OrderCompensationState,
    @Column(name = "correlation_id", nullable = false, length = 160)
    val correlationId: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)

internal data class OrderCompensationBenefitPolicySnapshotId(
    var caseId: UUID? = null,
    var benefitType: ExpiredBenefitType? = null,
) : Serializable

@Entity
@IdClass(OrderCompensationBenefitPolicySnapshotId::class)
@Table(name = "operations_order_compensation_benefit_policy_snapshot")
internal class OrderCompensationBenefitPolicySnapshotEntity(
    @Id
    @Column(name = "case_id", nullable = false)
    val caseId: UUID,
    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "benefit_type", nullable = false)
    val benefitType: ExpiredBenefitType,
    @Column(name = "policy_version_id", nullable = false)
    val policyVersionId: Long,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val mode: ExpiredBenefitRestorationMode,
    @Column(name = "compensation_validity_days", nullable = false)
    val compensationValidityDays: Int,
)

@Entity
@Table(name = "operations_order_compensation_step")
internal class OrderCompensationStepEntity(
    @Id
    val id: UUID,
    @Column(name = "case_id", nullable = false)
    val caseId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", nullable = false)
    val stepType: OrderCompensationStepType,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var state: OrderCompensationStepState,
    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int,
    @Column(name = "last_error_code", length = 100)
    var lastErrorCode: String?,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Version
    var version: Long = 0,
)

internal interface OrderCompensationCaseJpaRepository : JpaRepository<OrderCompensationCaseEntity, UUID> {
    fun findByOrderId(orderId: UUID): OrderCompensationCaseEntity?

    fun findBySourceReference(sourceReference: String): OrderCompensationCaseEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select beanCase from OrderCompensationCaseEntity beanCase where beanCase.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): OrderCompensationCaseEntity?
}

internal interface OrderCompensationBenefitPolicySnapshotJpaRepository :
    JpaRepository<OrderCompensationBenefitPolicySnapshotEntity, OrderCompensationBenefitPolicySnapshotId> {
    fun findAllByCaseIdOrderByBenefitType(caseId: UUID): List<OrderCompensationBenefitPolicySnapshotEntity>
}

internal interface OrderCompensationStepJpaRepository : JpaRepository<OrderCompensationStepEntity, UUID> {
    fun findAllByCaseIdOrderByStepType(caseId: UUID): List<OrderCompensationStepEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select step from OrderCompensationStepEntity step " +
            "where step.caseId = :caseId and step.stepType = :stepType",
    )
    fun findLocked(
        @Param("caseId") caseId: UUID,
        @Param("stepType") stepType: OrderCompensationStepType,
    ): OrderCompensationStepEntity?
}

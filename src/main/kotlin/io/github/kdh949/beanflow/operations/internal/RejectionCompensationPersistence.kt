package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationMode
import io.github.kdh949.beanflow.operations.api.RejectionCompensationState
import io.github.kdh949.beanflow.operations.api.RejectionCompensationStepState
import io.github.kdh949.beanflow.operations.api.RejectionCompensationStepType
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
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "operations_rejection_compensation_case")
internal class RejectionCompensationCaseEntity(
    @Id
    val id: UUID,
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,
    @Column(name = "customer_id", nullable = false)
    val customerId: UUID,
    @Column(name = "store_id", nullable = false)
    val storeId: UUID,
    @Column(name = "event_id", nullable = false)
    val eventId: UUID,
    @Column(name = "source_reference", nullable = false, length = 200)
    val sourceReference: String,
    @Column(name = "policy_version", nullable = false)
    val policyVersion: Long,
    @Enumerated(EnumType.STRING)
    @Column(name = "policy_mode", nullable = false)
    val policyMode: ExpiredBenefitRestorationMode,
    @Column(name = "policy_validity_days", nullable = false)
    val policyValidityDays: Int,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var state: RejectionCompensationState,
    @Column(name = "correlation_id", nullable = false, length = 160)
    val correlationId: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)

@Entity
@Table(name = "operations_rejection_compensation_step")
internal class RejectionCompensationStepEntity(
    @Id
    val id: UUID,
    @Column(name = "case_id", nullable = false)
    val caseId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", nullable = false)
    val stepType: RejectionCompensationStepType,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var state: RejectionCompensationStepState,
    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int,
    @Column(name = "last_error_code", length = 100)
    var lastErrorCode: String?,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Version
    var version: Long = 0,
)

internal interface RejectionCompensationCaseJpaRepository : JpaRepository<RejectionCompensationCaseEntity, UUID> {
    fun findByOrderId(orderId: UUID): RejectionCompensationCaseEntity?

    fun findBySourceReference(sourceReference: String): RejectionCompensationCaseEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select beanCase from RejectionCompensationCaseEntity beanCase where beanCase.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): RejectionCompensationCaseEntity?
}

internal interface RejectionCompensationStepJpaRepository : JpaRepository<RejectionCompensationStepEntity, UUID> {
    fun findAllByCaseIdOrderByStepType(caseId: UUID): List<RejectionCompensationStepEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select step from RejectionCompensationStepEntity step " +
            "where step.caseId = :caseId and step.stepType = :stepType",
    )
    fun findLocked(
        @Param("caseId") caseId: UUID,
        @Param("stepType") stepType: RejectionCompensationStepType,
    ): RejectionCompensationStepEntity?
}

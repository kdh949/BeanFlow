package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.OpenReprocessingCaseCommand
import io.github.kdh949.beanflow.operations.api.ReprocessingCaseOperations
import io.github.kdh949.beanflow.shared.api.IdentifierSource
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
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

internal enum class ReprocessingCaseType {
    PAYMENT_RECONCILIATION,
    NOTIFICATION_DELIVERY,
    EVENT_PUBLICATION,
    SETTLEMENT_LATE_ITEM,
    ACCEPTANCE_TIMEOUT_WORK,
    PAYMENT_CANCELLATION_SETUP,
    SETTLEMENT_ADJUSTMENT,
    SETTLEMENT_DISPUTE,
}

internal enum class ReprocessingCaseStatus {
    OPEN,
    RUNNING,
    RESOLVED,
    MANUAL_REVIEW,
}

@Entity
@Table(name = "operations_reprocessing_case")
internal class ReprocessingCaseEntity(
    @Id
    val id: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "case_type", nullable = false)
    val caseType: ReprocessingCaseType,
    @Column(name = "owner_reference", nullable = false)
    val ownerReference: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ReprocessingCaseStatus,
    @Column(nullable = false)
    val reason: String,
    @Column(name = "correlation_id", nullable = false)
    val correlationId: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Column(length = 120)
    var resolution: String? = null,
    @Version
    var version: Long = 0,
)

internal interface ReprocessingCaseJpaRepository : JpaRepository<ReprocessingCaseEntity, UUID> {
    fun findByCaseTypeAndOwnerReference(
        caseType: ReprocessingCaseType,
        ownerReference: String,
    ): ReprocessingCaseEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select beanCase from ReprocessingCaseEntity beanCase where beanCase.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): ReprocessingCaseEntity?
}

@Service
internal class ReprocessingCaseService(
    private val repository: ReprocessingCaseJpaRepository,
    private val identifierSource: IdentifierSource,
) : ReprocessingCaseOperations {
    override fun openPaymentCase(command: OpenReprocessingCaseCommand): UUID {
        require(command.ownerReference.isNotBlank())
        require(command.reason.isNotBlank())
        require(command.correlationId.isNotBlank())
        repository
            .findByCaseTypeAndOwnerReference(
                ReprocessingCaseType.PAYMENT_RECONCILIATION,
                command.ownerReference,
            )?.let { return it.id }
        val entity =
            ReprocessingCaseEntity(
                id = identifierSource.next(),
                caseType = ReprocessingCaseType.PAYMENT_RECONCILIATION,
                ownerReference = command.ownerReference,
                status = ReprocessingCaseStatus.MANUAL_REVIEW,
                reason = command.reason,
                correlationId = command.correlationId,
                createdAt = command.now,
                updatedAt = command.now,
            )
        return repository.save(entity).id
    }
}

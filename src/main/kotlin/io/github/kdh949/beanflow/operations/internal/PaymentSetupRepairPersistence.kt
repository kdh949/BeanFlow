package io.github.kdh949.beanflow.operations.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

internal enum class PaymentSetupRepairAction {
    RECREATE_MISSING_CANCELLATION_REFUND,
}

internal enum class PaymentSetupRepairProposalState {
    PENDING_APPROVAL,
    EXECUTED,
    REJECTED,
    EXPIRED,
    STALE,
}

internal enum class PaymentSetupRepairIdempotencyOperation {
    PROPOSE,
    DECIDE,
}

@Entity
@Table(name = "operations_payment_setup_repair_proposal")
internal class PaymentSetupRepairProposalEntity(
    @Id
    val id: UUID,
    @Column(name = "case_id", nullable = false)
    val caseId: UUID,
    @Column(name = "case_version", nullable = false)
    val caseVersion: Long,
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,
    @Column(name = "cancellation_order_version", nullable = false)
    val cancellationOrderVersion: Long,
    @Column(name = "payment_id", nullable = false)
    val paymentId: UUID,
    @Column(name = "snapshot_id", nullable = false)
    val snapshotId: UUID,
    @Column(name = "snapshot_version", nullable = false)
    val snapshotVersion: Long,
    @Column(name = "refund_id", nullable = false)
    val refundId: UUID,
    @Column(name = "requested_amount_krw", nullable = false)
    val requestedAmountKrw: Long,
    @Column(name = "refund_source_fingerprint", nullable = false, length = 64)
    val refundSourceFingerprint: String,
    @Column(name = "provider_key_fingerprint", nullable = false, length = 64)
    val providerKeyFingerprint: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val action: PaymentSetupRepairAction,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var state: PaymentSetupRepairProposalState,
    @Column(name = "proposed_by", nullable = false)
    val proposedBy: UUID,
    @Column(name = "proposal_reason", nullable = false, length = 500)
    val proposalReason: String,
    @Column(name = "decided_by")
    var decidedBy: UUID? = null,
    @Column(name = "decision_reason", length = 500)
    var decisionReason: String? = null,
    @Column(name = "correlation_id", nullable = false, length = 160)
    val correlationId: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
    @Column(name = "decided_at")
    var decidedAt: Instant? = null,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Version
    var version: Long = 0,
)

internal interface PaymentSetupRepairProposalJpaRepository : JpaRepository<PaymentSetupRepairProposalEntity, UUID> {
    fun findByCaseIdAndState(
        caseId: UUID,
        state: PaymentSetupRepairProposalState,
    ): PaymentSetupRepairProposalEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select proposal from PaymentSetupRepairProposalEntity proposal where proposal.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): PaymentSetupRepairProposalEntity?

    @Query(
        "select proposal.id from PaymentSetupRepairProposalEntity proposal " +
            "where proposal.state = io.github.kdh949.beanflow.operations.internal.PaymentSetupRepairProposalState.PENDING_APPROVAL " +
            "and proposal.expiresAt <= :now order by proposal.expiresAt, proposal.id",
    )
    fun findDueIds(
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<UUID>
}

@Entity
@Table(name = "operations_payment_setup_repair_idempotency")
internal class PaymentSetupRepairIdempotencyEntity(
    @Id
    val id: UUID,
    @Column(name = "actor_id", nullable = false)
    val actorId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val operation: PaymentSetupRepairIdempotencyOperation,
    @Column(name = "idempotency_key", nullable = false, length = 128)
    val idempotencyKey: String,
    @Column(name = "payload_hash", nullable = false, length = 64)
    val payloadHash: String,
    @Column(name = "proposal_id", nullable = false)
    val proposalId: UUID,
    @Column(name = "response_json", nullable = false, columnDefinition = "text")
    val responseJson: String,
    @Column(name = "failure_code", length = 64)
    val failureCode: String?,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "retention_expires_at", nullable = false)
    val retentionExpiresAt: Instant,
)

internal interface PaymentSetupRepairIdempotencyJpaRepository : JpaRepository<PaymentSetupRepairIdempotencyEntity, UUID> {
    fun findByActorIdAndOperationAndIdempotencyKey(
        actorId: UUID,
        operation: PaymentSetupRepairIdempotencyOperation,
        idempotencyKey: String,
    ): PaymentSetupRepairIdempotencyEntity?

    @Query(
        "select record.id from PaymentSetupRepairIdempotencyEntity record " +
            "where record.retentionExpiresAt <= :now order by record.retentionExpiresAt, record.id",
    )
    fun findDueIds(
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<UUID>
}

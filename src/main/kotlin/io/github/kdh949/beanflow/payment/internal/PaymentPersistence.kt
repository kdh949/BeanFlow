package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.payment.internal.domain.PaymentApprovalState
import io.github.kdh949.beanflow.payment.internal.domain.PaymentType
import io.github.kdh949.beanflow.payment.internal.domain.RefundState
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

@Entity
@Table(name = "payment_payment")
internal class PaymentEntity(
    @Id
    val id: UUID,
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,
    @Column(name = "customer_id")
    val customerId: UUID? = null,
    @Column(name = "payment_method_id")
    val paymentMethodId: UUID? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val type: PaymentType,
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_state", nullable = false)
    var approvalState: PaymentApprovalState,
    @Column(name = "requested_amount_krw", nullable = false)
    val requestedAmountKrw: Long = 0,
    @Column(name = "approved_amount_krw")
    var approvedAmountKrw: Long? = null,
    @Column(name = "succeeded_refund_amount_krw", nullable = false)
    var succeededRefundAmountKrw: Long = 0,
    @Column(nullable = false)
    val currency: String,
    @Column(name = "benefit_snapshot_reference")
    val benefitSnapshotReference: String? = null,
    @Column(name = "source_reference", nullable = false)
    val sourceReference: String,
    @Column(name = "provider_transaction_reference")
    var providerTransactionReference: String? = null,
    @Column(name = "last_failure_code")
    var lastFailureCode: String? = null,
    @Column(name = "correlation_id", nullable = false)
    val correlationId: String,
    @Column(name = "approved_at")
    var approvedAt: Instant? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Version
    var version: Long = 0,
)

internal enum class PaymentMethodStatus {
    ACTIVE,
    REVOKED,
}

@Entity
@Table(name = "payment_method")
internal class PaymentMethodEntity(
    @Id
    val id: UUID,
    @Column(name = "customer_id", nullable = false)
    val customerId: UUID,
    @Column(nullable = false)
    val provider: String,
    @Column(name = "token_reference", nullable = false)
    val tokenReference: String,
    @Column(name = "display_alias", nullable = false)
    val displayAlias: String,
    @Column(name = "card_brand", nullable = false)
    val cardBrand: String,
    @Column(name = "last_four", nullable = false, length = 4)
    val lastFour: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PaymentMethodStatus,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Version
    var version: Long = 0,
)

internal enum class PaymentIdempotencyStatus {
    PROCESSING,
    UNKNOWN,
    RECONCILING,
    COMPLETED,
    FAILED,
    MANUAL_REVIEW,
}

@Entity
@Table(name = "payment_idempotency_record")
internal class PaymentIdempotencyEntity(
    @Id
    val id: UUID,
    @Column(name = "actor_id", nullable = false)
    val actorId: UUID,
    @Column(nullable = false)
    val operation: String,
    @Column(name = "idempotency_key", nullable = false)
    val idempotencyKey: String,
    @Column(name = "payload_hash", nullable = false, length = 64)
    val payloadHash: String,
    @Column(name = "payment_id", nullable = false)
    val paymentId: UUID,
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PaymentIdempotencyStatus,
    @Column(name = "response_status")
    var responseStatus: Int? = null,
    @Column(name = "response_body", columnDefinition = "text")
    var responseBody: String? = null,
    @Column(name = "started_at", nullable = false)
    val startedAt: Instant,
    @Column(name = "terminal_at")
    var terminalAt: Instant? = null,
    @Version
    var version: Long = 0,
)

internal enum class ReconciliationKind {
    APPROVAL_LOOKUP,
    LATE_VOID,
    LATE_REFUND,
}

internal enum class ReconciliationStatus {
    SCHEDULED,
    PROCESSING,
    RETRY_SCHEDULED,
    SUCCEEDED,
    MANUAL_REVIEW,
}

@Entity
@Table(name = "payment_reconciliation")
internal class PaymentReconciliationEntity(
    @Id
    val id: UUID,
    @Column(name = "payment_id", nullable = false)
    val paymentId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val kind: ReconciliationKind,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ReconciliationStatus,
    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int,
    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant,
    @Column(name = "claim_token")
    var claimToken: UUID? = null,
    @Column(name = "claim_until")
    var claimUntil: Instant? = null,
    @Column(name = "source_reference", nullable = false)
    val sourceReference: String,
    @Column(name = "last_failure_code")
    var lastFailureCode: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Version
    var version: Long = 0,
)

@Entity
@Table(name = "payment_refund")
internal class RefundEntity(
    @Id
    val id: UUID,
    @Column(name = "payment_id", nullable = false)
    val paymentId: UUID,
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,
    @Column(name = "requested_amount_krw", nullable = false)
    val requestedAmountKrw: Long,
    @Column(name = "succeeded_amount_krw")
    var succeededAmountKrw: Long? = null,
    @Column(nullable = false)
    val reason: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var state: RefundState,
    @Column(name = "provider_refund_reference")
    var providerRefundReference: String? = null,
    @Column(name = "provider_idempotency_key", nullable = false)
    val providerIdempotencyKey: String,
    @Column(name = "source_reference", nullable = false)
    val sourceReference: String,
    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int,
    @Column(name = "next_attempt_at")
    var nextAttemptAt: Instant?,
    @Column(name = "provider_request_started_at")
    var providerRequestStartedAt: Instant? = null,
    @Column(name = "claim_token")
    var claimToken: UUID? = null,
    @Column(name = "claim_until")
    var claimUntil: Instant? = null,
    @Column(name = "last_failure_code")
    var lastFailureCode: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Version
    var version: Long = 0,
)

internal interface PaymentJpaRepository : JpaRepository<PaymentEntity, UUID> {
    fun findByOrderId(orderId: UUID): PaymentEntity?

    fun findBySourceReference(sourceReference: String): PaymentEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select payment from PaymentEntity payment where payment.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): PaymentEntity?

    @Query(
        "select min(payment.updatedAt) from PaymentEntity payment " +
            "where payment.approvalState = " +
            "io.github.kdh949.beanflow.payment.internal.domain.PaymentApprovalState.UNKNOWN",
    )
    fun findOldestUnknownUpdatedAt(): Instant?
}

internal interface PaymentMethodJpaRepository : JpaRepository<PaymentMethodEntity, UUID>

internal interface PaymentIdempotencyJpaRepository : JpaRepository<PaymentIdempotencyEntity, UUID> {
    fun findByPaymentId(paymentId: UUID): PaymentIdempotencyEntity?

    fun findByActorIdAndOperationAndIdempotencyKey(
        actorId: UUID,
        operation: String,
        idempotencyKey: String,
    ): PaymentIdempotencyEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select record from PaymentIdempotencyEntity record where record.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): PaymentIdempotencyEntity?
}

internal interface PaymentReconciliationJpaRepository : JpaRepository<PaymentReconciliationEntity, UUID> {
    fun findByPaymentIdAndKind(
        paymentId: UUID,
        kind: ReconciliationKind,
    ): PaymentReconciliationEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select work from PaymentReconciliationEntity work where work.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): PaymentReconciliationEntity?

    @Query(
        "select work.id from PaymentReconciliationEntity work " +
            "where work.nextAttemptAt <= :now and (" +
            "work.status in (" +
            "io.github.kdh949.beanflow.payment.internal.ReconciliationStatus.SCHEDULED, " +
            "io.github.kdh949.beanflow.payment.internal.ReconciliationStatus.RETRY_SCHEDULED" +
            ") or (work.status = io.github.kdh949.beanflow.payment.internal.ReconciliationStatus.PROCESSING " +
            "and work.claimUntil <= :now)) order by work.nextAttemptAt, work.id",
    )
    fun findDueIds(
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<UUID>
}

internal interface RefundJpaRepository : JpaRepository<RefundEntity, UUID> {
    fun findBySourceReference(sourceReference: String): RefundEntity?

    fun findByPaymentIdAndReason(
        paymentId: UUID,
        reason: String,
    ): RefundEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select refund from RefundEntity refund where refund.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): RefundEntity?

    @Query(
        "select refund.id from RefundEntity refund where (" +
            "(refund.state in (" +
            "io.github.kdh949.beanflow.payment.internal.domain.RefundState.REQUESTED, " +
            "io.github.kdh949.beanflow.payment.internal.domain.RefundState.UNKNOWN" +
            ") and refund.nextAttemptAt <= :now) or " +
            "(refund.state in (" +
            "io.github.kdh949.beanflow.payment.internal.domain.RefundState.PROCESSING, " +
            "io.github.kdh949.beanflow.payment.internal.domain.RefundState.RECONCILING" +
            ") and refund.claimUntil <= :now)) " +
            "order by refund.nextAttemptAt, refund.id",
    )
    fun findDueIds(
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<UUID>
}

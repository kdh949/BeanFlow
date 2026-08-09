package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.payment.internal.domain.PaymentApprovalState
import io.github.kdh949.beanflow.payment.internal.domain.PaymentType
import io.github.kdh949.beanflow.payment.internal.domain.RefundClaimMode
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
    DEACTIVATION_REQUESTED,
    DEACTIVATION_UNKNOWN,
    RECONCILING,
    MANUAL_REVIEW,
    DEACTIVATED,
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
    @Column(name = "provider_customer_reference")
    val providerCustomerReference: String? = null,
    @Column(name = "display_alias", nullable = false)
    val displayAlias: String,
    @Column(name = "card_brand", nullable = false)
    val cardBrand: String,
    @Column(name = "last_four", nullable = false, length = 4)
    val lastFour: String,
    @Column(name = "is_default", nullable = false)
    var isDefault: Boolean = false,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PaymentMethodStatus,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Version
    var version: Long = 0,
) {
    fun markDefault(now: Instant) {
        if (status != PaymentMethodStatus.ACTIVE) throw PaymentMethodStateConflict()
        isDefault = true
        updatedAt = now
    }

    fun clearDefault(now: Instant) {
        isDefault = false
        updatedAt = now
    }

    fun requestDeactivation(now: Instant) {
        if (status != PaymentMethodStatus.ACTIVE) throw PaymentMethodStateConflict()
        status = PaymentMethodStatus.DEACTIVATION_REQUESTED
        isDefault = false
        updatedAt = now
    }

    fun markDeactivationUnknown(now: Instant) {
        if (status !in setOf(PaymentMethodStatus.DEACTIVATION_REQUESTED, PaymentMethodStatus.RECONCILING)) {
            throw PaymentMethodStateConflict()
        }
        status = PaymentMethodStatus.DEACTIVATION_UNKNOWN
        updatedAt = now
    }

    fun markReconciling(now: Instant) {
        if (status != PaymentMethodStatus.DEACTIVATION_UNKNOWN) throw PaymentMethodStateConflict()
        status = PaymentMethodStatus.RECONCILING
        updatedAt = now
    }

    fun markManualReview(now: Instant) {
        if (
            status !in
            setOf(
                PaymentMethodStatus.DEACTIVATION_REQUESTED,
                PaymentMethodStatus.DEACTIVATION_UNKNOWN,
                PaymentMethodStatus.RECONCILING,
            )
        ) {
            throw PaymentMethodStateConflict()
        }
        status = PaymentMethodStatus.MANUAL_REVIEW
        isDefault = false
        updatedAt = now
    }

    fun confirmDeactivated(now: Instant) {
        if (
            status !in
            setOf(
                PaymentMethodStatus.DEACTIVATION_REQUESTED,
                PaymentMethodStatus.DEACTIVATION_UNKNOWN,
                PaymentMethodStatus.RECONCILING,
                PaymentMethodStatus.MANUAL_REVIEW,
            )
        ) {
            throw PaymentMethodStateConflict()
        }
        status = PaymentMethodStatus.DEACTIVATED
        isDefault = false
        updatedAt = now
    }

    fun confirmProviderDeactivated(now: Instant) {
        if (status == PaymentMethodStatus.DEACTIVATED) return
        if (
            status !in
            setOf(
                PaymentMethodStatus.ACTIVE,
                PaymentMethodStatus.DEACTIVATION_REQUESTED,
                PaymentMethodStatus.DEACTIVATION_UNKNOWN,
                PaymentMethodStatus.RECONCILING,
                PaymentMethodStatus.MANUAL_REVIEW,
            )
        ) {
            throw PaymentMethodStateConflict()
        }
        status = PaymentMethodStatus.DEACTIVATED
        isDefault = false
        updatedAt = now
    }

    companion object {
        private val PROVIDER_REFERENCE_PATTERN = Regex("^bf_[A-Za-z0-9_-]{43}${'$'}")
        private val LAST_FOUR_PATTERN = Regex("^[0-9]{4}${'$'}")

        fun issueToss(
            id: UUID,
            customerId: UUID,
            tokenReference: String,
            providerCustomerReference: String,
            displayAlias: String,
            cardBrand: String,
            lastFour: String,
            now: Instant,
        ): PaymentMethodEntity {
            require(tokenReference.isNotBlank() && tokenReference.length <= 200)
            require(displayAlias.isNotBlank() && displayAlias.length <= 80 && displayAlias == displayAlias.trim())
            require(displayAlias.none(Char::isISOControl))
            require(cardBrand.isNotBlank() && cardBrand.length <= 40 && cardBrand == cardBrand.trim())
            require(LAST_FOUR_PATTERN.matches(lastFour))
            require(PROVIDER_REFERENCE_PATTERN.matches(providerCustomerReference))
            return PaymentMethodEntity(
                id = id,
                customerId = customerId,
                provider = "TOSS_PAYMENTS",
                tokenReference = tokenReference,
                providerCustomerReference = providerCustomerReference,
                displayAlias = displayAlias,
                cardBrand = cardBrand,
                lastFour = lastFour,
                isDefault = false,
                status = PaymentMethodStatus.ACTIVE,
                createdAt = now,
                updatedAt = now,
            )
        }
    }
}

internal class PaymentMethodStateConflict : RuntimeException("Payment method state does not allow this transition")

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
    @Column(name = "requested_points_krw", nullable = false)
    val requestedPointsKrw: Long = 0,
    @Column(name = "succeeded_amount_krw")
    var succeededAmountKrw: Long? = null,
    @Column(nullable = false)
    val reason: String,
    @Column(name = "customer_reason_code", length = 32)
    val customerReasonCode: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var state: RefundState,
    @Column(name = "provider_refund_reference")
    var providerRefundReference: String? = null,
    @Column(name = "provider_idempotency_key", nullable = false)
    val providerIdempotencyKey: String,
    @Column(name = "source_reference", nullable = false)
    val sourceReference: String,
    @Column(name = "actor_id")
    val actorId: UUID? = null,
    @Column(name = "idempotency_key")
    val idempotencyKey: String? = null,
    @Column(name = "payload_hash")
    val payloadHash: String? = null,
    @Column(name = "correlation_id")
    val correlationId: String? = null,
    @Column(name = "point_restoration_policy_version_id")
    val pointRestorationPolicyVersionId: Long? = null,
    @Column(name = "point_restoration_policy_trigger")
    val pointRestorationPolicyTrigger: String? = null,
    @Column(name = "point_restoration_policy_benefit_type")
    val pointRestorationPolicyBenefitType: String? = null,
    @Column(name = "point_restoration_policy_mode")
    val pointRestorationPolicyMode: String? = null,
    @Column(name = "point_restoration_policy_validity_days")
    val pointRestorationPolicyValidityDays: Int? = null,
    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int,
    @Column(name = "request_attempt_count", nullable = false)
    var requestAttemptCount: Int = 0,
    @Column(name = "lookup_attempt_count", nullable = false)
    var lookupAttemptCount: Int = 0,
    @Enumerated(EnumType.STRING)
    @Column(name = "next_action", nullable = false)
    var nextAction: RefundClaimMode = RefundClaimMode.REQUEST,
    @Column(name = "operator_reconciliation_pending", nullable = false)
    var operatorReconciliationPending: Boolean = false,
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
    @Column(name = "response_status")
    var responseStatus: Int? = null,
    @Column(name = "response_body", columnDefinition = "text")
    var responseBody: String? = null,
    @Column(name = "response_recorded_at")
    var responseRecordedAt: Instant? = null,
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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select payment from PaymentEntity payment where payment.orderId = :orderId")
    fun findLockedByOrderId(
        @Param("orderId") orderId: UUID,
    ): PaymentEntity?

    @Query(
        "select min(payment.updatedAt) from PaymentEntity payment " +
            "where payment.approvalState = " +
            "io.github.kdh949.beanflow.payment.internal.domain.PaymentApprovalState.UNKNOWN",
    )
    fun findOldestUnknownUpdatedAt(): Instant?
}

internal interface PaymentMethodJpaRepository : JpaRepository<PaymentMethodEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select method from PaymentMethodEntity method where method.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): PaymentMethodEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select method from PaymentMethodEntity method " +
            "where method.customerId = :customerId order by method.id",
    )
    fun findAllLockedByCustomerId(
        @Param("customerId") customerId: UUID,
    ): List<PaymentMethodEntity>

    fun findAllByProviderAndTokenReference(
        provider: String,
        tokenReference: String,
    ): List<PaymentMethodEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select method from PaymentMethodEntity method " +
            "where method.provider = :provider and method.tokenReference = :tokenReference order by method.id",
    )
    fun findAllLockedByProviderAndTokenReference(
        @Param("provider") provider: String,
        @Param("tokenReference") tokenReference: String,
    ): List<PaymentMethodEntity>
}

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

    fun findByProviderIdempotencyKey(providerIdempotencyKey: String): RefundEntity?

    fun findByPaymentIdAndReason(
        paymentId: UUID,
        reason: String,
    ): RefundEntity?

    fun findByActorIdAndIdempotencyKey(
        actorId: UUID,
        idempotencyKey: String,
    ): RefundEntity?

    @Query(
        "select refund from RefundEntity refund where refund.paymentId = :paymentId " +
            "and refund.state in (" +
            "io.github.kdh949.beanflow.payment.internal.domain.RefundState.REQUESTED, " +
            "io.github.kdh949.beanflow.payment.internal.domain.RefundState.PROCESSING, " +
            "io.github.kdh949.beanflow.payment.internal.domain.RefundState.RETRY_SCHEDULED, " +
            "io.github.kdh949.beanflow.payment.internal.domain.RefundState.UNKNOWN, " +
            "io.github.kdh949.beanflow.payment.internal.domain.RefundState.RECONCILING, " +
            "io.github.kdh949.beanflow.payment.internal.domain.RefundState.MANUAL_REVIEW) " +
            "order by refund.createdAt, refund.id",
    )
    fun findUnresolvedByPaymentId(
        @Param("paymentId") paymentId: UUID,
    ): List<RefundEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select refund from RefundEntity refund where refund.paymentId = :paymentId order by refund.id")
    fun findAllLockedByPaymentId(
        @Param("paymentId") paymentId: UUID,
    ): List<RefundEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select refund from RefundEntity refund where refund.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): RefundEntity?

    @Query(
        "select refund.id from RefundEntity refund where refund.reason <> :excludedReason and (" +
            "(refund.state in (" +
            "io.github.kdh949.beanflow.payment.internal.domain.RefundState.REQUESTED, " +
            "io.github.kdh949.beanflow.payment.internal.domain.RefundState.RETRY_SCHEDULED, " +
            "io.github.kdh949.beanflow.payment.internal.domain.RefundState.UNKNOWN" +
            ") and refund.nextAttemptAt <= :now) or " +
            "(refund.state in (" +
            "io.github.kdh949.beanflow.payment.internal.domain.RefundState.PROCESSING, " +
            "io.github.kdh949.beanflow.payment.internal.domain.RefundState.RECONCILING" +
            ") and refund.claimUntil <= :now)) " +
            "order by refund.nextAttemptAt, refund.id",
    )
    fun findDueIdsExcludingReason(
        @Param("now") now: Instant,
        @Param("excludedReason") excludedReason: String,
        pageable: Pageable,
    ): List<UUID>

    @Query(
        "select refund.id from RefundEntity refund where refund.reason = :reason and (" +
            "(refund.state in (" +
            "io.github.kdh949.beanflow.payment.internal.domain.RefundState.REQUESTED, " +
            "io.github.kdh949.beanflow.payment.internal.domain.RefundState.RETRY_SCHEDULED, " +
            "io.github.kdh949.beanflow.payment.internal.domain.RefundState.UNKNOWN" +
            ") and refund.nextAttemptAt <= :now) or " +
            "(refund.state in (" +
            "io.github.kdh949.beanflow.payment.internal.domain.RefundState.PROCESSING, " +
            "io.github.kdh949.beanflow.payment.internal.domain.RefundState.RECONCILING" +
            ") and refund.claimUntil <= :now)) " +
            "order by refund.nextAttemptAt, refund.id",
    )
    fun findDueIdsByReason(
        @Param("now") now: Instant,
        @Param("reason") reason: String,
        pageable: Pageable,
    ): List<UUID>
}

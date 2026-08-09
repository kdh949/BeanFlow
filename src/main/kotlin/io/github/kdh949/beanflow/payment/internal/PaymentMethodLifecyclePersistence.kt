package io.github.kdh949.beanflow.payment.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
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
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

internal enum class PaymentMethodRegistrationStatus {
    READY,
    PROCESSING,
    COMPLETED,
    REJECTED,
    REGISTRATION_UNKNOWN,
    MISCONFIGURED_RETRYABLE,
    MANUAL_REVIEW,
}

@Entity
@Table(name = "payment_method_registration")
internal class PaymentMethodRegistrationEntity(
    @Id
    val id: UUID,
    @Column(name = "actor_id", nullable = false)
    val actorId: UUID,
    @Column(nullable = false)
    val operation: String,
    @Column(name = "idempotency_key", nullable = false)
    val idempotencyKey: String,
    @Column(name = "customer_id", nullable = false)
    val customerId: UUID,
    @Column(name = "intended_payment_method_id", nullable = false)
    val intendedPaymentMethodId: UUID,
    @Column(nullable = false)
    val provider: String,
    @Column(name = "authorization_key_hash", nullable = false, length = 64)
    val authorizationKeyHash: String,
    @Column(name = "payload_hash", nullable = false, length = 64)
    val payloadHash: String,
    @Column(name = "display_alias", nullable = false)
    val displayAlias: String,
    @Column(name = "provider_customer_reference", nullable = false)
    val providerCustomerReference: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PaymentMethodRegistrationStatus,
    @Column(name = "claim_token")
    var claimToken: UUID? = null,
    @Column(name = "claim_started_at")
    var claimStartedAt: Instant? = null,
    @Column(name = "first_response_status")
    var firstResponseStatus: Int? = null,
    @Column(name = "first_response_body", columnDefinition = "text")
    var firstResponseBody: String? = null,
    @Column(name = "started_at", nullable = false)
    val startedAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Column(name = "terminal_at")
    var terminalAt: Instant? = null,
    @Column(name = "retention_expires_at")
    var retentionExpiresAt: Instant? = null,
    @Column(name = "manual_review_reason")
    var manualReviewReason: String? = null,
    @Version
    var version: Long = 0,
)

internal interface PaymentMethodRegistrationJpaRepository : JpaRepository<PaymentMethodRegistrationEntity, UUID> {
    fun findByActorIdAndOperationAndIdempotencyKey(
        actorId: UUID,
        operation: String,
        idempotencyKey: String,
    ): PaymentMethodRegistrationEntity?

    fun findByCustomerIdAndProviderAndAuthorizationKeyHash(
        customerId: UUID,
        provider: String,
        authorizationKeyHash: String,
    ): PaymentMethodRegistrationEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select registration from PaymentMethodRegistrationEntity registration where registration.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): PaymentMethodRegistrationEntity?

    @Query(
        "select registration.id from PaymentMethodRegistrationEntity registration " +
            "where registration.status = " +
            "io.github.kdh949.beanflow.payment.internal.PaymentMethodRegistrationStatus.PROCESSING " +
            "order by registration.claimStartedAt, registration.id",
    )
    fun findInterruptedClaimIds(pageable: Pageable): List<UUID>
}

@Entity
@Table(name = "payment_method_default_command")
internal class PaymentMethodDefaultCommandEntity(
    @Id
    val id: UUID,
    @Column(name = "actor_id", nullable = false)
    val actorId: UUID,
    @Column(nullable = false)
    val operation: String,
    @Column(name = "idempotency_key", nullable = false)
    val idempotencyKey: String,
    @Column(name = "customer_id", nullable = false)
    val customerId: UUID,
    @Column(name = "payment_method_id", nullable = false)
    val paymentMethodId: UUID,
    @Column(name = "payload_hash", nullable = false, length = 64)
    val payloadHash: String,
    @Column(name = "first_response_status", nullable = false)
    val firstResponseStatus: Int,
    @Column(name = "first_response_body", nullable = false, columnDefinition = "text")
    val firstResponseBody: String,
    @Column(name = "started_at", nullable = false)
    val startedAt: Instant,
    @Column(name = "terminal_at", nullable = false)
    val terminalAt: Instant,
    @Column(name = "retention_expires_at", nullable = false)
    val retentionExpiresAt: Instant,
    @Version
    var version: Long = 0,
)

internal interface PaymentMethodDefaultCommandJpaRepository : JpaRepository<PaymentMethodDefaultCommandEntity, UUID> {
    fun findByActorIdAndOperationAndIdempotencyKey(
        actorId: UUID,
        operation: String,
        idempotencyKey: String,
    ): PaymentMethodDefaultCommandEntity?
}

internal enum class PaymentMethodDeactivationStatus {
    READY,
    PROCESSING,
    DEACTIVATION_UNKNOWN,
    RECONCILING,
    MANUAL_REVIEW,
    COMPLETED,
}

@Entity
@Table(name = "payment_method_deactivation")
internal class PaymentMethodDeactivationEntity(
    @Id
    val id: UUID,
    @Column(name = "actor_id", nullable = false)
    val actorId: UUID,
    @Column(nullable = false)
    val operation: String,
    @Column(name = "idempotency_key", nullable = false)
    val idempotencyKey: String,
    @Column(name = "customer_id", nullable = false)
    val customerId: UUID,
    @Column(name = "payment_method_id", nullable = false)
    val paymentMethodId: UUID,
    @Column(name = "payload_hash", nullable = false, length = 64)
    val payloadHash: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PaymentMethodDeactivationStatus,
    @Column(name = "claim_token")
    var claimToken: UUID? = null,
    @Column(name = "claim_started_at")
    var claimStartedAt: Instant? = null,
    @Column(name = "unknown_at")
    var unknownAt: Instant? = null,
    @Column(name = "manual_review_at")
    var manualReviewAt: Instant? = null,
    @Column(name = "first_response_status")
    var firstResponseStatus: Int? = null,
    @Column(name = "first_response_body", columnDefinition = "text")
    var firstResponseBody: String? = null,
    @Column(name = "started_at", nullable = false)
    val startedAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Column(name = "terminal_at")
    var terminalAt: Instant? = null,
    @Column(name = "retention_expires_at")
    var retentionExpiresAt: Instant? = null,
    @Column(name = "manual_review_reason")
    var manualReviewReason: String? = null,
    @Version
    var version: Long = 0,
)

internal interface PaymentMethodDeactivationJpaRepository : JpaRepository<PaymentMethodDeactivationEntity, UUID> {
    fun findByActorIdAndOperationAndIdempotencyKey(
        actorId: UUID,
        operation: String,
        idempotencyKey: String,
    ): PaymentMethodDeactivationEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select deactivation from PaymentMethodDeactivationEntity deactivation where deactivation.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): PaymentMethodDeactivationEntity?

    @Query(
        "select deactivation.id from PaymentMethodDeactivationEntity deactivation " +
            "where deactivation.status in (" +
            "io.github.kdh949.beanflow.payment.internal.PaymentMethodDeactivationStatus.DEACTIVATION_UNKNOWN, " +
            "io.github.kdh949.beanflow.payment.internal.PaymentMethodDeactivationStatus.RECONCILING) " +
            "and deactivation.manualReviewAt <= :now order by deactivation.manualReviewAt, deactivation.id",
    )
    fun findDueManualReviewIds(
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<UUID>

    @Query(
        "select deactivation.id from PaymentMethodDeactivationEntity deactivation " +
            "where deactivation.status = " +
            "io.github.kdh949.beanflow.payment.internal.PaymentMethodDeactivationStatus.PROCESSING " +
            "order by deactivation.claimStartedAt, deactivation.id",
    )
    fun findInterruptedClaimIds(pageable: Pageable): List<UUID>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select deactivation from PaymentMethodDeactivationEntity deactivation " +
            "where deactivation.paymentMethodId = :paymentMethodId and deactivation.status <> " +
            "io.github.kdh949.beanflow.payment.internal.PaymentMethodDeactivationStatus.COMPLETED",
    )
    fun findActiveLockedByPaymentMethodId(
        @Param("paymentMethodId") paymentMethodId: UUID,
    ): PaymentMethodDeactivationEntity?
}

internal enum class PaymentProviderNotificationStatus {
    ACCEPTED,
    PROCESSED,
    MANUAL_REVIEW,
}

@Entity
@Table(name = "payment_provider_notification_inbox")
internal class PaymentProviderNotificationInboxEntity(
    @Id
    val id: UUID,
    @Column(nullable = false)
    val provider: String,
    @Column(name = "notification_id", nullable = false)
    val notificationId: String,
    @Column(name = "notification_type", nullable = false)
    val notificationType: String,
    @Column(name = "token_fingerprint", nullable = false, length = 64)
    val tokenFingerprint: String,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,
    @Column(name = "received_at", nullable = false)
    val receivedAt: Instant,
    @Column(name = "processed_at")
    var processedAt: Instant? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PaymentProviderNotificationStatus,
    @Column(name = "closed_reason")
    var closedReason: String? = null,
    @Column(name = "retention_expires_at")
    var retentionExpiresAt: Instant? = null,
    @Version
    var version: Long = 0,
)

internal interface PaymentProviderNotificationInboxJpaRepository : JpaRepository<PaymentProviderNotificationInboxEntity, UUID> {
    fun findByProviderAndNotificationId(
        provider: String,
        notificationId: String,
    ): PaymentProviderNotificationInboxEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select inbox from PaymentProviderNotificationInboxEntity inbox where inbox.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): PaymentProviderNotificationInboxEntity?
}

@Entity
@Table(name = "payment_provider_request_snapshot")
internal class PaymentProviderRequestSnapshotEntity(
    @Id
    @Column(name = "payment_id")
    val paymentId: UUID,
    @Column(name = "payment_method_id", nullable = false)
    val paymentMethodId: UUID,
    @Column(nullable = false)
    val provider: String,
    @Column(name = "token_reference", nullable = false)
    val tokenReference: String,
    @Column(name = "provider_customer_reference")
    val providerCustomerReference: String?,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)

@Repository
internal class PaymentProviderRequestSnapshotStore(
    private val entityManager: EntityManager,
) {
    fun create(snapshot: PaymentProviderRequestSnapshotEntity) {
        entityManager.persist(snapshot)
    }

    fun findByPaymentId(paymentId: UUID): PaymentProviderRequestSnapshotEntity? =
        entityManager.find(PaymentProviderRequestSnapshotEntity::class.java, paymentId)
}

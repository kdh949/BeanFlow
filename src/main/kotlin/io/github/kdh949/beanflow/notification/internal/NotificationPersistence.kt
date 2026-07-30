package io.github.kdh949.beanflow.notification.internal

import io.github.kdh949.beanflow.notification.internal.domain.NotificationDeliveryState
import io.github.kdh949.beanflow.notification.internal.domain.NotificationLogicalChannel
import io.github.kdh949.beanflow.notification.internal.domain.NotificationRecipientType
import io.github.kdh949.beanflow.notification.internal.domain.NotificationTemplate
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
@Table(name = "notification_delivery")
internal class NotificationDeliveryEntity(
    @Id
    val id: UUID,
    @Column(name = "event_id", nullable = false)
    val eventId: UUID,
    @Column(name = "event_type", nullable = false)
    val eventType: String,
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_type", nullable = false)
    val recipientType: NotificationRecipientType,
    @Column(name = "recipient_id", nullable = false)
    val recipientId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "logical_channel", nullable = false)
    val logicalChannel: NotificationLogicalChannel,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val template: NotificationTemplate,
    @Column(name = "payload_json", nullable = false, columnDefinition = "text")
    val payloadJson: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var state: NotificationDeliveryState,
    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int,
    @Column(name = "next_attempt_at")
    var nextAttemptAt: Instant?,
    @Column(name = "provider_idempotency_key", nullable = false)
    val providerIdempotencyKey: String,
    @Column(name = "provider_delivery_reference")
    var providerDeliveryReference: String? = null,
    @Column(name = "claim_token")
    var claimToken: UUID? = null,
    @Column(name = "claim_until")
    var claimUntil: Instant? = null,
    @Column(name = "last_failure_code")
    var lastFailureCode: String? = null,
    @Column(name = "correlation_id", nullable = false)
    val correlationId: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Version
    var version: Long = 0,
)

internal interface NotificationDeliveryJpaRepository : JpaRepository<NotificationDeliveryEntity, UUID> {
    fun findByEventIdAndRecipientIdAndLogicalChannel(
        eventId: UUID,
        recipientId: UUID,
        logicalChannel: NotificationLogicalChannel,
    ): NotificationDeliveryEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select delivery from NotificationDeliveryEntity delivery where delivery.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): NotificationDeliveryEntity?

    @Query(
        "select delivery.id from NotificationDeliveryEntity delivery where (" +
            "(delivery.state in (" +
            "io.github.kdh949.beanflow.notification.internal.domain.NotificationDeliveryState.PENDING, " +
            "io.github.kdh949.beanflow.notification.internal.domain.NotificationDeliveryState.RETRY_SCHEDULED" +
            ") and delivery.nextAttemptAt <= :now) or " +
            "(delivery.state = " +
            "io.github.kdh949.beanflow.notification.internal.domain.NotificationDeliveryState.PROCESSING " +
            "and delivery.claimUntil <= :now)) order by delivery.nextAttemptAt, delivery.id",
    )
    fun findDueIds(
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<UUID>
}

package io.github.kdh949.beanflow.notification.internal

import io.github.kdh949.beanflow.eventing.api.OrderReadyV1
import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import io.github.kdh949.beanflow.eventing.api.StoreAcceptanceWarningRequestedV1
import io.github.kdh949.beanflow.notification.internal.domain.NotificationDelivery
import io.github.kdh949.beanflow.notification.internal.domain.NotificationDeliveryState
import io.github.kdh949.beanflow.notification.internal.domain.NotificationLogicalChannel
import io.github.kdh949.beanflow.notification.internal.domain.NotificationRecipientType
import io.github.kdh949.beanflow.notification.internal.domain.NotificationTemplate
import io.github.kdh949.beanflow.operations.api.NotificationReprocessingCaseOperations
import io.github.kdh949.beanflow.operations.api.OpenReprocessingCaseCommand
import io.github.kdh949.beanflow.operations.api.OrderCompensationOperations
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepState
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepType
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal data class ClaimedNotificationDelivery(
    val deliveryId: UUID,
    val orderId: UUID,
    val recipientType: NotificationRecipientType,
    val recipientId: UUID,
    val logicalChannel: NotificationLogicalChannel,
    val template: NotificationTemplate,
    val payloadJson: String,
    val providerIdempotencyKey: String,
    val attemptCount: Int,
    val claimToken: UUID,
    val dueAt: Instant,
)

private data class NewNotificationDelivery(
    val eventId: UUID,
    val eventType: String,
    val orderId: UUID,
    val recipientType: NotificationRecipientType,
    val recipientId: UUID,
    val logicalChannel: NotificationLogicalChannel,
    val template: NotificationTemplate,
    val payload: Map<String, Any>,
    val correlationId: String,
    val occurredAt: Instant,
)

@Service
internal class NotificationDeliveryService(
    private val deliveryRepository: NotificationDeliveryJpaRepository,
    private val provider: NotificationProvider,
    private val compensationOperations: OrderCompensationOperations,
    private val reprocessingCaseOperations: NotificationReprocessingCaseOperations,
    private val identifierSource: IdentifierSource,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    @Value("\${beanflow.notification.claim-lease:PT1M}")
    private val claimLease: Duration,
) {
    @Transactional
    fun requestWarning(event: StoreAcceptanceWarningRequestedV1) {
        request(
            NewNotificationDelivery(
                eventId = event.envelope.eventId,
                eventType = event.envelope.eventType,
                orderId = event.orderId,
                recipientType = NotificationRecipientType.STORE,
                recipientId = event.storeId,
                logicalChannel = NotificationLogicalChannel.STORE_OPERATIONS,
                template = NotificationTemplate.STORE_ACCEPTANCE_WARNING,
                payload =
                    mapOf(
                        "orderId" to event.orderId,
                        "storeId" to event.storeId,
                        "acceptanceDeadlineAt" to event.acceptanceDeadlineAt,
                    ),
                correlationId = event.envelope.correlationId,
                occurredAt = event.envelope.occurredAt,
            ),
        )
    }

    @Transactional
    fun requestRejection(event: OrderRejectedV1) {
        val delivery =
            request(
                NewNotificationDelivery(
                    eventId = event.envelope.eventId,
                    eventType = event.envelope.eventType,
                    orderId = event.orderId,
                    recipientType = NotificationRecipientType.CUSTOMER,
                    recipientId = event.customerId,
                    logicalChannel = NotificationLogicalChannel.CUSTOMER_APP,
                    template = NotificationTemplate.ORDER_REJECTED,
                    payload =
                        mapOf(
                            "orderId" to event.orderId,
                            "storeId" to event.storeId,
                            "reason" to event.reason,
                            "rejectedAt" to event.rejectedAt,
                        ),
                    correlationId = event.envelope.correlationId,
                    occurredAt = event.envelope.occurredAt,
                ),
            )
        if (delivery.state == NotificationDeliveryState.SUCCEEDED) {
            recordRejectionStep(
                event.orderId,
                OrderCompensationStepState.SUCCEEDED,
                null,
                event.rejectedAt,
            )
        }
    }

    @Transactional
    fun requestReady(event: OrderReadyV1) {
        request(
            NewNotificationDelivery(
                eventId = event.envelope.eventId,
                eventType = event.envelope.eventType,
                orderId = event.orderId,
                recipientType = NotificationRecipientType.CUSTOMER,
                recipientId = event.customerId,
                logicalChannel = NotificationLogicalChannel.CUSTOMER_APP,
                template = NotificationTemplate.ORDER_READY,
                payload =
                    mapOf(
                        "orderId" to event.orderId,
                        "storeId" to event.storeId,
                        "readyAt" to event.readyAt,
                    ),
                correlationId = event.envelope.correlationId,
                occurredAt = event.envelope.occurredAt,
            ),
        )
    }

    @Transactional
    fun claimDue(
        now: Instant,
        limit: Int,
    ): List<ClaimedNotificationDelivery> {
        require(limit in 1..100)
        return deliveryRepository.findDueIds(now, PageRequest.of(0, limit)).mapNotNull { deliveryId ->
            val entity = deliveryRepository.findLockedById(deliveryId) ?: return@mapNotNull null
            val delivery = entity.toDomain()
            val dueAt = entity.nextAttemptAt ?: entity.claimUntil ?: now
            val token = identifierSource.next()
            try {
                delivery.claim(token, now, claimLease, MAX_ATTEMPTS)
            } catch (_: IllegalStateException) {
                if (entity.attemptCount >= MAX_ATTEMPTS) {
                    delivery.markManualReviewAfterExpiredClaim(now, MAX_ATTEMPTS)
                    entity.apply(delivery)
                    enterManualReview(entity, "CLAIM_LEASE_EXPIRED", now)
                }
                return@mapNotNull null
            }
            entity.apply(delivery)
            ClaimedNotificationDelivery(
                deliveryId = entity.id,
                orderId = entity.orderId,
                recipientType = entity.recipientType,
                recipientId = entity.recipientId,
                logicalChannel = entity.logicalChannel,
                template = entity.template,
                payloadJson = entity.payloadJson,
                providerIdempotencyKey = entity.providerIdempotencyKey,
                attemptCount = entity.attemptCount,
                claimToken = token,
                dueAt = dueAt,
            )
        }
    }

    fun callProvider(claim: ClaimedNotificationDelivery): NotificationProviderResult =
        provider.send(
            NotificationProviderRequest(
                deliveryId = claim.deliveryId,
                recipientType = claim.recipientType,
                recipientId = claim.recipientId,
                logicalChannel = claim.logicalChannel,
                template = claim.template,
                payloadJson = claim.payloadJson,
                providerIdempotencyKey = claim.providerIdempotencyKey,
            ),
        )

    @Transactional
    fun recordResult(
        claim: ClaimedNotificationDelivery,
        result: NotificationProviderResult,
        now: Instant,
    ) {
        val entity =
            deliveryRepository.findLockedById(claim.deliveryId)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Claimed notification delivery is missing")
        val delivery = entity.toDomain()
        try {
            delivery.requireClaim(claim.claimToken)
        } catch (failure: IllegalStateException) {
            throw DomainFailure(
                FailureCode.ORDER_STATE_CONFLICT,
                failure.message ?: "Notification delivery claim was lost",
            )
        }
        when (result) {
            is NotificationProviderResult.Acknowledged -> {
                delivery.succeed(result.providerDeliveryReference, now)
                entity.apply(delivery)
                if (entity.template == NotificationTemplate.ORDER_REJECTED) {
                    recordRejectionStep(
                        entity.orderId,
                        OrderCompensationStepState.SUCCEEDED,
                        null,
                        now,
                    )
                }
            }

            is NotificationProviderResult.Failed -> {
                recordFailure(entity, delivery, result.code, false, now)
            }

            is NotificationProviderResult.Unknown -> {
                recordFailure(entity, delivery, result.code, true, now)
            }
        }
        meterRegistry
            .counter(
                "beanflow.notification.delivery.count",
                "template",
                entity.template.name.lowercase(),
                "outcome",
                result.outcomeTag(),
            ).increment()
    }

    private fun request(command: NewNotificationDelivery): NotificationDeliveryEntity {
        deliveryRepository
            .findByEventIdAndRecipientIdAndLogicalChannel(
                command.eventId,
                command.recipientId,
                command.logicalChannel,
            )?.let { return it }
        val id = identifierSource.next()
        return deliveryRepository.save(
            NotificationDelivery
                .pending(
                    id = id,
                    eventId = command.eventId,
                    eventType = command.eventType,
                    orderId = command.orderId,
                    recipientType = command.recipientType,
                    recipientId = command.recipientId,
                    logicalChannel = command.logicalChannel,
                    template = command.template,
                    payloadJson = objectMapper.writeValueAsString(command.payload),
                    providerIdempotencyKey =
                        "notification:${command.eventId}:${command.recipientId}:${command.logicalChannel.name}",
                    correlationId = command.correlationId,
                    now = command.occurredAt,
                ).toEntity(),
        )
    }

    private fun recordFailure(
        entity: NotificationDeliveryEntity,
        delivery: NotificationDelivery,
        code: String,
        unknown: Boolean,
        now: Instant,
    ) {
        delivery.recordFailure(code, now, RETRY_DELAYS, MAX_ATTEMPTS)
        entity.apply(delivery)
        if (delivery.state == NotificationDeliveryState.MANUAL_REVIEW) {
            enterManualReview(entity, normalized(code), now)
        } else if (entity.template == NotificationTemplate.ORDER_REJECTED) {
            recordRejectionStep(
                entity.orderId,
                if (unknown) {
                    OrderCompensationStepState.UNKNOWN
                } else {
                    OrderCompensationStepState.RETRY_SCHEDULED
                },
                normalized(code),
                now,
            )
        }
    }

    private fun enterManualReview(
        entity: NotificationDeliveryEntity,
        code: String,
        now: Instant,
    ) {
        reprocessingCaseOperations.openNotificationCase(
            OpenReprocessingCaseCommand(
                ownerReference = "notification:${entity.id}",
                reason = "NOTIFICATION_DELIVERY_ATTEMPTS_EXHAUSTED:$code",
                correlationId = entity.correlationId,
                now = now,
            ),
        )
        if (entity.template == NotificationTemplate.ORDER_REJECTED) {
            recordRejectionStep(
                entity.orderId,
                OrderCompensationStepState.MANUAL_REVIEW,
                code,
                now,
            )
        }
        meterRegistry.counter("beanflow.notification.delivery.manual_review.count").increment()
    }

    private fun recordRejectionStep(
        orderId: UUID,
        state: OrderCompensationStepState,
        code: String?,
        now: Instant,
    ) {
        compensationOperations.recordStep(
            orderId,
            OrderCompensationStepType.CUSTOMER_NOTIFICATION,
            state,
            code,
            now,
        )
    }

    private fun NotificationDeliveryEntity.toDomain(): NotificationDelivery =
        NotificationDelivery.restore(
            id = id,
            eventId = eventId,
            eventType = eventType,
            orderId = orderId,
            recipientType = recipientType,
            recipientId = recipientId,
            logicalChannel = logicalChannel,
            template = template,
            payloadJson = payloadJson,
            providerIdempotencyKey = providerIdempotencyKey,
            correlationId = correlationId,
            createdAt = createdAt,
            state = state,
            attemptCount = attemptCount,
            nextAttemptAt = nextAttemptAt,
            providerDeliveryReference = providerDeliveryReference,
            claimToken = claimToken,
            claimUntil = claimUntil,
            lastFailureCode = lastFailureCode,
            updatedAt = updatedAt,
        )

    private fun NotificationDelivery.toEntity(): NotificationDeliveryEntity =
        NotificationDeliveryEntity(
            id = id,
            eventId = eventId,
            eventType = eventType,
            orderId = orderId,
            recipientType = recipientType,
            recipientId = recipientId,
            logicalChannel = logicalChannel,
            template = template,
            payloadJson = payloadJson,
            state = state,
            attemptCount = attemptCount,
            nextAttemptAt = nextAttemptAt,
            providerIdempotencyKey = providerIdempotencyKey,
            providerDeliveryReference = providerDeliveryReference,
            claimToken = claimToken,
            claimUntil = claimUntil,
            lastFailureCode = lastFailureCode,
            correlationId = correlationId,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private fun NotificationDeliveryEntity.apply(delivery: NotificationDelivery) {
        state = delivery.state
        attemptCount = delivery.attemptCount
        nextAttemptAt = delivery.nextAttemptAt
        providerDeliveryReference = delivery.providerDeliveryReference
        claimToken = delivery.claimToken
        claimUntil = delivery.claimUntil
        lastFailureCode = delivery.lastFailureCode
        updatedAt = delivery.updatedAt
    }

    private fun NotificationProviderResult.outcomeTag(): String =
        when (this) {
            is NotificationProviderResult.Acknowledged -> "succeeded"
            is NotificationProviderResult.Failed -> "failed"
            is NotificationProviderResult.Unknown -> "unknown"
        }

    private fun normalized(code: String): String =
        code
            .trim()
            .uppercase()
            .replace(Regex("[^A-Z0-9_]+"), "_")
            .take(80)
            .ifBlank { "UNKNOWN" }

    private fun fail(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)

    private companion object {
        const val MAX_ATTEMPTS = 4
        val RETRY_DELAYS: List<Duration> =
            listOf(
                Duration.ofMinutes(1),
                Duration.ofMinutes(5),
                Duration.ofMinutes(30),
            )
    }
}

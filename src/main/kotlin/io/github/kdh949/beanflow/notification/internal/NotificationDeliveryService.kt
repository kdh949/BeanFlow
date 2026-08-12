package io.github.kdh949.beanflow.notification.internal

import io.github.kdh949.beanflow.eventing.api.CustomerCancellationRefundDelayedV1
import io.github.kdh949.beanflow.eventing.api.CustomerCancellationRefundSucceededV1
import io.github.kdh949.beanflow.eventing.api.OrderReadyV1
import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import io.github.kdh949.beanflow.eventing.api.StoreAcceptanceWarningRequestedV1
import io.github.kdh949.beanflow.notification.api.AcceptedCustomerCancellationNotification
import io.github.kdh949.beanflow.notification.api.AcceptedPostAcceptanceResolutionNotification
import io.github.kdh949.beanflow.notification.api.AcceptedSupportOrderChangeNotification
import io.github.kdh949.beanflow.notification.api.CustomerCancellationNotificationOperations
import io.github.kdh949.beanflow.notification.api.PostAcceptanceResolutionNotificationOperations
import io.github.kdh949.beanflow.notification.api.PostAcceptanceResolutionNotificationView
import io.github.kdh949.beanflow.notification.api.AcceptedGoodwillCompensationNotification
import io.github.kdh949.beanflow.notification.api.GoodwillCompensationNotificationOperations
import io.github.kdh949.beanflow.notification.api.GoodwillCompensationNotificationView
import io.github.kdh949.beanflow.notification.api.RequestGoodwillCompensationNotificationCommand
import io.github.kdh949.beanflow.notification.api.RequestCustomerCancellationAcceptedNotificationCommand
import io.github.kdh949.beanflow.notification.api.RequestPostAcceptanceResolutionNotificationCommand
import io.github.kdh949.beanflow.notification.api.RequestSupportPickupRescheduledNotificationCommand
import io.github.kdh949.beanflow.notification.api.SupportOrderChangeNotificationOperations
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
import org.springframework.transaction.annotation.Propagation
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
    val logicalSource: String,
    val providerIdempotencyKey: String,
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
) : CustomerCancellationNotificationOperations,
    SupportOrderChangeNotificationOperations,
    PostAcceptanceResolutionNotificationOperations,
    GoodwillCompensationNotificationOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun requestAccepted(
        command: RequestCustomerCancellationAcceptedNotificationCommand,
    ): AcceptedCustomerCancellationNotification {
        val delivery =
            request(
                NewNotificationDelivery(
                    eventId = command.eventId,
                    eventType = "CustomerOrderCancellationAcceptedV1",
                    logicalSource =
                        "order:${command.orderId}:customer-cancellation:" +
                            "${command.orderAggregateVersion}:accepted-notification",
                    providerIdempotencyKey =
                        "notification:customer-cancellation-accepted:${command.orderId}:${command.orderAggregateVersion}",
                    orderId = command.orderId,
                    recipientType = NotificationRecipientType.CUSTOMER,
                    recipientId = command.customerId,
                    logicalChannel = NotificationLogicalChannel.CUSTOMER_APP,
                    template = NotificationTemplate.ORDER_CANCELLATION_ACCEPTED,
                    payload =
                        mapOf(
                            "orderId" to command.orderId,
                            "storeId" to command.storeId,
                            "cancelledAt" to command.cancelledAt,
                        ),
                    correlationId = command.correlationId,
                    occurredAt = command.cancelledAt,
                ),
            )
        return AcceptedCustomerCancellationNotification(delivery.id, delivery.state.name)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun requestPickupRescheduled(
        command: RequestSupportPickupRescheduledNotificationCommand,
    ): AcceptedSupportOrderChangeNotification {
        val delivery =
            request(
                NewNotificationDelivery(
                    eventId = command.executionId,
                    eventType = "SupportPickupRescheduledV1",
                    logicalSource = "order:${command.orderId}:support-pickup-reschedule:${command.orderAggregateVersion}",
                    providerIdempotencyKey =
                        "notification:support-pickup-reschedule:${command.orderId}:${command.orderAggregateVersion}",
                    orderId = command.orderId,
                    recipientType = NotificationRecipientType.CUSTOMER,
                    recipientId = command.customerId,
                    logicalChannel = NotificationLogicalChannel.CUSTOMER_APP,
                    template = NotificationTemplate.SUPPORT_PICKUP_RESCHEDULED,
                    payload =
                        mapOf(
                            "orderId" to command.orderId,
                            "storeId" to command.storeId,
                            "previousPickupSlotId" to command.previousPickupSlotId,
                            "currentPickupSlotId" to command.currentPickupSlotId,
                            "rescheduledAt" to command.occurredAt,
                        ),
                    correlationId = command.correlationId,
                    occurredAt = command.occurredAt,
                ),
            )
        return AcceptedSupportOrderChangeNotification(delivery.id, delivery.state.name)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun request(command: RequestPostAcceptanceResolutionNotificationCommand): AcceptedPostAcceptanceResolutionNotification {
        if (command.outcome !in RESOLUTION_OUTCOMES || command.resolutionState !in RESOLUTION_TERMINAL_STATES ||
            command.correlationId.isBlank()
        ) {
            fail(FailureCode.INVALID_REQUEST, "Resolution notification command is invalid")
        }
        val delivery =
            request(
                NewNotificationDelivery(
                    eventId = command.resolutionId,
                    eventType = "SupportPostAcceptanceResolutionV1",
                    logicalSource = "support-resolution:${command.resolutionId}:customer-notification",
                    providerIdempotencyKey = "notification:support-resolution:${command.resolutionId}",
                    orderId = command.orderId,
                    recipientType = NotificationRecipientType.CUSTOMER,
                    recipientId = command.customerId,
                    logicalChannel = NotificationLogicalChannel.CUSTOMER_APP,
                    template = NotificationTemplate.SUPPORT_POST_ACCEPTANCE_RESOLUTION,
                    payload =
                        mapOf(
                            "orderId" to command.orderId,
                            "storeId" to command.storeId,
                            "outcome" to command.outcome,
                            "resolutionState" to command.resolutionState,
                            "occurredAt" to command.occurredAt,
                            "locale" to "ko-KR",
                        ),
                    correlationId = command.correlationId,
                    occurredAt = command.occurredAt,
                ),
            )
        return AcceptedPostAcceptanceResolutionNotification(delivery.id, delivery.state.name)
    }

    @Transactional(readOnly = true)
    override fun find(deliveryId: UUID): PostAcceptanceResolutionNotificationView? =
        deliveryRepository
            .findById(deliveryId)
            .orElse(null)
            ?.takeIf { it.template == NotificationTemplate.SUPPORT_POST_ACCEPTANCE_RESOLUTION }
            ?.let { PostAcceptanceResolutionNotificationView(it.id, it.state.name, it.updatedAt) }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun requestGoodwill(
        command: RequestGoodwillCompensationNotificationCommand,
    ): AcceptedGoodwillCompensationNotification {
        if (command.benefitType !in setOf("POINT", "COUPON") || command.amountKrw <= 0 || command.correlationId.isBlank()) {
            fail(FailureCode.INVALID_REQUEST, "Goodwill compensation notification command is invalid")
        }
        val delivery =
            request(
                NewNotificationDelivery(
                    eventId = command.compensationRequestId,
                    eventType = "SupportGoodwillCompensationIssuedV1",
                    logicalSource = "support-compensation:${command.compensationRequestId}:customer-notification",
                    providerIdempotencyKey = "notification:support-compensation:${command.compensationRequestId}",
                    orderId = command.relatedOrderId ?: command.compensationRequestId,
                    recipientType = NotificationRecipientType.CUSTOMER,
                    recipientId = command.customerId,
                    logicalChannel = NotificationLogicalChannel.CUSTOMER_APP,
                    template = NotificationTemplate.SUPPORT_GOODWILL_COMPENSATION_ISSUED,
                    payload =
                        buildMap {
                            command.relatedOrderId?.let { put("relatedOrderId", it) }
                            command.storeId?.let { put("storeId", it) }
                            put("benefitType", command.benefitType)
                            put("amountKrw", command.amountKrw)
                            put("issuedAt", command.issuedAt)
                            put("locale", "ko-KR")
                        },
                    correlationId = command.correlationId,
                    occurredAt = command.issuedAt,
                ),
            )
        return AcceptedGoodwillCompensationNotification(delivery.id, delivery.state.name)
    }

    @Transactional(readOnly = true)
    override fun findGoodwill(deliveryId: UUID): GoodwillCompensationNotificationView? =
        deliveryRepository
            .findById(deliveryId)
            .orElse(null)
            ?.takeIf { it.template == NotificationTemplate.SUPPORT_GOODWILL_COMPENSATION_ISSUED }
            ?.let { GoodwillCompensationNotificationView(it.id, it.state.name, it.updatedAt) }

    @Transactional
    fun requestWarning(event: StoreAcceptanceWarningRequestedV1) {
        request(
            NewNotificationDelivery(
                eventId = event.envelope.eventId,
                eventType = event.envelope.eventType,
                logicalSource = genericLogicalSource(event.envelope.eventId, event.storeId, NotificationLogicalChannel.STORE_OPERATIONS),
                providerIdempotencyKey =
                    genericProviderKey(event.envelope.eventId, event.storeId, NotificationLogicalChannel.STORE_OPERATIONS),
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
                    logicalSource = genericLogicalSource(event.envelope.eventId, event.customerId, NotificationLogicalChannel.CUSTOMER_APP),
                    providerIdempotencyKey =
                        genericProviderKey(event.envelope.eventId, event.customerId, NotificationLogicalChannel.CUSTOMER_APP),
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
                logicalSource = genericLogicalSource(event.envelope.eventId, event.customerId, NotificationLogicalChannel.CUSTOMER_APP),
                providerIdempotencyKey =
                    genericProviderKey(event.envelope.eventId, event.customerId, NotificationLogicalChannel.CUSTOMER_APP),
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
    fun requestCustomerCancellationRefundSucceeded(event: CustomerCancellationRefundSucceededV1) {
        requestCustomerCancellationRefund(
            eventId = event.envelope.eventId,
            eventType = event.envelope.eventType,
            orderId = event.orderId,
            customerId = event.customerId,
            orderVersion = event.orderAggregateVersion,
            amountKrw = event.refundAmountKrw,
            outcomeAt = event.outcomeAt,
            correlationId = event.envelope.correlationId,
            outcome = "succeeded",
            template = NotificationTemplate.CUSTOMER_CANCELLATION_REFUND_SUCCEEDED,
        )
    }

    @Transactional
    fun requestCustomerCancellationRefundDelayed(event: CustomerCancellationRefundDelayedV1) {
        requestCustomerCancellationRefund(
            eventId = event.envelope.eventId,
            eventType = event.envelope.eventType,
            orderId = event.orderId,
            customerId = event.customerId,
            orderVersion = event.orderAggregateVersion,
            amountKrw = event.refundAmountKrw,
            outcomeAt = event.outcomeAt,
            correlationId = event.envelope.correlationId,
            outcome = "delayed",
            template = NotificationTemplate.CUSTOMER_CANCELLATION_REFUND_DELAYED,
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
        val payloadJson = objectMapper.writeValueAsString(command.payload)
        deliveryRepository.findByLogicalSource(command.logicalSource)?.let { existing ->
            if (existing.eventType != command.eventType || existing.orderId != command.orderId ||
                existing.recipientType != command.recipientType || existing.recipientId != command.recipientId ||
                existing.logicalChannel != command.logicalChannel || existing.template != command.template ||
                existing.payloadJson != payloadJson || existing.correlationId != command.correlationId ||
                existing.providerIdempotencyKey != command.providerIdempotencyKey
            ) {
                fail(FailureCode.DEPENDENCY_UNAVAILABLE, "NOTIFICATION_SOURCE_CONFLICT")
            }
            return existing
        }
        val id = identifierSource.next()
        return deliveryRepository.save(
            NotificationDelivery
                .pending(
                    id = id,
                    eventId = command.eventId,
                    eventType = command.eventType,
                    logicalSource = command.logicalSource,
                    orderId = command.orderId,
                    recipientType = command.recipientType,
                    recipientId = command.recipientId,
                    logicalChannel = command.logicalChannel,
                    template = command.template,
                    payloadJson = payloadJson,
                    providerIdempotencyKey = command.providerIdempotencyKey,
                    correlationId = command.correlationId,
                    now = command.occurredAt,
                ).toEntity(),
        )
    }

    @Suppress("LongParameterList")
    private fun requestCustomerCancellationRefund(
        eventId: UUID,
        eventType: String,
        orderId: UUID,
        customerId: UUID,
        orderVersion: Long,
        amountKrw: Long,
        outcomeAt: Instant,
        correlationId: String,
        outcome: String,
        template: NotificationTemplate,
    ) {
        request(
            NewNotificationDelivery(
                eventId = eventId,
                eventType = eventType,
                logicalSource = "order:$orderId:customer-cancellation:$orderVersion:refund-$outcome",
                providerIdempotencyKey =
                    "notification:customer-cancellation-refund-$outcome:$orderId:$orderVersion",
                orderId = orderId,
                recipientType = NotificationRecipientType.CUSTOMER,
                recipientId = customerId,
                logicalChannel = NotificationLogicalChannel.CUSTOMER_APP,
                template = template,
                payload =
                    mapOf(
                        "orderId" to orderId,
                        "refundAmountKrw" to amountKrw,
                        "outcomeAt" to outcomeAt,
                        "locale" to "ko-KR",
                    ),
                correlationId = correlationId,
                occurredAt = outcomeAt,
            ),
        )
        meterRegistry
            .counter(
                "beanflow.notification.cancellation_refund.count",
                "template",
                outcome,
                "outcome",
                "requested",
            ).increment()
    }

    private fun genericLogicalSource(
        eventId: UUID,
        recipientId: UUID,
        channel: NotificationLogicalChannel,
    ): String = "event:$eventId:recipient:$recipientId:channel:${channel.name}"

    private fun genericProviderKey(
        eventId: UUID,
        recipientId: UUID,
        channel: NotificationLogicalChannel,
    ): String = "notification:$eventId:$recipientId:${channel.name}"

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
            logicalSource = logicalSource,
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
            logicalSource = logicalSource,
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
        val RESOLUTION_OUTCOMES =
            setOf("FULL_REFUND", "PARTIAL_REFUND", "NO_MONETARY_RESOLUTION", "MANUAL_SETTLEMENT_REVIEW")
        val RESOLUTION_TERMINAL_STATES = setOf("PARTIALLY_RESOLVED", "RESOLVED", "MANUAL_REVIEW")
    }
}

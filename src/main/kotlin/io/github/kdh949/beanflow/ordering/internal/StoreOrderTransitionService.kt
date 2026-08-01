package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.OrderAcceptedV1
import io.github.kdh949.beanflow.eventing.api.OrderCompletedV1
import io.github.kdh949.beanflow.eventing.api.OrderReadyV1
import io.github.kdh949.beanflow.eventing.api.OrderRejectionActorType
import io.github.kdh949.beanflow.identity.api.StoreAccessOperations
import io.github.kdh949.beanflow.identity.api.StoreActor
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.RejectionCompensationCaseView
import io.github.kdh949.beanflow.operations.api.RejectionCompensationOperations
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

internal data class StoreTransitionActor(
    val actorId: UUID,
    val roles: Set<StoreActorRole>,
)

internal data class StoreTransitionHttpResult(
    val status: Int,
    val body: String,
)

@Service
internal class StoreOrderTransitionService(
    private val orderRepository: OrderJpaRepository,
    private val orderLineRepository: OrderLineJpaRepository,
    private val idempotencyRepository: StoreCommandIdempotencyJpaRepository,
    private val storeAccessOperations: StoreAccessOperations,
    private val compensationOperations: RejectionCompensationOperations,
    private val rejectionCoordinator: OrderRejectionCoordinator,
    private val auditRecordOperations: AuditRecordOperations,
    private val eventPublisher: ApplicationEventPublisher,
    private val identifierSource: IdentifierSource,
    private val correlationIdSource: CorrelationIdSource,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun get(
        actor: StoreTransitionActor,
        orderId: UUID,
    ): StoreOrderResult {
        val order = find(orderId)
        storeAccessOperations.requireOrderManagementAccess(actor.actorId, order.storeId, actor.roles)
        return StoreOrderResult(
            order = response(order),
            rejectionRecovery = compensationOperations.findByOrderId(orderId)?.toResponse(),
        )
    }

    @Transactional
    fun transition(
        actor: StoreTransitionActor,
        orderId: UUID,
        idempotencyKey: String,
        request: StoreOrderTransitionRequest,
    ): StoreTransitionHttpResult {
        validate(request)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val order =
            orderRepository.findLockedById(orderId)
                ?: notFound()
        val storeActor =
            storeAccessOperations.requireOrderManagementAccess(
                actor.actorId,
                order.storeId,
                actor.roles,
            )
        val payloadHash = CanonicalStoreOrderTransitionPayload.hash(request.targetState, request.reason)
        idempotencyRepository
            .findByActorIdAndOperationAndIdempotencyKey(
                actor.actorId,
                OPERATION,
                idempotencyKey,
            )?.let { existing ->
                if (existing.payloadHash != payloadHash) {
                    throw DomainFailure(
                        FailureCode.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency-Key was reused with a different store transition payload",
                    )
                }
                return StoreTransitionHttpResult(existing.responseStatus, replay(existing.responseBody))
            }

        val before = order.state.name
        val correlationId = correlationIdSource.currentOrCreate()
        val causationId = "store-order-command:$idempotencyKey"
        val recovery =
            when (request.targetState) {
                StoreOrderTargetState.ACCEPTED -> {
                    order.accept(now)
                    publishAccepted(order, now, correlationId, causationId)
                    null
                }

                StoreOrderTargetState.PREPARING -> {
                    order.startPreparing(now)
                    null
                }

                StoreOrderTargetState.READY -> {
                    order.markReady(now)
                    publishReady(order, now, correlationId, causationId)
                    null
                }

                StoreOrderTargetState.COMPLETED -> {
                    order.complete(now)
                    publishCompleted(order, now, correlationId, causationId)
                    null
                }

                StoreOrderTargetState.REJECTED -> {
                    reject(order, storeActor, requireNotNull(request.reason), now, correlationId, causationId)
                }
            }
        appendAudit(order, storeActor, before, request, now, correlationId, causationId)
        val status = if (request.targetState == StoreOrderTargetState.REJECTED) 202 else 200
        val body =
            objectMapper.writeValueAsString(
                StoreOrderTransitionResult(
                    order = response(order),
                    rejectionRecovery = recovery?.toResponse(),
                    replayed = false,
                ),
            )
        idempotencyRepository.save(
            StoreCommandIdempotencyEntity(
                id = identifierSource.next(),
                actorId = actor.actorId,
                orderId = orderId,
                operation = OPERATION,
                idempotencyKey = idempotencyKey,
                payloadHash = payloadHash,
                responseStatus = status,
                responseBody = body,
                createdAt = now,
            ),
        )
        return StoreTransitionHttpResult(status, body)
    }

    private fun reject(
        order: OrderEntity,
        actor: StoreActor,
        reason: String,
        now: Instant,
        correlationId: String,
        causationId: String,
    ): RejectionCompensationCaseView =
        rejectionCoordinator.reject(
            order = order,
            actor =
                RejectionActor(
                    actorId = actor.actorId.toString(),
                    actorType =
                        when (actor.role) {
                            StoreActorRole.OWNER -> OrderRejectionActorType.STORE_OWNER
                            StoreActorRole.STAFF -> OrderRejectionActorType.STORE_STAFF
                        },
                ),
            reason = reason,
            now = now,
            correlationId = correlationId,
            causationId = causationId,
        )

    private fun publishAccepted(
        order: OrderEntity,
        now: Instant,
        correlationId: String,
        causationId: String,
    ) {
        val eventId = identifierSource.next()
        eventPublisher.publishEvent(
            OrderAcceptedV1(
                envelope(eventId, "OrderAcceptedV1", order, now, correlationId, causationId),
                order.id,
                order.storeId,
                now,
            ),
        )
    }

    private fun publishReady(
        order: OrderEntity,
        now: Instant,
        correlationId: String,
        causationId: String,
    ) {
        val eventId = identifierSource.next()
        eventPublisher.publishEvent(
            OrderReadyV1(
                envelope(eventId, "OrderReadyV1", order, now, correlationId, causationId),
                order.id,
                order.customerId,
                order.storeId,
                now,
            ),
        )
    }

    private fun publishCompleted(
        order: OrderEntity,
        now: Instant,
        correlationId: String,
        causationId: String,
    ) {
        val eventId = identifierSource.next()
        eventPublisher.publishEvent(
            OrderCompletedV1(
                envelope(eventId, "OrderCompletedV1", order, now, correlationId, causationId),
                order.id,
                order.customerId,
                order.storeId,
                now,
            ),
        )
    }

    private fun envelope(
        eventId: UUID,
        eventType: String,
        order: OrderEntity,
        now: Instant,
        correlationId: String,
        causationId: String,
    ) = EventEnvelope(
        eventId = eventId,
        eventType = eventType,
        aggregateId = order.id,
        aggregateVersion = order.version + 1,
        occurredAt = now,
        payloadVersion = 1,
        correlationId = correlationId,
        causationId = causationId,
    )

    private fun appendAudit(
        order: OrderEntity,
        actor: StoreActor,
        before: String,
        request: StoreOrderTransitionRequest,
        now: Instant,
        correlationId: String,
        sourceReference: String,
    ) {
        auditRecordOperations.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = actor.actorId.toString(),
                    actorType =
                        when (actor.role) {
                            StoreActorRole.OWNER -> AuditActorType.STORE_OWNER
                            StoreActorRole.STAFF -> AuditActorType.STORE_STAFF
                        },
                    action = "STORE_ORDER_${request.targetState.name}",
                    targetType = "ORDER",
                    targetId = order.id,
                    occurredAt = now,
                    reason = request.reason?.trim() ?: "STORE_LIFECYCLE_TRANSITION",
                    beforeSummary = mapOf("state" to before),
                    afterSummary = mapOf("state" to order.state.name),
                    correlationId = correlationId,
                    sourceReference = sourceReference,
                ),
            ),
        )
    }

    private fun response(order: OrderEntity): OrderResponse {
        val lines = orderLineRepository.findAllByOrderIdOrderByLineSequence(order.id)
        return OrderResponse(
            orderId = order.id,
            storeId = order.storeId,
            state = order.state.name,
            reservationExpiresAt = order.reservationExpiresAt,
            paidAt = order.paidAt,
            acceptanceWarningAt = order.acceptanceWarningAt,
            acceptanceWarningRequestedAt = order.acceptanceWarningRequestedAt,
            acceptanceDeadlineAt = order.acceptanceDeadlineAt,
            acceptedAt = order.acceptedAt,
            rejectedAt = order.rejectedAt,
            preparingAt = order.preparingAt,
            readyAt = order.readyAt,
            completedAt = order.completedAt,
            rejectionReason = order.rejectionReason,
            lines =
                lines.map {
                    OrderLineResponse(
                        orderLineId = it.id,
                        menuId = it.menuId,
                        menuName = it.menuName,
                        optionNames = objectMapper.readValue(it.optionNamesJson, Array<String>::class.java).toList(),
                        unitPriceKrw = it.unitPriceKrw,
                        quantity = it.quantity,
                        couponDiscountKrw = it.couponDiscountKrw,
                        pointsAppliedKrw = it.pointsAppliedKrw,
                        cashPaidKrw = it.cashPayableKrw,
                    )
                },
            subtotalKrw = order.subtotalKrw,
            couponDiscountKrw = order.couponDiscountKrw,
            pointsAppliedKrw = order.pointsAppliedKrw,
            payableKrw = order.payableKrw,
            currency = order.currency,
            createdAt = order.createdAt,
            updatedAt = order.updatedAt,
        )
    }

    private fun RejectionCompensationCaseView.toResponse() =
        RejectionRecoveryResponse(
            caseId,
            policyVersion,
            state,
            steps.map {
                RejectionRecoveryStepResponse(it.type, it.state, it.attemptCount, it.lastErrorCode)
            },
            updatedAt,
        )

    private fun replay(body: String): String {
        val tree = objectMapper.readTree(body)
        (tree as tools.jackson.databind.node.ObjectNode).put("replayed", true)
        return objectMapper.writeValueAsString(tree)
    }

    private fun validate(request: StoreOrderTransitionRequest) {
        if (request.targetState == StoreOrderTargetState.REJECTED &&
            request.reason?.trim()?.length !in 1..500
        ) {
            throw DomainFailure(
                FailureCode.INVALID_REQUEST,
                "Rejection reason must contain between 1 and 500 characters",
            )
        }
    }

    private fun find(orderId: UUID): OrderEntity = orderRepository.findById(orderId).orElse(null) ?: notFound()

    private fun notFound(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Order was not found")

    private companion object {
        const val OPERATION = "STORE_ORDER_TRANSITION"
    }
}

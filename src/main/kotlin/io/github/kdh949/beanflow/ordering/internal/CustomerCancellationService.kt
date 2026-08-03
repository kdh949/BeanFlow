package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.eventing.api.BenefitRestorationPolicySnapshotV1
import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.OrderCancelledV1
import io.github.kdh949.beanflow.fulfillment.api.PickupReservationOperations
import io.github.kdh949.beanflow.inventory.api.StockReservationOperations
import io.github.kdh949.beanflow.loyalty.api.PointReservationOperations
import io.github.kdh949.beanflow.notification.api.CustomerCancellationNotificationOperations
import io.github.kdh949.beanflow.notification.api.RequestCustomerCancellationAcceptedNotificationCommand
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicyOperations
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicySnapshot
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationTrigger
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitType
import io.github.kdh949.beanflow.operations.api.OpenOrderCompensationCaseCommand
import io.github.kdh949.beanflow.operations.api.OrderCompensationOperations
import io.github.kdh949.beanflow.operations.api.OrderCompensationTrigger
import io.github.kdh949.beanflow.ordering.api.CustomerCancellationReasonCode
import io.github.kdh949.beanflow.ordering.api.ReservationExpiryOutcome
import io.github.kdh949.beanflow.ordering.api.ReservationExpiryUseCase
import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.payment.api.CustomerCancellationPaymentOperations
import io.github.kdh949.beanflow.payment.api.CustomerCancellationPaymentSnapshot
import io.github.kdh949.beanflow.payment.api.PrepareCustomerCancellationPaymentCommand
import io.github.kdh949.beanflow.promotion.api.CouponReservationOperations
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.ReservationTransitionReport
import io.github.kdh949.beanflow.shared.api.ReservationTransitionResult
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

internal sealed interface CustomerCancellationTransactionOutcome {
    data class Success(
        val response: CustomerCancellationHttpResult,
    ) : CustomerCancellationTransactionOutcome

    data object ReservationExpired : CustomerCancellationTransactionOutcome

    data class AcceptanceDeadlineReached(
        val workId: UUID,
    ) : CustomerCancellationTransactionOutcome
}

internal data class CustomerCancellationMetricsContext(
    var fromState: String = "unresolved",
    var phase: String = "unresolved",
    var rollbackTarget: String = "transaction",
)

@Service
internal class CustomerCancellationService(
    private val transaction: CustomerCancellationTransaction,
    private val timeoutWorkWorker: AcceptanceTimeoutWorkWorker,
    private val meterRegistry: MeterRegistry,
) {
    fun cancel(
        customerId: UUID,
        orderId: UUID,
        idempotencyKey: String,
        request: CustomerCancellationRequest,
    ): CustomerCancellationHttpResult {
        val metrics = CustomerCancellationMetricsContext()
        val timer = Timer.start(meterRegistry)
        var transactionReturned = false
        var outcomeTag = "unexpected_failure"
        try {
            return when (val outcome = transaction.execute(customerId, orderId, idempotencyKey, request, metrics)) {
                is CustomerCancellationTransactionOutcome.Success -> {
                    transactionReturned = true
                    outcomeTag = "accepted"
                    outcome.response
                }

                CustomerCancellationTransactionOutcome.ReservationExpired -> {
                    transactionReturned = true
                    throw DomainFailure(FailureCode.RESERVATION_EXPIRED, "Order reservation has expired")
                }

                is CustomerCancellationTransactionOutcome.AcceptanceDeadlineReached -> {
                    transactionReturned = true
                    timeoutWorkWorker.wake(outcome.workId)
                    throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, "Store acceptance deadline has passed")
                }
            }
        } catch (failure: RuntimeException) {
            outcomeTag = (failure as? DomainFailure)?.code?.name?.lowercase() ?: "unexpected_failure"
            if (!transactionReturned) {
                meterRegistry
                    .counter(
                        "beanflow.order.customer_cancellation.transaction.rollback.count",
                        "phase",
                        metrics.phase,
                        "target",
                        metrics.rollbackTarget,
                    ).increment()
            }
            throw failure
        } finally {
            meterRegistry
                .counter(
                    "beanflow.order.customer_cancellation.transaction.count",
                    "from_state",
                    metrics.fromState,
                    "phase",
                    metrics.phase,
                    "outcome",
                    outcomeTag,
                    "reason_code",
                    request.reasonCode.name.lowercase(),
                ).increment()
            meterRegistry
                .counter(
                    "beanflow.order.idempotency.model_selection.count",
                    "model",
                    "command_transaction",
                    "operation",
                    "customer_order_cancellation",
                    "outcome",
                    outcomeTag,
                ).increment()
            timer.stop(
                meterRegistry.timer(
                    "beanflow.order.customer_cancellation.transaction.duration",
                    "phase",
                    metrics.phase,
                    "outcome",
                    outcomeTag,
                ),
            )
        }
    }
}

@Suppress("LongParameterList")
@Service
internal class CustomerCancellationTransaction(
    private val orders: OrderJpaRepository,
    private val idempotencyRecords: CancellationCommandIdempotencyJpaRepository,
    private val timeoutWorks: AcceptanceTimeoutWorkJpaRepository,
    private val expiryUseCase: ReservationExpiryUseCase,
    private val pickupOperations: PickupReservationOperations,
    private val stockOperations: StockReservationOperations,
    private val couponOperations: CouponReservationOperations,
    private val pointOperations: PointReservationOperations,
    private val paymentOperations: CustomerCancellationPaymentOperations,
    private val policyOperations: ExpiredBenefitRestorationPolicyOperations,
    private val compensationOperations: OrderCompensationOperations,
    private val notificationOperations: CustomerCancellationNotificationOperations,
    private val auditOperations: AuditRecordOperations,
    private val eventPublisher: ApplicationEventPublisher,
    private val identifiers: IdentifierSource,
    private val correlations: CorrelationIdSource,
    private val objectMapper: ObjectMapper,
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
) {
    @Transactional
    fun execute(
        customerId: UUID,
        orderId: UUID,
        idempotencyKey: String,
        request: CustomerCancellationRequest,
        metrics: CustomerCancellationMetricsContext = CustomerCancellationMetricsContext(),
    ): CustomerCancellationTransactionOutcome {
        validateIdempotencyKey(idempotencyKey)
        val normalizedDetail = CanonicalCustomerCancellationPayload.normalizeDetail(request.detail)
        val payloadHash = CanonicalCustomerCancellationPayload.hash(orderId, request.reasonCode, normalizedDetail, objectMapper)
        val order = lockOwned(customerId, orderId)
        metrics.fromState = order.state.name.lowercase()
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        lockIdempotencyScope(customerId, idempotencyKey)
        replay(customerId, idempotencyKey, payloadHash)?.let {
            metrics.phase = "replay"
            metrics.rollbackTarget = "transaction_commit"
            return CustomerCancellationTransactionOutcome.Success(it)
        }
        val correlationId = correlations.currentOrCreate()

        return when (order.state) {
            OrderState.PENDING_PAYMENT -> {
                cancelPending(
                    order,
                    customerId,
                    idempotencyKey,
                    request.reasonCode,
                    normalizedDetail,
                    payloadHash,
                    correlationId,
                    now,
                    metrics,
                )
            }

            OrderState.PAID -> {
                cancelPaid(
                    order,
                    customerId,
                    idempotencyKey,
                    request.reasonCode,
                    normalizedDetail,
                    payloadHash,
                    correlationId,
                    now,
                    metrics,
                )
            }

            else -> {
                metrics.phase = "ineligible"
                metrics.rollbackTarget = "state_guard"
                throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, "Order state does not allow customer cancellation")
            }
        }
    }

    private fun cancelPending(
        order: OrderEntity,
        customerId: UUID,
        idempotencyKey: String,
        reasonCode: CustomerCancellationReasonCode,
        detail: String?,
        payloadHash: String,
        correlationId: String,
        now: Instant,
        metrics: CustomerCancellationMetricsContext,
    ): CustomerCancellationTransactionOutcome {
        val deadline =
            order.reservationExpiresAt
                ?: dependency("Pending-payment order has no reservation deadline")
        if (!now.isBefore(deadline)) {
            metrics.phase = "expiry"
            metrics.rollbackTarget = "reservation_expiry"
            val expiry = expiryUseCase.expireIfDue(order.id, now)
            if (expiry.outcome != ReservationExpiryOutcome.EXPIRED) {
                dependency("Due order could not be atomically expired")
            }
            return CustomerCancellationTransactionOutcome.ReservationExpired
        }

        metrics.phase = "c0"
        metrics.rollbackTarget = "pickup"
        val pickup = requireApplied("PICKUP", pickupOperations.release(order.id, now, OrderCreationTransaction.pickupSource(order.id)))
        metrics.rollbackTarget = "stock"
        val stock = requireApplied("STOCK", stockOperations.release(order.id, now, OrderCreationTransaction.stockSource(order.id)))
        val coupon =
            if (order.couponDiscountKrw > 0) {
                metrics.rollbackTarget = "coupon"
                requireApplied("COUPON", couponOperations.release(order.id, now, OrderCreationTransaction.couponSource(order.id)))
            } else {
                null
            }
        val points =
            if (order.pointsAppliedKrw > 0) {
                metrics.rollbackTarget = "points"
                requireApplied("POINTS", pointOperations.release(order.id, now, OrderCreationTransaction.pointsSource(order.id)))
            } else {
                null
            }
        metrics.rollbackTarget = "order"
        order.cancelByCustomer(now, reasonCode, detail)
        val terminalVersion = order.version + 1
        metrics.rollbackTarget = "notification_delivery"
        val notification = acceptedNotification(order, identifiers.next(), correlationId, now)
        val sourcePrefix = sourcePrefix(order.id, terminalVersion)
        val audits =
            mutableListOf(
                audit(
                    customerId,
                    "ORDER_CUSTOMER_CANCELLED",
                    "ORDER",
                    order.id,
                    reasonCode,
                    now,
                    "$sourcePrefix:order",
                    correlationId,
                    mapOf("state" to "PENDING_PAYMENT"),
                    mapOf("state" to "CANCELLED", "cause" to "CUSTOMER_REQUEST"),
                ),
            )
        pickup.targetIds.forEach {
            audits += releaseAudit(customerId, "PICKUP", it, reasonCode, now, sourcePrefix, correlationId)
        }
        stock.targetIds.forEach {
            audits += releaseAudit(customerId, "STOCK", it, reasonCode, now, sourcePrefix, correlationId)
        }
        coupon?.targetIds?.forEach {
            audits += releaseAudit(customerId, "COUPON", it, reasonCode, now, sourcePrefix, correlationId)
        }
        points?.targetIds?.forEach {
            audits += releaseAudit(customerId, "POINT", it, reasonCode, now, sourcePrefix, correlationId)
        }
        audits +=
            audit(
                customerId,
                "ORDER_CANCELLATION_ACCEPTED_DELIVERY_CREATED",
                "NOTIFICATION_DELIVERY",
                notification.deliveryId,
                reasonCode,
                now,
                "$sourcePrefix:notification:${notification.deliveryId}",
                correlationId,
                after = mapOf("state" to notification.state, "template" to "ORDER_CANCELLATION_ACCEPTED"),
            )
        metrics.rollbackTarget = "audit"
        auditOperations.appendAll(audits)
        metrics.rollbackTarget = "idempotency_record"
        val outcome =
            success(
                status = 200,
                order = order,
                reasonCode = reasonCode,
                recovery = CancellationRefundRecoverySummary("NOT_REQUIRED"),
                customerId = customerId,
                idempotencyKey = idempotencyKey,
                payloadHash = payloadHash,
                correlationId = correlationId,
                now = now,
            )
        metrics.rollbackTarget = "transaction_commit"
        return outcome
    }

    private fun cancelPaid(
        order: OrderEntity,
        customerId: UUID,
        idempotencyKey: String,
        reasonCode: CustomerCancellationReasonCode,
        detail: String?,
        payloadHash: String,
        correlationId: String,
        now: Instant,
        metrics: CustomerCancellationMetricsContext,
    ): CustomerCancellationTransactionOutcome {
        val deadline =
            order.acceptanceDeadlineAt
                ?: dependency("Paid order has no acceptance deadline")
        if (!now.isBefore(deadline)) {
            metrics.phase = "ct"
            metrics.rollbackTarget = "acceptance_timeout_work"
            val outcome =
                CustomerCancellationTransactionOutcome.AcceptanceDeadlineReached(
                    requestAcceptanceTimeoutWork(order, deadline, correlationId, now),
                )
            metrics.rollbackTarget = "transaction_commit"
            return outcome
        }

        metrics.phase = "c1"
        val terminalVersion = order.version + 1
        metrics.rollbackTarget = "payment_snapshot"
        val payment =
            paymentOperations.prepare(
                PrepareCustomerCancellationPaymentCommand(
                    orderId = order.id,
                    cancellationOrderVersion = terminalVersion,
                    customerReasonCode = reasonCode.name,
                    correlationId = correlationId,
                    now = now,
                ),
            )
        metrics.rollbackTarget = "benefit_policy"
        val couponPolicy =
            policyOperations.current(ExpiredBenefitRestorationTrigger.CUSTOMER_CANCELLATION, ExpiredBenefitType.COUPON)
        val pointsPolicy =
            policyOperations.current(ExpiredBenefitRestorationTrigger.CUSTOMER_CANCELLATION, ExpiredBenefitType.POINTS)
        metrics.rollbackTarget = "order"
        order.cancelByCustomer(now, reasonCode, detail)
        val eventId = identifiers.next()
        val caseId = identifiers.next()
        val sourcePrefix = sourcePrefix(order.id, terminalVersion)
        metrics.rollbackTarget = "compensation_case"
        val compensation =
            compensationOperations.open(
                OpenOrderCompensationCaseCommand(
                    caseId = caseId,
                    eventId = eventId,
                    orderId = order.id,
                    terminalOrderVersion = terminalVersion,
                    customerId = order.customerId,
                    storeId = order.storeId,
                    trigger = OrderCompensationTrigger.CUSTOMER_CANCELLATION,
                    sourceReference = "$sourcePrefix:case",
                    couponPolicy = couponPolicy,
                    pointsPolicy = pointsPolicy,
                    paymentRequired = payment.paymentRecoveryRequired,
                    couponRequired = order.couponDiscountKrw > 0,
                    pointsRequired = order.pointsAppliedKrw > 0,
                    correlationId = correlationId,
                    now = now,
                ),
            )
        metrics.rollbackTarget = "notification_delivery"
        val notification = acceptedNotification(order, identifiers.next(), correlationId, now)
        metrics.rollbackTarget = "audit"
        auditOperations.appendAll(
            paidAudits(
                order,
                customerId,
                reasonCode,
                payment,
                compensation.caseId,
                notification.deliveryId,
                notification.state,
                couponPolicy,
                pointsPolicy,
                sourcePrefix,
                correlationId,
                now,
            ),
        )
        metrics.rollbackTarget = "event_publication"
        eventPublisher.publishEvent(
            OrderCancelledV1(
                envelope =
                    EventEnvelope(
                        eventId = eventId,
                        eventType = "OrderCancelledV1",
                        aggregateId = order.id,
                        aggregateVersion = terminalVersion,
                        occurredAt = now,
                        payloadVersion = 1,
                        correlationId = correlationId,
                        causationId = "$sourcePrefix:command",
                    ),
                orderId = order.id,
                cancelledAt = now,
                couponRequired = order.couponDiscountKrw > 0,
                pointsRequired = order.pointsAppliedKrw > 0,
                couponPolicy = couponPolicy.toEventSnapshot(),
                pointsPolicy = pointsPolicy.toEventSnapshot(),
            ),
        )
        val recovery =
            CancellationRefundRecoverySummary(
                state = if (payment.paymentRecoveryRequired) "REQUESTED" else "NOT_REQUIRED",
                approvedAmountKrw = payment.approvedAmountKrw,
                succeededRefundAmountBeforeCancellationKrw = payment.succeededRefundAmountBeforeCancellationKrw,
                cancellationRequestedRefundAmountKrw = payment.requestedRefundAmountKrw,
                remainingRefundableAmountKrw = payment.requestedRefundAmountKrw,
                lastUpdatedAt = payment.updatedAt,
            )
        metrics.rollbackTarget = "idempotency_record"
        val outcome =
            success(
                status = 202,
                order = order,
                reasonCode = reasonCode,
                recovery = recovery,
                customerId = customerId,
                idempotencyKey = idempotencyKey,
                payloadHash = payloadHash,
                correlationId = correlationId,
                now = now,
            )
        metrics.rollbackTarget = "transaction_commit"
        return outcome
    }

    private fun acceptedNotification(
        order: OrderEntity,
        eventId: UUID,
        correlationId: String,
        now: Instant,
    ) = notificationOperations
        .requestAccepted(
            RequestCustomerCancellationAcceptedNotificationCommand(
                eventId = eventId,
                orderId = order.id,
                customerId = order.customerId,
                storeId = order.storeId,
                cancelledAt = now,
                correlationId = correlationId,
            ),
        ).also {
            if (it.state != "PENDING") dependency("Cancellation acceptance delivery is not pending")
        }

    private fun requestAcceptanceTimeoutWork(
        order: OrderEntity,
        deadline: Instant,
        correlationId: String,
        now: Instant,
    ): UUID {
        timeoutWorks.findByOrderIdAndAcceptanceDeadlineAt(order.id, deadline)?.let { return it.id }
        val work =
            timeoutWorks.save(
                AcceptanceTimeoutWorkEntity(
                    id = identifiers.next(),
                    orderId = order.id,
                    acceptanceDeadlineAt = deadline,
                    state = AcceptanceTimeoutWorkState.PENDING,
                    sourceReference = "order:${order.id}:acceptance-timeout:$deadline",
                    createdAt = now,
                    updatedAt = now,
                    nextAttemptAt = now,
                ),
            )
        auditOperations.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = "SYSTEM",
                    actorType = AuditActorType.SYSTEM,
                    action = "ACCEPTANCE_TIMEOUT_WORK_REQUESTED",
                    targetType = "ACCEPTANCE_TIMEOUT_WORK",
                    targetId = work.id,
                    occurredAt = now,
                    reason = "ACCEPTANCE_DEADLINE_REACHED",
                    afterSummary = mapOf("state" to "PENDING"),
                    correlationId = correlationId,
                    sourceReference = "${work.sourceReference}:request",
                ),
            ),
        )
        return work.id
    }

    private fun success(
        status: Int,
        order: OrderEntity,
        reasonCode: CustomerCancellationReasonCode,
        recovery: CancellationRefundRecoverySummary,
        customerId: UUID,
        idempotencyKey: String,
        payloadHash: String,
        correlationId: String,
        now: Instant,
    ): CustomerCancellationTransactionOutcome.Success {
        val body =
            objectMapper.writeValueAsString(
                CustomerCancellationResponse(
                    orderId = order.id,
                    orderState = "CANCELLED",
                    reasonCode = reasonCode,
                    paymentRecovery = recovery,
                    cancelledAt = requireNotNull(order.cancelledAt),
                    correlationId = correlationId,
                ),
            )
        idempotencyRecords.save(
            CancellationCommandIdempotencyEntity(
                id = identifiers.next(),
                actorId = customerId,
                orderId = order.id,
                operation = OPERATION,
                idempotencyKey = idempotencyKey,
                payloadHash = payloadHash,
                responseStatus = status,
                responseBody = body,
                responseVersion = 1,
                createdAt = now,
                retentionExpiresAt = now.plus(IDEMPOTENCY_RETENTION),
            ),
        )
        return CustomerCancellationTransactionOutcome.Success(CustomerCancellationHttpResult(status, body))
    }

    private fun replay(
        customerId: UUID,
        idempotencyKey: String,
        payloadHash: String,
    ): CustomerCancellationHttpResult? =
        idempotencyRecords.findByActorIdAndOperationAndIdempotencyKey(customerId, OPERATION, idempotencyKey)?.let {
            if (it.payloadHash != payloadHash) {
                throw DomainFailure(
                    FailureCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key was reused with a different cancellation payload",
                )
            }
            CustomerCancellationHttpResult(it.responseStatus, it.responseBody)
        }

    private fun paidAudits(
        order: OrderEntity,
        customerId: UUID,
        reasonCode: CustomerCancellationReasonCode,
        payment: CustomerCancellationPaymentSnapshot,
        caseId: UUID,
        deliveryId: UUID,
        deliveryState: String,
        couponPolicy: ExpiredBenefitRestorationPolicySnapshot,
        pointsPolicy: ExpiredBenefitRestorationPolicySnapshot,
        sourcePrefix: String,
        correlationId: String,
        now: Instant,
    ): List<AppendAuditRecordCommand> {
        val audits =
            mutableListOf(
                audit(
                    customerId,
                    "ORDER_CUSTOMER_CANCELLED",
                    "ORDER",
                    order.id,
                    reasonCode,
                    now,
                    "$sourcePrefix:order",
                    correlationId,
                    mapOf("state" to "PAID"),
                    mapOf("state" to "CANCELLED", "cause" to "CUSTOMER_REQUEST"),
                ),
                audit(
                    customerId,
                    "ORDER_COMPENSATION_CASE_CREATED",
                    "ORDER_COMPENSATION_CASE",
                    caseId,
                    reasonCode,
                    now,
                    "$sourcePrefix:case",
                    correlationId,
                    after =
                        mapOf(
                            "state" to "PROCESSING",
                            "stepCount" to "6",
                            "couponPolicyVersionId" to couponPolicy.policyVersion.toString(),
                            "pointsPolicyVersionId" to pointsPolicy.policyVersion.toString(),
                        ),
                ),
                audit(
                    customerId,
                    "PAYMENT_CANCELLATION_RECOVERY_SNAPSHOT_CREATED",
                    "PAYMENT_CANCELLATION_RECOVERY_SNAPSHOT",
                    payment.snapshotId,
                    reasonCode,
                    now,
                    "$sourcePrefix:payment-snapshot",
                    correlationId,
                    after =
                        mapOf(
                            "approvedAmountKrw" to payment.approvedAmountKrw.toString(),
                            "succeededRefundAmountBeforeCancellationKrw" to
                                payment.succeededRefundAmountBeforeCancellationKrw.toString(),
                            "cancellationRequestedRefundAmountKrw" to payment.requestedRefundAmountKrw.toString(),
                        ),
                ),
                audit(
                    customerId,
                    "ORDER_CANCELLATION_ACCEPTED_DELIVERY_CREATED",
                    "NOTIFICATION_DELIVERY",
                    deliveryId,
                    reasonCode,
                    now,
                    "$sourcePrefix:notification:$deliveryId",
                    correlationId,
                    after = mapOf("state" to deliveryState, "template" to "ORDER_CANCELLATION_ACCEPTED"),
                ),
            )
        payment.refundId?.let { refundId ->
            audits +=
                audit(
                    customerId,
                    "CUSTOMER_CANCELLATION_REFUND_REQUESTED",
                    "REFUND",
                    refundId,
                    reasonCode,
                    now,
                    "$sourcePrefix:refund",
                    correlationId,
                    after = mapOf("state" to "REQUESTED", "requestedAmountKrw" to payment.requestedRefundAmountKrw.toString()),
                )
        }
        return audits
    }

    private fun releaseAudit(
        customerId: UUID,
        owner: String,
        targetId: UUID,
        reasonCode: CustomerCancellationReasonCode,
        now: Instant,
        sourcePrefix: String,
        correlationId: String,
    ) = audit(
        customerId,
        "${owner}_RESERVATION_RELEASED_BY_CUSTOMER_CANCELLATION",
        "${owner}_RESERVATION",
        targetId,
        reasonCode,
        now,
        "$sourcePrefix:${owner.lowercase()}:$targetId",
        correlationId,
        mapOf("state" to "RESERVED"),
        mapOf("state" to "RELEASED"),
    )

    private fun audit(
        customerId: UUID,
        action: String,
        targetType: String,
        targetId: UUID,
        reasonCode: CustomerCancellationReasonCode,
        now: Instant,
        sourceReference: String,
        correlationId: String,
        before: Map<String, String> = emptyMap(),
        after: Map<String, String> = emptyMap(),
    ) = AppendAuditRecordCommand(
        actorId = customerId.toString(),
        actorType = AuditActorType.CUSTOMER,
        action = action,
        targetType = targetType,
        targetId = targetId,
        occurredAt = now,
        reason = reasonCode.name,
        beforeSummary = before,
        afterSummary = after,
        correlationId = correlationId,
        sourceReference = sourceReference,
    )

    private fun requireApplied(
        owner: String,
        report: ReservationTransitionReport,
    ): ReservationTransitionReport {
        if (report.result != ReservationTransitionResult.APPLIED || report.targetIds.isEmpty()) {
            dependency("$owner reservation was not eligible for atomic customer cancellation")
        }
        return report
    }

    private fun lockOwned(
        customerId: UUID,
        orderId: UUID,
    ): OrderEntity {
        val order = orders.findLockedById(orderId) ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Order was not found")
        if (order.customerId != customerId) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Order belongs to another customer")
        }
        return order
    }

    private fun lockIdempotencyScope(
        customerId: UUID,
        idempotencyKey: String,
    ) {
        jdbcTemplate.query(
            "select pg_advisory_xact_lock(hashtextextended(?, 0))",
            { _, _ -> Unit },
            "customer-cancellation:$customerId:$idempotencyKey",
        )
    }

    private fun validateIdempotencyKey(idempotencyKey: String) {
        if (idempotencyKey.length !in 8..128 || idempotencyKey.any(Char::isISOControl)) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Idempotency-Key must contain 8 to 128 non-control characters")
        }
    }

    private fun ExpiredBenefitRestorationPolicySnapshot.toEventSnapshot() =
        BenefitRestorationPolicySnapshotV1(
            policyVersionId = policyVersion,
            mode = mode.name,
            compensationValidityDays = compensationValidityDays,
        )

    private fun sourcePrefix(
        orderId: UUID,
        aggregateVersion: Long,
    ) = "order:$orderId:customer-cancellation:$aggregateVersion"

    private fun dependency(message: String): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)

    private companion object {
        const val OPERATION = "CUSTOMER_ORDER_CANCELLATION"
        val IDEMPOTENCY_RETENTION: Duration = Duration.ofDays(90)
    }
}

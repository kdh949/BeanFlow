package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.fulfillment.api.PickupReservationOperations
import io.github.kdh949.beanflow.inventory.api.StockReservationOperations
import io.github.kdh949.beanflow.loyalty.api.PointReservationOperations
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.ordering.api.ReservationExpiryUseCase
import io.github.kdh949.beanflow.ordering.api.StoredHttpResponse
import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.payment.api.ApplyExternalPaymentResultCommand
import io.github.kdh949.beanflow.payment.api.ClaimedPaymentReconciliation
import io.github.kdh949.beanflow.payment.api.ExternalPaymentOperations
import io.github.kdh949.beanflow.payment.api.PaymentReconciliationOperations
import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import io.github.kdh949.beanflow.promotion.api.CouponReservationOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.ReservationTransitionReport
import io.github.kdh949.beanflow.shared.api.ReservationTransitionResult
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
internal class PaymentResultTransaction(
    private val orderRepository: OrderJpaRepository,
    private val expiryUseCase: ReservationExpiryUseCase,
    private val pickupOperations: PickupReservationOperations,
    private val stockOperations: StockReservationOperations,
    private val couponOperations: CouponReservationOperations,
    private val pointOperations: PointReservationOperations,
    private val paymentOperations: ExternalPaymentOperations,
    private val reconciliationOperations: PaymentReconciliationOperations,
    private val auditOperations: AuditRecordOperations,
    private val responseFactory: PaymentConfirmationResponseFactory,
    private val meterRegistry: MeterRegistry,
) {
    @Transactional
    fun apply(
        customerId: UUID,
        orderId: UUID,
        paymentId: UUID,
        result: ProviderPaymentResult,
        now: Instant,
    ): StoredHttpResponse =
        when (result) {
            is ProviderPaymentResult.Unknown -> unknown(paymentId, orderId, result, now)
            is ProviderPaymentResult.Declined -> decline(customerId, orderId, paymentId, result, now)
            is ProviderPaymentResult.Approved -> approve(customerId, orderId, paymentId, result, now)
        }

    @Transactional
    fun reconcileUnknown(
        work: ClaimedPaymentReconciliation,
        result: ProviderPaymentResult.Unknown,
        now: Instant,
    ) {
        val body =
            confirmationBody(
                work.paymentId,
                work.orderId,
                "UNKNOWN",
                null,
                work.currency,
                "RECONCILING",
                now,
            )
        reconciliationOperations.recordUnknown(
            work = work,
            responseStatus = 202,
            responseBody = body,
            code = result.code,
            now = now,
        )
    }

    @Transactional
    fun reconcileMismatch(
        work: ClaimedPaymentReconciliation,
        result: ProviderPaymentResult.Approved,
        now: Instant,
    ) {
        val body =
            confirmationBody(
                work.paymentId,
                work.orderId,
                "RECONCILING",
                null,
                work.currency,
                "RECONCILING",
                now,
            )
        reconciliationOperations.recordUnknown(
            work = work,
            responseStatus = 202,
            responseBody = body,
            code = if (result.currency != work.currency) "CURRENCY_MISMATCH" else "AMOUNT_MISMATCH",
            now = now,
        )
    }

    private fun unknown(
        paymentId: UUID,
        orderId: UUID,
        result: ProviderPaymentResult.Unknown,
        now: Instant,
    ): StoredHttpResponse {
        val body =
            confirmationBody(
                paymentId,
                orderId,
                "UNKNOWN",
                null,
                "KRW",
                "REQUESTED",
                now,
            )
        paymentOperations.applyResult(
            ApplyExternalPaymentResultCommand(paymentId, result, 202, body, now),
        )
        meterRegistry.counter("beanflow.payment.unknown.count").increment()
        return StoredHttpResponse(202, body)
    }

    private fun decline(
        customerId: UUID,
        orderId: UUID,
        paymentId: UUID,
        result: ProviderPaymentResult.Declined,
        now: Instant,
    ): StoredHttpResponse {
        val order = lockOwned(customerId, orderId)
        expireIfDue(order, now)
        if (order.state == OrderState.PENDING_PAYMENT) {
            val reports = release(order, now)
            order.cancelAfterPaymentDeclined(now)
            appendAudits(customerId, orderId, paymentId, now, "PAYMENT_DECLINED", reports)
        }
        val response =
            responseFactory.error(
                FailureCode.PAYMENT_DECLINED,
                "Provider declined the payment",
                correlation(paymentId),
            )
        paymentOperations.applyResult(
            ApplyExternalPaymentResultCommand(paymentId, result, response.status, response.body, now),
        )
        return response
    }

    private fun approve(
        customerId: UUID,
        orderId: UUID,
        paymentId: UUID,
        result: ProviderPaymentResult.Approved,
        now: Instant,
    ): StoredHttpResponse {
        val order = lockOwned(customerId, orderId)
        expireIfDue(order, now)
        val exact = result.amountKrw == order.payableKrw && result.currency == order.currency
        val late = order.state == OrderState.EXPIRED || order.state == OrderState.CANCELLED
        if (late) {
            val body =
                confirmationBody(
                    paymentId,
                    orderId,
                    "RECONCILING",
                    result.amountKrw,
                    result.currency,
                    "REQUESTED",
                    now,
                )
            paymentOperations.applyResult(
                ApplyExternalPaymentResultCommand(paymentId, result, 202, body, now, lateApproval = true),
            )
            meterRegistry.counter("beanflow.payment.late_approval.count").increment()
            return StoredHttpResponse(202, body)
        }
        if (!exact) {
            val body =
                confirmationBody(
                    paymentId,
                    orderId,
                    "RECONCILING",
                    null,
                    order.currency,
                    "REQUESTED",
                    now,
                )
            paymentOperations.applyResult(
                ApplyExternalPaymentResultCommand(paymentId, result, 202, body, now),
            )
            return StoredHttpResponse(202, body)
        }
        if (order.state != OrderState.PENDING_PAYMENT) {
            throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, "Order is not eligible for approval")
        }
        val reports = confirm(order, now)
        order.markPaid(now)
        val body =
            confirmationBody(
                paymentId,
                orderId,
                "APPROVED",
                result.amountKrw,
                result.currency,
                "NOT_REQUIRED",
                now,
            )
        paymentOperations.applyResult(
            ApplyExternalPaymentResultCommand(paymentId, result, 200, body, now),
        )
        appendAudits(customerId, orderId, paymentId, now, "PAYMENT_APPROVED", reports)
        return StoredHttpResponse(200, body)
    }

    private fun expireIfDue(
        order: OrderEntity,
        now: Instant,
    ) {
        val deadline = order.reservationExpiresAt
        if (order.state == OrderState.PENDING_PAYMENT && deadline != null && !now.isBefore(deadline)) {
            expiryUseCase.expireIfDue(order.id, now)
        }
    }

    private fun confirm(
        order: OrderEntity,
        now: Instant,
    ): List<Pair<String, ReservationTransitionReport>> {
        val reports = mutableListOf<Pair<String, ReservationTransitionReport>>()
        reports += "PICKUP" to
            requireApplied(
                "PICKUP",
                pickupOperations.confirm(order.id, now, OrderCreationTransaction.pickupSource(order.id)),
            )
        reports += "STOCK" to
            requireApplied(
                "STOCK",
                stockOperations.confirm(order.id, OrderCreationTransaction.stockSource(order.id)),
            )
        if (order.couponDiscountKrw > 0) {
            reports += "COUPON" to
                requireApplied(
                    "COUPON",
                    couponOperations.confirm(order.id, OrderCreationTransaction.couponSource(order.id)),
                )
        }
        if (order.pointsAppliedKrw > 0) {
            reports += "POINTS" to
                requireApplied(
                    "POINTS",
                    pointOperations.confirm(order.id, OrderCreationTransaction.pointsSource(order.id)),
                )
        }
        return reports
    }

    private fun release(
        order: OrderEntity,
        now: Instant,
    ): List<Pair<String, ReservationTransitionReport>> {
        val reports = mutableListOf<Pair<String, ReservationTransitionReport>>()
        reports += "PICKUP" to
            requireApplied(
                "PICKUP",
                pickupOperations.release(order.id, now, OrderCreationTransaction.pickupSource(order.id)),
            )
        reports += "STOCK" to
            requireApplied(
                "STOCK",
                stockOperations.release(order.id, now, OrderCreationTransaction.stockSource(order.id)),
            )
        if (order.couponDiscountKrw > 0) {
            reports += "COUPON" to
                requireApplied(
                    "COUPON",
                    couponOperations.release(order.id, now, OrderCreationTransaction.couponSource(order.id)),
                )
        }
        if (order.pointsAppliedKrw > 0) {
            reports += "POINTS" to
                requireApplied(
                    "POINTS",
                    pointOperations.release(order.id, now, OrderCreationTransaction.pointsSource(order.id)),
                )
        }
        return reports
    }

    private fun requireApplied(
        owner: String,
        report: ReservationTransitionReport,
    ): ReservationTransitionReport {
        if (report.result != ReservationTransitionResult.APPLIED || report.targetIds.isEmpty()) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "$owner reservation was not eligible for atomic payment transition",
            )
        }
        return report
    }

    private fun lockOwned(
        customerId: UUID,
        orderId: UUID,
    ): OrderEntity {
        val order =
            orderRepository.findLockedById(orderId)
                ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Order was not found")
        if (order.customerId != customerId) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Order belongs to another customer")
        }
        return order
    }

    private fun confirmationBody(
        paymentId: UUID,
        orderId: UUID,
        approvalState: String,
        approvedAmountKrw: Long?,
        currency: String,
        recoveryState: String,
        now: Instant,
    ): String =
        responseFactory.confirmationBody(
            paymentId,
            orderId,
            approvalState,
            approvedAmountKrw,
            currency,
            recoveryState,
            now,
            correlation(paymentId),
        )

    private fun correlation(paymentId: UUID): String = paymentOperations.current(paymentId).correlationId

    private fun appendAudits(
        customerId: UUID,
        orderId: UUID,
        paymentId: UUID,
        now: Instant,
        action: String,
        reports: List<Pair<String, ReservationTransitionReport>>,
    ) {
        val source = "payment:$paymentId:tx2"
        val correlationId = correlation(paymentId)
        val terminal = if (action == "PAYMENT_APPROVED") "CONFIRMED" else "RELEASED"
        val commands =
            mutableListOf(
                audit(
                    customerId,
                    action,
                    "PAYMENT",
                    paymentId,
                    now,
                    source,
                    "APPROVING",
                    action.removePrefix("PAYMENT_"),
                    correlationId,
                ),
                audit(
                    customerId,
                    if (action == "PAYMENT_APPROVED") "ORDER_PAID" else "ORDER_CANCELLED",
                    "ORDER",
                    orderId,
                    now,
                    source,
                    "PENDING_PAYMENT",
                    if (action == "PAYMENT_APPROVED") "PAID" else "CANCELLED",
                    correlationId,
                ),
            )
        reports.forEach { (owner, report) ->
            report.targetIds.forEach { targetId ->
                commands +=
                    audit(
                        customerId,
                        "${owner}_$terminal",
                        "${owner}_RESERVATION",
                        targetId,
                        now,
                        source,
                        "RESERVED",
                        terminal,
                        correlationId,
                    )
            }
        }
        auditOperations.appendAll(commands)
    }

    private fun audit(
        customerId: UUID,
        action: String,
        targetType: String,
        targetId: UUID,
        now: Instant,
        source: String,
        before: String,
        after: String,
        correlationId: String,
    ) = AppendAuditRecordCommand(
        actorId = customerId.toString(),
        actorType = AuditActorType.CUSTOMER,
        action = action,
        targetType = targetType,
        targetId = targetId,
        occurredAt = now,
        reason = action,
        beforeSummary = mapOf("state" to before),
        afterSummary = mapOf("state" to after),
        correlationId = correlationId,
        sourceReference = source,
    )
}

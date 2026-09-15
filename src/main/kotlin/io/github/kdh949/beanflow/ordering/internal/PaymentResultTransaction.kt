package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.fulfillment.api.PickupReservationOperations
import io.github.kdh949.beanflow.loyalty.api.PointReservationOperations
import io.github.kdh949.beanflow.loyalty.api.UsePointsImmediatelyCommand
import io.github.kdh949.beanflow.merchant.api.StoreOrderAvailabilityOperations
import io.github.kdh949.beanflow.merchant.api.StoreOrderAvailabilityReason
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.ordering.api.ReservationExpiryUseCase
import io.github.kdh949.beanflow.ordering.api.StoredHttpResponse
import io.github.kdh949.beanflow.ordering.internal.domain.CheckoutInputSnapshot
import io.github.kdh949.beanflow.ordering.internal.domain.CheckoutMode
import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.payment.api.ApplyExternalPaymentResultCommand
import io.github.kdh949.beanflow.payment.api.ClaimedPaymentReconciliation
import io.github.kdh949.beanflow.payment.api.ExternalPaymentOperations
import io.github.kdh949.beanflow.payment.api.PaymentReconciliationOperations
import io.github.kdh949.beanflow.payment.api.PaymentReconciliationResponseBodies
import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import io.github.kdh949.beanflow.promotion.api.CouponReservationOperations
import io.github.kdh949.beanflow.promotion.api.UseCouponImmediatelyCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.ReservationTransitionReport
import io.github.kdh949.beanflow.shared.api.ReservationTransitionResult
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

internal class ImmediatePaymentCommitmentFailure(
    val failureCode: FailureCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

@Service
internal class PaymentResultTransaction(
    private val orderRepository: OrderJpaRepository,
    private val expiryUseCase: ReservationExpiryUseCase,
    private val pickupOperations: PickupReservationOperations,
    private val couponOperations: CouponReservationOperations,
    private val pointOperations: PointReservationOperations,
    private val availabilityOperations: StoreOrderAvailabilityOperations,
    private val settlementInputSnapshotService: OrderSettlementInputSnapshotService,
    private val paymentOperations: ExternalPaymentOperations,
    private val reconciliationOperations: PaymentReconciliationOperations,
    private val auditOperations: AuditRecordOperations,
    private val responseFactory: PaymentConfirmationResponseFactory,
    private val orderReferenceProjection: PaymentOrderReferenceProjection,
    private val meterRegistry: MeterRegistry,
    private val objectMapper: ObjectMapper,
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
        val manualReviewBody =
            confirmationBody(
                work.paymentId,
                work.orderId,
                "MANUAL_REVIEW",
                paymentOperations.current(work.paymentId).approvedAmountKrw,
                work.currency,
                "MANUAL_REVIEW",
                now,
            )
        reconciliationOperations.recordUnknown(
            work = work,
            responseStatus = 202,
            responseBody = body,
            manualReviewResponseBody = manualReviewBody,
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
        val manualReviewBody =
            confirmationBody(
                work.paymentId,
                work.orderId,
                "MANUAL_REVIEW",
                paymentOperations.current(work.paymentId).approvedAmountKrw,
                work.currency,
                "MANUAL_REVIEW",
                now,
            )
        reconciliationOperations.recordUnknown(
            work = work,
            responseStatus = 202,
            responseBody = body,
            manualReviewResponseBody = manualReviewBody,
            code = if (result.currency != work.currency) "CURRENCY_MISMATCH" else "AMOUNT_MISMATCH",
            now = now,
        )
    }

    @Transactional
    fun reconcileRecovery(
        work: ClaimedPaymentReconciliation,
        result: io.github.kdh949.beanflow.payment.api.ProviderRecoveryResult,
        now: Instant,
    ) {
        val payment = paymentOperations.current(work.paymentId)
        val responseBodies =
            PaymentReconciliationResponseBodies(
                completedResponseBody =
                    confirmationBody(
                        work.paymentId,
                        work.orderId,
                        "RECONCILING",
                        payment.approvedAmountKrw,
                        payment.currency,
                        "SUCCEEDED",
                        now,
                    ),
                manualReviewResponseBody =
                    confirmationBody(
                        work.paymentId,
                        work.orderId,
                        "MANUAL_REVIEW",
                        payment.approvedAmountKrw,
                        payment.currency,
                        "MANUAL_REVIEW",
                        now,
                    ),
            )
        reconciliationOperations.recordRecovery(work, result, responseBodies, now)
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
        val order = lockOwnedForApproval(customerId, orderId, now)
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
        val reports =
            if (order.checkoutMode == CheckoutMode.IMMEDIATE) {
                try {
                    commitImmediateBenefits(order, now)
                } catch (failure: DomainFailure) {
                    if (failure.code in IMMEDIATE_COMMITMENT_FAILURES) {
                        throw ImmediatePaymentCommitmentFailure(failure.code, failure.message, failure)
                    }
                    throw failure
                }
            } else {
                confirm(order, now)
            }
        try {
            order.markPaid(now)
        } catch (failure: DomainFailure) {
            if (order.checkoutMode == CheckoutMode.IMMEDIATE && failure.code == FailureCode.ORDER_STATE_CONFLICT) {
                throw ImmediatePaymentCommitmentFailure(FailureCode.STORE_CLOSED, failure.message, failure)
            }
            throw failure
        }
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
        appendAudits(
            customerId,
            orderId,
            paymentId,
            now,
            "PAYMENT_APPROVED",
            reports,
            immediate = order.checkoutMode == CheckoutMode.IMMEDIATE,
        )
        return StoredHttpResponse(200, body)
    }

    private fun expireIfDue(
        order: OrderEntity,
        now: Instant,
    ) {
        if (order.checkoutMode == CheckoutMode.IMMEDIATE) return
        val deadline = order.reservationExpiresAt
        if (order.state == OrderState.PENDING_PAYMENT && deadline != null && !now.isBefore(deadline)) {
            expiryUseCase.expireIfDue(order.id, now)
        }
    }

    private fun commitImmediateBenefits(
        order: OrderEntity,
        now: Instant,
    ): List<Pair<String, ReservationTransitionReport>> {
        val input =
            order.checkoutInputSnapshotJson
                ?.let {
                    try {
                        objectMapper.readValue(it, CheckoutInputSnapshot::class.java)
                    } catch (failure: RuntimeException) {
                        throw DomainFailure(
                            FailureCode.SETTLEMENT_INPUT_UNAVAILABLE,
                            "Immediate checkout input snapshot is invalid",
                        ).also { it.initCause(failure) }
                    }
                }
                ?: throw DomainFailure(FailureCode.SETTLEMENT_INPUT_UNAVAILABLE, "Immediate checkout input snapshot is missing")
        val reports = mutableListOf<Pair<String, ReservationTransitionReport>>()
        val coupon =
            input.couponQuote?.let { quoted ->
                couponOperations
                    .useImmediately(
                        UseCouponImmediatelyCommand(
                            orderId = order.id,
                            customerId = order.customerId,
                            storeId = order.storeId,
                            couponIssuanceId = quoted.couponIssuanceId,
                            quoted = quoted,
                            sourceReference = OrderCreationTransaction.couponSource(order.id),
                            usedAt = now,
                        ),
                    ).also {
                        reports += "COUPON" to
                            ReservationTransitionReport(ReservationTransitionResult.APPLIED, listOf(it.reservationId))
                    }
            }
        val points =
            if (order.pointsAppliedKrw > 0) {
                pointOperations
                    .useImmediately(
                        UsePointsImmediatelyCommand(
                            orderId = order.id,
                            customerId = order.customerId,
                            amountKrw = order.pointsAppliedKrw,
                            sourceReference = OrderCreationTransaction.pointsSource(order.id),
                            usedAt = now,
                        ),
                    ).also {
                        reports += "POINTS" to
                            ReservationTransitionReport(ReservationTransitionResult.APPLIED, listOf(it.reservationId))
                    }
            } else {
                null
            }
        settlementInputSnapshotService.materializeImmediate(
            order = order,
            terms = input.settlementTerms,
            coupon = coupon,
            points = points,
            createdAt = order.createdAt,
        )
        return reports
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
        if (order.checkoutMode == CheckoutMode.LEGACY_RESERVED) {
            reports += "PICKUP" to
                requireApplied(
                    "PICKUP",
                    pickupOperations.release(order.id, now, OrderCreationTransaction.pickupSource(order.id)),
                )
        }
        if (order.couponDiscountKrw > 0) {
            if (order.checkoutMode == CheckoutMode.LEGACY_RESERVED) {
                reports += "COUPON" to
                    requireApplied(
                        "COUPON",
                        couponOperations.release(order.id, now, OrderCreationTransaction.couponSource(order.id)),
                    )
            }
        }
        if (order.pointsAppliedKrw > 0) {
            if (order.checkoutMode == CheckoutMode.LEGACY_RESERVED) {
                reports += "POINTS" to
                    requireApplied(
                        "POINTS",
                        pointOperations.release(order.id, now, OrderCreationTransaction.pointsSource(order.id)),
                    )
            }
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

    private fun lockOwnedForApproval(
        customerId: UUID,
        orderId: UUID,
        now: Instant,
    ): OrderEntity {
        val initial =
            orderRepository.findById(orderId).orElse(null)
                ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Order was not found")
        if (initial.customerId != customerId) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Order belongs to another customer")
        }
        if (initial.checkoutMode == CheckoutMode.IMMEDIATE && initial.state == OrderState.PENDING_PAYMENT) {
            val availability =
                try {
                    availabilityOperations.lockForOrderCommitment(initial.storeId, now)
                } catch (failure: DomainFailure) {
                    if (failure.code == FailureCode.DEPENDENCY_UNAVAILABLE) {
                        throw ImmediatePaymentCommitmentFailure(failure.code, failure.message, failure)
                    }
                    throw failure
                }
            if (!availability.available) {
                val code = availability.reason.toFailureCode()
                throw ImmediatePaymentCommitmentFailure(code, "Store is not available for immediate order confirmation")
            }
        }
        val locked = lockOwned(customerId, orderId)
        if (locked.checkoutMode == CheckoutMode.IMMEDIATE && locked.state == OrderState.PENDING_PAYMENT) {
            val cutoff =
                locked.orderingWindowClosesAt
                    ?: throw ImmediatePaymentCommitmentFailure(
                        FailureCode.SETTLEMENT_INPUT_UNAVAILABLE,
                        "Immediate order cutoff is missing",
                    )
            if (!now.isBefore(cutoff)) {
                throw ImmediatePaymentCommitmentFailure(FailureCode.STORE_CLOSED, "Store ordering window has closed")
            }
        }
        return locked
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
            orderReferenceProjection.resolve(orderId),
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
        immediate: Boolean = false,
    ) {
        val source = "payment:$paymentId:tx2"
        val correlationId = correlation(paymentId)
        val terminal = if (action == "PAYMENT_APPROVED") "CONFIRMED" else "RELEASED"
        val commands =
            mutableListOf(
                audit(
                    customerId,
                    AuditCategory.FINANCIAL_TRANSACTION,
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
                    AuditCategory.FINANCIAL_TRANSACTION,
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
                val immediateUseAction =
                    when (owner) {
                        "COUPON" -> "COUPON_USED_AT_APPROVAL"
                        "POINTS" -> "POINTS_USED_AT_APPROVAL"
                        else -> "${owner}_$terminal"
                    }
                commands +=
                    audit(
                        customerId,
                        if (immediate) AuditCategory.FINANCIAL_TRANSACTION else AuditCategory.ORDER_AND_FULFILLMENT,
                        if (immediate) immediateUseAction else "${owner}_$terminal",
                        "${owner}_RESERVATION",
                        targetId,
                        now,
                        source,
                        if (immediate) "AVAILABLE" else "RESERVED",
                        if (immediate) "USED" else terminal,
                        correlationId,
                    )
            }
        }
        auditOperations.appendAll(commands)
    }

    private fun StoreOrderAvailabilityReason.toFailureCode(): FailureCode =
        when (this) {
            StoreOrderAvailabilityReason.AVAILABLE -> FailureCode.DEPENDENCY_UNAVAILABLE
            StoreOrderAvailabilityReason.STORE_HOURS_NOT_CONFIGURED -> FailureCode.STORE_HOURS_NOT_CONFIGURED
            StoreOrderAvailabilityReason.STORE_CLOSED -> FailureCode.STORE_CLOSED
            StoreOrderAvailabilityReason.STORE_NOT_ACCEPTING_ORDERS -> FailureCode.STORE_NOT_ACCEPTING_ORDERS
        }

    private fun audit(
        customerId: UUID,
        category: AuditCategory,
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
        category = category,
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

    private companion object {
        val IMMEDIATE_COMMITMENT_FAILURES =
            setOf(
                FailureCode.COUPON_NOT_AVAILABLE,
                FailureCode.POINT_BALANCE_INSUFFICIENT,
                FailureCode.STORE_HOURS_NOT_CONFIGURED,
                FailureCode.STORE_CLOSED,
                FailureCode.STORE_NOT_ACCEPTING_ORDERS,
                FailureCode.SETTLEMENT_INPUT_UNAVAILABLE,
            )
    }
}

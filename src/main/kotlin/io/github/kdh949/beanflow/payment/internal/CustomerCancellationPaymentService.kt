package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.operations.api.DetectPaymentCancellationSetupIssueCommand
import io.github.kdh949.beanflow.operations.api.PaymentCancellationSetupIntegrityOperations
import io.github.kdh949.beanflow.operations.api.PaymentCancellationSetupInvariantViolation
import io.github.kdh949.beanflow.operations.api.PaymentCancellationSetupMissingArtifact
import io.github.kdh949.beanflow.payment.api.CustomerCancellationPaymentOperations
import io.github.kdh949.beanflow.payment.api.CustomerCancellationPaymentProjection
import io.github.kdh949.beanflow.payment.api.CustomerCancellationPaymentSnapshot
import io.github.kdh949.beanflow.payment.api.PrepareCustomerCancellationPaymentCommand
import io.github.kdh949.beanflow.payment.api.ProjectCustomerCancellationPaymentCommand
import io.github.kdh949.beanflow.payment.internal.domain.PaymentApprovalState
import io.github.kdh949.beanflow.payment.internal.domain.PaymentType
import io.github.kdh949.beanflow.payment.internal.domain.RefundClaimMode
import io.github.kdh949.beanflow.payment.internal.domain.RefundState
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class CustomerCancellationPaymentService(
    private val payments: PaymentJpaRepository,
    private val refunds: RefundJpaRepository,
    private val snapshots: CustomerCancellationPaymentSnapshotJpaRepository,
    private val setupIntegrity: PaymentCancellationSetupIntegrityOperations,
    private val identifiers: IdentifierSource,
    private val jdbcTemplate: JdbcTemplate,
    private val meterRegistry: MeterRegistry,
) : CustomerCancellationPaymentOperations {
    @Transactional(readOnly = true)
    override fun findSnapshot(orderId: UUID): CustomerCancellationPaymentSnapshot? =
        snapshots.findByOrderId(orderId)?.let { snapshot ->
            val payment =
                payments.findById(snapshot.paymentId).orElse(null)
                    ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Cancellation snapshot payment is missing")
            snapshot.toApi(payment.type)
        }

    @Transactional(readOnly = true)
    override fun project(command: ProjectCustomerCancellationPaymentCommand): CustomerCancellationPaymentProjection {
        val snapshot = snapshots.findByOrderId(command.orderId)
        if (!command.paymentExpected && snapshot == null) {
            return measured(CustomerCancellationPaymentProjection(state = "NOT_REQUIRED"))
        }
        if (snapshot == null) {
            return setupIncomplete(
                command,
                missing = setOf(PaymentCancellationSetupMissingArtifact.PAYMENT_RECOVERY_SNAPSHOT),
                errorCode = "MISSING_PAYMENT_RECOVERY_SNAPSHOT",
            )
        }
        val payment = payments.findById(snapshot.paymentId).orElse(null)
        if (payment == null) {
            return setupIncomplete(
                command,
                violations =
                    setOf(
                        PaymentCancellationSetupInvariantViolation.SOURCE_MISMATCH,
                        PaymentCancellationSetupInvariantViolation.AMOUNT_TIE_OUT_MISMATCH,
                    ),
                errorCode = "PAYMENT_SOURCE_MISSING",
            )
        }
        if (snapshot.orderId != command.orderId ||
            snapshot.cancellationOrderVersion != command.cancellationOrderVersion ||
            payment.orderId != command.orderId || payment.approvalState != PaymentApprovalState.APPROVED
        ) {
            return setupIncomplete(
                command,
                violations = setOf(PaymentCancellationSetupInvariantViolation.SOURCE_MISMATCH),
                errorCode = "CANCELLATION_SOURCE_MISMATCH",
            )
        }
        if (!snapshotAmountsTieOut(snapshot, payment)) {
            return setupIncomplete(
                command,
                violations = setOf(PaymentCancellationSetupInvariantViolation.AMOUNT_TIE_OUT_MISMATCH),
                errorCode = "CANCELLATION_AMOUNT_TIE_OUT_MISMATCH",
            )
        }
        if (snapshot.cancellationRequestedRefundAmountKrw == 0L) {
            if (snapshot.cancellationRefundId != null || snapshot.refundSourceReference != null ||
                snapshot.providerIdempotencyKey != null ||
                payment.succeededRefundAmountKrw != snapshot.succeededRefundAmountBeforeCancellationKrw
            ) {
                return setupIncomplete(
                    command,
                    violations = setOf(PaymentCancellationSetupInvariantViolation.SOURCE_MISMATCH),
                    errorCode = "ZERO_REFUND_SOURCE_MISMATCH",
                )
            }
            return measured(
                completeProjection(
                    state = "NOT_REQUIRED",
                    snapshot = snapshot,
                    payment = payment,
                    updatedAt = maxOf(snapshot.updatedAt, payment.updatedAt),
                ),
            )
        }
        val refundId = snapshot.cancellationRefundId
        val expectedSource = sourceReference(command.orderId, command.cancellationOrderVersion)
        if (refundId == null || snapshot.refundSourceReference != expectedSource ||
            snapshot.providerIdempotencyKey.isNullOrBlank()
        ) {
            return setupIncomplete(
                command,
                violations = setOf(PaymentCancellationSetupInvariantViolation.SOURCE_MISMATCH),
                errorCode = "CANCELLATION_REFUND_SOURCE_MISMATCH",
            )
        }
        val refund = refunds.findById(refundId).orElse(null)
        if (refund == null) {
            return setupIncomplete(
                command,
                missing = setOf(PaymentCancellationSetupMissingArtifact.CANCELLATION_REFUND),
                errorCode = "MISSING_CANCELLATION_REFUND",
                verifiedProjection =
                    completeProjection(
                        state = "PROCESSING",
                        snapshot = snapshot,
                        payment = payment,
                        noticeCode = "REFUND_DELAYED",
                        updatedAt = maxOf(snapshot.updatedAt, payment.updatedAt),
                    ),
            )
        }
        if (refund.reason != REASON || refund.orderId != command.orderId || refund.paymentId != payment.id ||
            refund.requestedAmountKrw != snapshot.cancellationRequestedRefundAmountKrw ||
            refund.sourceReference != expectedSource || refund.providerIdempotencyKey != snapshot.providerIdempotencyKey
        ) {
            return setupIncomplete(
                command,
                violations = setOf(PaymentCancellationSetupInvariantViolation.SOURCE_MISMATCH),
                errorCode = "CANCELLATION_REFUND_SOURCE_MISMATCH",
            )
        }
        val expectedSucceeded =
            if (refund.state == RefundState.SUCCEEDED) {
                Math.addExact(
                    snapshot.succeededRefundAmountBeforeCancellationKrw,
                    snapshot.cancellationRequestedRefundAmountKrw,
                )
            } else {
                snapshot.succeededRefundAmountBeforeCancellationKrw
            }
        if (payment.succeededRefundAmountKrw != expectedSucceeded ||
            (
                refund.state == RefundState.SUCCEEDED &&
                    refund.succeededAmountKrw != snapshot.cancellationRequestedRefundAmountKrw
            )
        ) {
            return setupIncomplete(
                command,
                violations = setOf(PaymentCancellationSetupInvariantViolation.AMOUNT_TIE_OUT_MISMATCH),
                errorCode = "CANCELLATION_AMOUNT_TIE_OUT_MISMATCH",
            )
        }
        val (state, notice) = customerState(refund.state)
        return measured(
            completeProjection(
                state = state,
                noticeCode = notice,
                snapshot = snapshot,
                payment = payment,
                updatedAt = maxOf(snapshot.updatedAt, payment.updatedAt, refund.updatedAt),
            ),
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun prepare(command: PrepareCustomerCancellationPaymentCommand): CustomerCancellationPaymentSnapshot {
        snapshots.findByOrderId(command.orderId)?.let { return replay(it, command) }
        val payment =
            payments.findLockedByOrderId(command.orderId)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Paid order payment is missing")
        val lockedRefunds = refunds.findAllLockedByPaymentId(payment.id)
        lockSuccessfulAllocations(payment.id)
        snapshots.findByOrderId(command.orderId)?.let { return replay(it, command) }

        if (payment.approvalState != PaymentApprovalState.APPROVED) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Paid order payment is not approved")
        }
        if (lockedRefunds.any { it.state in UNRESOLVED_REFUND_STATES }) {
            fail(FailureCode.PAYMENT_REFUND_UNRESOLVED, "Payment has an unresolved Refund")
        }

        val approvedAmount =
            payment.approvedAmountKrw
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Approved payment amount is missing")
        if (payment.succeededRefundAmountKrw !in 0..approvedAmount) {
            fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Payment refund total is inconsistent")
        }
        val requestedAmount = Math.subtractExact(approvedAmount, payment.succeededRefundAmountKrw)
        if (payment.type == PaymentType.BENEFIT_ONLY && requestedAmount != 0L) {
            fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Benefit-only payment has a refundable cash amount")
        }

        val refundId = if (requestedAmount > 0) identifiers.next() else null
        val source = sourceReference(command).takeIf { requestedAmount > 0 }
        val providerKey = providerIdempotencyKey(command).takeIf { requestedAmount > 0 }
        if (refundId != null) {
            refunds.save(
                RefundEntity(
                    id = refundId,
                    paymentId = payment.id,
                    orderId = command.orderId,
                    requestedAmountKrw = requestedAmount,
                    succeededAmountKrw = null,
                    reason = REASON,
                    customerReasonCode = command.customerReasonCode,
                    state = RefundState.REQUESTED,
                    providerRefundReference = null,
                    providerIdempotencyKey = requireNotNull(providerKey),
                    sourceReference = requireNotNull(source),
                    correlationId = command.correlationId,
                    attemptCount = 0,
                    requestAttemptCount = 0,
                    lookupAttemptCount = 0,
                    nextAction = RefundClaimMode.REQUEST,
                    nextAttemptAt = command.now,
                    createdAt = command.now,
                    updatedAt = command.now,
                ),
            )
        }
        val snapshot =
            snapshots.save(
                CustomerCancellationPaymentSnapshotEntity(
                    id = identifiers.next(),
                    paymentId = payment.id,
                    orderId = command.orderId,
                    cancellationOrderVersion = command.cancellationOrderVersion,
                    approvedAmountKrw = approvedAmount,
                    succeededRefundAmountBeforeCancellationKrw = payment.succeededRefundAmountKrw,
                    cancellationRequestedRefundAmountKrw = requestedAmount,
                    cancellationRefundId = refundId,
                    refundSourceReference = source,
                    providerIdempotencyKey = providerKey,
                    correlationId = command.correlationId,
                    createdAt = command.now,
                    updatedAt = command.now,
                ),
            )
        return snapshot.toApi(payment.type)
    }

    private fun replay(
        snapshot: CustomerCancellationPaymentSnapshotEntity,
        command: PrepareCustomerCancellationPaymentCommand,
    ): CustomerCancellationPaymentSnapshot {
        if (snapshot.cancellationOrderVersion != command.cancellationOrderVersion ||
            snapshot.correlationId != command.correlationId
        ) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Customer cancellation payment snapshot conflicts with the command")
        }
        val payment =
            payments.findById(snapshot.paymentId).orElse(null)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Cancellation snapshot payment is missing")
        return snapshot.toApi(payment.type)
    }

    private fun lockSuccessfulAllocations(paymentId: UUID) {
        jdbcTemplate.query(
            """
            SELECT allocation.id
              FROM payment_refund_line_allocation allocation
              JOIN payment_refund refund ON refund.id = allocation.refund_id
             WHERE refund.payment_id = ?
             ORDER BY allocation.order_line_id, allocation.id
             FOR UPDATE OF allocation
            """.trimIndent(),
            { _, _ -> Unit },
            paymentId,
        )
        jdbcTemplate.query(
            """
            SELECT allocation.id
              FROM payment_refund_point_allocation allocation
              JOIN payment_refund refund ON refund.id = allocation.refund_id
             WHERE refund.payment_id = ?
             ORDER BY allocation.point_reservation_allocation_id, allocation.id
             FOR UPDATE OF allocation
            """.trimIndent(),
            { _, _ -> Unit },
            paymentId,
        )
    }

    private fun sourceReference(command: PrepareCustomerCancellationPaymentCommand): String =
        "order:${command.orderId}:customer-cancellation:${command.cancellationOrderVersion}:payment"

    private fun sourceReference(
        orderId: UUID,
        cancellationOrderVersion: Long,
    ): String = "order:$orderId:customer-cancellation:$cancellationOrderVersion:payment"

    private fun snapshotAmountsTieOut(
        snapshot: CustomerCancellationPaymentSnapshotEntity,
        payment: PaymentEntity,
    ): Boolean =
        try {
            snapshot.approvedAmountKrw >= 0 &&
                snapshot.succeededRefundAmountBeforeCancellationKrw in 0..snapshot.approvedAmountKrw &&
                snapshot.cancellationRequestedRefundAmountKrw >= 0 &&
                snapshot.approvedAmountKrw == payment.approvedAmountKrw &&
                snapshot.cancellationRequestedRefundAmountKrw ==
                Math.subtractExact(
                    snapshot.approvedAmountKrw,
                    snapshot.succeededRefundAmountBeforeCancellationKrw,
                ) &&
                payment.succeededRefundAmountKrw in 0..snapshot.approvedAmountKrw
        } catch (_: ArithmeticException) {
            false
        }

    private fun completeProjection(
        state: String,
        snapshot: CustomerCancellationPaymentSnapshotEntity,
        payment: PaymentEntity,
        updatedAt: java.time.Instant,
        noticeCode: String? = null,
    ) = CustomerCancellationPaymentProjection(
        state = state,
        noticeCode = noticeCode,
        approvedAmountKrw = snapshot.approvedAmountKrw,
        succeededRefundAmountBeforeCancellationKrw = snapshot.succeededRefundAmountBeforeCancellationKrw,
        cancellationRequestedRefundAmountKrw = snapshot.cancellationRequestedRefundAmountKrw,
        remainingRefundableAmountKrw =
            Math.subtractExact(snapshot.approvedAmountKrw, payment.succeededRefundAmountKrw),
        lastUpdatedAt = updatedAt,
    )

    private fun customerState(state: RefundState): Pair<String, String?> =
        when (state) {
            RefundState.REQUESTED -> "REQUESTED" to null

            RefundState.PROCESSING,
            RefundState.RETRY_SCHEDULED,
            RefundState.UNKNOWN,
            RefundState.RECONCILING,
            -> "PROCESSING" to null

            RefundState.FAILED,
            RefundState.MANUAL_REVIEW,
            -> "PROCESSING" to "REFUND_DELAYED"

            RefundState.SUCCEEDED -> "SUCCEEDED" to null
        }

    private fun setupIncomplete(
        command: ProjectCustomerCancellationPaymentCommand,
        missing: Set<PaymentCancellationSetupMissingArtifact> = emptySet(),
        violations: Set<PaymentCancellationSetupInvariantViolation> = emptySet(),
        errorCode: String,
        verifiedProjection: CustomerCancellationPaymentProjection? = null,
    ): CustomerCancellationPaymentProjection {
        setupIntegrity.detect(
            DetectPaymentCancellationSetupIssueCommand(
                orderId = command.orderId,
                cancellationOrderVersion = command.cancellationOrderVersion,
                missingArtifacts = missing,
                invariantViolations = violations,
                errorCode = errorCode,
                correlationId = command.correlationId,
                now = command.now,
            ),
        )
        meterRegistry
            .counter(
                "beanflow.payment.cancellation.setup_incomplete.count",
                "reason",
                setupReasonTag(missing, violations),
            ).increment()
        return measured(
            verifiedProjection
                ?: CustomerCancellationPaymentProjection(
                    state = "PROCESSING",
                    noticeCode = "REFUND_DELAYED",
                ),
        )
    }

    private fun setupReasonTag(
        missing: Set<PaymentCancellationSetupMissingArtifact>,
        violations: Set<PaymentCancellationSetupInvariantViolation>,
    ): String =
        when {
            PaymentCancellationSetupMissingArtifact.PAYMENT_RECOVERY_SNAPSHOT in missing -> "missing_snapshot"
            PaymentCancellationSetupMissingArtifact.CANCELLATION_REFUND in missing -> "missing_refund"
            PaymentCancellationSetupInvariantViolation.SOURCE_MISMATCH in violations -> "source_mismatch"
            else -> "amount_tie_out_mismatch"
        }

    private fun measured(projection: CustomerCancellationPaymentProjection): CustomerCancellationPaymentProjection {
        meterRegistry
            .counter(
                "beanflow.payment.cancellation.customer_projection.count",
                "state",
                projection.state.lowercase(),
                "notice_code",
                projection.noticeCode?.lowercase() ?: "none",
            ).increment()
        return projection
    }

    private fun providerIdempotencyKey(command: PrepareCustomerCancellationPaymentCommand): String =
        "refund:customer-cancellation:${command.orderId}:${command.cancellationOrderVersion}"

    private fun CustomerCancellationPaymentSnapshotEntity.toApi(type: PaymentType) =
        CustomerCancellationPaymentSnapshot(
            snapshotId = id,
            paymentId = paymentId,
            paymentType = type.name,
            approvedAmountKrw = approvedAmountKrw,
            succeededRefundAmountBeforeCancellationKrw = succeededRefundAmountBeforeCancellationKrw,
            requestedRefundAmountKrw = cancellationRequestedRefundAmountKrw,
            refundId = cancellationRefundId,
            refundSourceReference = refundSourceReference,
            providerIdempotencyKey = providerIdempotencyKey,
            updatedAt = updatedAt,
        )

    private fun fail(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)

    private companion object {
        const val REASON = "CUSTOMER_ORDER_CANCELLED"
        val UNRESOLVED_REFUND_STATES =
            setOf(
                RefundState.REQUESTED,
                RefundState.PROCESSING,
                RefundState.RETRY_SCHEDULED,
                RefundState.UNKNOWN,
                RefundState.RECONCILING,
                RefundState.MANUAL_REVIEW,
            )
    }
}

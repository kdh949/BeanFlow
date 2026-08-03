package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.operations.api.CustomerCancellationMissingRefundRepairOperations
import io.github.kdh949.beanflow.operations.api.CustomerCancellationMissingRefundRepairSnapshot
import io.github.kdh949.beanflow.operations.api.InspectCustomerCancellationMissingRefundCommand
import io.github.kdh949.beanflow.operations.api.OrderCompensationOperations
import io.github.kdh949.beanflow.operations.api.RecreateCustomerCancellationMissingRefundCommand
import io.github.kdh949.beanflow.operations.api.RecreateCustomerCancellationMissingRefundResult
import io.github.kdh949.beanflow.payment.internal.domain.PaymentApprovalState
import io.github.kdh949.beanflow.payment.internal.domain.PaymentType
import io.github.kdh949.beanflow.payment.internal.domain.RefundClaimMode
import io.github.kdh949.beanflow.payment.internal.domain.RefundState
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Service
internal class CustomerCancellationMissingRefundRepairService(
    private val payments: PaymentJpaRepository,
    private val refunds: RefundJpaRepository,
    private val snapshots: CustomerCancellationPaymentSnapshotJpaRepository,
    private val compensation: OrderCompensationOperations,
) : CustomerCancellationMissingRefundRepairOperations {
    @Transactional(readOnly = true)
    override fun inspect(command: InspectCustomerCancellationMissingRefundCommand): CustomerCancellationMissingRefundRepairSnapshot {
        val snapshot = snapshots.findByOrderId(command.orderId) ?: notSafe("Payment recovery snapshot is missing")
        val payment = payments.findById(snapshot.paymentId).orElse(null) ?: notSafe("Payment is missing")
        validateSafeSnapshot(snapshot, payment, command.orderId, command.cancellationOrderVersion)?.let(::notSafe)
        validateRefundAbsence(snapshot, payment)?.let(::notSafe)
        return repairSnapshot(snapshot)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun recreateForLookup(
        command: RecreateCustomerCancellationMissingRefundCommand,
    ): RecreateCustomerCancellationMissingRefundResult {
        val expected = command.expected
        val payment = payments.findLockedById(expected.paymentId) ?: return stale("PAYMENT_MISSING")
        val lockedRefunds = refunds.findAllLockedByPaymentId(payment.id)
        val snapshot = snapshots.findLockedById(expected.snapshotId) ?: return stale("SNAPSHOT_MISSING")
        validateSafeSnapshot(snapshot, payment, expected.orderId, expected.cancellationOrderVersion)?.let {
            return stale(normalized(it))
        }
        if (snapshot.version != expected.snapshotVersion || repairSnapshot(snapshot) != expected) {
            return stale("SNAPSHOT_FINGERPRINT_CHANGED")
        }
        if (refunds.existsById(expected.refundId) ||
            refunds.findBySourceReference(requireNotNull(snapshot.refundSourceReference)) != null ||
            refunds.findByProviderIdempotencyKey(requireNotNull(snapshot.providerIdempotencyKey)) != null
        ) {
            return stale("REFUND_SOURCE_OCCUPIED")
        }
        if (
            lockedRefunds.any {
                it.id == expected.refundId ||
                    it.sourceReference == snapshot.refundSourceReference ||
                    it.providerIdempotencyKey == snapshot.providerIdempotencyKey
            }
        ) {
            return stale("REFUND_SOURCE_OCCUPIED")
        }
        if (lockedRefunds.any { it.state in UNRESOLVED_STATES }) {
            return stale("OTHER_UNRESOLVED_REFUND")
        }
        refunds.saveAndFlush(
            RefundEntity(
                id = expected.refundId,
                paymentId = payment.id,
                orderId = expected.orderId,
                requestedAmountKrw = expected.requestedAmountKrw,
                reason = REASON,
                state = RefundState.RECONCILING,
                providerIdempotencyKey = requireNotNull(snapshot.providerIdempotencyKey),
                sourceReference = requireNotNull(snapshot.refundSourceReference),
                correlationId = snapshot.correlationId,
                attemptCount = 0,
                requestAttemptCount = 0,
                lookupAttemptCount = 0,
                nextAction = RefundClaimMode.LOOKUP,
                nextAttemptAt = null,
                providerRequestStartedAt = null,
                claimToken = command.proposalId,
                claimUntil = command.now,
                lastFailureCode = "MISSING_REFUND_RECREATED_LOOKUP_REQUIRED",
                createdAt = command.now,
                updatedAt = command.now,
            ),
        )
        compensation.reopenPaymentForSetupRepair(
            expected.orderId,
            expected.cancellationOrderVersion,
            "MISSING_REFUND_RECREATED_LOOKUP_REQUIRED",
            command.now,
        )
        return RecreateCustomerCancellationMissingRefundResult.Succeeded(expected.refundId)
    }

    private fun validateSafeSnapshot(
        snapshot: CustomerCancellationPaymentSnapshotEntity,
        payment: PaymentEntity,
        orderId: java.util.UUID,
        orderVersion: Long,
    ): String? {
        if (snapshot.orderId != orderId || snapshot.cancellationOrderVersion != orderVersion ||
            snapshot.paymentId != payment.id || payment.orderId != orderId
        ) {
            return "SOURCE_MISMATCH"
        }
        if (payment.type != PaymentType.EXTERNAL || payment.approvalState != PaymentApprovalState.APPROVED ||
            payment.approvedAmountKrw != snapshot.approvedAmountKrw ||
            snapshot.cancellationRequestedRefundAmountKrw <= 0 ||
            snapshot.cancellationRefundId == null || snapshot.refundSourceReference.isNullOrBlank() ||
            snapshot.providerIdempotencyKey.isNullOrBlank() ||
            snapshot.refundSourceReference != "order:$orderId:customer-cancellation:$orderVersion:payment" ||
            snapshot.providerIdempotencyKey != "refund:customer-cancellation:$orderId:$orderVersion"
        ) {
            return "SOURCE_MISMATCH"
        }
        val tiesOut =
            try {
                snapshot.approvedAmountKrw ==
                    Math.addExact(
                        snapshot.succeededRefundAmountBeforeCancellationKrw,
                        snapshot.cancellationRequestedRefundAmountKrw,
                    ) &&
                    payment.succeededRefundAmountKrw == snapshot.succeededRefundAmountBeforeCancellationKrw
            } catch (_: ArithmeticException) {
                false
            }
        return if (tiesOut) null else "AMOUNT_TIE_OUT_MISMATCH"
    }

    private fun validateRefundAbsence(
        snapshot: CustomerCancellationPaymentSnapshotEntity,
        payment: PaymentEntity,
    ): String? {
        if (refunds.existsById(requireNotNull(snapshot.cancellationRefundId)) ||
            refunds.findBySourceReference(requireNotNull(snapshot.refundSourceReference)) != null ||
            refunds.findByProviderIdempotencyKey(requireNotNull(snapshot.providerIdempotencyKey)) != null
        ) {
            return "REFUND_SOURCE_OCCUPIED"
        }
        return if (refunds.findUnresolvedByPaymentId(payment.id).isEmpty()) null else "OTHER_UNRESOLVED_REFUND"
    }

    private fun repairSnapshot(snapshot: CustomerCancellationPaymentSnapshotEntity) =
        CustomerCancellationMissingRefundRepairSnapshot(
            orderId = snapshot.orderId,
            cancellationOrderVersion = snapshot.cancellationOrderVersion,
            paymentId = snapshot.paymentId,
            snapshotId = snapshot.id,
            snapshotVersion = snapshot.version,
            refundId = requireNotNull(snapshot.cancellationRefundId),
            requestedAmountKrw = snapshot.cancellationRequestedRefundAmountKrw,
            refundSourceFingerprint = fingerprint(requireNotNull(snapshot.refundSourceReference)),
            providerKeyFingerprint = fingerprint(requireNotNull(snapshot.providerIdempotencyKey)),
        )

    private fun fingerprint(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun normalized(value: String): String =
        value
            .uppercase()
            .replace(Regex("[^A-Z0-9_]+"), "_")
            .take(80)
            .ifBlank { "UNKNOWN" }

    private fun stale(code: String) = RecreateCustomerCancellationMissingRefundResult.Stale(code)

    private fun notSafe(message: String): Nothing = throw DomainFailure(FailureCode.REPROCESSING_NOT_SAFE, message)

    private companion object {
        const val REASON = "CUSTOMER_ORDER_CANCELLED"
        val UNRESOLVED_STATES =
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

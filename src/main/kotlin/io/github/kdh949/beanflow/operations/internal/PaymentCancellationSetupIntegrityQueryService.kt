package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.PaymentCancellationSetupInvariantViolation
import io.github.kdh949.beanflow.operations.api.PaymentCancellationSetupMissingArtifact
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

internal data class PaymentCancellationSetupAssessment(
    val orderId: UUID,
    val cancellationOrderVersion: Long,
    val missingArtifacts: Set<PaymentCancellationSetupMissingArtifact>,
    val invariantViolations: Set<PaymentCancellationSetupInvariantViolation>,
    val errorCode: String,
    val correlationId: String,
)

internal data class PaymentCancellationSetupScanKey(
    val orderId: UUID,
    val cancellationOrderVersion: Long,
)

@Service
internal class PaymentCancellationSetupIntegrityQueryService(
    private val jdbcTemplate: JdbcTemplate,
) {
    @Transactional(readOnly = true)
    fun assess(
        orderId: UUID,
        cancellationOrderVersion: Long,
    ): PaymentCancellationSetupAssessment? =
        jdbcTemplate
            .query(
                ASSESSMENT_SQL,
                { rs, _ ->
                    val missing =
                        buildSet {
                            if (rs.getBoolean("snapshot_missing")) {
                                add(PaymentCancellationSetupMissingArtifact.PAYMENT_RECOVERY_SNAPSHOT)
                            }
                            if (rs.getBoolean("refund_missing")) {
                                add(PaymentCancellationSetupMissingArtifact.CANCELLATION_REFUND)
                            }
                        }
                    val violations =
                        buildSet {
                            if (rs.getBoolean("source_mismatch")) {
                                add(PaymentCancellationSetupInvariantViolation.SOURCE_MISMATCH)
                            }
                            if (rs.getBoolean("amount_mismatch")) {
                                add(PaymentCancellationSetupInvariantViolation.AMOUNT_TIE_OUT_MISMATCH)
                            }
                        }
                    if (missing.isEmpty() && violations.isEmpty()) {
                        null
                    } else {
                        PaymentCancellationSetupAssessment(
                            orderId = orderId,
                            cancellationOrderVersion = cancellationOrderVersion,
                            missingArtifacts = missing,
                            invariantViolations = violations,
                            errorCode = errorCode(missing, violations),
                            correlationId = rs.getString("correlation_id"),
                        )
                    }
                },
                orderId,
                cancellationOrderVersion,
                orderId,
                cancellationOrderVersion,
            ).singleOrNull()

    @Transactional(readOnly = true)
    fun findScanKeys(limit: Int): List<PaymentCancellationSetupScanKey> {
        require(limit in 1..100)
        return jdbcTemplate.query(
            SCAN_SQL,
            { rs, _ ->
                PaymentCancellationSetupScanKey(
                    orderId = rs.getObject("order_id", UUID::class.java),
                    cancellationOrderVersion = rs.getLong("cancellation_order_version"),
                )
            },
            limit,
        )
    }

    private fun errorCode(
        missing: Set<PaymentCancellationSetupMissingArtifact>,
        violations: Set<PaymentCancellationSetupInvariantViolation>,
    ): String =
        when {
            PaymentCancellationSetupMissingArtifact.PAYMENT_RECOVERY_SNAPSHOT in missing -> {
                "MISSING_PAYMENT_RECOVERY_SNAPSHOT"
            }

            PaymentCancellationSetupMissingArtifact.CANCELLATION_REFUND in missing -> {
                "MISSING_CANCELLATION_REFUND"
            }

            PaymentCancellationSetupInvariantViolation.SOURCE_MISMATCH in violations -> {
                "CANCELLATION_SOURCE_MISMATCH"
            }

            else -> {
                "CANCELLATION_AMOUNT_TIE_OUT_MISMATCH"
            }
        }

    private companion object {
        val ASSESSMENT_SQL =
            """
            SELECT COALESCE(snapshot.correlation_id, payment.correlation_id, 'setup-integrity:' || customer_order.id) AS correlation_id,
                   snapshot.id IS NULL AS snapshot_missing,
                   snapshot.id IS NOT NULL
                       AND snapshot.cancellation_requested_refund_amount_krw > 0
                       AND refund.id IS NULL AS refund_missing,
                   CASE
                       WHEN snapshot.id IS NULL THEN false
                       ELSE payment.id IS NULL
                         OR snapshot.order_id <> ?
                         OR snapshot.cancellation_order_version <> ?
                         OR snapshot.payment_id <> payment.id
                         OR (snapshot.cancellation_requested_refund_amount_krw = 0 AND (
                                snapshot.cancellation_refund_id IS NOT NULL
                                OR snapshot.refund_source_reference IS NOT NULL
                                OR snapshot.provider_idempotency_key IS NOT NULL))
                         OR (snapshot.cancellation_requested_refund_amount_krw > 0 AND (
                                snapshot.cancellation_refund_id IS NULL
                                OR snapshot.refund_source_reference IS NULL
                                OR snapshot.provider_idempotency_key IS NULL))
                         OR (refund.id IS NOT NULL AND (
                                refund.order_id <> customer_order.id
                                OR refund.payment_id <> payment.id
                                OR refund.reason <> 'CUSTOMER_ORDER_CANCELLED'
                                OR refund.requested_amount_krw <> snapshot.cancellation_requested_refund_amount_krw
                                OR refund.source_reference <> snapshot.refund_source_reference
                                OR refund.provider_idempotency_key <> snapshot.provider_idempotency_key))
                   END AS source_mismatch,
                   CASE
                       WHEN snapshot.id IS NULL THEN false
                       ELSE payment.id IS NULL
                         OR snapshot.approved_amount_krw < 0
                         OR snapshot.succeeded_refund_amount_before_cancellation_krw < 0
                         OR snapshot.cancellation_requested_refund_amount_krw < 0
                         OR snapshot.approved_amount_krw < snapshot.succeeded_refund_amount_before_cancellation_krw
                         OR snapshot.approved_amount_krw < snapshot.cancellation_requested_refund_amount_krw
                         OR snapshot.approved_amount_krw <>
                            snapshot.succeeded_refund_amount_before_cancellation_krw
                            + snapshot.cancellation_requested_refund_amount_krw
                         OR payment.approved_amount_krw <> snapshot.approved_amount_krw
                         OR payment.succeeded_refund_amount_krw < 0
                         OR payment.succeeded_refund_amount_krw > snapshot.approved_amount_krw
                         OR (refund.id IS NULL
                             AND payment.succeeded_refund_amount_krw <>
                                 snapshot.succeeded_refund_amount_before_cancellation_krw)
                         OR (refund.id IS NOT NULL AND refund.state = 'SUCCEEDED' AND (
                                refund.succeeded_amount_krw <> snapshot.cancellation_requested_refund_amount_krw
                                OR payment.succeeded_refund_amount_krw <> snapshot.approved_amount_krw))
                         OR (refund.id IS NOT NULL AND refund.state <> 'SUCCEEDED'
                             AND payment.succeeded_refund_amount_krw <>
                                 snapshot.succeeded_refund_amount_before_cancellation_krw)
                   END AS amount_mismatch
              FROM ordering_order customer_order
              LEFT JOIN payment_payment payment ON payment.order_id = customer_order.id
              LEFT JOIN payment_cancellation_recovery_snapshot snapshot ON snapshot.order_id = customer_order.id
              LEFT JOIN payment_refund refund ON refund.id = snapshot.cancellation_refund_id
             WHERE customer_order.id = ?
               AND customer_order.state = 'CANCELLED'
               AND customer_order.cancellation_cause = 'CUSTOMER_REQUEST'
               AND customer_order.version = ?
            """.trimIndent()

        val SCAN_SQL =
            """
            SELECT customer_order.id AS order_id,
                   customer_order.version AS cancellation_order_version
              FROM ordering_order customer_order
              JOIN payment_payment payment ON payment.order_id = customer_order.id
             WHERE customer_order.state = 'CANCELLED'
               AND customer_order.cancellation_cause = 'CUSTOMER_REQUEST'
               AND payment.type = 'EXTERNAL'
               AND payment.approval_state = 'APPROVED'
               AND NOT EXISTS (
                    SELECT 1
                      FROM operations_reprocessing_case reprocessing_case
                     WHERE reprocessing_case.case_type = 'PAYMENT_CANCELLATION_SETUP'
                       AND reprocessing_case.owner_reference =
                           'order:' || customer_order.id || ':customer-cancellation:' ||
                           customer_order.version || ':payment-setup'
               )
             ORDER BY customer_order.cancelled_at, customer_order.id
             LIMIT ?
            """.trimIndent()
    }
}

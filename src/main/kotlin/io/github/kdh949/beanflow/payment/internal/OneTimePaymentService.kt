package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.payment.api.ClaimOneTimePaymentConfirmationCommand
import io.github.kdh949.beanflow.payment.api.ExternalPaymentView
import io.github.kdh949.beanflow.payment.api.OneTimePaymentAmount
import io.github.kdh949.beanflow.payment.api.OneTimePaymentAttemptView
import io.github.kdh949.beanflow.payment.api.OneTimePaymentConfirmationClaim
import io.github.kdh949.beanflow.payment.api.OneTimePaymentConfirmationClaimState
import io.github.kdh949.beanflow.payment.api.OneTimePaymentOperations
import io.github.kdh949.beanflow.payment.api.PrepareOneTimePaymentCommand
import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import io.github.kdh949.beanflow.payment.internal.domain.PaymentApprovalState
import io.github.kdh949.beanflow.payment.internal.domain.PaymentType
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Duration
import java.util.Base64
import java.util.UUID

@Service
internal class OneTimePaymentService(
    private val payments: PaymentJpaRepository,
    private val attempts: OneTimePaymentAttemptJpaRepository,
    private val idempotency: PaymentIdempotencyJpaRepository,
    private val reconciliation: PaymentReconciliationJpaRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val identifierSource: IdentifierSource,
    private val gateway: PaymentGateway,
) : OneTimePaymentOperations {
    private val secureRandom = SecureRandom()

    @Transactional(propagation = Propagation.MANDATORY)
    override fun existing(command: PrepareOneTimePaymentCommand): OneTimePaymentAttemptView? {
        validatePrepare(command)
        val record =
            idempotency.findByActorIdAndOperationAndIdempotencyKey(
                command.actorId,
                PREPARE_OPERATION,
                command.idempotencyKey,
            ) ?: return null
        return replay(record, command)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun prepare(command: PrepareOneTimePaymentCommand): OneTimePaymentAttemptView {
        validatePrepare(command)
        val paymentId = identifierSource.next()
        val inserted =
            jdbcTemplate.update(
                """
                INSERT INTO payment_idempotency_record (
                    id, actor_id, operation, idempotency_key, payload_hash, payment_id,
                    order_id, status, started_at, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PROCESSING', ?, 0)
                ON CONFLICT (actor_id, operation, idempotency_key) DO NOTHING
                """.trimIndent(),
                identifierSource.next(),
                command.actorId,
                PREPARE_OPERATION,
                command.idempotencyKey,
                command.payloadHash,
                paymentId,
                command.orderId,
                Timestamp.from(command.now),
            )
        if (inserted == 0) {
            val record =
                idempotency.findByActorIdAndOperationAndIdempotencyKey(
                    command.actorId,
                    PREPARE_OPERATION,
                    command.idempotencyKey,
                ) ?: dependency("One-time payment idempotency record is missing")
            return replay(record, command)
        }
        if (payments.findByOrderId(command.orderId) != null) {
            conflict(FailureCode.ORDER_STATE_CONFLICT, "Order already has a payment")
        }

        val providerOrderId = "bf_${paymentId.toString().replace("-", "")}"
        val callbackBase = normalizedCallbackBase(command.callbackBaseUrl)
        val payment =
            PaymentEntity(
                id = paymentId,
                orderId = command.orderId,
                customerId = command.actorId,
                paymentMethodId = null,
                type = PaymentType.EXTERNAL,
                approvalState = PaymentApprovalState.READY,
                requestedAmountKrw = command.requestedAmountKrw,
                approvedAmountKrw = null,
                currency = "KRW",
                benefitSnapshotReference = null,
                sourceReference = "payment:$paymentId:one-time",
                correlationId = command.correlationId,
                approvedAt = null,
                createdAt = command.now,
                updatedAt = command.now,
            )
        payments.saveAndFlush(payment)
        val attempt =
            attempts.saveAndFlush(
                OneTimePaymentAttemptEntity(
                    paymentId = paymentId,
                    providerOrderId = providerOrderId,
                    customerKey = customerKey(),
                    orderName = command.orderName,
                    amountKrw = command.requestedAmountKrw,
                    currency = "KRW",
                    state = OneTimePaymentAttemptState.READY,
                    providerIdempotencyKey = identifierSource.next().toString(),
                    successUrl = "$callbackBase/app/payments/$paymentId/success",
                    failUrl = "$callbackBase/app/payments/$paymentId/fail",
                    expiresAt = command.expiresAt,
                    createdAt = command.now,
                    updatedAt = command.now,
                ),
            )
        reconciliation.save(
            PaymentReconciliationEntity(
                id = identifierSource.next(),
                paymentId = paymentId,
                kind = ReconciliationKind.APPROVAL_LOOKUP,
                status = ReconciliationStatus.WAITING,
                attemptCount = 0,
                nextAttemptAt = command.expiresAt,
                sourceReference = "payment:$paymentId:approval-lookup",
                createdAt = command.now,
                updatedAt = command.now,
            ),
        )
        return attempt.toPrepareView(payment)
    }

    @Transactional
    override fun claimConfirmation(command: ClaimOneTimePaymentConfirmationCommand): OneTimePaymentConfirmationClaim {
        val payment =
            payments.findLockedById(command.paymentId)
                ?: conflict(FailureCode.RESOURCE_NOT_FOUND, "Payment was not found")
        if (payment.customerId != command.actorId) {
            conflict(FailureCode.ACCESS_DENIED, "Payment belongs to another customer")
        }
        if (payment.type != PaymentType.EXTERNAL || payment.paymentMethodId != null) {
            conflict(FailureCode.ORDER_STATE_CONFLICT, "Payment is not a one-time checkout")
        }
        val attempt =
            attempts.findLockedByPaymentId(command.paymentId)
                ?: dependency("One-time payment attempt is missing")
        val payloadHash = callbackHash(command)
        if (attempt.callbackPayloadHash != null) {
            if (attempt.callbackPayloadHash != payloadHash || attempt.paymentKey != command.paymentKey) {
                callbackMismatch()
            }
            return OneTimePaymentConfirmationClaim(
                OneTimePaymentConfirmationClaimState.CURRENT,
                payment.toExternalView(reconciliation.findByPaymentIdAndKind(payment.id, ReconciliationKind.APPROVAL_LOOKUP)),
            )
        }
        if (
            attempt.state != OneTimePaymentAttemptState.READY ||
            payment.approvalState != PaymentApprovalState.READY ||
            command.providerOrderId != attempt.providerOrderId ||
            command.amountKrw != attempt.amountKrw ||
            command.paymentKey.isBlank() ||
            command.paymentKey.length > 200
        ) {
            callbackMismatch()
        }

        attempt.claim(command.paymentKey, payloadHash, identifierSource.next(), command.now)
        payment.approvalState = PaymentApprovalState.APPROVING
        payment.updatedAt = command.now
        val work =
            reconciliation.findByPaymentIdAndKind(payment.id, ReconciliationKind.APPROVAL_LOOKUP)
                ?: dependency("One-time payment reconciliation is missing")
        if (work.status != ReconciliationStatus.WAITING) {
            conflict(FailureCode.ORDER_STATE_CONFLICT, "Payment confirmation work is not waiting")
        }
        work.status = ReconciliationStatus.SCHEDULED
        work.nextAttemptAt = command.now.plus(CONFIRM_LOOKUP_DELAY)
        work.updatedAt = command.now
        return OneTimePaymentConfirmationClaim(
            OneTimePaymentConfirmationClaimState.ACQUIRED,
            payment.toExternalView(work),
        )
    }

    override fun requestProviderConfirmation(paymentId: UUID): ProviderPaymentResult {
        val payment =
            payments.findById(paymentId).orElse(null)
                ?: conflict(FailureCode.RESOURCE_NOT_FOUND, "Payment was not found")
        val attempt =
            attempts.findById(paymentId).orElse(null)
                ?: dependency("One-time payment attempt is missing")
        if (
            payment.approvalState != PaymentApprovalState.APPROVING ||
            attempt.state != OneTimePaymentAttemptState.CONFIRMING ||
            attempt.paymentKey.isNullOrBlank()
        ) {
            conflict(FailureCode.ORDER_STATE_CONFLICT, "Payment is not awaiting one-time confirmation")
        }
        return gateway.confirmOneTime(
            GatewayOneTimeConfirmationRequest(
                paymentId = payment.id,
                provider = PROVIDER,
                providerOrderId = attempt.providerOrderId,
                paymentKey = requireNotNull(attempt.paymentKey),
                amountKrw = attempt.amountKrw,
                currency = attempt.currency,
                providerIdempotencyKey = attempt.providerIdempotencyKey,
            ),
        )
    }

    @Transactional(readOnly = true)
    override fun current(
        actorId: UUID,
        paymentId: UUID,
    ): ExternalPaymentView {
        val payment =
            payments.findById(paymentId).orElse(null)
                ?: conflict(FailureCode.RESOURCE_NOT_FOUND, "Payment was not found")
        if (payment.customerId != actorId) {
            conflict(FailureCode.ACCESS_DENIED, "Payment belongs to another customer")
        }
        if (!attempts.existsById(paymentId)) {
            conflict(FailureCode.ORDER_STATE_CONFLICT, "Payment is not a one-time checkout")
        }
        return payment.toExternalView(reconciliation.findByPaymentIdAndKind(paymentId, ReconciliationKind.APPROVAL_LOOKUP))
    }

    private fun replay(
        record: PaymentIdempotencyEntity,
        command: PrepareOneTimePaymentCommand,
    ): OneTimePaymentAttemptView {
        if (record.payloadHash != command.payloadHash || record.orderId != command.orderId) {
            conflict(FailureCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was reused with another order")
        }
        val payment =
            payments.findById(record.paymentId).orElse(null)
                ?: dependency("One-time payment is missing")
        val attempt =
            attempts.findById(record.paymentId).orElse(null)
                ?: dependency("One-time payment attempt is missing")
        return attempt.toPrepareView(payment)
    }

    private fun OneTimePaymentAttemptEntity.toPrepareView(payment: PaymentEntity) =
        OneTimePaymentAttemptView(
            paymentId = paymentId,
            orderId = payment.orderId,
            state = state.name,
            providerOrderId = providerOrderId,
            customerKey = customerKey,
            orderName = orderName,
            amount = OneTimePaymentAmount(amountKrw, currency),
            method = "CARD",
            successUrl = successUrl,
            failUrl = failUrl,
            expiresAt = expiresAt,
            updatedAt = updatedAt,
            correlationId = payment.correlationId,
        )

    private fun PaymentEntity.toExternalView(work: PaymentReconciliationEntity?) =
        ExternalPaymentView(
            paymentId = id,
            orderId = orderId,
            type = type.name,
            approvalState = approvalState.name,
            approvedAmountKrw = approvedAmountKrw,
            currency = currency,
            recoveryState =
                when (work?.status) {
                    null, ReconciliationStatus.WAITING, ReconciliationStatus.SUCCEEDED -> "NOT_REQUIRED"
                    ReconciliationStatus.SCHEDULED -> "REQUESTED"
                    ReconciliationStatus.RETRY_SCHEDULED -> "RECONCILING"
                    else -> work.status.name
                },
            updatedAt = updatedAt,
            correlationId = correlationId,
        )

    private fun validatePrepare(command: PrepareOneTimePaymentCommand) {
        if (command.idempotencyKey.length !in 8..128 || command.payloadHash.length != 64) {
            conflict(FailureCode.INVALID_REQUEST, "Payment idempotency input is invalid")
        }
        if (
            command.requestedAmountKrw <= 0 ||
            command.orderName.isBlank() ||
            command.orderName.length > 100 ||
            !command.expiresAt.isAfter(command.now) ||
            command.correlationId.isBlank()
        ) {
            conflict(FailureCode.INVALID_REQUEST, "One-time payment prepare input is invalid")
        }
        normalizedCallbackBase(command.callbackBaseUrl)
    }

    private fun normalizedCallbackBase(value: String): String {
        val uri =
            try {
                URI(value.trim().removeSuffix("/"))
            } catch (_: IllegalArgumentException) {
                conflict(FailureCode.INVALID_REQUEST, "Checkout callback base URL is invalid")
            }
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.query != null || uri.fragment != null) {
            conflict(FailureCode.INVALID_REQUEST, "Checkout callback base URL is invalid")
        }
        if (uri.scheme == "http" && uri.host !in setOf("localhost", "127.0.0.1")) {
            conflict(FailureCode.INVALID_REQUEST, "Checkout callback base URL must use HTTPS")
        }
        return uri.toString().removeSuffix("/")
    }

    private fun customerKey(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return "bf_${Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)}"
    }

    private fun callbackHash(command: ClaimOneTimePaymentConfirmationCommand): String =
        sha256("${command.paymentId}:${command.providerOrderId}:${command.amountKrw}:${command.paymentKey}")

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun callbackMismatch(): Nothing =
        conflict(FailureCode.PAYMENT_CALLBACK_MISMATCH, "Payment callback does not match the prepared attempt")

    private fun dependency(message: String): Nothing = conflict(FailureCode.DEPENDENCY_UNAVAILABLE, message)

    private fun conflict(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)

    private companion object {
        const val PREPARE_OPERATION = "PREPARE_ONE_TIME_PAYMENT_V1"
        const val PROVIDER = "TOSS_PAYMENTS"
        val CONFIRM_LOOKUP_DELAY: Duration = Duration.ofSeconds(10)
    }
}

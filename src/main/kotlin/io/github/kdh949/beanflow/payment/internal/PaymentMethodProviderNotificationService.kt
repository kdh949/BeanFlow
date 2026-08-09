package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.payment.api.PaymentMethodProviderNotificationOperations
import io.github.kdh949.beanflow.payment.api.PaymentMethodProviderNotificationResult
import io.github.kdh949.beanflow.payment.api.VerifiedPaymentMethodProviderNotification
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.UUID

@Service
internal class PaymentMethodProviderNotificationService(
    private val transactions: PaymentMethodProviderNotificationTransactions,
) : PaymentMethodProviderNotificationOperations {
    override fun accept(notification: VerifiedPaymentMethodProviderNotification): PaymentMethodProviderNotificationResult {
        validate(notification)
        val recorded = transactions.record(notification)
        if (recorded is NotificationPreparation.Respond) return recorded.result
        return transactions.apply(
            inboxId = (recorded as NotificationPreparation.Process).inboxId,
            rawTokenReference = notification.tokenReference,
        )
    }

    private fun validate(notification: VerifiedPaymentMethodProviderNotification) {
        if (
            notification.provider != PROVIDER ||
            notification.notificationType != TYPE ||
            notification.notificationId.isBlank() || notification.notificationId.length > 200 ||
            notification.tokenReference.isBlank() || notification.tokenReference.length > 200
        ) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Verified payment method notification is invalid")
        }
    }

    private companion object {
        const val PROVIDER = "TOSS_PAYMENTS"
        const val TYPE = "BILLING_DELETED"
    }
}

internal sealed interface NotificationPreparation {
    data class Respond(
        val result: PaymentMethodProviderNotificationResult,
    ) : NotificationPreparation

    data class Process(
        val inboxId: UUID,
    ) : NotificationPreparation
}

@Service
internal class PaymentMethodProviderNotificationTransactions(
    private val inboxes: PaymentProviderNotificationInboxJpaRepository,
    private val methods: PaymentMethodJpaRepository,
    private val deactivations: PaymentMethodDeactivationJpaRepository,
    private val identifiers: IdentifierSource,
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
    private val metrics: PaymentMethodLifecycleMetrics,
    private val audits: PaymentMethodAuditWriter,
) {
    @Transactional
    fun record(notification: VerifiedPaymentMethodProviderNotification): NotificationPreparation {
        jdbcTemplate.query(
            "select pg_advisory_xact_lock(hashtextextended(?, 0))",
            { _, _ -> Unit },
            "payment-method-notification:${notification.provider}:${notification.notificationId}",
        )
        val fingerprint = sha256(notification.tokenReference)
        val existing = inboxes.findByProviderAndNotificationId(notification.provider, notification.notificationId)
        if (existing != null) {
            if (existing.notificationType != notification.notificationType || existing.tokenFingerprint != fingerprint) {
                if (existing.status == PaymentProviderNotificationStatus.ACCEPTED) {
                    existing.status = PaymentProviderNotificationStatus.MANUAL_REVIEW
                    existing.processedAt = clock.instant()
                    existing.closedReason = "NOTIFICATION_ID_CONFLICT"
                }
                metrics.notification(notification.notificationType, "ID_CONFLICT")
                return NotificationPreparation.Respond(PaymentMethodProviderNotificationResult.MANUAL_REVIEW)
            }
            if (existing.status != PaymentProviderNotificationStatus.ACCEPTED) {
                metrics.notification(notification.notificationType, "DUPLICATE_TERMINAL")
                return NotificationPreparation.Respond(PaymentMethodProviderNotificationResult.DUPLICATE_TERMINAL)
            }
            return NotificationPreparation.Process(existing.id)
        }

        val inbox =
            PaymentProviderNotificationInboxEntity(
                id = identifiers.next(),
                provider = notification.provider,
                notificationId = notification.notificationId,
                notificationType = notification.notificationType,
                tokenFingerprint = fingerprint,
                occurredAt = notification.occurredAt,
                receivedAt = clock.instant(),
                status = PaymentProviderNotificationStatus.ACCEPTED,
            )
        inboxes.saveAndFlush(inbox)
        metrics.notification(notification.notificationType, "ACCEPTED")
        return NotificationPreparation.Process(inbox.id)
    }

    @Transactional
    fun apply(
        inboxId: UUID,
        rawTokenReference: String,
    ): PaymentMethodProviderNotificationResult {
        val inbox =
            inboxes.findLockedById(inboxId)
                ?: throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Payment method notification inbox is missing")
        if (inbox.status != PaymentProviderNotificationStatus.ACCEPTED) {
            return PaymentMethodProviderNotificationResult.DUPLICATE_TERMINAL
        }
        if (inbox.tokenFingerprint != sha256(rawTokenReference)) {
            inbox.status = PaymentProviderNotificationStatus.MANUAL_REVIEW
            inbox.processedAt = clock.instant()
            inbox.closedReason = "TOKEN_BINDING_MISMATCH"
            metrics.notification(inbox.notificationType, "MANUAL_REVIEW")
            return PaymentMethodProviderNotificationResult.MANUAL_REVIEW
        }

        jdbcTemplate.queryForObject(
            "SELECT pg_advisory_xact_lock(?)",
            Any::class.java,
            hash64("payment-method-token:${sha256("${inbox.provider}:$rawTokenReference")}"),
        )
        val bindingIds = methods.findAllIdsByProviderAndTokenReference(inbox.provider, rawTokenReference)
        val now = clock.instant()
        return if (bindingIds.size == 1) {
            val methodId = bindingIds.single()
            val method =
                methods.findLockedById(methodId)
                    ?: throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Payment method notification target is missing")
            val deactivation = deactivations.findActiveLockedByPaymentMethodId(methodId)
            val before = method.status.name
            method.confirmProviderDeactivated(now)
            if (deactivation != null) {
                deactivation.status = PaymentMethodDeactivationStatus.COMPLETED
                deactivation.unknownAt = null
                deactivation.manualReviewAt = null
                deactivation.manualReviewReason = null
                deactivation.firstResponseStatus = 204
                deactivation.firstResponseBody = ""
                deactivation.terminalAt = now
                deactivation.retentionExpiresAt = now.plus(RETENTION)
                deactivation.updatedAt = now
                metrics.work("DEACTIVATION", "COMPLETED_BY_NOTIFICATION")
            }
            inbox.status = PaymentProviderNotificationStatus.PROCESSED
            inbox.processedAt = now
            inbox.closedReason = "PAYMENT_METHOD_DEACTIVATED"
            inbox.retentionExpiresAt = now.plus(RETENTION)
            audits.system(
                action = "PAYMENT_METHOD_PROVIDER_DEACTIVATED",
                targetType = "PAYMENT_METHOD",
                targetId = method.id,
                occurredAt = now,
                beforeState = before,
                afterState = method.status.name,
                sourceReference = "payment-method-notification:${inbox.id}:processed",
            )
            metrics.notification(inbox.notificationType, "MAPPED")
            PaymentMethodProviderNotificationResult.MAPPED
        } else {
            inbox.status = PaymentProviderNotificationStatus.MANUAL_REVIEW
            inbox.processedAt = now
            inbox.closedReason = if (bindingIds.isEmpty()) "TOKEN_BINDING_NOT_FOUND" else "TOKEN_BINDING_AMBIGUOUS"
            metrics.notification(inbox.notificationType, "MANUAL_REVIEW")
            PaymentMethodProviderNotificationResult.MANUAL_REVIEW
        }
    }

    private companion object {
        val RETENTION: Duration = Duration.ofDays(90)
    }
}

private fun sha256(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

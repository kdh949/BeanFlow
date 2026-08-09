package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class PaymentMethodLifecycleMaintenance(
    private val transactions: PaymentMethodMaintenanceTransactions,
    private val clock: Clock,
    @Value("\${beanflow.payment-method.maintenance.batch-size:50}")
    private val batchSize: Int,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun recoverInterruptedClaims() {
        while (true) {
            val registrationIds = transactions.interruptedRegistrationClaimIds(batchSize)
            val deactivationIds = transactions.interruptedDeactivationClaimIds(batchSize)
            if (registrationIds.isEmpty() && deactivationIds.isEmpty()) return
            registrationIds.forEach(transactions::recoverInterruptedRegistrationClaim)
            deactivationIds.forEach(transactions::recoverInterruptedDeactivationClaim)
        }
    }

    @Scheduled(
        fixedDelayString = "\${beanflow.payment-method.maintenance.fixed-delay-ms:30000}",
        initialDelayString = "\${beanflow.payment-method.maintenance.initial-delay-ms:30000}",
    )
    fun runDeadline() {
        transactions.dueDeadlineIds(clock.instant(), batchSize).forEach(transactions::moveToManualReview)
    }

    @Scheduled(
        fixedDelayString = "\${beanflow.payment-method.retention.fixed-delay-ms:3600000}",
        initialDelayString = "\${beanflow.payment-method.retention.initial-delay-ms:3600000}",
    )
    fun runRetention() {
        transactions.cleanupTerminal(clock.instant(), batchSize)
    }

    fun cleanupTerminal(now: Instant): Int = transactions.cleanupTerminal(now, batchSize)
}

@Service
internal class PaymentMethodMaintenanceTransactions(
    private val registrations: PaymentMethodRegistrationJpaRepository,
    private val deactivations: PaymentMethodDeactivationJpaRepository,
    private val methods: PaymentMethodJpaRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
    private val metrics: PaymentMethodLifecycleMetrics,
    private val audits: PaymentMethodAuditWriter,
) {
    @Transactional(readOnly = true)
    fun interruptedRegistrationClaimIds(batchSize: Int): List<UUID> = registrations.findInterruptedClaimIds(PageRequest.of(0, batchSize))

    @Transactional(readOnly = true)
    fun interruptedDeactivationClaimIds(batchSize: Int): List<UUID> = deactivations.findInterruptedClaimIds(PageRequest.of(0, batchSize))

    @Transactional
    fun recoverInterruptedRegistrationClaim(registrationId: UUID) {
        val registration = registrations.findLockedById(registrationId) ?: return
        if (registration.status != PaymentMethodRegistrationStatus.PROCESSING) return
        val now = clock.instant()
        registration.status = PaymentMethodRegistrationStatus.REGISTRATION_UNKNOWN
        registration.updatedAt = now
        audits.system(
            action = "PAYMENT_METHOD_REGISTRATION_UNKNOWN",
            targetType = "PAYMENT_METHOD",
            targetId = registration.intendedPaymentMethodId,
            occurredAt = now,
            beforeState = "PROCESSING",
            afterState = "REGISTRATION_UNKNOWN",
            sourceReference = "payment-method-registration:${registration.id}:startup-recovery",
        )
        metrics.work("REGISTRATION", "REGISTRATION_UNKNOWN")
    }

    @Transactional
    fun recoverInterruptedDeactivationClaim(deactivationId: UUID) {
        val work = deactivations.findLockedById(deactivationId) ?: return
        if (work.status != PaymentMethodDeactivationStatus.PROCESSING) return
        val method =
            methods.findLockedById(work.paymentMethodId)
                ?: throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Payment method recovery target is missing")
        val now = clock.instant()
        if (method.status != PaymentMethodStatus.DEACTIVATED) {
            method.markDeactivationUnknown(now)
        }
        work.status = PaymentMethodDeactivationStatus.DEACTIVATION_UNKNOWN
        work.unknownAt = now
        work.manualReviewAt = now.plus(DEACTIVATION_WINDOW)
        work.updatedAt = now
        audits.system(
            action = "PAYMENT_METHOD_DEACTIVATION_UNKNOWN",
            targetType = "PAYMENT_METHOD",
            targetId = method.id,
            occurredAt = now,
            beforeState = "PROCESSING",
            afterState = method.status.name,
            sourceReference = "payment-method-deactivation:${work.id}:startup-recovery",
        )
        metrics.work("DEACTIVATION", "DEACTIVATION_UNKNOWN")
    }

    @Transactional(readOnly = true)
    fun dueDeadlineIds(
        now: Instant,
        batchSize: Int,
    ): List<UUID> = deactivations.findDueManualReviewIds(now, PageRequest.of(0, batchSize))

    @Transactional
    fun moveToManualReview(deactivationId: UUID) {
        val work = deactivations.findLockedById(deactivationId) ?: return
        val now = clock.instant()
        if (
            work.status !in
            setOf(PaymentMethodDeactivationStatus.DEACTIVATION_UNKNOWN, PaymentMethodDeactivationStatus.RECONCILING) ||
            work.manualReviewAt?.isAfter(now) != false
        ) {
            return
        }
        val method =
            methods.findLockedById(work.paymentMethodId)
                ?: throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Payment method deadline target is missing")
        method.markManualReview(now)
        work.status = PaymentMethodDeactivationStatus.MANUAL_REVIEW
        work.manualReviewReason = "DEACTIVATION_DEADLINE_EXPIRED"
        work.updatedAt = now
        val currentBody = checkNotNull(work.firstResponseBody)
        work.firstResponseBody =
            if (currentBody.contains("\"noticeCode\"")) {
                currentBody
            } else {
                currentBody.replace("\"correlationId\"", "\"noticeCode\":\"DEACTIVATION_DELAYED\",\"correlationId\"")
            }
        audits.system(
            action = "PAYMENT_METHOD_DEACTIVATION_MANUAL_REVIEW",
            targetType = "PAYMENT_METHOD",
            targetId = method.id,
            occurredAt = now,
            beforeState = "DEACTIVATION_UNKNOWN",
            afterState = "MANUAL_REVIEW",
            sourceReference = "payment-method-deactivation:${work.id}:deadline",
        )
        metrics.work("DEACTIVATION", "MANUAL_REVIEW")
    }

    @Transactional
    fun cleanupTerminal(
        now: Instant,
        batchSize: Int,
    ): Int {
        val cutoff = Timestamp.from(now)
        return TERMINAL_TABLES.sumOf { table ->
            jdbcTemplate.update(
                """
                DELETE FROM $table
                 WHERE id IN (
                     SELECT id FROM $table
                      WHERE retention_expires_at <= ?
                      ORDER BY retention_expires_at, id
                      LIMIT ?
                 )
                """.trimIndent(),
                cutoff,
                batchSize,
            )
        }
    }

    private companion object {
        val DEACTIVATION_WINDOW = java.time.Duration.ofHours(96)
        val TERMINAL_TABLES =
            listOf(
                "payment_method_registration",
                "payment_method_default_command",
                "payment_method_deactivation",
                "payment_provider_notification_inbox",
            )
    }
}

package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditRecordKey
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.AuditRecordQueryOperations
import io.github.kdh949.beanflow.operations.api.DetectPaymentCancellationSetupIssueCommand
import io.github.kdh949.beanflow.operations.api.DetectedPaymentCancellationSetupIssue
import io.github.kdh949.beanflow.operations.api.PaymentCancellationSetupIntegrityOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
internal class PaymentCancellationSetupIntegrityService(
    private val cases: ReprocessingCaseJpaRepository,
    private val identifiers: IdentifierSource,
    private val audits: AuditRecordOperations,
    private val auditQueries: AuditRecordQueryOperations,
    private val jdbcTemplate: JdbcTemplate,
    private val meterRegistry: MeterRegistry,
) : PaymentCancellationSetupIntegrityOperations {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun detect(command: DetectPaymentCancellationSetupIssueCommand): DetectedPaymentCancellationSetupIssue =
        try {
            detectInTransaction(command)
        } catch (failure: DomainFailure) {
            throw failure
        } catch (failure: RuntimeException) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Payment cancellation setup issue could not be recorded",
            ).also { it.initCause(failure) }
        }

    private fun detectInTransaction(command: DetectPaymentCancellationSetupIssueCommand): DetectedPaymentCancellationSetupIssue {
        require(command.cancellationOrderVersion >= 0)
        require(command.missingArtifacts.isNotEmpty() || command.invariantViolations.isNotEmpty())
        require(command.errorCode.isNotBlank())
        require(command.correlationId.isNotBlank())
        val source = sourceReference(command.orderId, command.cancellationOrderVersion)
        jdbcTemplate.query(
            "select pg_advisory_xact_lock(hashtext(?))",
            { _, _ -> Unit },
            source,
        )
        val existing =
            cases.findByCaseTypeAndOwnerReference(
                ReprocessingCaseType.PAYMENT_CANCELLATION_SETUP,
                source,
            )
        val beanCase =
            existing
                ?: cases.saveAndFlush(
                    ReprocessingCaseEntity(
                        id = identifiers.next(),
                        caseType = ReprocessingCaseType.PAYMENT_CANCELLATION_SETUP,
                        ownerReference = source,
                        status = ReprocessingCaseStatus.OPEN,
                        reason = normalized(command.errorCode),
                        correlationId = command.correlationId,
                        createdAt = command.now,
                        updatedAt = command.now,
                    ),
                )
        val auditKey = AuditRecordKey(ACTION, TARGET_TYPE, command.orderId, source)
        if (!auditQueries.exists(auditKey)) {
            audits.appendAll(
                listOf(
                    AppendAuditRecordCommand(
                        actorId = SYSTEM_ACTOR,
                        actorType = AuditActorType.SYSTEM,
                        action = ACTION,
                        targetType = TARGET_TYPE,
                        targetId = command.orderId,
                        occurredAt = command.now,
                        reason = beanCase.reason,
                        beforeSummary =
                            mapOf(
                                "missingArtifacts" to
                                    command.missingArtifacts
                                        .map(Enum<*>::name)
                                        .sorted()
                                        .joinToString(","),
                                "invariantViolations" to
                                    command.invariantViolations
                                        .map(Enum<*>::name)
                                        .sorted()
                                        .joinToString(","),
                            ),
                        afterSummary = mapOf("reprocessingCaseState" to beanCase.status.name),
                        correlationId = command.correlationId,
                        sourceReference = source,
                    ),
                ),
            )
            meterRegistry
                .counter(
                    "beanflow.operations.payment_setup.case.count",
                    "reason",
                    reasonTag(command),
                    "state",
                    "open",
                ).increment()
        }
        return DetectedPaymentCancellationSetupIssue(beanCase.id, beanCase.createdAt, beanCase.reason)
    }

    private fun reasonTag(command: DetectPaymentCancellationSetupIssueCommand): String =
        when {
            command.missingArtifacts.isNotEmpty() -> "missing_artifact"
            command.invariantViolations.isNotEmpty() -> "invariant_violation"
            else -> "unknown"
        }

    private fun normalized(code: String): String =
        code
            .trim()
            .uppercase()
            .replace(Regex("[^A-Z0-9_]+"), "_")
            .take(80)
            .ifBlank { "UNKNOWN" }

    private fun sourceReference(
        orderId: java.util.UUID,
        version: Long,
    ): String = "order:$orderId:customer-cancellation:$version:payment-setup"

    private companion object {
        const val ACTION = "PAYMENT_CANCELLATION_SETUP_INCOMPLETE_DETECTED"
        const val TARGET_TYPE = "ORDER"
        const val SYSTEM_ACTOR = "SYSTEM"
    }
}

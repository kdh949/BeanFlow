package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.CustomerCancellationRefundReconciliationOperations
import io.github.kdh949.beanflow.operations.api.InspectPaymentCancellationSetupCommand
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.operations.api.PaymentCancellationSetupIntegrityOperations
import io.github.kdh949.beanflow.operations.api.ScheduleCustomerCancellationRefundLookupCommand
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
internal class CustomerCancellationRefundReconciliationService(
    private val commands: CustomerCancellationRefundReconciliationCommandJpaRepository,
    private val authorization: OperatorPermissionAuthorization,
    private val setupQueries: PaymentCancellationSetupIntegrityQueryService,
    private val setupIntegrity: PaymentCancellationSetupIntegrityOperations,
    private val payment: CustomerCancellationRefundReconciliationOperations,
    private val audits: AuditRecordOperations,
    private val identifiers: IdentifierSource,
    private val correlationIds: CorrelationIdSource,
    private val advisoryLock: DatabaseAdvisoryLock,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    @Transactional
    fun schedule(command: ScheduleCustomerCancellationRefundReconciliationCommand): CustomerCancellationRefundReconciliationResponse {
        val reason = normalizeReason(command.reason)
        val idempotencyKey = normalizeIdempotencyKey(command.idempotencyKey)
        val hash = fingerprint("${command.orderId}|$reason")
        advisoryLock.lock("customer-cancellation-refund-reconciliation:${command.actorId}:$idempotencyKey")
        authorization.requireActive(
            command.actorId,
            OperatorPermission.CUSTOMER_CANCELLATION_REFUND_RECONCILE,
        )
        commands.findByActorIdAndIdempotencyKey(command.actorId, idempotencyKey)?.let { existing ->
            if (existing.payloadHash != hash) {
                conflict(FailureCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was reused for another reconciliation")
            }
            return replay(existing)
        }

        val cancellationOrderVersion =
            setupQueries.findCurrentCancellationVersion(command.orderId)
                ?: conflict(FailureCode.ORDER_STATE_CONFLICT, "Order is not a current customer cancellation")
        val setupIssue =
            setupIntegrity.inspect(
                InspectPaymentCancellationSetupCommand(
                    orderId = command.orderId,
                    cancellationOrderVersion = cancellationOrderVersion,
                    now = command.now,
                ),
            )
        if (setupIssue != null) {
            conflict(FailureCode.REPROCESSING_NOT_SAFE, "Customer cancellation payment setup is incomplete")
        }
        val scheduled =
            payment.scheduleLookup(
                ScheduleCustomerCancellationRefundLookupCommand(
                    orderId = command.orderId,
                    cancellationOrderVersion = cancellationOrderVersion,
                    now = command.now,
                ),
            )
        val response =
            CustomerCancellationRefundReconciliationResponse(
                operationId = identifiers.next(),
                orderId = command.orderId,
                cancellationOrderVersion = cancellationOrderVersion,
                state = LOOKUP_SCHEDULED,
                scheduledAt = command.now,
            )
        commands.saveAndFlush(
            CustomerCancellationRefundReconciliationCommandEntity(
                id = response.operationId,
                actorId = command.actorId,
                idempotencyKey = idempotencyKey,
                payloadHash = hash,
                orderId = command.orderId,
                cancellationOrderVersion = cancellationOrderVersion,
                operatorReason = reason,
                state = LOOKUP_SCHEDULED,
                responseJson = objectMapper.writeValueAsString(response),
                createdAt = command.now,
                retentionExpiresAt = command.now.plus(90, ChronoUnit.DAYS),
            ),
        )
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = command.actorId.toString(),
                    actorType = AuditActorType.PLATFORM_OPERATOR,
                    action = AUDIT_ACTION,
                    targetType = "ORDER",
                    targetId = command.orderId,
                    occurredAt = command.now,
                    reason = AUDIT_REASON,
                    beforeSummary =
                        mapOf(
                            "cancellationOrderVersion" to cancellationOrderVersion.toString(),
                            "refundState" to scheduled.previousRefundState,
                        ),
                    afterSummary =
                        mapOf(
                            "paymentStepState" to "UNKNOWN",
                            "refundState" to "UNKNOWN",
                        ),
                    correlationId = correlationIds.currentOrCreate(),
                    sourceReference = "customer-cancellation-refund-reconciliation:${response.operationId}",
                ),
            ),
        )
        afterCommit { metric("scheduled") }
        return response
    }

    @Transactional
    fun purgeDue(
        now: Instant,
        limit: Int,
    ): Int {
        require(limit in 1..100)
        val ids = commands.findDueIds(now, PageRequest.of(0, limit))
        if (ids.isNotEmpty()) commands.deleteAllByIdInBatch(ids)
        afterCommit {
            if (ids.isNotEmpty()) {
                meterRegistry
                    .counter("beanflow.operations.customer_cancellation_refund_reconciliation.retention.deleted")
                    .increment(ids.size.toDouble())
            }
        }
        return ids.size
    }

    private fun replay(existing: CustomerCancellationRefundReconciliationCommandEntity): CustomerCancellationRefundReconciliationResponse =
        try {
            objectMapper.readValue(existing.responseJson, CustomerCancellationRefundReconciliationResponse::class.java)
        } catch (failure: RuntimeException) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Stored refund reconciliation response is invalid",
            ).also { it.initCause(failure) }
        }

    private fun normalizeReason(raw: String): String {
        val normalized = raw.trim()
        if (normalized.length !in 1..500 || normalized.any(Char::isISOControl)) {
            invalid("Reconciliation reason must contain 1 to 500 non-control characters")
        }
        return normalized
    }

    private fun normalizeIdempotencyKey(raw: String): String {
        if (raw.length !in 8..128 || raw.any(Char::isISOControl)) {
            invalid("Idempotency-Key must contain 8 to 128 non-control characters")
        }
        return raw
    }

    private fun fingerprint(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun metric(outcome: String) {
        meterRegistry
            .counter(
                "beanflow.operations.customer_cancellation_refund_reconciliation.count",
                "outcome",
                outcome,
            ).increment()
    }

    private fun afterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = action()
            },
        )
    }

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    private fun conflict(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)

    private companion object {
        const val LOOKUP_SCHEDULED = "LOOKUP_SCHEDULED"
        const val AUDIT_ACTION = "CUSTOMER_CANCELLATION_REFUND_RECONCILIATION_SCHEDULED"
        const val AUDIT_REASON = "OPERATOR_INITIATED_LOOKUP"
    }
}

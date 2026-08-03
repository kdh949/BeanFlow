package io.github.kdh949.beanflow.dispute.internal

import com.fasterxml.jackson.annotation.JsonInclude
import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.FinancialEventPublicationOperations
import io.github.kdh949.beanflow.eventing.api.SettlementBatchConfirmedV1
import io.github.kdh949.beanflow.eventing.api.SettlementDisputeDecidedV1
import io.github.kdh949.beanflow.eventing.api.SettlementDisputeFiledV1
import io.github.kdh949.beanflow.identity.api.StoreAccessOperations
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OpenReprocessingCaseCommand
import io.github.kdh949.beanflow.operations.api.SettlementDisputeReprocessingCaseOperations
import io.github.kdh949.beanflow.settlement.api.ConfirmedSettlementBatchOperations
import io.github.kdh949.beanflow.settlement.api.ConfirmedSettlementItemOperations
import io.github.kdh949.beanflow.settlement.api.CreateSettlementAdjustmentCommand
import io.github.kdh949.beanflow.settlement.api.SettlementAdjustmentOperations
import io.github.kdh949.beanflow.settlement.api.SettlementAdjustmentReasonCode
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.dao.DataAccessException
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCallback
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.HexFormat
import java.util.UUID

internal data class FileSettlementDisputeCommand(
    val actorId: UUID,
    val actorRoles: Set<StoreActorRole>,
    val settlementItemId: UUID,
    val idempotencyKey: String,
    val expectedAdjustmentKrw: Long,
    val reason: String,
    val evidenceReferences: List<String>,
    val previousDisputeId: UUID?,
    val correlationId: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class SettlementDisputeResponse(
    val disputeId: UUID,
    val settlementItemId: UUID,
    val previousDisputeId: UUID?,
    val state: SettlementDisputeState,
    val heldAmountKrw: Long,
    val currency: String,
    val filedAt: Instant,
)

@Component
internal class SettlementDisputeFilingLock(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun lock(
        settlementItemId: UUID,
        actorId: UUID,
        idempotencyKey: String,
    ) {
        listOf(lockKey("item:$settlementItemId"), lockKey("idempotency:$actorId:$idempotencyKey"))
            .sorted()
            .forEach { key ->
                jdbcTemplate.execute(
                    "SELECT pg_advisory_xact_lock(?)",
                    PreparedStatementCallback<Unit> { statement ->
                        statement.setLong(1, key)
                        statement.execute()
                        Unit
                    },
                )
            }
    }

    private fun lockKey(source: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(StandardCharsets.UTF_8))
        return java.nio.ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long
    }
}

@Service
internal class SettlementDisputeFilingService(
    private val confirmedItems: ConfirmedSettlementItemOperations,
    private val storeAccess: StoreAccessOperations,
    private val repository: SettlementDisputeJpaRepository,
    private val filingLock: SettlementDisputeFilingLock,
    private val audits: AuditRecordOperations,
    private val financialEvents: FinancialEventPublicationOperations,
    private val identifierSource: IdentifierSource,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val metrics: SettlementDisputeMetrics,
) {
    @Transactional
    fun file(command: FileSettlementDisputeCommand): SettlementDisputeResponse =
        try {
            val normalized = normalize(command)
            val item =
                confirmedItems.findConfirmedItem(normalized.settlementItemId)
                    ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Confirmed SettlementItem was not found")
            if (StoreActorRole.OWNER !in normalized.actorRoles) {
                throw DomainFailure(FailureCode.ACCESS_DENIED, "Store owner role is required")
            }
            val storeActor = storeAccess.requireStoreAccess(command.actorId, item.storeId, normalized.actorRoles)
            if (storeActor.role != StoreActorRole.OWNER) {
                throw DomainFailure(FailureCode.ACCESS_DENIED, "Active owner membership is required")
            }
            filingLock.lock(item.settlementItemId, normalized.actorId, normalized.idempotencyKey)
            val payloadHash = payloadHash(normalized)
            repository
                .findByActorIdAndOperationAndIdempotencyKey(
                    normalized.actorId,
                    OPERATION,
                    normalized.idempotencyKey,
                )?.let { existing ->
                    if (existing.payloadHash != payloadHash) {
                        conflict(FailureCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was reused with another dispute")
                    }
                    metrics.record(existing.state, "REPLAYED")
                    return objectMapper.readValue(existing.responseBody, SettlementDisputeResponse::class.java)
                }
            val now = clock.instant()
            requireWindow(item.batchConfirmedAt, now)
            repository.findBySettlementItemIdAndStateIn(item.settlementItemId, ACTIVE_STATES)?.let {
                conflict(FailureCode.DISPUTE_ALREADY_ACTIVE, "SettlementItem already has an active dispute")
            }
            val latest = repository.findFirstBySettlementItemIdOrderByFiledAtDescIdDesc(item.settlementItemId)
            val refileCount = validateRefile(normalized, latest)
            val disputeId = identifierSource.next()
            val response =
                SettlementDisputeResponse(
                    disputeId = disputeId,
                    settlementItemId = item.settlementItemId,
                    previousDisputeId = normalized.previousDisputeId,
                    state = SettlementDisputeState.FILED,
                    heldAmountKrw = normalized.expectedAdjustmentKrw,
                    currency = item.currency,
                    filedAt = now,
                )
            val dispute =
                repository.saveAndFlush(
                    SettlementDisputeEntity(
                        id = disputeId,
                        settlementItemId = item.settlementItemId,
                        storeId = item.storeId,
                        previousDisputeId = normalized.previousDisputeId,
                        refileCount = refileCount,
                        state = SettlementDisputeState.FILED,
                        expectedAdjustmentKrw = normalized.expectedAdjustmentKrw,
                        heldAmountKrw = normalized.expectedAdjustmentKrw,
                        reason = normalized.reason,
                        evidenceReferences = normalized.evidenceReferences,
                        actorId = normalized.actorId,
                        operation = OPERATION,
                        idempotencyKey = normalized.idempotencyKey,
                        payloadHash = payloadHash,
                        responseStatus = 201,
                        responseBody = objectMapper.writeValueAsString(response),
                        correlationId = normalized.correlationId,
                        filedAt = now,
                    ),
                )
            audits.appendAll(listOf(dispute.filedAudit()))
            financialEvents.publish(dispute.toFiledEvent(item.currency, identifierSource.next()))
            metrics.record(dispute.state, "FILED")
            response
        } catch (failure: DomainFailure) {
            metrics.record(null, failure.code.name)
            throw failure
        } catch (failure: DataAccessException) {
            metrics.record(null, "DEPENDENCY_UNAVAILABLE")
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "SettlementDispute persistence is unavailable",
            ).also { it.initCause(failure) }
        } catch (failure: RuntimeException) {
            metrics.record(null, "DEPENDENCY_UNAVAILABLE")
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "SettlementDispute response persistence is unavailable",
            ).also { it.initCause(failure) }
        }

    private fun normalize(command: FileSettlementDisputeCommand): FileSettlementDisputeCommand {
        val key = command.idempotencyKey.trim()
        val reason = command.reason.trim()
        val evidence = command.evidenceReferences.map(String::trim)
        if (key.length !in 8..128 || key.any(Char::isISOControl) ||
            reason.isEmpty() || reason.length > 1_000 || reason.any(Char::isISOControl) ||
            evidence.isEmpty() || evidence.any { it.isEmpty() || it.length > 500 || it.any(Char::isISOControl) } ||
            evidence.size != evidence.toSet().size || command.correlationId.isBlank() ||
            command.correlationId != command.correlationId.trim() || command.correlationId.length > 240
        ) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "SettlementDispute filing input is invalid")
        }
        return command.copy(idempotencyKey = key, reason = reason, evidenceReferences = evidence)
    }

    private fun requireWindow(
        confirmedAt: Instant,
        now: Instant,
    ) {
        val confirmedDate = confirmedAt.atZone(SEOUL).toLocalDate()
        val opensAt = confirmedDate.plusDays(1).atStartOfDay(SEOUL).toInstant()
        val closesAt = confirmedDate.plusDays(15).atStartOfDay(SEOUL).toInstant()
        if (now.isBefore(opensAt) || !now.isBefore(closesAt)) {
            conflict(FailureCode.DISPUTE_WINDOW_CLOSED, "SettlementDispute filing window is closed")
        }
    }

    private fun validateRefile(
        command: FileSettlementDisputeCommand,
        latest: SettlementDisputeEntity?,
    ): Int {
        val previousId = command.previousDisputeId
        if (previousId == null) {
            if (latest != null) {
                conflict(FailureCode.DISPUTE_REFILE_NOT_ALLOWED, "A terminal dispute requires an explicit refile")
            }
            return 0
        }
        if (latest == null || latest.id != previousId || latest.settlementItemId != command.settlementItemId ||
            latest.state !in TERMINAL_STATES || latest.refileCount != 0 ||
            command.evidenceReferences.none { it !in latest.evidenceReferences }
        ) {
            conflict(FailureCode.DISPUTE_REFILE_NOT_ALLOWED, "SettlementDispute refile requirements are not met")
        }
        return 1
    }

    private fun payloadHash(command: FileSettlementDisputeCommand): String {
        val canonical =
            buildList {
                add(command.settlementItemId.toString())
                add(command.expectedAdjustmentKrw.toString())
                add(command.reason)
                add(command.evidenceReferences.size.toString())
                addAll(command.evidenceReferences)
                add(command.previousDisputeId?.toString().orEmpty())
            }.joinToString("\u0000")
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8)),
        )
    }

    private fun SettlementDisputeEntity.filedAudit(): AppendAuditRecordCommand =
        AppendAuditRecordCommand(
            actorId = actorId.toString(),
            actorType = AuditActorType.STORE_OWNER,
            action = "SETTLEMENT_DISPUTE_FILED",
            targetType = "SETTLEMENT_DISPUTE",
            targetId = id,
            occurredAt = filedAt,
            reason = "STORE_OWNER_DISPUTE",
            afterSummary =
                mapOf(
                    "state" to state.name,
                    "settlementItemId" to settlementItemId.toString(),
                    "heldAmountKrw" to heldAmountKrw.toString(),
                    "refileCount" to refileCount.toString(),
                ),
            correlationId = correlationId,
            sourceReference = "settlement-dispute:$id:filed",
        )

    private fun SettlementDisputeEntity.toFiledEvent(
        currency: String,
        eventId: UUID,
    ): SettlementDisputeFiledV1 =
        SettlementDisputeFiledV1(
            envelope =
                EventEnvelope(
                    eventId = eventId,
                    eventType = "SettlementDisputeFiledV1",
                    aggregateId = id,
                    aggregateVersion = version,
                    occurredAt = filedAt,
                    payloadVersion = 1,
                    correlationId = correlationId,
                    causationId = "settlement-dispute:$id:filed",
                ),
            disputeId = id,
            settlementItemId = settlementItemId,
            previousDisputeId = previousDisputeId,
            state = state.name,
            expectedAdjustmentKrw = expectedAdjustmentKrw,
            heldAmountKrw = heldAmountKrw,
            currency = currency,
            filedAt = filedAt,
        )

    private fun conflict(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)

    private companion object {
        const val OPERATION = "CREATE_SETTLEMENT_DISPUTE"
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val ACTIVE_STATES = setOf(SettlementDisputeState.FILED, SettlementDisputeState.UNDER_REVIEW)
        val TERMINAL_STATES =
            setOf(
                SettlementDisputeState.ACCEPTED,
                SettlementDisputeState.REJECTED,
                SettlementDisputeState.WITHDRAWN,
            )
    }
}

internal data class SettlementDisputeDecisionResult(
    val disputeId: UUID,
    val state: SettlementDisputeState,
    val heldAmountKrw: Long,
    val settlementAdjustmentId: UUID?,
    val decidedAt: Instant?,
)

@Service
internal class SettlementDisputeDecisionService(
    private val repository: SettlementDisputeJpaRepository,
    private val adjustments: SettlementAdjustmentOperations,
    private val confirmedItems: ConfirmedSettlementItemOperations,
    private val audits: AuditRecordOperations,
    private val financialEvents: FinancialEventPublicationOperations,
    private val reprocessingCases: SettlementDisputeReprocessingCaseOperations,
    private val identifierSource: IdentifierSource,
    private val metrics: SettlementDisputeMetrics,
) {
    @Transactional
    fun startReview(disputeId: UUID): SettlementDisputeDecisionResult {
        val dispute = locked(disputeId)
        if (dispute.state != SettlementDisputeState.FILED) return dispute.toDecisionResult()
        dispute.startReview()
        repository.saveAndFlush(dispute)
        metrics.record(dispute.state, "STARTED")
        return dispute.toDecisionResult()
    }

    @Transactional
    fun accept(
        disputeId: UUID,
        decidedAt: Instant,
    ): SettlementDisputeDecisionResult =
        decide(disputeId, SettlementDisputeState.ACCEPTED, decidedAt)

    @Transactional
    fun reject(
        disputeId: UUID,
        decidedAt: Instant,
    ): SettlementDisputeDecisionResult =
        decide(disputeId, SettlementDisputeState.REJECTED, decidedAt)

    @Transactional
    fun withdraw(
        disputeId: UUID,
        decidedAt: Instant,
    ): SettlementDisputeDecisionResult =
        decide(disputeId, SettlementDisputeState.WITHDRAWN, decidedAt)

    private fun decide(
        disputeId: UUID,
        outcome: SettlementDisputeState,
        decidedAt: Instant,
    ): SettlementDisputeDecisionResult =
        try {
            val dispute = locked(disputeId)
            if (dispute.state == outcome) {
                resolveCaseAfterCommit(disputeId, decidedAt)
                return dispute.toDecisionResult()
            }
            if (dispute.state != SettlementDisputeState.UNDER_REVIEW) {
                throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, "SettlementDispute is not under review")
            }
            val item =
                confirmedItems.findConfirmedItem(dispute.settlementItemId)
                    ?: throw DomainFailure(
                        FailureCode.SETTLEMENT_INPUT_UNAVAILABLE,
                        "SettlementDispute confirmed Item is unavailable",
                    )
            val adjustmentId =
                if (outcome == SettlementDisputeState.ACCEPTED) {
                    adjustments
                        .create(
                            CreateSettlementAdjustmentCommand(
                                settlementItemId = dispute.settlementItemId,
                                adjustmentSource = "dispute:${dispute.id}:accepted",
                                reasonCode = SettlementAdjustmentReasonCode.DISPUTE_ACCEPTED,
                                effectiveAt = decidedAt,
                                amountKrw = dispute.expectedAdjustmentKrw,
                                correlationId = dispute.correlationId,
                            ),
                        ).settlementAdjustmentId
                } else {
                    null
                }
            when (outcome) {
                SettlementDisputeState.ACCEPTED -> dispute.accept(requireNotNull(adjustmentId), decidedAt)
                SettlementDisputeState.REJECTED -> dispute.reject(decidedAt)
                SettlementDisputeState.WITHDRAWN -> dispute.withdraw(decidedAt)
                else -> error("Unsupported SettlementDispute decision")
            }
            repository.saveAndFlush(dispute)
            audits.appendAll(listOf(dispute.decisionAudit()))
            financialEvents.publish(dispute.toDecidedEvent(item.currency, identifierSource.next()))
            resolveCaseAfterCommit(disputeId, decidedAt)
            metrics.record(dispute.state, "DECIDED")
            dispute.toDecisionResult()
        } catch (failure: DomainFailure) {
            openDecisionCase(disputeId, outcome, failure.code.name, decidedAt)
            metrics.record(outcome, failure.code.name)
            throw failure
        } catch (failure: DataAccessException) {
            openDecisionCase(disputeId, outcome, "DEPENDENCY_UNAVAILABLE", decidedAt)
            metrics.record(outcome, "DEPENDENCY_UNAVAILABLE")
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "SettlementDispute decision persistence is unavailable",
            ).also { it.initCause(failure) }
        }

    private fun openDecisionCase(
        disputeId: UUID,
        outcome: SettlementDisputeState,
        reason: String,
        now: Instant,
    ) {
        reprocessingCases.openDisputeCase(
            OpenReprocessingCaseCommand(
                ownerReference = decisionCaseOwner(disputeId),
                reason = "DECISION_${outcome.name}_$reason",
                correlationId = "settlement-dispute:$disputeId",
                now = now,
            ),
        )
    }

    private fun resolveCaseAfterCommit(
        disputeId: UUID,
        now: Instant,
    ) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    reprocessingCases.resolveDisputeCase(
                        decisionCaseOwner(disputeId),
                        "DECISION_COMMITTED",
                        now,
                    )
                }
            },
        )
    }

    private fun decisionCaseOwner(disputeId: UUID): String = "settlement-dispute:$disputeId:decision"

    private fun locked(disputeId: UUID): SettlementDisputeEntity =
        repository.findLockedById(disputeId)
            ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "SettlementDispute was not found")

    private fun SettlementDisputeEntity.decisionAudit(): AppendAuditRecordCommand =
        AppendAuditRecordCommand(
            actorId = SYSTEM_ACTOR,
            actorType = AuditActorType.SYSTEM,
            action = "SETTLEMENT_DISPUTE_DECIDED",
            targetType = "SETTLEMENT_DISPUTE",
            targetId = id,
            occurredAt = requireNotNull(decidedAt),
            reason = state.name,
            beforeSummary = mapOf("state" to SettlementDisputeState.UNDER_REVIEW.name),
            afterSummary =
                buildMap {
                    put("state", state.name)
                    put("heldAmountKrw", heldAmountKrw.toString())
                    settlementAdjustmentId?.let { put("settlementAdjustmentId", it.toString()) }
                },
            correlationId = correlationId,
            sourceReference = "settlement-dispute:$id:decided",
        )

    private fun SettlementDisputeEntity.toDecidedEvent(
        currency: String,
        eventId: UUID,
    ): SettlementDisputeDecidedV1 =
        SettlementDisputeDecidedV1(
            envelope =
                EventEnvelope(
                    eventId = eventId,
                    eventType = "SettlementDisputeDecidedV1",
                    aggregateId = id,
                    aggregateVersion = version,
                    occurredAt = requireNotNull(decidedAt),
                    payloadVersion = 1,
                    correlationId = correlationId,
                    causationId = "settlement-dispute:$id:decided",
                ),
            disputeId = id,
            settlementItemId = settlementItemId,
            state = state.name,
            heldAmountKrw = heldAmountKrw,
            settlementAdjustmentId = settlementAdjustmentId,
            currency = currency,
            decidedAt = requireNotNull(decidedAt),
        )

    private fun SettlementDisputeEntity.toDecisionResult(): SettlementDisputeDecisionResult =
        SettlementDisputeDecisionResult(id, state, heldAmountKrw, settlementAdjustmentId, decidedAt)

    private companion object {
        const val SYSTEM_ACTOR = "beanflow-dispute"
    }
}

@Component
internal class SettlementDisputeReviewWorker(
    private val repository: SettlementDisputeJpaRepository,
    private val service: SettlementDisputeDecisionService,
    private val metrics: SettlementDisputeMetrics,
) {
    @Scheduled(
        fixedDelayString = "\${beanflow.settlement.dispute.fixed-delay-ms:60000}",
        initialDelayString = "\${beanflow.settlement.dispute.initial-delay-ms:3600000}",
    )
    fun run() {
        repository
            .findByStateOrderByFiledAtAscIdAsc(SettlementDisputeState.FILED, PageRequest.of(0, WORK_LIMIT))
            .map(SettlementDisputeEntity::id)
            .forEach {
                try {
                    service.startReview(it)
                } catch (_: RuntimeException) {
                    metrics.record(SettlementDisputeState.FILED, "RETRY_SCHEDULED")
                }
            }
    }

    private companion object {
        const val WORK_LIMIT = 100
    }
}

@Component
internal class SettlementBatchConfirmedDisputeListener(
    private val confirmedBatches: ConfirmedSettlementBatchOperations,
    private val metrics: SettlementDisputeMetrics,
) {
    @ApplicationModuleListener(id = "beanflow.dispute.settlement-batch-confirmed-v1")
    fun on(event: SettlementBatchConfirmedV1) {
        val batch = confirmedBatches.findConfirmedBatch(event.settlementBatchId)
        if (batch == null || batch.settlementDate != event.settlementDate ||
            batch.netSettlementKrw != event.netSettlementKrw || batch.currency != event.currency ||
            event.state != "CONFIRMED"
        ) {
            throw DomainFailure(
                FailureCode.SETTLEMENT_INPUT_UNAVAILABLE,
                "SettlementBatch confirmation does not match the confirmed Dispute view",
            )
        }
        metrics.record(null, "BATCH_CONFIRMED_OBSERVED")
    }
}

@Component
internal class SettlementDisputeMetrics(
    private val meterRegistry: MeterRegistry,
) {
    fun record(
        state: SettlementDisputeState?,
        outcome: String,
    ) {
        meterRegistry
            .counter(
                "beanflow.settlement.dispute.count",
                "state",
                state?.name ?: "NONE",
                "outcome",
                outcome,
            ).increment()
    }
}

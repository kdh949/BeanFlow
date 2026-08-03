package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.OpenReprocessingCaseCommand
import io.github.kdh949.beanflow.operations.api.SettlementAdjustmentReprocessingCaseOperations
import io.github.kdh949.beanflow.operations.api.SettlementDisputeReprocessingCaseOperations
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class SettlementAdjustmentReprocessingCaseService(
    private val repository: ReprocessingCaseJpaRepository,
    private val identifierSource: IdentifierSource,
    private val metrics: SettlementReprocessingMetrics,
) : SettlementAdjustmentReprocessingCaseOperations {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun openAdjustmentCase(command: OpenReprocessingCaseCommand): UUID =
        openCase(repository, identifierSource, ReprocessingCaseType.SETTLEMENT_ADJUSTMENT, command)
            .also { metrics.record(ReprocessingCaseType.SETTLEMENT_ADJUSTMENT, "OPENED_OR_REPLAYED") }
}

@Service
internal class SettlementDisputeReprocessingCaseService(
    private val repository: ReprocessingCaseJpaRepository,
    private val identifierSource: IdentifierSource,
    private val metrics: SettlementReprocessingMetrics,
) : SettlementDisputeReprocessingCaseOperations {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun openDisputeCase(command: OpenReprocessingCaseCommand): UUID =
        openCase(repository, identifierSource, ReprocessingCaseType.SETTLEMENT_DISPUTE, command)
            .also { metrics.record(ReprocessingCaseType.SETTLEMENT_DISPUTE, "OPENED_OR_REPLAYED") }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun resolveDisputeCase(
        ownerReference: String,
        resolution: String,
        now: java.time.Instant,
    ) {
        require(ownerReference.isNotBlank())
        require(resolution.isNotBlank())
        repository
            .findLockedByCaseTypeAndOwnerReference(ReprocessingCaseType.SETTLEMENT_DISPUTE, ownerReference)
            ?.let { existing ->
                existing.status = ReprocessingCaseStatus.RESOLVED
                existing.resolution = resolution
                existing.updatedAt = now
                repository.saveAndFlush(existing)
                metrics.record(ReprocessingCaseType.SETTLEMENT_DISPUTE, "RESOLVED")
            }
    }
}

@Component
internal class SettlementReprocessingMetrics(
    private val meterRegistry: MeterRegistry,
) {
    fun record(
        type: ReprocessingCaseType,
        outcome: String,
    ) {
        meterRegistry
            .counter(
                "beanflow.settlement.reprocessing.count",
                "reason",
                type.name,
                "outcome",
                outcome,
            ).increment()
    }
}

private fun openCase(
    repository: ReprocessingCaseJpaRepository,
    identifierSource: IdentifierSource,
    type: ReprocessingCaseType,
    command: OpenReprocessingCaseCommand,
): UUID {
    require(command.ownerReference.isNotBlank())
    require(command.reason.isNotBlank())
    require(command.correlationId.isNotBlank())
    repository.findByCaseTypeAndOwnerReference(type, command.ownerReference)?.let { return it.id }
    return repository
        .saveAndFlush(
            ReprocessingCaseEntity(
                id = identifierSource.next(),
                caseType = type,
                ownerReference = command.ownerReference,
                status = ReprocessingCaseStatus.MANUAL_REVIEW,
                reason = command.reason,
                correlationId = command.correlationId,
                createdAt = command.now,
                updatedAt = command.now,
            ),
        ).id
}

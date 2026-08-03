package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.OpenReprocessingCaseCommand
import io.github.kdh949.beanflow.operations.api.SettlementAdjustmentReprocessingCaseOperations
import io.github.kdh949.beanflow.operations.api.SettlementDisputeReprocessingCaseOperations
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class SettlementAdjustmentReprocessingCaseService(
    private val repository: ReprocessingCaseJpaRepository,
    private val identifierSource: IdentifierSource,
) : SettlementAdjustmentReprocessingCaseOperations {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun openAdjustmentCase(command: OpenReprocessingCaseCommand): UUID =
        openCase(repository, identifierSource, ReprocessingCaseType.SETTLEMENT_ADJUSTMENT, command)
}

@Service
internal class SettlementDisputeReprocessingCaseService(
    private val repository: ReprocessingCaseJpaRepository,
    private val identifierSource: IdentifierSource,
) : SettlementDisputeReprocessingCaseOperations {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun openDisputeCase(command: OpenReprocessingCaseCommand): UUID =
        openCase(repository, identifierSource, ReprocessingCaseType.SETTLEMENT_DISPUTE, command)

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
            }
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

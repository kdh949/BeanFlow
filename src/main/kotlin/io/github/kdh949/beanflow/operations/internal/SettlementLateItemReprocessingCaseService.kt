package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.OpenReprocessingCaseCommand
import io.github.kdh949.beanflow.operations.api.SettlementLateItemReprocessingCaseOperations
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class SettlementLateItemReprocessingCaseService(
    private val repository: ReprocessingCaseJpaRepository,
    private val identifierSource: IdentifierSource,
) : SettlementLateItemReprocessingCaseOperations {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun openLateItemCase(command: OpenReprocessingCaseCommand): UUID {
        require(command.ownerReference.isNotBlank())
        require(command.reason.isNotBlank())
        require(command.correlationId.isNotBlank())
        repository
            .findByCaseTypeAndOwnerReference(
                ReprocessingCaseType.SETTLEMENT_LATE_ITEM,
                command.ownerReference,
            )?.let { return it.id }
        return repository
            .saveAndFlush(
                ReprocessingCaseEntity(
                    id = identifierSource.next(),
                    caseType = ReprocessingCaseType.SETTLEMENT_LATE_ITEM,
                    ownerReference = command.ownerReference,
                    status = ReprocessingCaseStatus.MANUAL_REVIEW,
                    reason = command.reason,
                    correlationId = command.correlationId,
                    createdAt = command.now,
                    updatedAt = command.now,
                ),
            ).id
    }
}

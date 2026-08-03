package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.AcceptanceTimeoutWorkReprocessingCaseOperations
import io.github.kdh949.beanflow.operations.api.OpenReprocessingCaseCommand
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.stereotype.Service
import java.util.UUID

@Service
internal class AcceptanceTimeoutWorkReprocessingCaseService(
    private val repository: ReprocessingCaseJpaRepository,
    private val identifierSource: IdentifierSource,
) : AcceptanceTimeoutWorkReprocessingCaseOperations {
    override fun openAcceptanceTimeoutWorkCase(command: OpenReprocessingCaseCommand): UUID {
        require(command.ownerReference.isNotBlank())
        require(command.reason.isNotBlank())
        require(command.correlationId.isNotBlank())
        repository
            .findByCaseTypeAndOwnerReference(
                ReprocessingCaseType.ACCEPTANCE_TIMEOUT_WORK,
                command.ownerReference,
            )?.let { return it.id }
        return repository
            .save(
                ReprocessingCaseEntity(
                    id = identifierSource.next(),
                    caseType = ReprocessingCaseType.ACCEPTANCE_TIMEOUT_WORK,
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

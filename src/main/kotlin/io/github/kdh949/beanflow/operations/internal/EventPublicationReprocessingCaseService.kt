package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.EventPublicationReprocessingCaseOperations
import io.github.kdh949.beanflow.operations.api.OpenReprocessingCaseCommand
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.stereotype.Service
import java.util.UUID

@Service
internal class EventPublicationReprocessingCaseService(
    private val repository: ReprocessingCaseJpaRepository,
    private val identifierSource: IdentifierSource,
) : EventPublicationReprocessingCaseOperations {
    override fun openEventPublicationCase(command: OpenReprocessingCaseCommand): UUID {
        require(command.ownerReference.isNotBlank())
        require(command.reason.isNotBlank())
        require(command.correlationId.isNotBlank())
        repository
            .findByCaseTypeAndOwnerReference(
                ReprocessingCaseType.EVENT_PUBLICATION,
                command.ownerReference,
            )?.let { return it.id }
        return repository
            .save(
                ReprocessingCaseEntity(
                    id = identifierSource.next(),
                    caseType = ReprocessingCaseType.EVENT_PUBLICATION,
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

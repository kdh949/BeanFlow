package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.EventPublicationManualReviewResult
import io.github.kdh949.beanflow.operations.api.EventPublicationReprocessingCaseOperations
import io.github.kdh949.beanflow.operations.api.OpenReprocessingCaseCommand
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp

@Service
internal class EventPublicationReprocessingCaseService(
    private val repository: ReprocessingCaseJpaRepository,
    private val identifierSource: IdentifierSource,
    private val jdbcTemplate: JdbcTemplate,
) : EventPublicationReprocessingCaseOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun openEventPublicationCase(command: OpenReprocessingCaseCommand): EventPublicationManualReviewResult {
        require(command.ownerReference.isNotBlank())
        require(command.reason.isNotBlank())
        require(command.correlationId.isNotBlank())
        val id = identifierSource.next()
        val inserted =
            jdbcTemplate.update(
                """
                INSERT INTO operations_reprocessing_case
                    (id, case_type, owner_reference, status, reason, correlation_id, created_at, updated_at, version)
                VALUES (?, 'EVENT_PUBLICATION', ?, 'MANUAL_REVIEW', ?, ?, ?, ?, 0)
                ON CONFLICT (case_type, owner_reference) DO NOTHING
                """.trimIndent(),
                id,
                command.ownerReference,
                command.reason,
                command.correlationId,
                Timestamp.from(command.now),
                Timestamp.from(command.now),
            )
        if (inserted == 1) return EventPublicationManualReviewResult(id, true)
        val existing =
            requireNotNull(
                repository.findByCaseTypeAndOwnerReference(
                    ReprocessingCaseType.EVENT_PUBLICATION,
                    command.ownerReference,
                ),
            )
        return EventPublicationManualReviewResult(existing.id, false)
    }
}

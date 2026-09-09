package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.eventing.api.OrderCancelledV1
import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import io.github.kdh949.beanflow.operations.api.EventPublicationReprocessingCaseOperations
import io.github.kdh949.beanflow.operations.api.OpenReprocessingCaseCommand
import io.github.kdh949.beanflow.operations.api.OrderCompensationOperations
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
internal class EventPublicationManualReviewService(
    private val queries: EventPublicationRecoveryQueries,
    private val cases: EventPublicationReprocessingCaseOperations,
    private val compensationOperations: OrderCompensationOperations,
    private val compensationTargets: CompensationPublicationTargetRegistry,
) {
    @Transactional
    fun transition(
        publicationId: UUID,
        now: Instant,
    ): PublicationManualReviewTransition? {
        val exhausted = queries.lockExhausted(publicationId) ?: return null
        val publication = exhausted.publication
        val event = publication.event
        val compensationOrderId =
            when (event) {
                is OrderRejectedV1 -> event.orderId
                is OrderCancelledV1 -> event.orderId
                else -> null
            }
        val listenerId = publication.targetIdentifier.value
        val stepType = if (compensationOrderId == null) null else compensationTargets.find(event, listenerId)
        val unmapped = compensationOrderId != null && stepType == null
        val reason = if (unmapped) "PUBLICATION_TARGET_UNMAPPED" else "EVENT_PUBLICATION_RETRY_EXHAUSTED"
        val correlationId = exhausted.correlationId ?: publicationId.toString()
        val result =
            cases.openEventPublicationCase(
                OpenReprocessingCaseCommand(
                    ownerReference = "event-publication:$publicationId",
                    reason = reason,
                    correlationId = correlationId,
                    now = now,
                ),
            )
        if (!result.transitioned) return null
        if (stepType != null) {
            compensationOperations.markPublicationManualReview(
                requireNotNull(compensationOrderId),
                stepType,
                "EVENT_PUBLICATION_RETRY_EXHAUSTED",
                now,
            )
        }
        return PublicationManualReviewTransition(
            publicationId,
            exhausted.eventId,
            event.javaClass.simpleName,
            listenerId,
            correlationId,
            result.caseId,
            publication.completionAttempts,
            reason,
            unmapped,
        )
    }
}

internal data class PublicationManualReviewTransition(
    val publicationId: UUID,
    val eventId: String?,
    val eventType: String,
    val listenerId: String,
    val correlationId: String,
    val caseId: UUID,
    val attempts: Int,
    val reason: String,
    val unmapped: Boolean,
)

package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.operations.api.OpenOperationsSupportInvestigationCommand
import io.github.kdh949.beanflow.operations.api.OperationsSupportInvestigationOperations
import io.github.kdh949.beanflow.ordering.api.OrderingSupportTimelineOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.support.internal.domain.SupportActionDecision
import io.github.kdh949.beanflow.support.internal.domain.SupportActionRequestState
import io.github.kdh949.beanflow.support.internal.domain.SupportActionType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class SupportActionRequestApplicationService(
    private val createHandler: CreateSupportActionRequestHandler,
    private val reviseHandler: ReviseSupportActionRequestHandler,
    private val managerDecisionHandler: DecideSupportManagerHandler,
    private val reassignHandler: ReassignSupportActionRequestHandler,
    private val queryHandler: QuerySupportActionRequestHandler,
) {
    fun create(command: CreateSupportActionRequestCommand): SupportActionRequestResource = createHandler.handle(command)

    fun revise(command: ReviseSupportActionRequestCommand): SupportActionRequestResource = reviseHandler.handle(command)

    fun decideSupportManager(command: DecideSupportManagerApprovalCommand): SupportActionCommandOutcome =
        managerDecisionHandler.handle(command)

    fun get(
        actorId: UUID,
        requestId: UUID,
    ): SupportActionRequestResource = queryHandler.get(actorId, requestId)

    fun reassign(command: ReassignSupportActionRequestCommand): SupportActionRequestResource = reassignHandler.handle(command)
}

@Service
internal class CreateSupportActionRequestHandler(
    private val evaluations: SupportActionEvaluationApplicationService,
    private val transactions: SupportActionRequestTransactionService,
) {
    fun handle(command: CreateSupportActionRequestCommand): SupportActionRequestResource {
        val evaluation =
            evaluations.evaluate(
                EvaluateSupportActionCommand(
                    command.actorId,
                    command.caseId,
                    command.action,
                    command.orderId,
                    command.expectedTargetVersion,
                    command.verificationSessionId,
                ),
            )
        if (evaluation.decision == SupportActionDecision.DENIED) {
            throw DomainFailure(FailureCode.SUPPORT_ACTION_POLICY_DENIED, "Current action policy denies this request")
        }
        return transactions.create(command, evaluation)
    }
}

@Service
internal class ReviseSupportActionRequestHandler(
    private val evaluations: SupportActionEvaluationApplicationService,
    private val transactions: SupportActionRequestTransactionService,
) {
    fun handle(command: ReviseSupportActionRequestCommand): SupportActionRequestResource {
        val guard = transactions.requesterGuard(command.actorId, command.requestId)
        val evaluation =
            evaluations.evaluate(
                EvaluateSupportActionCommand(
                    command.actorId,
                    guard.caseId,
                    guard.action,
                    guard.targetId,
                    command.expectedTargetVersion,
                    command.verificationSessionId,
                ),
            )
        if (evaluation.decision == SupportActionDecision.DENIED) {
            throw DomainFailure(FailureCode.SUPPORT_ACTION_POLICY_DENIED, "Current action policy denies the new revision")
        }
        return transactions.revise(command, evaluation)
    }
}

@Service
internal class DecideSupportManagerHandler(
    private val ordering: OrderingSupportTimelineOperations,
    private val profileVersions: SupportProfileChangeTargetVersionOperations,
    private val investigations: OperationsSupportInvestigationOperations,
    private val transactions: SupportActionRequestTransactionService,
) {
    @Transactional
    fun handle(command: DecideSupportManagerApprovalCommand): SupportActionCommandOutcome {
        val guard = transactions.managerGuard(command.actorId, command.requestId)
        val currentTargetVersion =
            when (guard.action) {
                SupportActionType.GOODWILL_COMPENSATION -> transactions.compensationTargetVersion(guard.targetId)
                SupportActionType.PROFILE_CHANGE -> profileVersions.currentVersion(guard.targetId)
                else -> ordering.findOrderSnapshots(setOf(guard.targetId)).singleOrNull()?.version ?: notFound("Order")
            }
        val outcome = transactions.decideSupportManager(command, currentTargetVersion)
        if (outcome is SupportActionCommandOutcome.Succeeded &&
            outcome.resource.action == SupportActionType.PROFILE_CHANGE &&
            outcome.resource.state == SupportActionRequestState.AWAITING_OPERATIONS
        ) {
            val binding = transactions.profileInvestigationBinding(outcome.resource.requestId)
            investigations.open(
                OpenOperationsSupportInvestigationCommand(
                    binding.requestId,
                    binding.revisionId,
                    binding.revisionNumber,
                    binding.requesterActorId,
                    binding.supportApproverActorId,
                    binding.executorActorId,
                    binding.expiresAt,
                    binding.occurredAt,
                ),
            )
        }
        return outcome
    }

    private fun notFound(resource: String): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "$resource was not found")
}

@Service
internal class ReassignSupportActionRequestHandler(
    private val transactions: SupportActionRequestTransactionService,
) {
    fun handle(command: ReassignSupportActionRequestCommand): SupportActionRequestResource = transactions.reassign(command)
}

@Service
internal class QuerySupportActionRequestHandler(
    private val transactions: SupportActionRequestTransactionService,
) {
    fun get(
        actorId: UUID,
        requestId: UUID,
    ): SupportActionRequestResource = transactions.get(actorId, requestId)
}

@Service
internal class SupportActionRequestProfileApprovalHandler(
    private val transactions: SupportActionRequestTransactionService,
) {
    fun open(command: OpenProfileChangeApprovalCommand): SupportActionRequestResource = transactions.openProfileChangeApproval(command)

    fun revise(command: ReviseProfileChangeApprovalCommand): SupportActionRequestResource =
        transactions.reviseProfileChangeApproval(command)

    fun complete(command: ProfileChangeExecutionApprovalCommand): SupportActionRequestResource =
        transactions.completeProfileChangeApproval(command)
}

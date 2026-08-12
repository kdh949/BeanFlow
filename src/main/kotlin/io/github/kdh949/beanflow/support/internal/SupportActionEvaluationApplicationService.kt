package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.ordering.api.OrderingSupportTimelineOperations
import io.github.kdh949.beanflow.ordering.api.SupportOrderSnapshot
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.support.internal.domain.SupportActionApprovalRequirement
import io.github.kdh949.beanflow.support.internal.domain.SupportActionDecision
import io.github.kdh949.beanflow.support.internal.domain.SupportActionOrderState
import io.github.kdh949.beanflow.support.internal.domain.SupportActionPolicy
import io.github.kdh949.beanflow.support.internal.domain.SupportActionPolicyInput
import io.github.kdh949.beanflow.support.internal.domain.SupportActionReasonCode
import io.github.kdh949.beanflow.support.internal.domain.SupportActionType
import io.github.kdh949.beanflow.support.internal.domain.SupportCaseState
import io.github.kdh949.beanflow.support.internal.domain.VerificationActionScope
import io.github.kdh949.beanflow.support.internal.domain.VerificationLevel
import io.github.kdh949.beanflow.support.internal.domain.VerificationPurpose
import io.github.kdh949.beanflow.support.internal.domain.VerificationState
import io.github.kdh949.beanflow.support.internal.domain.VerificationSubjectType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class EvaluateSupportActionCommand(
    val actorId: UUID,
    val caseId: UUID,
    val action: SupportActionType,
    val orderId: UUID,
    val expectedTargetVersion: Long,
    val verificationSessionId: UUID,
)

internal data class SupportActionEvaluationResource(
    val action: SupportActionType,
    val orderId: UUID,
    val decision: SupportActionDecision,
    val reasonCodes: List<SupportActionReasonCode>,
    val requiredPermissions: List<OperatorPermission>,
    val requiredVerificationLevel: VerificationLevel,
    val approvalRequirements: List<SupportActionApprovalRequirement>,
    val policyVersion: String,
    val targetVersion: Long,
    val evaluatedAt: Instant,
    val expiresAt: Instant,
)

internal data class SupportActionAuthorizationSnapshot(
    val caseEligible: Boolean,
    val subjectType: VerificationSubjectType,
    val subjectId: UUID,
    val subjectRelationship: SupportSubjectRelationship,
    val hasGenericPermission: Boolean,
    val hasCapabilityPermission: Boolean,
    val verificationScope: VerificationActionScope,
    val verificationPurpose: VerificationPurpose,
    val verificationLevel: VerificationLevel,
) {
    fun relationshipMatches(order: SupportOrderSnapshot): Boolean =
        when (subjectType) {
            VerificationSubjectType.CUSTOMER -> {
                subjectId == order.customerId &&
                    subjectRelationship in
                    setOf(SupportSubjectRelationship.REQUESTER, SupportSubjectRelationship.AFFECTED_CUSTOMER)
            }

            VerificationSubjectType.STORE -> {
                subjectId == order.storeId &&
                    subjectRelationship in setOf(SupportSubjectRelationship.REQUESTER, SupportSubjectRelationship.AFFECTED_STORE)
            }

            VerificationSubjectType.DELIVERY -> {
                false
            }
        }
}

@Service
internal class SupportActionEvaluationAuthorization(
    private val permissions: OperatorPermissionAuthorization,
    private val cases: SupportCaseJpaRepository,
    private val subjectLinks: SupportCaseSubjectLinkJpaRepository,
    private val sessions: VerificationSessionJpaRepository,
    private val clock: Clock,
) {
    @Transactional
    fun authorizeTarget(
        actorId: UUID,
        caseId: UUID,
        orderId: UUID,
    ) {
        requireReadableTarget(actorId, caseId, orderId)
    }

    @Transactional
    fun loadPolicySnapshot(command: EvaluateSupportActionCommand): SupportActionAuthorizationSnapshot {
        val supportCase = requireReadableTarget(command.actorId, command.caseId, command.orderId)
        val session = sessions.findLockedById(command.verificationSessionId) ?: notFound("VerificationSession")
        if (session.actorId != command.actorId || session.supportCaseId != command.caseId) denied()
        val subjectLink =
            subjectLinks
                .findByIdAndSupportCaseId(session.subjectLinkId, command.caseId)
                ?.takeIf { it.unlinkedAt == null && it.subjectId == session.subjectId }
                ?: denied()
        val now = clock.instant()
        if ((session.state == VerificationState.PENDING || session.state == VerificationState.VERIFIED) &&
            !now.isBefore(session.expiresAt)
        ) {
            session.state = VerificationState.EXPIRED
            session.version += 1
            sessions.saveAndFlush(session)
        }
        val level =
            if (session.state == VerificationState.VERIFIED) {
                session.requestedLevel
            } else {
                VerificationLevel.UNVERIFIED
            }
        return SupportActionAuthorizationSnapshot(
            caseEligible = supportCase.state in ACTIVE_CASE_STATES,
            subjectType = session.subjectType,
            subjectId = session.subjectId,
            subjectRelationship = subjectLink.relationship,
            hasGenericPermission = permissions.hasActive(command.actorId, OperatorPermission.SUPPORT_ACTION_REQUEST),
            hasCapabilityPermission = permissions.hasActive(command.actorId, command.action.capabilityPermission()),
            verificationScope = session.actionScope,
            verificationPurpose = session.purpose,
            verificationLevel = level,
        )
    }

    private fun requireReadableTarget(
        actorId: UUID,
        caseId: UUID,
        orderId: UUID,
    ): SupportCaseEntity {
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_ORDER_READ)
        val supportCase = cases.findLockedById(caseId) ?: notFound("SupportCase")
        if (supportCase.currentAssigneeId != actorId) denied()
        val linked =
            subjectLinks.existsBySupportCaseIdAndSubjectTypeAndSubjectIdAndRelationshipAndUnlinkedAtIsNull(
                caseId,
                SupportSubjectType.ORDER,
                orderId,
                SupportSubjectRelationship.RELATED_ORDER,
            )
        if (!linked) denied()
        return supportCase
    }

    private fun denied(): Nothing = throw DomainFailure(FailureCode.ACCESS_DENIED, "Support action object scope is required")

    private fun notFound(resource: String): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "$resource was not found")

    private companion object {
        val ACTIVE_CASE_STATES = setOf(SupportCaseState.OPEN, SupportCaseState.IN_PROGRESS, SupportCaseState.WAITING)
    }
}

@Service
internal class SupportActionEvaluationApplicationService(
    private val authorization: SupportActionEvaluationAuthorization,
    private val ordering: OrderingSupportTimelineOperations,
    private val clock: Clock,
) {
    private val policy = SupportActionPolicy()

    fun evaluate(command: EvaluateSupportActionCommand): SupportActionEvaluationResource {
        authorization.authorizeTarget(command.actorId, command.caseId, command.orderId)
        val order = ordering.findOrderSnapshots(setOf(command.orderId)).singleOrNull() ?: notFound()
        val access = authorization.loadPolicySnapshot(command)
        val evaluated =
            policy.evaluate(
                SupportActionPolicyInput(
                    action = command.action,
                    targetState = SupportActionOrderState.valueOf(order.state.name),
                    expectedTargetVersion = command.expectedTargetVersion,
                    currentTargetVersion = order.version,
                    caseEligible = access.caseEligible,
                    relationshipMatches = access.relationshipMatches(order),
                    hasGenericPermission = access.hasGenericPermission,
                    hasCapabilityPermission = access.hasCapabilityPermission,
                    verificationScope = access.verificationScope,
                    verificationPurpose = access.verificationPurpose,
                    verificationLevel = access.verificationLevel,
                ),
                clock.instant(),
            )
        return SupportActionEvaluationResource(
            action = command.action,
            orderId = command.orderId,
            decision = evaluated.decision,
            reasonCodes = evaluated.reasonCodes,
            requiredPermissions =
                listOf(OperatorPermission.SUPPORT_ACTION_REQUEST, command.action.capabilityPermission()),
            requiredVerificationLevel = evaluated.requiredVerificationLevel,
            approvalRequirements = evaluated.approvalRequirements,
            policyVersion = evaluated.policyVersion,
            targetVersion = evaluated.targetVersion,
            evaluatedAt = evaluated.evaluatedAt,
            expiresAt = evaluated.expiresAt,
        )
    }

    private fun notFound(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Order was not found")
}

internal fun SupportActionType.capabilityPermission(): OperatorPermission =
    when (this) {
        SupportActionType.ORDER_CANCELLATION -> OperatorPermission.SUPPORT_ORDER_CANCEL
        SupportActionType.PICKUP_RESCHEDULE -> OperatorPermission.SUPPORT_PICKUP_RESCHEDULE
        SupportActionType.POST_ACCEPTANCE_RESOLUTION -> OperatorPermission.SUPPORT_RESOLUTION_REQUEST
        SupportActionType.GOODWILL_COMPENSATION -> OperatorPermission.SUPPORT_COMPENSATION_REQUEST
    }

internal fun SupportActionType.executionCapabilityPermission(): OperatorPermission =
    if (this == SupportActionType.GOODWILL_COMPENSATION) {
        OperatorPermission.SUPPORT_COMPENSATION_EXECUTE
    } else {
        capabilityPermission()
    }

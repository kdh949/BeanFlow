package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OperatorActor
import io.github.kdh949.beanflow.support.internal.domain.ProfileChangePurpose
import io.github.kdh949.beanflow.support.internal.domain.ProfileOwnerType
import io.github.kdh949.beanflow.support.internal.domain.ProfileRiskClass
import io.github.kdh949.beanflow.support.internal.domain.SupportActionRequestState
import io.github.kdh949.beanflow.support.internal.domain.SupportActionType
import io.github.kdh949.beanflow.support.internal.domain.SupportCaseState
import io.github.kdh949.beanflow.support.internal.domain.SupportProfileChangeState
import io.github.kdh949.beanflow.support.internal.domain.SupportProfileNotificationState
import io.github.kdh949.beanflow.support.internal.domain.VerificationLevel
import io.github.kdh949.beanflow.support.internal.domain.VerificationState
import io.github.kdh949.beanflow.support.internal.domain.descriptor
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class SupportProfileContextResource(
    val caseId: UUID,
    val subjectLinkId: UUID,
    val subjectId: UUID,
    val subjectType: ProfileChangeSubjectType,
    val purpose: ProfileChangePurpose,
    val riskClass: ProfileRiskClass,
    val requiredVerificationLevel: VerificationLevel,
    val currentProfileVersion: Long,
)

internal enum class SupportProfileWorkflowAction { REVISE, DECIDE_SUPPORT_MANAGER, REASSIGN, EXECUTE, RETRY_NOTIFICATION }

internal data class SupportProfileWorkflowResource(
    val profileChange: SupportProfileChangeResource,
    val approval: SupportActionRequestResource?,
    val caseVersion: Long,
    val currentProfileVersion: Long,
    val verificationExpiresAt: Instant,
    val allowedActions: List<SupportProfileWorkflowAction>,
)

@Service
internal class SupportProfileWorkflowQuery(
    private val transactions: SupportProfileChangeTransactionService,
    private val actions: SupportActionRequestTransactionService,
    private val owners: SupportProfileChangeOwnerHandler,
    private val cases: SupportCaseJpaRepository,
    private val links: SupportCaseSubjectLinkJpaRepository,
    private val sessions: VerificationSessionJpaRepository,
    private val permissions: OperatorPermissionAuthorization,
    private val clock: Clock,
) {
    @Transactional
    fun context(
        actorId: UUID,
        caseId: UUID,
        subjectLinkId: UUID,
        purpose: ProfileChangePurpose,
    ): SupportProfileContextResource {
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_WRITE)
        permissions.requireActive(actorId, purpose.requestPermission())
        val descriptor = purpose.descriptor()
        if (descriptor.requiresDualApproval) permissions.requireActive(actorId, OperatorPermission.SUPPORT_ACTION_REQUEST)
        val supportCase = cases.findById(caseId).orElse(null) ?: missing()
        if (supportCase.currentAssigneeId != actorId || supportCase.state !in ACTIVE_CASE_STATES) denied()
        val link = links.findByIdAndSupportCaseId(subjectLinkId, caseId) ?: missing()
        val subjectType = descriptor.owner.profileSubjectType()
        if (link.unlinkedAt != null || link.subjectType != descriptor.owner.caseSubjectType()) denied()
        return SupportProfileContextResource(
            caseId,
            subjectLinkId,
            link.subjectId,
            subjectType,
            purpose,
            descriptor.risk,
            if (descriptor.risk == ProfileRiskClass.R1) VerificationLevel.BASIC else VerificationLevel.ENHANCED,
            owners.currentVersion(purpose, link.subjectId),
        )
    }

    @Transactional
    fun workflow(
        actorId: UUID,
        profileChangeId: UUID,
    ): SupportProfileWorkflowResource {
        val profile = transactions.get(actorId, profileChangeId)
        // Authorize requester grants before the approval row, matching profile revision writers.
        val requesterCanExecute =
            profile.executorActorId == actorId &&
                listOf(
                    OperatorPermission.SUPPORT_CASE_READ,
                    OperatorPermission.SUPPORT_CASE_WRITE,
                    OperatorPermission.SUPPORT_ACTION_REQUEST,
                    OperatorPermission.SUPPORT_PROFILE_R3_REQUEST,
                ).all { permissions.hasActive(profile.requesterActorId, it) }
        val request = profile.actionRequestId?.let { actions.get(actorId, it) }
        if (request != null && (
                request.action != SupportActionType.PROFILE_CHANGE || request.targetId != profileChangeId ||
                    request.actionPayloadDigest != profile.payloadDigest || request.targetVersion != profile.expectedProfileVersion
            )
        ) {
            throw DomainFailure(FailureCode.SUPPORT_ACTION_REQUEST_STALE, "Profile approval binding is stale")
        }
        val supportCase = cases.findById(profile.caseId).orElse(null) ?: missing()
        val session = sessions.findById(profile.verificationSessionId).orElse(null) ?: missing()
        val linked =
            links.findBySupportCaseIdAndUnlinkedAtIsNullOrderByLinkedAtAsc(profile.caseId).any {
                it.subjectId == profile.subjectId && it.subjectType ==
                    profile.purpose
                        .descriptor()
                        .owner
                        .caseSubjectType() &&
                    it.id == session.subjectLinkId
            }
        val active = supportCase.state in ACTIVE_CASE_STATES && linked
        val current = active && session.state == VerificationState.VERIFIED && clock.instant().isBefore(session.expiresAt)
        val ownerVersion = owners.currentVersion(profile.purpose, profile.subjectId)
        val allowed = mutableListOf<SupportProfileWorkflowAction>()

        fun has(permission: OperatorPermission) = permissions.hasActive(actorId, permission)
        val assigned = active && supportCase.currentAssigneeId == actorId && has(OperatorPermission.SUPPORT_CASE_WRITE)
        if (assigned && profile.notificationState == SupportProfileNotificationState.RETRY_SCHEDULED) {
            allowed += SupportProfileWorkflowAction.RETRY_NOTIFICATION
        }
        if (request != null && profile.state != SupportProfileChangeState.EXECUTED) {
            if (assigned && actorId == profile.requesterActorId && request.state in REVISION_STATES &&
                has(OperatorPermission.SUPPORT_PROFILE_R3_REQUEST) && has(OperatorPermission.SUPPORT_ACTION_REQUEST)
            ) {
                allowed += SupportProfileWorkflowAction.REVISE
            }
            val fresh = current && clock.instant().isBefore(request.expiresAt) && ownerVersion == profile.expectedProfileVersion
            if (fresh && request.state == SupportActionRequestState.AWAITING_SUPPORT_MANAGER &&
                actorId != request.requesterActorId && actorId != request.executorActorId &&
                has(OperatorPermission.SUPPORT_ACTION_APPROVE) && has(OperatorPermission.SUPPORT_PROFILE_R3_APPROVE)
            ) {
                allowed += SupportProfileWorkflowAction.DECIDE_SUPPORT_MANAGER
            }
            if (fresh && request.state in EXECUTOR_STATES && has(OperatorPermission.SUPPORT_CASE_ASSIGN)) {
                allowed += SupportProfileWorkflowAction.REASSIGN
            }
            if (fresh && assigned && request.state == SupportActionRequestState.READY_FOR_EXECUTION &&
                actorId == request.executorActorId && has(OperatorPermission.SUPPORT_ACTION_EXECUTE) &&
                has(OperatorPermission.SUPPORT_PROFILE_R3_REQUEST) &&
                requesterCanExecute &&
                request.approvalSteps.none { it.decidedByActorId == actorId }
            ) {
                allowed += SupportProfileWorkflowAction.EXECUTE
            }
        }
        return SupportProfileWorkflowResource(profile, request, supportCase.version, ownerVersion, session.expiresAt, allowed)
    }

    private fun ProfileChangePurpose.requestPermission(): OperatorPermission =
        when (descriptor().risk) {
            ProfileRiskClass.R1 -> OperatorPermission.SUPPORT_PROFILE_R1_CHANGE
            ProfileRiskClass.R2 -> OperatorPermission.SUPPORT_PROFILE_R2_CHANGE
            ProfileRiskClass.R3, ProfileRiskClass.R4 -> OperatorPermission.SUPPORT_PROFILE_R3_REQUEST
            ProfileRiskClass.R0 -> throw DomainFailure(FailureCode.INVALID_REQUEST, "System fields cannot be edited")
        }

    private fun ProfileOwnerType.caseSubjectType(): SupportSubjectType =
        when (this) {
            ProfileOwnerType.CUSTOMER -> SupportSubjectType.CUSTOMER
            ProfileOwnerType.STORE -> SupportSubjectType.STORE
            ProfileOwnerType.EXTERNAL_COURIER -> SupportSubjectType.DELIVERY
        }

    private fun ProfileOwnerType.profileSubjectType(): ProfileChangeSubjectType =
        when (this) {
            ProfileOwnerType.CUSTOMER -> ProfileChangeSubjectType.CUSTOMER
            ProfileOwnerType.STORE -> ProfileChangeSubjectType.STORE
            ProfileOwnerType.EXTERNAL_COURIER -> ProfileChangeSubjectType.RIDER
        }

    private fun missing(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Profile workflow resource was not found")

    private fun denied(): Nothing = throw DomainFailure(FailureCode.ACCESS_DENIED, "Current profile case scope is required")

    private companion object {
        val ACTIVE_CASE_STATES = setOf(SupportCaseState.OPEN, SupportCaseState.IN_PROGRESS, SupportCaseState.WAITING)
        val REVISION_STATES =
            setOf(
                SupportActionRequestState.AWAITING_SUPPORT_MANAGER,
                SupportActionRequestState.AWAITING_OPERATIONS,
                SupportActionRequestState.REVISION_REQUIRED,
            )
        val EXECUTOR_STATES = setOf(SupportActionRequestState.READY_FOR_EXECUTION, SupportActionRequestState.REASSIGNMENT_REQUIRED)
    }
}

@RestController
internal class SupportProfileWorkflowController(
    private val query: SupportProfileWorkflowQuery,
) {
    @GetMapping("/api/v1/support/cases/{caseId}/profile-contexts/{linkId}")
    @PreAuthorize("isAuthenticated()")
    fun context(
        actor: OperatorActor,
        @PathVariable caseId: UUID,
        @PathVariable linkId: UUID,
        @RequestParam purpose: ProfileChangePurpose,
    ): ResponseEntity<SupportProfileContextResource> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.context(actor.actorId, caseId, linkId, purpose))

    @GetMapping("/api/v1/support/profile-changes/{profileChangeId}/workflow")
    @PreAuthorize("isAuthenticated()")
    fun workflow(
        actor: OperatorActor,
        @PathVariable profileChangeId: UUID,
    ): ResponseEntity<SupportProfileWorkflowResource> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.workflow(actor.actorId, profileChangeId))
}

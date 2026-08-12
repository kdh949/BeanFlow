package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.delivery.api.ExternalCourierSupportProfileChangeOperations
import io.github.kdh949.beanflow.identity.api.CustomerSupportProfileChangeOperations
import io.github.kdh949.beanflow.merchant.api.StoreSupportProfileChangeOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.support.internal.domain.ProfileOwnerType
import io.github.kdh949.beanflow.support.internal.domain.SupportActionType
import io.github.kdh949.beanflow.support.internal.domain.descriptor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

internal const val PROFILE_CHANGE_POLICY_VERSION = "support-profile-change-policy/2026-08-13/v1"

internal data class OpenProfileChangeApprovalCommand(
    val requestId: UUID,
    val revisionId: UUID,
    val profileChangeId: UUID,
    val caseId: UUID,
    val actorId: UUID,
    val verificationSessionId: UUID,
    val expectedProfileVersion: Long,
    val payloadDigest: String,
    val reason: String,
    val evidenceDigest: String,
    val expiresAt: Instant,
    val occurredAt: Instant,
)

internal data class ReviseProfileChangeApprovalCommand(
    val profileChangeId: UUID,
    val actionRequestId: UUID,
    val actorId: UUID,
    val expectedRequestVersion: Long,
    val verificationSessionId: UUID,
    val expectedProfileVersion: Long,
    val payloadDigest: String,
    val reason: String,
    val evidenceDigest: String,
    val expiresAt: Instant,
    val occurredAt: Instant,
)

internal data class ProfileChangeExecutionApprovalCommand(
    val profileChangeId: UUID,
    val actionRequestId: UUID,
    val actorId: UUID,
    val revisionNumber: Int,
    val expectedRequestVersion: Long,
    val payloadDigest: String,
    val expectedProfileVersion: Long,
    val occurredAt: Instant,
)

internal data class ProfileChangeInvestigationBinding(
    val requestId: UUID,
    val revisionId: UUID,
    val revisionNumber: Int,
    val requesterActorId: UUID,
    val supportApproverActorId: UUID,
    val executorActorId: UUID,
    val expiresAt: Instant,
    val occurredAt: Instant,
)

internal interface SupportProfileChangeTargetVersionOperations {
    fun currentVersion(profileChangeId: UUID): Long
}

@Service
internal class SupportProfileChangeTargetVersionService(
    private val changes: SupportProfileChangeJpaRepository,
    private val customers: CustomerSupportProfileChangeOperations,
    private val stores: StoreSupportProfileChangeOperations,
    private val couriers: ExternalCourierSupportProfileChangeOperations,
) : SupportProfileChangeTargetVersionOperations {
    @Transactional(readOnly = true)
    override fun currentVersion(profileChangeId: UUID): Long {
        val change = changes.findById(profileChangeId).orElse(null) ?: notFound()
        return when (change.purpose.descriptor().owner) {
            ProfileOwnerType.CUSTOMER -> customers.currentVersion(change.subjectId)
            ProfileOwnerType.STORE -> stores.currentVersion(change.subjectId)
            ProfileOwnerType.EXTERNAL_COURIER -> couriers.currentVersion(change.subjectId)
        }
    }

    private fun notFound(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Profile change was not found")
}

internal interface SupportActionReassignmentProjectionUpdater {
    fun update(
        requestId: UUID,
        action: SupportActionType,
        actorId: UUID,
        occurredAt: Instant,
    )
}

@Service
internal class SupportProfileChangeReassignmentProjectionUpdater(
    private val changes: SupportProfileChangeJpaRepository,
) : SupportActionReassignmentProjectionUpdater {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun update(
        requestId: UUID,
        action: SupportActionType,
        actorId: UUID,
        occurredAt: Instant,
    ) {
        if (action != SupportActionType.PROFILE_CHANGE) return
        val entity = changes.findByActionRequestId(requestId) ?: notFound()
        entity.executorActorId = actorId
        entity.updatedAt = occurredAt
        entity.version++
        changes.saveAndFlush(entity)
    }

    private fun notFound(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Profile change was not found")
}

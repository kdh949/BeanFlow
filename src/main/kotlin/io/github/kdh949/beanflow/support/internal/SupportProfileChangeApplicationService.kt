package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.delivery.api.ExternalCourierSupportProfileChangeOperations
import io.github.kdh949.beanflow.delivery.api.PrepareCourierDisplayNameCorrection
import io.github.kdh949.beanflow.delivery.api.PrepareCourierPayoutReferenceChange
import io.github.kdh949.beanflow.delivery.api.PrepareCourierProviderIdentityChange
import io.github.kdh949.beanflow.delivery.api.PrepareCourierProviderReregistration
import io.github.kdh949.beanflow.delivery.api.PrepareCourierRelayContactCorrection
import io.github.kdh949.beanflow.identity.api.CustomerSupportProfileChangeOperations
import io.github.kdh949.beanflow.identity.api.PrepareCustomerCredentialReset
import io.github.kdh949.beanflow.identity.api.PrepareCustomerDisplayNameCorrection
import io.github.kdh949.beanflow.identity.api.PrepareCustomerLegalNameCorrection
import io.github.kdh949.beanflow.identity.api.PrepareCustomerPrimaryPhoneChange
import io.github.kdh949.beanflow.merchant.api.PrepareStoreAccessReregistration
import io.github.kdh949.beanflow.merchant.api.PrepareStoreOperationsContactCorrection
import io.github.kdh949.beanflow.merchant.api.PrepareStorePublicProfileCorrection
import io.github.kdh949.beanflow.merchant.api.PrepareStoreRepresentativeChange
import io.github.kdh949.beanflow.merchant.api.PrepareStoreSettlementAccountChange
import io.github.kdh949.beanflow.merchant.api.StoreSupportProfileChangeOperations
import io.github.kdh949.beanflow.notification.api.ProfileChangeNotificationOperations
import io.github.kdh949.beanflow.notification.api.ProfileNotificationOwnerType
import io.github.kdh949.beanflow.notification.api.RequestProfileChangeNotificationCommand
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.OwnerProfileChangeResult
import io.github.kdh949.beanflow.shared.api.ProfileNotificationChannel
import io.github.kdh949.beanflow.shared.api.ProfileNotificationTargetKind
import io.github.kdh949.beanflow.support.internal.domain.ChallengeState
import io.github.kdh949.beanflow.support.internal.domain.ProfileChangePurpose
import io.github.kdh949.beanflow.support.internal.domain.ProfileOwnerType
import io.github.kdh949.beanflow.support.internal.domain.ProfileRiskClass
import io.github.kdh949.beanflow.support.internal.domain.SupportActionRequestState
import io.github.kdh949.beanflow.support.internal.domain.SupportCaseState
import io.github.kdh949.beanflow.support.internal.domain.SupportProfileChange
import io.github.kdh949.beanflow.support.internal.domain.SupportProfileChangeState
import io.github.kdh949.beanflow.support.internal.domain.SupportProfileNotificationState
import io.github.kdh949.beanflow.support.internal.domain.VerificationActionScope
import io.github.kdh949.beanflow.support.internal.domain.VerificationChannel
import io.github.kdh949.beanflow.support.internal.domain.VerificationLevel
import io.github.kdh949.beanflow.support.internal.domain.VerificationPurpose
import io.github.kdh949.beanflow.support.internal.domain.VerificationState
import io.github.kdh949.beanflow.support.internal.domain.VerificationSubjectType
import io.github.kdh949.beanflow.support.internal.domain.descriptor
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal data class ProfileChangePreflight(
    val expiresAt: Instant,
    val currentOwnerVersion: Long,
)

internal data class ProfileChangeExecutionBinding(
    val entity: SupportProfileChangeResource,
    val actionRequestId: UUID,
)

internal data class ProfileNotificationDispatch(
    val claimId: UUID,
    val lineId: UUID,
    val profileChangeId: UUID,
    val ownerType: ProfileNotificationOwnerType,
    val ownerTargetId: UUID,
    val targetKind: ProfileNotificationTargetKind,
    val channel: ProfileNotificationChannel,
    val purpose: String,
    val occurredAt: Instant,
    val correlationId: String,
)

@Service
internal class SupportProfileChangeApplicationService(
    private val transactions: SupportProfileChangeTransactionService,
    private val customers: CustomerSupportProfileChangeOperations,
    private val stores: StoreSupportProfileChangeOperations,
    private val couriers: ExternalCourierSupportProfileChangeOperations,
    private val notifications: ProfileChangeNotificationOperations,
    private val identifiers: IdentifierSource,
) {
    fun submit(command: SubmitSupportProfileChangeCommand): SupportProfileChangeResource {
        val payloadDigest = SupportProfilePayloadDigest.digest(command.subjectId, command.expectedProfileVersion, command.payload)
        transactions.replaySubmit(command, payloadDigest)?.let {
            return if (it.state == SupportProfileChangeState.EXECUTED) dispatchNotifications(it.profileChangeId, false) else it
        }
        val ownerVersion = currentOwnerVersion(command.payload.purpose, command.subjectId)
        transactions.preflight(command, ownerVersion)
        val profileChangeId = identifiers.next()
        val resource =
            if (command.payload.purpose
                    .descriptor()
                    .requiresDualApproval
            ) {
                transactions.requestApproval(profileChangeId, command, payloadDigest, ownerVersion)
            } else {
                val prepared = prepare(profileChangeId, command.subjectId, command.expectedProfileVersion, command.payload)
                transactions.executeDirect(profileChangeId, command, payloadDigest, ownerVersion, prepared)
            }
        return if (resource.state == SupportProfileChangeState.EXECUTED) {
            dispatchNotifications(resource.profileChangeId, false)
        } else {
            resource
        }
    }

    fun revise(command: ReviseSupportProfileChangeCommand): SupportProfileChangeResource {
        transactions.replayRevision(command)?.let { return it }
        val binding = transactions.revisionBinding(command)
        if (binding.purpose != command.payload.purpose) stale()
        val digest = SupportProfilePayloadDigest.digest(binding.subjectId, command.expectedProfileVersion, command.payload)
        val ownerVersion = currentOwnerVersion(command.payload.purpose, binding.subjectId)
        if (ownerVersion != command.expectedProfileVersion) stale()
        return transactions.revise(command, digest, ownerVersion)
    }

    fun execute(command: ExecuteSupportProfileChangeCommand): SupportProfileChangeResource {
        transactions.replayExecution(command)?.let { return dispatchNotifications(it.profileChangeId, false) }
        val binding = transactions.executionBinding(command)
        if (binding.entity.purpose != command.payload.purpose) stale()
        val digest = SupportProfilePayloadDigest.digest(binding.entity.subjectId, command.expectedProfileVersion, command.payload)
        if (digest != binding.entity.payloadDigest) stale()
        val ownerVersion = currentOwnerVersion(binding.entity.purpose, binding.entity.subjectId)
        if (ownerVersion != command.expectedProfileVersion) stale()
        val prepared = prepare(command.profileChangeId, binding.entity.subjectId, command.expectedProfileVersion, command.payload)
        val resource = transactions.executeApproved(command, digest, ownerVersion, prepared)
        return dispatchNotifications(resource.profileChangeId, false)
    }

    fun retryNotifications(command: RetrySupportProfileNotificationCommand): SupportProfileChangeResource {
        transactions.authorizeRetry(command)
        return dispatchNotifications(command.profileChangeId, true)
    }

    fun get(
        actorId: UUID,
        profileChangeId: UUID,
    ): SupportProfileChangeResource = transactions.get(actorId, profileChangeId)

    fun recoverNotifications(): Int {
        val profileChangeIds = transactions.recoverableNotificationProfileChangeIds()
        profileChangeIds.forEach { dispatchNotifications(it, false) }
        return profileChangeIds.size
    }

    private fun dispatchNotifications(
        profileChangeId: UUID,
        includeRetryScheduled: Boolean,
    ): SupportProfileChangeResource {
        val claimId = identifiers.next()
        transactions.claimNotifications(profileChangeId, claimId, includeRetryScheduled).forEach { dispatch ->
            try {
                val accepted =
                    notifications.requestProfileChange(
                        RequestProfileChangeNotificationCommand(
                            dispatch.profileChangeId,
                            dispatch.ownerType,
                            dispatch.ownerTargetId,
                            dispatch.targetKind,
                            dispatch.channel,
                            dispatch.purpose,
                            dispatch.occurredAt,
                            dispatch.correlationId,
                        ),
                    )
                transactions.acceptNotification(dispatch.lineId, dispatch.claimId, accepted.deliveryId)
            } catch (failure: RuntimeException) {
                transactions.failNotification(dispatch.lineId, dispatch.claimId, normalizedFailure(failure))
            }
        }
        return transactions.getSystem(profileChangeId)
    }

    private fun currentOwnerVersion(
        purpose: ProfileChangePurpose,
        subjectId: UUID,
    ): Long =
        when (purpose.descriptor().owner) {
            ProfileOwnerType.CUSTOMER -> customers.currentVersion(subjectId)
            ProfileOwnerType.STORE -> stores.currentVersion(subjectId)
            ProfileOwnerType.EXTERNAL_COURIER -> couriers.currentVersion(subjectId)
        }

    private fun prepare(
        profileChangeId: UUID,
        subjectId: UUID,
        expectedVersion: Long,
        payload: SupportProfileChangePayload,
    ): PreparedOwnerProfileChange =
        when (payload) {
            is SupportProfileChangePayload.CustomerDisplayName -> {
                PreparedOwnerProfileChange.Customer(
                    customers.prepareDisplayName(
                        PrepareCustomerDisplayNameCorrection(profileChangeId, subjectId, expectedVersion, payload.displayName),
                    ),
                )
            }

            is SupportProfileChangePayload.CustomerLegalName -> {
                PreparedOwnerProfileChange.Customer(
                    customers.prepareLegalName(
                        PrepareCustomerLegalNameCorrection(profileChangeId, subjectId, expectedVersion, payload.legalName),
                    ),
                )
            }

            is SupportProfileChangePayload.CustomerPrimaryPhone -> {
                PreparedOwnerProfileChange.Customer(
                    customers.preparePrimaryPhone(
                        PrepareCustomerPrimaryPhoneChange(profileChangeId, subjectId, expectedVersion, payload.primaryPhone),
                    ),
                )
            }

            SupportProfileChangePayload.CustomerCredentialReset -> {
                PreparedOwnerProfileChange.Customer(
                    customers.prepareCredentialReset(PrepareCustomerCredentialReset(profileChangeId, subjectId, expectedVersion)),
                )
            }

            is SupportProfileChangePayload.StorePublicProfile -> {
                PreparedOwnerProfileChange.Store(
                    stores.preparePublicProfile(
                        PrepareStorePublicProfileCorrection(
                            profileChangeId,
                            subjectId,
                            expectedVersion,
                            payload.displayName,
                            payload.publicPhone,
                            payload.description,
                            payload.pickupInstructions,
                        ),
                    ),
                )
            }

            is SupportProfileChangePayload.StoreOperationsContact -> {
                PreparedOwnerProfileChange.Store(
                    stores.prepareOperationsContact(
                        PrepareStoreOperationsContactCorrection(profileChangeId, subjectId, expectedVersion, payload.phone, payload.email),
                    ),
                )
            }

            is SupportProfileChangePayload.StoreRepresentative -> {
                PreparedOwnerProfileChange.Store(
                    stores.prepareRepresentative(
                        PrepareStoreRepresentativeChange(profileChangeId, subjectId, expectedVersion, payload.representativeName),
                    ),
                )
            }

            is SupportProfileChangePayload.StoreSettlementAccount -> {
                PreparedOwnerProfileChange.Store(
                    stores.prepareSettlementAccount(
                        PrepareStoreSettlementAccountChange(profileChangeId, subjectId, expectedVersion, payload.accountReference),
                    ),
                )
            }

            SupportProfileChangePayload.StoreAccessReregistration -> {
                PreparedOwnerProfileChange.Store(
                    stores.prepareAccessReregistration(PrepareStoreAccessReregistration(profileChangeId, subjectId, expectedVersion)),
                )
            }

            is SupportProfileChangePayload.CourierDisplayName -> {
                PreparedOwnerProfileChange.Courier(
                    couriers.prepareDisplayName(
                        PrepareCourierDisplayNameCorrection(profileChangeId, subjectId, expectedVersion, payload.displayName),
                    ),
                )
            }

            is SupportProfileChangePayload.CourierRelayContact -> {
                PreparedOwnerProfileChange.Courier(
                    couriers.prepareRelayContact(
                        PrepareCourierRelayContactCorrection(profileChangeId, subjectId, expectedVersion, payload.phone, payload.email),
                    ),
                )
            }

            is SupportProfileChangePayload.CourierProviderIdentity -> {
                PreparedOwnerProfileChange.Courier(
                    couriers.prepareProviderIdentity(
                        PrepareCourierProviderIdentityChange(profileChangeId, subjectId, expectedVersion, payload.providerReference),
                    ),
                )
            }

            is SupportProfileChangePayload.CourierPayoutReference -> {
                PreparedOwnerProfileChange.Courier(
                    couriers.preparePayoutReference(
                        PrepareCourierPayoutReferenceChange(profileChangeId, subjectId, expectedVersion, payload.payoutReference),
                    ),
                )
            }

            SupportProfileChangePayload.CourierProviderReregistration -> {
                PreparedOwnerProfileChange.Courier(
                    couriers.prepareProviderReregistration(
                        PrepareCourierProviderReregistration(profileChangeId, subjectId, expectedVersion),
                    ),
                )
            }
        }

    private fun normalizedFailure(failure: RuntimeException): String =
        ((failure as? DomainFailure)?.code?.name ?: "NOTIFICATION_DEPENDENCY_FAILURE").take(80)

    private fun stale(): Nothing = throw DomainFailure(FailureCode.SUPPORT_ACTION_REQUEST_STALE, "Profile change binding is stale")
}

@Service
internal class SupportProfileChangeTransactionService(
    private val changes: SupportProfileChangeJpaRepository,
    private val notificationLines: SupportProfileChangeNotificationJpaRepository,
    private val idempotencies: SupportProfileChangeIdempotencyJpaRepository,
    private val cases: SupportCaseJpaRepository,
    private val subjectLinks: SupportCaseSubjectLinkJpaRepository,
    private val sessions: VerificationSessionJpaRepository,
    private val challenges: VerificationChallengeJpaRepository,
    private val actionRequests: SupportActionRequestJpaRepository,
    private val actionRevisions: SupportActionRevisionJpaRepository,
    private val permissions: OperatorPermissionAuthorization,
    private val actionTransactions: SupportActionRequestTransactionService,
    private val commandLock: SupportCaseCommandLock,
    private val customers: CustomerSupportProfileChangeOperations,
    private val stores: StoreSupportProfileChangeOperations,
    private val couriers: ExternalCourierSupportProfileChangeOperations,
    private val audits: AuditRecordOperations,
    private val identifiers: IdentifierSource,
    private val correlations: CorrelationIdSource,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun replaySubmit(
        command: SubmitSupportProfileChangeCommand,
        payloadDigest: String,
    ): SupportProfileChangeResource? {
        val operation = "CREATE_${command.payload.purpose.name}"
        val payloadHash = submitIdempotencyHash(operation, command, payloadDigest)
        return replay(command.actorId, operation, command.idempotencyKey, payloadHash)?.let(::resource)
    }

    @Transactional(readOnly = true)
    fun replayRevision(command: ReviseSupportProfileChangeCommand): SupportProfileChangeResource? {
        val operation = "REVISE_${command.payload.purpose.name}"
        val existing =
            idempotencies.findByActorIdAndOperationAndIdempotencyKey(command.actorId, operation, command.idempotencyKey)
                ?: return null
        val entity = changes.findById(existing.profileChangeId).orElse(null) ?: dependency()
        val digest = SupportProfilePayloadDigest.digest(entity.subjectId, command.expectedProfileVersion, command.payload)
        val hash =
            SupportProfilePayloadDigest.idempotency(
                operation,
                command.actorId,
                null,
                command.profileChangeId,
                command.verificationSessionId,
                digest,
                command.expectedActionRequestVersion,
                command.reason,
                command.evidenceDigest,
            )
        return replay(command.actorId, operation, command.idempotencyKey, hash)?.let(::resource)
    }

    @Transactional(readOnly = true)
    fun replayExecution(command: ExecuteSupportProfileChangeCommand): SupportProfileChangeResource? {
        val operation = "EXECUTE_${command.payload.purpose.name}"
        val existing =
            idempotencies.findByActorIdAndOperationAndIdempotencyKey(command.actorId, operation, command.idempotencyKey)
                ?: return null
        val entity = changes.findById(existing.profileChangeId).orElse(null) ?: dependency()
        val digest = SupportProfilePayloadDigest.digest(entity.subjectId, command.expectedProfileVersion, command.payload)
        val hash =
            SupportProfilePayloadDigest.idempotency(
                operation,
                command.actorId,
                null,
                command.profileChangeId,
                null,
                digest,
                command.expectedActionRequestVersion,
                null,
                null,
            )
        return replay(command.actorId, operation, command.idempotencyKey, hash)?.let(::resource)
    }

    @Transactional
    fun preflight(
        command: SubmitSupportProfileChangeCommand,
        ownerVersion: Long,
    ): ProfileChangePreflight {
        validateSubmit(command)
        validateScope(
            command.actorId,
            command.caseId,
            command.subjectId,
            command.payload.purpose,
            command.verificationSessionId,
            ownerVersion,
            command.expectedProfileVersion,
        )
        val session = sessions.findLockedById(command.verificationSessionId) ?: notFound("VerificationSession")
        return ProfileChangePreflight(session.expiresAt, ownerVersion)
    }

    @Transactional
    fun executeDirect(
        profileChangeId: UUID,
        command: SubmitSupportProfileChangeCommand,
        payloadDigest: String,
        ownerVersion: Long,
        prepared: PreparedOwnerProfileChange,
    ): SupportProfileChangeResource {
        val operation = "CREATE_${command.payload.purpose.name}"
        commandLock.lock(command.caseId, command.actorId, operation, command.idempotencyKey)
        val payloadHash = submitIdempotencyHash(operation, command, payloadDigest)
        replay(command.actorId, operation, command.idempotencyKey, payloadHash)?.let { return resource(it) }
        validateScope(
            command.actorId,
            command.caseId,
            command.subjectId,
            command.payload.purpose,
            command.verificationSessionId,
            ownerVersion,
            command.expectedProfileVersion,
        )
        if (command.payload.purpose
                .descriptor()
                .requiresDualApproval
        ) {
            invalid("Approved profile change cannot use direct execution")
        }
        val now = clock.instant()
        val result = applyOwner(prepared)
        val aggregate =
            SupportProfileChange.direct(
                profileChangeId,
                command.caseId,
                command.subjectId,
                command.payload.purpose,
                command.actorId,
                command.verificationSessionId,
                command.expectedProfileVersion,
                payloadDigest,
                result,
                now,
            )
        val entity = aggregate.toEntity()
        changes.saveAndFlush(entity)
        saveNotificationLines(entity, result, now)
        appendAudit(entity, command.actorId, "SUPPORT_PROFILE_CHANGE_EXECUTED", "DIRECT_CHANGE", now)
        val response = resource(entity)
        saveIdempotency(command.actorId, operation, command.idempotencyKey, payloadHash, entity.id, response, 201, now)
        return response
    }

    @Transactional
    fun requestApproval(
        profileChangeId: UUID,
        command: SubmitSupportProfileChangeCommand,
        payloadDigest: String,
        ownerVersion: Long,
    ): SupportProfileChangeResource {
        val operation = "CREATE_${command.payload.purpose.name}"
        commandLock.lock(command.caseId, command.actorId, operation, command.idempotencyKey)
        val payloadHash = submitIdempotencyHash(operation, command, payloadDigest)
        replay(command.actorId, operation, command.idempotencyKey, payloadHash)?.let { return resource(it) }
        validateScope(
            command.actorId,
            command.caseId,
            command.subjectId,
            command.payload.purpose,
            command.verificationSessionId,
            ownerVersion,
            command.expectedProfileVersion,
        )
        if (!command.payload.purpose
                .descriptor()
                .requiresDualApproval
        ) {
            invalid("Direct profile change cannot request approval")
        }
        val now = clock.instant()
        val session = sessions.findLockedById(command.verificationSessionId) ?: notFound("VerificationSession")
        val actionRequestId = identifiers.next()
        actionTransactions.openProfileChangeApproval(
            OpenProfileChangeApprovalCommand(
                actionRequestId,
                identifiers.next(),
                profileChangeId,
                command.caseId,
                command.actorId,
                command.verificationSessionId,
                command.expectedProfileVersion,
                payloadDigest,
                command.reason,
                command.evidenceDigest,
                session.expiresAt,
                now,
            ),
        )
        val aggregate =
            SupportProfileChange.pending(
                profileChangeId,
                command.caseId,
                command.subjectId,
                command.payload.purpose,
                command.actorId,
                command.verificationSessionId,
                command.expectedProfileVersion,
                payloadDigest,
                actionRequestId,
                now,
            )
        val entity = aggregate.toEntity()
        changes.saveAndFlush(entity)
        appendAudit(entity, command.actorId, "SUPPORT_PROFILE_CHANGE_REQUESTED", "DUAL_APPROVAL_REQUESTED", now)
        val response = resource(entity)
        saveIdempotency(command.actorId, operation, command.idempotencyKey, payloadHash, entity.id, response, 201, now)
        return response
    }

    @Transactional
    fun revisionBinding(command: ReviseSupportProfileChangeCommand): SupportProfileChangeResource {
        permissions.requireActive(command.actorId, OperatorPermission.SUPPORT_PROFILE_R3_REQUEST)
        val entity = changes.findById(command.profileChangeId).orElse(null) ?: notFound("ProfileChange")
        if (entity.requesterActorId != command.actorId || entity.version != command.expectedProfileChangeVersion ||
            entity.actionRequestId == null || entity.state == SupportProfileChangeState.EXECUTED
        ) {
            stale()
        }
        return resource(entity)
    }

    @Transactional
    fun revise(
        command: ReviseSupportProfileChangeCommand,
        payloadDigest: String,
        ownerVersion: Long,
    ): SupportProfileChangeResource {
        val operation = "REVISE_${command.payload.purpose.name}"
        commandLock.lock(null, command.actorId, operation, command.idempotencyKey)
        val hash =
            SupportProfilePayloadDigest.idempotency(
                operation,
                command.actorId,
                null,
                command.profileChangeId,
                command.verificationSessionId,
                payloadDigest,
                command.expectedActionRequestVersion,
                command.reason,
                command.evidenceDigest,
            )
        replay(command.actorId, operation, command.idempotencyKey, hash)?.let { return resource(it) }
        val entity = changes.findLockedById(command.profileChangeId) ?: notFound("ProfileChange")
        if (entity.version != command.expectedProfileChangeVersion || entity.purpose != command.payload.purpose ||
            entity.requesterActorId != command.actorId || entity.actionRequestId == null
        ) {
            stale()
        }
        validateScope(
            command.actorId,
            entity.supportCaseId,
            entity.subjectId,
            entity.purpose,
            command.verificationSessionId,
            ownerVersion,
            command.expectedProfileVersion,
        )
        val session = sessions.findLockedById(command.verificationSessionId) ?: notFound("VerificationSession")
        actionTransactions.reviseProfileChangeApproval(
            ReviseProfileChangeApprovalCommand(
                entity.id,
                requireNotNull(entity.actionRequestId),
                command.actorId,
                command.expectedActionRequestVersion,
                command.verificationSessionId,
                command.expectedProfileVersion,
                payloadDigest,
                command.reason,
                command.evidenceDigest,
                session.expiresAt,
                clock.instant(),
            ),
        )
        val aggregate = entity.toAggregate()
        aggregate.reviseBinding(
            command.actorId,
            command.verificationSessionId,
            command.expectedProfileVersion,
            payloadDigest,
            clock.instant(),
        )
        entity.apply(aggregate)
        changes.saveAndFlush(entity)
        appendAudit(entity, command.actorId, "SUPPORT_PROFILE_CHANGE_REQUESTED", "REVISION_CREATED", clock.instant())
        val response = resource(entity)
        saveIdempotency(command.actorId, operation, command.idempotencyKey, hash, entity.id, response, 200, clock.instant())
        return response
    }

    @Transactional
    fun executionBinding(command: ExecuteSupportProfileChangeCommand): ProfileChangeExecutionBinding {
        permissions.requireActive(command.actorId, OperatorPermission.SUPPORT_CASE_READ)
        permissions.requireActive(command.actorId, OperatorPermission.SUPPORT_CASE_WRITE)
        permissions.requireActive(command.actorId, OperatorPermission.SUPPORT_ACTION_EXECUTE)
        permissions.requireActive(command.actorId, OperatorPermission.SUPPORT_PROFILE_R3_REQUEST)
        val entity = changes.findLockedById(command.profileChangeId) ?: notFound("ProfileChange")
        if (entity.version != command.expectedProfileChangeVersion || entity.actionRequestId == null ||
            entity.state == SupportProfileChangeState.EXECUTED
        ) {
            stale()
        }
        val request = actionRequests.findLockedById(requireNotNull(entity.actionRequestId)) ?: notFound("SupportActionRequest")
        if (request.executorActorId != command.actorId || request.currentRevisionNumber != command.revisionNumber ||
            request.version != command.expectedActionRequestVersion || request.state != SupportActionRequestState.READY_FOR_EXECUTION
        ) {
            stale()
        }
        return ProfileChangeExecutionBinding(resource(entity), request.id)
    }

    @Transactional
    fun executeApproved(
        command: ExecuteSupportProfileChangeCommand,
        payloadDigest: String,
        ownerVersion: Long,
        prepared: PreparedOwnerProfileChange,
    ): SupportProfileChangeResource {
        val operation = "EXECUTE_${command.payload.purpose.name}"
        commandLock.lock(null, command.actorId, operation, command.idempotencyKey)
        val hash =
            SupportProfilePayloadDigest.idempotency(
                operation,
                command.actorId,
                null,
                command.profileChangeId,
                null,
                payloadDigest,
                command.expectedActionRequestVersion,
                null,
                null,
            )
        replay(command.actorId, operation, command.idempotencyKey, hash)?.let { return resource(it) }
        val entity = changes.findLockedById(command.profileChangeId) ?: notFound("ProfileChange")
        if (entity.version != command.expectedProfileChangeVersion || entity.payloadDigest != payloadDigest ||
            entity.expectedProfileVersion != ownerVersion || entity.actionRequestId == null
        ) {
            stale()
        }
        validateExecutionScope(entity, command.actorId, ownerVersion)
        val result = applyOwner(prepared)
        val now = clock.instant()
        val aggregate = entity.toAggregate()
        aggregate.executorActorId = command.actorId
        aggregate.complete(command.actorId, result, now)
        entity.apply(aggregate)
        changes.saveAndFlush(entity)
        saveNotificationLines(entity, result, now)
        actionTransactions.completeProfileChangeApproval(
            ProfileChangeExecutionApprovalCommand(
                entity.id,
                requireNotNull(entity.actionRequestId),
                command.actorId,
                command.revisionNumber,
                command.expectedActionRequestVersion,
                payloadDigest,
                ownerVersion,
                now,
            ),
        )
        appendAudit(entity, command.actorId, "SUPPORT_PROFILE_CHANGE_EXECUTED", "APPROVED_CHANGE", now)
        val response = resource(entity)
        saveIdempotency(command.actorId, operation, command.idempotencyKey, hash, entity.id, response, 200, now)
        return response
    }

    @Transactional
    fun authorizeRetry(command: RetrySupportProfileNotificationCommand) {
        command.idempotencyKey.requireKey()
        val operation = "RETRY_PROFILE_NOTIFICATIONS"
        commandLock.lock(null, command.actorId, operation, command.idempotencyKey)
        val hash =
            SupportProfilePayloadDigest.idempotency(
                operation,
                command.actorId,
                null,
                command.profileChangeId,
                null,
                null,
                command.expectedProfileChangeVersion,
                null,
                null,
            )
        replay(command.actorId, operation, command.idempotencyKey, hash)?.let { return }
        permissions.requireActive(command.actorId, OperatorPermission.SUPPORT_CASE_WRITE)
        val entity = changes.findLockedById(command.profileChangeId) ?: notFound("ProfileChange")
        if (entity.version != command.expectedProfileChangeVersion ||
            entity.notificationState != SupportProfileNotificationState.RETRY_SCHEDULED
        ) {
            stale()
        }
        val supportCase = cases.findLockedById(entity.supportCaseId) ?: notFound("SupportCase")
        if (supportCase.currentAssigneeId != command.actorId || supportCase.state !in ACTIVE_CASE_STATES) denied()
        val now = clock.instant()
        appendAudit(entity, command.actorId, "SUPPORT_PROFILE_CHANGE_NOTIFICATION_RETRY", "RETRY_REQUESTED", now)
        saveIdempotency(
            command.actorId,
            operation,
            command.idempotencyKey,
            hash,
            entity.id,
            resource(entity),
            200,
            now,
        )
    }

    @Transactional
    fun get(
        actorId: UUID,
        profileChangeId: UUID,
    ): SupportProfileChangeResource {
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
        val entity = changes.findById(profileChangeId).orElse(null) ?: notFound("ProfileChange")
        val supportCase = cases.findById(entity.supportCaseId).orElse(null) ?: notFound("SupportCase")
        val visible =
            actorId == entity.requesterActorId || actorId == entity.executorActorId || actorId == supportCase.currentAssigneeId ||
                permissions.hasActive(actorId, OperatorPermission.SUPPORT_PROFILE_R3_APPROVE) ||
                permissions.hasActive(actorId, OperatorPermission.OPERATIONS_SUPPORT_INVESTIGATION)
        if (!visible) denied()
        return resource(entity)
    }

    @Transactional(readOnly = true)
    fun getSystem(profileChangeId: UUID): SupportProfileChangeResource =
        resource(changes.findById(profileChangeId).orElse(null) ?: notFound("ProfileChange"))

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claimNotifications(
        profileChangeId: UUID,
        claimId: UUID,
        includeRetryScheduled: Boolean,
    ): List<ProfileNotificationDispatch> {
        val entity = changes.findLockedById(profileChangeId) ?: notFound("ProfileChange")
        val ownerType =
            entity.purpose
                .descriptor()
                .owner
                .notificationOwnerType()
        val now = clock.instant()
        val claimed =
            notificationLines
                .findLockedByProfileChangeId(profileChangeId)
                .filter {
                    it.state == SupportProfileNotificationState.PENDING ||
                        (
                            it.state == SupportProfileNotificationState.PROCESSING &&
                                it.claimExpiresAt?.let { expiry -> !now.isBefore(expiry) } == true
                        ) ||
                        (includeRetryScheduled && it.state == SupportProfileNotificationState.RETRY_SCHEDULED)
                }
        claimed.forEach {
            it.deliveryId = null
            it.state = SupportProfileNotificationState.PROCESSING
            it.failureCode = null
            it.claimId = claimId
            it.claimExpiresAt = now.plus(NOTIFICATION_CLAIM_LEASE)
            it.updatedAt = now
            notificationLines.save(it)
        }
        notificationLines.flush()
        return claimed.map {
            ProfileNotificationDispatch(
                claimId,
                it.id,
                entity.id,
                ownerType,
                it.ownerTargetId,
                it.targetKind,
                it.channelType,
                entity.purpose.name,
                it.sourceOccurredAt,
                it.sourceCorrelationId,
            )
        }
    }

    @Transactional(readOnly = true)
    fun recoverableNotificationProfileChangeIds(): List<UUID> =
        notificationLines.findRecoverableProfileChangeIds(
            clock.instant(),
            PageRequest.of(0, MAX_NOTIFICATION_RECOVERY_BATCH),
        )

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun acceptNotification(
        lineId: UUID,
        claimId: UUID,
        deliveryId: UUID,
    ) {
        val snapshot = notificationLines.findById(lineId).orElse(null) ?: notFound("ProfileNotification")
        changes.findLockedById(snapshot.profileChangeId) ?: notFound("ProfileChange")
        val line = notificationLines.findLockedById(lineId) ?: notFound("ProfileNotification")
        if (line.state == SupportProfileNotificationState.ACCEPTED && line.deliveryId == deliveryId) return
        if (line.state != SupportProfileNotificationState.PROCESSING || line.claimId != claimId) return
        line.deliveryId = deliveryId
        line.state = SupportProfileNotificationState.ACCEPTED
        line.failureCode = null
        line.claimId = null
        line.claimExpiresAt = null
        line.attemptCount++
        line.updatedAt = clock.instant()
        notificationLines.saveAndFlush(line)
        refreshNotificationSummary(line.profileChangeId)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun failNotification(
        lineId: UUID,
        claimId: UUID,
        failureCode: String,
    ) {
        val snapshot = notificationLines.findById(lineId).orElse(null) ?: notFound("ProfileNotification")
        changes.findLockedById(snapshot.profileChangeId) ?: notFound("ProfileChange")
        val line = notificationLines.findLockedById(lineId) ?: notFound("ProfileNotification")
        if (line.state != SupportProfileNotificationState.PROCESSING || line.claimId != claimId) return
        line.deliveryId = null
        line.attemptCount++
        val manual = line.attemptCount >= MAX_NOTIFICATION_ATTEMPTS
        line.state = if (manual) SupportProfileNotificationState.MANUAL_REVIEW else SupportProfileNotificationState.RETRY_SCHEDULED
        line.failureCode = failureCode
        line.claimId = null
        line.claimExpiresAt = null
        line.updatedAt = clock.instant()
        notificationLines.saveAndFlush(line)
        refreshNotificationSummary(line.profileChangeId)
    }

    private fun refreshNotificationSummary(profileChangeId: UUID) {
        val entity = changes.findLockedById(profileChangeId) ?: notFound("ProfileChange")
        val lines = notificationLines.findLockedByProfileChangeId(profileChangeId)
        val aggregate = entity.toAggregate()
        val now = clock.instant()
        when {
            lines.all { it.state == SupportProfileNotificationState.ACCEPTED } -> {
                aggregate.notificationAccepted(now)
            }

            lines.any { it.state == SupportProfileNotificationState.MANUAL_REVIEW } -> {
                aggregate.notificationFailed(
                    lines.first { it.state == SupportProfileNotificationState.MANUAL_REVIEW }.failureCode ?: "NOTIFICATION_FAILURE",
                    true,
                    now,
                )
            }

            lines.any { it.state == SupportProfileNotificationState.RETRY_SCHEDULED } -> {
                aggregate.notificationFailed(
                    lines.first { it.state == SupportProfileNotificationState.RETRY_SCHEDULED }.failureCode ?: "NOTIFICATION_FAILURE",
                    false,
                    now,
                )
            }
        }
        entity.apply(aggregate)
        changes.saveAndFlush(entity)
    }

    private fun validateSubmit(command: SubmitSupportProfileChangeCommand) {
        command.idempotencyKey.requireKey()
        command.reason.requireReason()
        command.evidenceDigest.requireDigest("Evidence")
        if (command.expectedProfileVersion < 0) invalid("Expected profile version cannot be negative")
    }

    private fun validateScope(
        actorId: UUID,
        caseId: UUID,
        subjectId: UUID,
        purpose: ProfileChangePurpose,
        sessionId: UUID,
        ownerVersion: Long,
        expectedVersion: Long,
    ) {
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_WRITE)
        val descriptor = purpose.descriptor()
        permissions.requireActive(
            actorId,
            when (descriptor.risk) {
                ProfileRiskClass.R1 -> OperatorPermission.SUPPORT_PROFILE_R1_CHANGE

                ProfileRiskClass.R2 -> OperatorPermission.SUPPORT_PROFILE_R2_CHANGE

                ProfileRiskClass.R3,
                ProfileRiskClass.R4,
                -> OperatorPermission.SUPPORT_PROFILE_R3_REQUEST

                ProfileRiskClass.R0 -> invalid("R0 profile fields are immutable")
            },
        )
        if (descriptor.requiresDualApproval) permissions.requireActive(actorId, OperatorPermission.SUPPORT_ACTION_REQUEST)
        if (ownerVersion != expectedVersion) stale()
        val supportCase = cases.findLockedById(caseId) ?: notFound("SupportCase")
        if (supportCase.currentAssigneeId != actorId || supportCase.state !in ACTIVE_CASE_STATES) denied()
        val link =
            subjectLinks.findBySupportCaseIdAndUnlinkedAtIsNullOrderByLinkedAtAsc(caseId).singleOrNull {
                it.subjectType == descriptor.owner.supportSubjectType() && it.subjectId == subjectId
            } ?: denied()
        val session = sessions.findLockedById(sessionId) ?: notFound("VerificationSession")
        val requiredLevel = if (descriptor.risk == ProfileRiskClass.R1) VerificationLevel.BASIC else VerificationLevel.ENHANCED
        if (session.actorId != actorId || session.supportCaseId != caseId || session.subjectLinkId != link.id ||
            session.subjectId != subjectId || session.subjectType != descriptor.owner.verificationSubjectType() ||
            session.purpose != VerificationPurpose.CASE_RESOLUTION || session.actionScope != VerificationActionScope.SUPPORT_ACTION ||
            session.state != VerificationState.VERIFIED || !session.requestedLevel.satisfies(requiredLevel) ||
            !clock.instant().isBefore(session.expiresAt)
        ) {
            deniedVerification()
        }
        if (purpose == ProfileChangePurpose.CUSTOMER_PRIMARY_PHONE) {
            val channels = challenges.findDistinctChannelsBySessionIdAndState(session.id, ChallengeState.VERIFIED)
            if (channels.none { it == VerificationChannel.REGISTERED_PHONE || it == VerificationChannel.REGISTERED_EMAIL }) {
                throw DomainFailure(
                    FailureCode.VERIFICATION_REQUIRED,
                    "Primary-phone change requires verification through a previously registered channel",
                )
            }
        }
    }

    private fun validateExecutionScope(
        entity: SupportProfileChangeEntity,
        actorId: UUID,
        ownerVersion: Long,
    ) {
        permissions.requireActive(entity.requesterActorId, OperatorPermission.SUPPORT_CASE_READ)
        permissions.requireActive(entity.requesterActorId, OperatorPermission.SUPPORT_CASE_WRITE)
        permissions.requireActive(entity.requesterActorId, OperatorPermission.SUPPORT_ACTION_REQUEST)
        permissions.requireActive(entity.requesterActorId, OperatorPermission.SUPPORT_PROFILE_R3_REQUEST)
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_WRITE)
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_ACTION_EXECUTE)
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_PROFILE_R3_REQUEST)
        if (ownerVersion != entity.expectedProfileVersion) stale()
        val supportCase = cases.findLockedById(entity.supportCaseId) ?: notFound("SupportCase")
        if (supportCase.currentAssigneeId != actorId || supportCase.state !in ACTIVE_CASE_STATES) denied()
        val descriptor = entity.purpose.descriptor()
        val link =
            subjectLinks.findBySupportCaseIdAndUnlinkedAtIsNullOrderByLinkedAtAsc(entity.supportCaseId).singleOrNull {
                it.subjectType == descriptor.owner.supportSubjectType() && it.subjectId == entity.subjectId
            } ?: denied()
        val request = actionRequests.findLockedById(requireNotNull(entity.actionRequestId)) ?: notFound("SupportActionRequest")
        val revision = actionRevisions.findByRequestIdAndRevisionNumber(request.id, request.currentRevisionNumber) ?: dependency()
        val session = sessions.findLockedById(revision.verificationSessionId) ?: notFound("VerificationSession")
        if (request.executorActorId != actorId || request.state != SupportActionRequestState.READY_FOR_EXECUTION ||
            revision.actionPayloadDigest != entity.payloadDigest || revision.targetVersion != ownerVersion ||
            revision.verificationSessionId != entity.verificationSessionId || !clock.instant().isBefore(revision.expiresAt)
        ) {
            stale()
        }
        if (session.id != entity.verificationSessionId || session.actorId != entity.requesterActorId ||
            session.supportCaseId != entity.supportCaseId || session.subjectLinkId != link.id ||
            session.subjectId != entity.subjectId || session.subjectType != descriptor.owner.verificationSubjectType() ||
            session.purpose != VerificationPurpose.CASE_RESOLUTION ||
            session.actionScope != VerificationActionScope.SUPPORT_ACTION ||
            session.state != VerificationState.VERIFIED || !session.requestedLevel.satisfies(VerificationLevel.ENHANCED) ||
            !clock.instant().isBefore(session.expiresAt)
        ) {
            deniedVerification()
        }
        if (entity.purpose == ProfileChangePurpose.CUSTOMER_PRIMARY_PHONE) {
            val channels = challenges.findDistinctChannelsBySessionIdAndState(session.id, ChallengeState.VERIFIED)
            if (channels.none { it == VerificationChannel.REGISTERED_PHONE || it == VerificationChannel.REGISTERED_EMAIL }) {
                throw DomainFailure(
                    FailureCode.VERIFICATION_REQUIRED,
                    "Primary-phone change requires verification through a previously registered channel",
                )
            }
        }
    }

    private fun applyOwner(prepared: PreparedOwnerProfileChange): OwnerProfileChangeResult =
        when (prepared) {
            is PreparedOwnerProfileChange.Customer -> customers.apply(prepared.value)
            is PreparedOwnerProfileChange.Store -> stores.apply(prepared.value)
            is PreparedOwnerProfileChange.Courier -> couriers.apply(prepared.value)
        }

    private fun saveNotificationLines(
        entity: SupportProfileChangeEntity,
        result: OwnerProfileChangeResult,
        now: Instant,
    ) {
        result.notificationTargets.forEach { target ->
            notificationLines.saveAndFlush(
                SupportProfileChangeNotificationEntity(
                    identifiers.next(),
                    entity.id,
                    target.targetId,
                    target.kind,
                    target.channel,
                    null,
                    SupportProfileNotificationState.PENDING,
                    null,
                    0,
                    now,
                    correlations.currentOrCreate(),
                    null,
                    null,
                    now,
                    now,
                ),
            )
        }
    }

    private fun appendAudit(
        entity: SupportProfileChangeEntity,
        actorId: UUID,
        action: String,
        event: String,
        now: Instant,
    ) {
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId.toString(),
                    AuditActorType.PLATFORM_OPERATOR,
                    AuditCategory.PII_ACCESS,
                    action,
                    "SUPPORT_PROFILE_CHANGE",
                    entity.id,
                    now,
                    "PURPOSE_SPECIFIC_PROFILE_CHANGE",
                    afterSummary =
                        mapOf(
                            "event" to event,
                            "purpose" to entity.purpose.name,
                            "riskClass" to entity.riskClass.name,
                            "state" to entity.state.name,
                            "profileVersion" to (entity.currentProfileVersion ?: entity.expectedProfileVersion).toString(),
                        ),
                    correlationId = correlations.currentOrCreate(),
                    sourceReference = "support-profile-change:${entity.id}:$action:${entity.version}",
                ),
            ),
        )
    }

    private fun submitIdempotencyHash(
        operation: String,
        command: SubmitSupportProfileChangeCommand,
        digest: String,
    ): String =
        SupportProfilePayloadDigest.idempotency(
            operation,
            command.actorId,
            command.caseId,
            null,
            command.verificationSessionId,
            digest,
            command.expectedProfileVersion,
            command.reason,
            command.evidenceDigest,
        )

    private fun replay(
        actorId: UUID,
        operation: String,
        key: String,
        hash: String,
    ): SupportProfileChangeEntity? {
        val existing = idempotencies.findByActorIdAndOperationAndIdempotencyKey(actorId, operation, key) ?: return null
        if (existing.payloadHash != hash) {
            throw DomainFailure(FailureCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was reused for another profile change")
        }
        return changes.findById(existing.profileChangeId).orElse(null) ?: dependency()
    }

    private fun saveIdempotency(
        actorId: UUID,
        operation: String,
        key: String,
        hash: String,
        profileChangeId: UUID,
        response: SupportProfileChangeResource,
        status: Int,
        now: Instant,
    ) {
        idempotencies.saveAndFlush(
            SupportProfileChangeIdempotencyEntity(
                identifiers.next(),
                actorId,
                operation,
                key,
                hash,
                profileChangeId,
                status,
                objectMapper.writeValueAsString(response),
                null,
                now,
                now.plus(IDEMPOTENCY_RETENTION),
            ),
        )
    }

    private fun resource(entity: SupportProfileChangeEntity): SupportProfileChangeResource =
        SupportProfileChangeResource(
            entity.id,
            entity.supportCaseId,
            entity.subjectType,
            entity.subjectId,
            entity.purpose,
            entity.riskClass,
            entity.requesterActorId,
            entity.executorActorId,
            entity.verificationSessionId,
            entity.expectedProfileVersion,
            entity.currentProfileVersion,
            entity.payloadDigest,
            entity.actionRequestId,
            entity.state,
            entity.notificationState,
            entity.notificationFailureCode,
            entity.maskedBefore,
            entity.maskedAfter,
            entity.version,
            entity.createdAt,
            entity.updatedAt,
            notificationLines.findByProfileChangeIdOrderById(entity.id).map {
                SupportProfileChangeNotificationResource(
                    it.targetKind.name,
                    it.channelType.name,
                    it.state,
                    it.deliveryId,
                    it.failureCode,
                    it.attemptCount,
                )
            },
        )

    private fun SupportProfileChange.toEntity(): SupportProfileChangeEntity =
        SupportProfileChangeEntity(
            id,
            supportCaseId,
            descriptor.owner.profileSubjectType(),
            subjectId,
            purpose,
            descriptor.risk,
            requesterActorId,
            executorActorId,
            verificationSessionId,
            expectedProfileVersion,
            currentProfileVersion,
            payloadDigest,
            actionRequestId,
            ownerChangeId,
            maskedBefore,
            maskedAfter,
            state,
            notificationState,
            notificationFailureCode,
            createdAt,
            updatedAt,
            version,
        )

    private fun VerificationLevel.satisfies(required: VerificationLevel): Boolean = ordinal >= required.ordinal

    private fun String.requireKey() {
        if (trim() != this || length !in 8..128 || any(Char::isISOControl)) invalid("Idempotency-Key is invalid")
    }

    private fun String.requireReason() {
        if (trim() != this || length !in 1..500 || any(Char::isISOControl)) invalid("Profile change reason is invalid")
    }

    private fun String.requireDigest(label: String) {
        if (!matches(Regex("^[0-9a-f]{64}$"))) invalid("$label digest is invalid")
    }

    private fun ProfileOwnerType.supportSubjectType(): SupportSubjectType =
        when (this) {
            ProfileOwnerType.CUSTOMER -> SupportSubjectType.CUSTOMER
            ProfileOwnerType.STORE -> SupportSubjectType.STORE
            ProfileOwnerType.EXTERNAL_COURIER -> SupportSubjectType.DELIVERY
        }

    private fun ProfileOwnerType.verificationSubjectType(): VerificationSubjectType =
        when (this) {
            ProfileOwnerType.CUSTOMER -> VerificationSubjectType.CUSTOMER
            ProfileOwnerType.STORE -> VerificationSubjectType.STORE
            ProfileOwnerType.EXTERNAL_COURIER -> VerificationSubjectType.DELIVERY
        }

    private fun ProfileOwnerType.profileSubjectType(): ProfileChangeSubjectType =
        when (this) {
            ProfileOwnerType.CUSTOMER -> ProfileChangeSubjectType.CUSTOMER
            ProfileOwnerType.STORE -> ProfileChangeSubjectType.STORE
            ProfileOwnerType.EXTERNAL_COURIER -> ProfileChangeSubjectType.RIDER
        }

    private fun ProfileOwnerType.notificationOwnerType(): ProfileNotificationOwnerType =
        when (this) {
            ProfileOwnerType.CUSTOMER -> ProfileNotificationOwnerType.CUSTOMER
            ProfileOwnerType.STORE -> ProfileNotificationOwnerType.STORE
            ProfileOwnerType.EXTERNAL_COURIER -> ProfileNotificationOwnerType.EXTERNAL_COURIER
        }

    private fun notFound(resource: String): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "$resource was not found")

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    private fun denied(): Nothing = throw DomainFailure(FailureCode.ACCESS_DENIED, "Profile change access is denied")

    private fun deniedVerification(): Nothing = throw DomainFailure(FailureCode.VERIFICATION_REQUIRED, "Bound verification is required")

    private fun stale(): Nothing = throw DomainFailure(FailureCode.SUPPORT_ACTION_REQUEST_STALE, "Profile change binding is stale")

    private fun dependency(): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Profile change dependency is inconsistent")

    private companion object {
        val ACTIVE_CASE_STATES = setOf(SupportCaseState.OPEN, SupportCaseState.IN_PROGRESS, SupportCaseState.WAITING)
        val IDEMPOTENCY_RETENTION: Duration = Duration.ofDays(90)
        const val MAX_NOTIFICATION_ATTEMPTS = 5
        const val MAX_NOTIFICATION_RECOVERY_BATCH = 50
        val NOTIFICATION_CLAIM_LEASE: Duration = Duration.ofMinutes(2)
    }
}

package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.notification.api.ProfileChangeNotificationOperations
import io.github.kdh949.beanflow.notification.api.RequestProfileChangeNotificationCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.support.internal.domain.SupportProfileChangeState
import io.github.kdh949.beanflow.support.internal.domain.descriptor
import org.springframework.stereotype.Service
import java.util.UUID

@Service
internal class SupportProfileChangeApplicationService(
    private val submitHandler: SubmitSupportProfileChangeHandler,
    private val reviseHandler: ReviseSupportProfileChangeHandler,
    private val executeHandler: ExecuteSupportProfileChangeHandler,
    private val notificationHandler: SupportProfileChangeNotificationHandler,
    private val queryHandler: QuerySupportProfileChangeHandler,
) {
    fun submit(command: SubmitSupportProfileChangeCommand): SupportProfileChangeResource = submitHandler.handle(command)

    fun revise(command: ReviseSupportProfileChangeCommand): SupportProfileChangeResource = reviseHandler.handle(command)

    fun execute(command: ExecuteSupportProfileChangeCommand): SupportProfileChangeResource = executeHandler.handle(command)

    fun retryNotifications(command: RetrySupportProfileNotificationCommand): SupportProfileChangeResource =
        notificationHandler.retry(command)

    fun get(
        actorId: UUID,
        profileChangeId: UUID,
    ): SupportProfileChangeResource = queryHandler.get(actorId, profileChangeId)

    fun recoverNotifications(): Int = notificationHandler.recover()
}

@Service
internal class SubmitSupportProfileChangeHandler(
    private val transactions: SupportProfileChangeTransactionService,
    private val owners: SupportProfileChangeOwnerHandler,
    private val notifications: SupportProfileChangeNotificationHandler,
    private val identifiers: IdentifierSource,
) {
    fun handle(command: SubmitSupportProfileChangeCommand): SupportProfileChangeResource {
        val payloadDigest = SupportProfilePayloadDigest.digest(command.subjectId, command.expectedProfileVersion, command.payload)
        transactions.replaySubmit(command, payloadDigest)?.let {
            return if (it.state == SupportProfileChangeState.EXECUTED) notifications.dispatch(it.profileChangeId, false) else it
        }
        val ownerVersion = owners.currentVersion(command.payload.purpose, command.subjectId)
        transactions.preflight(command, ownerVersion)
        val profileChangeId = identifiers.next()
        val resource =
            if (command.payload.purpose
                    .descriptor()
                    .requiresDualApproval
            ) {
                transactions.requestApproval(profileChangeId, command, payloadDigest, ownerVersion)
            } else {
                val prepared = owners.prepare(profileChangeId, command.subjectId, command.expectedProfileVersion, command.payload)
                transactions.executeDirect(profileChangeId, command, payloadDigest, ownerVersion, prepared)
            }
        return if (resource.state == SupportProfileChangeState.EXECUTED) {
            notifications.dispatch(resource.profileChangeId, false)
        } else {
            resource
        }
    }
}

@Service
internal class ReviseSupportProfileChangeHandler(
    private val transactions: SupportProfileChangeTransactionService,
    private val owners: SupportProfileChangeOwnerHandler,
) {
    fun handle(command: ReviseSupportProfileChangeCommand): SupportProfileChangeResource {
        transactions.replayRevision(command)?.let { return it }
        val binding = transactions.revisionBinding(command)
        if (binding.purpose != command.payload.purpose) stale()
        val digest = SupportProfilePayloadDigest.digest(binding.subjectId, command.expectedProfileVersion, command.payload)
        val ownerVersion = owners.currentVersion(command.payload.purpose, binding.subjectId)
        if (ownerVersion != command.expectedProfileVersion) stale()
        return transactions.revise(command, digest, ownerVersion)
    }
}

@Service
internal class ExecuteSupportProfileChangeHandler(
    private val transactions: SupportProfileChangeTransactionService,
    private val owners: SupportProfileChangeOwnerHandler,
    private val notifications: SupportProfileChangeNotificationHandler,
) {
    fun handle(command: ExecuteSupportProfileChangeCommand): SupportProfileChangeResource {
        transactions.replayExecution(command)?.let { return notifications.dispatch(it.profileChangeId, false) }
        val binding = transactions.executionBinding(command)
        if (binding.entity.purpose != command.payload.purpose) stale()
        val digest = SupportProfilePayloadDigest.digest(binding.entity.subjectId, command.expectedProfileVersion, command.payload)
        if (digest != binding.entity.payloadDigest) stale()
        val ownerVersion = owners.currentVersion(binding.entity.purpose, binding.entity.subjectId)
        if (ownerVersion != command.expectedProfileVersion) stale()
        val prepared = owners.prepare(command.profileChangeId, binding.entity.subjectId, command.expectedProfileVersion, command.payload)
        val resource = transactions.executeApproved(command, digest, ownerVersion, prepared)
        return notifications.dispatch(resource.profileChangeId, false)
    }
}

@Service
internal class SupportProfileChangeNotificationHandler(
    private val transactions: SupportProfileChangeTransactionService,
    private val notifications: ProfileChangeNotificationOperations,
    private val identifiers: IdentifierSource,
) {
    fun retry(command: RetrySupportProfileNotificationCommand): SupportProfileChangeResource {
        transactions.authorizeRetry(command)
        return dispatch(command.profileChangeId, true)
    }

    fun recover(): Int {
        val profileChangeIds = transactions.recoverableNotificationProfileChangeIds()
        profileChangeIds.forEach { dispatch(it, false) }
        return profileChangeIds.size
    }

    fun dispatch(
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

    private fun normalizedFailure(failure: RuntimeException): String =
        ((failure as? DomainFailure)?.code?.name ?: "NOTIFICATION_DEPENDENCY_FAILURE").take(80)
}

@Service
internal class QuerySupportProfileChangeHandler(
    private val transactions: SupportProfileChangeTransactionService,
) {
    fun get(
        actorId: UUID,
        profileChangeId: UUID,
    ): SupportProfileChangeResource = transactions.get(actorId, profileChangeId)
}

private fun stale(): Nothing = throw DomainFailure(FailureCode.SUPPORT_ACTION_REQUEST_STALE, "Profile change binding is stale")

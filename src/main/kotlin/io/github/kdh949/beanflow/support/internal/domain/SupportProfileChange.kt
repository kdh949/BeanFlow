package io.github.kdh949.beanflow.support.internal.domain

import io.github.kdh949.beanflow.shared.api.OwnerProfileChangeResult
import java.time.Instant
import java.util.UUID

internal enum class SupportProfileChangeState {
    AWAITING_APPROVAL,
    READY_FOR_EXECUTION,
    EXECUTED,
}

internal enum class SupportProfileNotificationState {
    NOT_REQUESTED,
    PENDING,
    ACCEPTED,
    RETRY_SCHEDULED,
    MANUAL_REVIEW,
}

internal class SupportProfileChange private constructor(
    val id: UUID,
    val supportCaseId: UUID,
    val subjectId: UUID,
    val purpose: ProfileChangePurpose,
    val requesterActorId: UUID,
    var executorActorId: UUID,
    var verificationSessionId: UUID,
    var expectedProfileVersion: Long,
    var payloadDigest: String,
    val actionRequestId: UUID?,
    var ownerChangeId: UUID?,
    var currentProfileVersion: Long?,
    var maskedBefore: String?,
    var maskedAfter: String?,
    var state: SupportProfileChangeState,
    var notificationState: SupportProfileNotificationState,
    var notificationFailureCode: String?,
    val createdAt: Instant,
    var updatedAt: Instant,
    var version: Long,
) {
    val descriptor: ProfileChangeDescriptor = purpose.descriptor()

    init {
        require(expectedProfileVersion >= 0) { "Expected profile version cannot be negative" }
        require(payloadDigest.matches(SHA_256)) { "Profile payload digest must be lowercase SHA-256" }
        require(updatedAt >= createdAt) { "Profile change time cannot move backward" }
        validate()
    }

    fun markReady(
        actorId: UUID,
        occurredAt: Instant,
    ) {
        require(actorId == executorActorId) { "Only the assigned actor can prepare execution" }
        check(state == SupportProfileChangeState.AWAITING_APPROVAL) { "Profile change is not awaiting approval" }
        require(occurredAt >= updatedAt) { "Profile change time cannot move backward" }
        state = SupportProfileChangeState.READY_FOR_EXECUTION
        version++
        updatedAt = occurredAt
    }

    fun reviseBinding(
        actorId: UUID,
        sessionId: UUID,
        expectedVersion: Long,
        digest: String,
        occurredAt: Instant,
    ) {
        require(actorId == requesterActorId) { "Only the requester can revise a profile change" }
        check(actionRequestId != null && state != SupportProfileChangeState.EXECUTED) {
            "Profile change does not allow revision"
        }
        require(expectedVersion >= 0) { "Expected profile version cannot be negative" }
        require(digest.matches(SHA_256)) { "Profile payload digest must be lowercase SHA-256" }
        require(occurredAt >= updatedAt) { "Profile change time cannot move backward" }
        verificationSessionId = sessionId
        expectedProfileVersion = expectedVersion
        payloadDigest = digest
        state = SupportProfileChangeState.AWAITING_APPROVAL
        version++
        updatedAt = occurredAt
    }

    fun complete(
        actorId: UUID,
        result: OwnerProfileChangeResult,
        occurredAt: Instant,
    ) {
        require(actorId == executorActorId) { "Only the assigned actor can execute a profile change" }
        check(state != SupportProfileChangeState.EXECUTED) { "Profile change is already executed" }
        check(result.previousVersion == expectedProfileVersion) { "Profile change owner version is stale" }
        require(occurredAt >= updatedAt) { "Profile change time cannot move backward" }
        ownerChangeId = result.ownerChangeId
        currentProfileVersion = result.currentVersion
        maskedBefore = result.maskedBefore
        maskedAfter = result.maskedAfter
        state = SupportProfileChangeState.EXECUTED
        notificationState =
            if (result.notificationTargets.isEmpty()) {
                SupportProfileNotificationState.NOT_REQUESTED
            } else {
                SupportProfileNotificationState.PENDING
            }
        notificationFailureCode = null
        version++
        updatedAt = occurredAt
        validate()
    }

    fun notificationAccepted(occurredAt: Instant) {
        check(state == SupportProfileChangeState.EXECUTED) { "Only an executed profile change can complete notification" }
        require(occurredAt >= updatedAt) { "Profile change time cannot move backward" }
        notificationState = SupportProfileNotificationState.ACCEPTED
        notificationFailureCode = null
        version++
        updatedAt = occurredAt
    }

    fun notificationFailed(
        failureCode: String,
        manualReview: Boolean,
        occurredAt: Instant,
    ) {
        check(state == SupportProfileChangeState.EXECUTED) { "Only an executed profile change can fail notification" }
        require(failureCode.matches(FAILURE_CODE)) { "Notification failure code is invalid" }
        require(occurredAt >= updatedAt) { "Profile change time cannot move backward" }
        notificationState =
            if (manualReview) SupportProfileNotificationState.MANUAL_REVIEW else SupportProfileNotificationState.RETRY_SCHEDULED
        notificationFailureCode = failureCode
        version++
        updatedAt = occurredAt
    }

    private fun validate() {
        require((descriptor.requiresDualApproval) == (actionRequestId != null)) { "Profile approval binding is invalid" }
        if (state == SupportProfileChangeState.EXECUTED) {
            require(ownerChangeId != null && currentProfileVersion != null && maskedBefore != null && maskedAfter != null) {
                "Executed profile change must bind its owner result"
            }
        } else {
            require(ownerChangeId == null && currentProfileVersion == null && maskedBefore == null && maskedAfter == null) {
                "Pending profile change cannot bind an owner result"
            }
            require(notificationState == SupportProfileNotificationState.NOT_REQUESTED && notificationFailureCode == null) {
                "Pending profile change cannot bind notification state"
            }
        }
    }

    companion object {
        private val SHA_256 = Regex("^[0-9a-f]{64}$")
        private val FAILURE_CODE = Regex("^[A-Z0-9_]{1,80}$")

        fun pending(
            id: UUID,
            caseId: UUID,
            subjectId: UUID,
            purpose: ProfileChangePurpose,
            actorId: UUID,
            sessionId: UUID,
            expectedVersion: Long,
            payloadDigest: String,
            actionRequestId: UUID,
            now: Instant,
        ): SupportProfileChange =
            SupportProfileChange(
                id,
                caseId,
                subjectId,
                purpose,
                actorId,
                actorId,
                sessionId,
                expectedVersion,
                payloadDigest,
                actionRequestId,
                null,
                null,
                null,
                null,
                SupportProfileChangeState.AWAITING_APPROVAL,
                SupportProfileNotificationState.NOT_REQUESTED,
                null,
                now,
                now,
                0,
            )

        fun direct(
            id: UUID,
            caseId: UUID,
            subjectId: UUID,
            purpose: ProfileChangePurpose,
            actorId: UUID,
            sessionId: UUID,
            expectedVersion: Long,
            payloadDigest: String,
            result: OwnerProfileChangeResult,
            now: Instant,
        ): SupportProfileChange =
            SupportProfileChange(
                id,
                caseId,
                subjectId,
                purpose,
                actorId,
                actorId,
                sessionId,
                expectedVersion,
                payloadDigest,
                null,
                result.ownerChangeId,
                result.currentVersion,
                result.maskedBefore,
                result.maskedAfter,
                SupportProfileChangeState.EXECUTED,
                if (result.notificationTargets.isEmpty()) {
                    SupportProfileNotificationState.NOT_REQUESTED
                } else {
                    SupportProfileNotificationState.PENDING
                },
                null,
                now,
                now,
                0,
            )

        @Suppress("LongParameterList")
        fun restore(
            id: UUID,
            caseId: UUID,
            subjectId: UUID,
            purpose: ProfileChangePurpose,
            requesterActorId: UUID,
            executorActorId: UUID,
            sessionId: UUID,
            expectedVersion: Long,
            payloadDigest: String,
            actionRequestId: UUID?,
            ownerChangeId: UUID?,
            currentVersion: Long?,
            maskedBefore: String?,
            maskedAfter: String?,
            state: SupportProfileChangeState,
            notificationState: SupportProfileNotificationState,
            failureCode: String?,
            createdAt: Instant,
            updatedAt: Instant,
            version: Long,
        ): SupportProfileChange =
            SupportProfileChange(
                id,
                caseId,
                subjectId,
                purpose,
                requesterActorId,
                executorActorId,
                sessionId,
                expectedVersion,
                payloadDigest,
                actionRequestId,
                ownerChangeId,
                currentVersion,
                maskedBefore,
                maskedAfter,
                state,
                notificationState,
                failureCode,
                createdAt,
                updatedAt,
                version,
            )
    }
}

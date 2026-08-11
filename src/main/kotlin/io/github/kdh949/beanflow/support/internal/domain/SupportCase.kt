package io.github.kdh949.beanflow.support.internal.domain

import java.time.Instant
import java.util.UUID

internal enum class SupportCaseState {
    OPEN,
    IN_PROGRESS,
    WAITING,
    RESOLVED,
    CLOSED,
}

internal enum class SupportRequesterType {
    CUSTOMER,
    STORE_OWNER,
    STORE_MEMBER,
    RIDER,
    THIRD_PARTY,
    INTERNAL_OPERATOR,
    SYSTEM,
    UNKNOWN,
}

internal enum class SupportInquiryCategory {
    ORDER_STATUS,
    PICKUP_RESCHEDULE,
    ORDER_CANCELLATION,
    PAYMENT_OR_REFUND,
    COUPON_OR_POINT,
    COMPENSATION,
    CUSTOMER_PROFILE,
    STORE_PROFILE,
    DELIVERY_STATUS,
    DELIVERY_INCIDENT,
    SETTLEMENT,
    DISPUTE,
    ACCOUNT_RECOVERY,
    PRIVACY,
    SAFETY,
    OTHER,
}

internal enum class SupportCasePriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT,
}

internal enum class SupportCaseMutation {
    ASSIGNMENT,
    INTERACTION,
    NOTE,
    SUBJECT_LINK,
    PRIVILEGED_ACTION,
}

/**
 * The state-owning Support aggregate. Append-only persistence records are created by the application
 * service from the transitions returned by this aggregate; it intentionally does not retain histories.
 */
internal class SupportCase private constructor(
    val id: UUID,
    val requesterType: SupportRequesterType,
    val requesterReference: String,
    val category: SupportInquiryCategory,
    val priority: SupportCasePriority,
    val openedAt: Instant,
    var assigneeId: UUID,
    var state: SupportCaseState,
    var version: Long,
    var closedAt: Instant?,
    private var lastChangedAt: Instant,
) {
    val latestChangeAt: Instant
        get() = lastChangedAt

    fun transitionTo(
        target: SupportCaseState,
        actorId: UUID,
        occurredAt: Instant,
    ): SupportCaseStateTransition {
        requireOpenFor(SupportCaseMutation.PRIVILEGED_ACTION)
        require(actorId == assigneeId) { "Only the current assignee can transition a case" }
        require(occurredAt >= lastChangedAt) { "Case transition time cannot move backward" }
        check(target in allowedTargets(state)) { "Illegal SupportCase transition: $state -> $target" }

        val previous = state
        state = target
        version += 1
        lastChangedAt = occurredAt
        if (target == SupportCaseState.CLOSED) closedAt = occurredAt
        return SupportCaseStateTransition(previous, target, version, occurredAt, actorId)
    }

    fun assign(
        targetAssigneeId: UUID,
        actorId: UUID,
        occurredAt: Instant,
    ): SupportCaseAssignmentChange {
        requireOpenFor(SupportCaseMutation.ASSIGNMENT)
        require(occurredAt >= lastChangedAt) { "Case assignment time cannot move backward" }

        val previous = assigneeId
        assigneeId = targetAssigneeId
        version += 1
        lastChangedAt = occurredAt
        return SupportCaseAssignmentChange(previous, targetAssigneeId, version, occurredAt, actorId)
    }

    fun recordMutation(
        mutation: SupportCaseMutation,
        occurredAt: Instant,
    ): Long {
        requireOpenFor(mutation)
        require(occurredAt >= lastChangedAt) { "Case mutation time cannot move backward" }
        version += 1
        lastChangedAt = occurredAt
        return version
    }

    fun requireOpenFor(mutation: SupportCaseMutation) {
        check(state != SupportCaseState.CLOSED) { "Closed SupportCase rejects ${mutation.name}" }
    }

    private fun allowedTargets(from: SupportCaseState): Set<SupportCaseState> =
        when (from) {
            SupportCaseState.OPEN -> setOf(SupportCaseState.IN_PROGRESS)
            SupportCaseState.IN_PROGRESS -> setOf(SupportCaseState.WAITING, SupportCaseState.RESOLVED)
            SupportCaseState.WAITING -> setOf(SupportCaseState.IN_PROGRESS)
            SupportCaseState.RESOLVED -> setOf(SupportCaseState.CLOSED)
            SupportCaseState.CLOSED -> emptySet()
        }

    companion object {
        fun open(
            id: UUID,
            requesterType: SupportRequesterType,
            requesterReference: String,
            category: SupportInquiryCategory,
            priority: SupportCasePriority,
            assigneeId: UUID,
            reason: String,
            openedAt: Instant,
        ): SupportCase {
            require(requesterReference.isValidReference()) { "Requester reference is invalid" }
            require(reason.isValidReason()) { "SupportCase reason is invalid" }
            if (category == SupportInquiryCategory.OTHER) {
                require(reason.trim().length >= OTHER_REASON_MIN_LENGTH) { "OTHER category requires structured detail" }
            }
            return SupportCase(
                id = id,
                requesterType = requesterType,
                requesterReference = requesterReference.trim(),
                category = category,
                priority = priority,
                openedAt = openedAt,
                assigneeId = assigneeId,
                state = SupportCaseState.OPEN,
                version = 0,
                closedAt = null,
                lastChangedAt = openedAt,
            )
        }

        fun reconstitute(
            id: UUID,
            requesterType: SupportRequesterType,
            requesterReference: String,
            category: SupportInquiryCategory,
            priority: SupportCasePriority,
            openedAt: Instant,
            assigneeId: UUID,
            state: SupportCaseState,
            version: Long,
            closedAt: Instant?,
            lastChangedAt: Instant,
        ): SupportCase {
            require(requesterReference.isValidReference()) { "Requester reference is invalid" }
            require(version >= 0) { "SupportCase version is invalid" }
            require(openedAt <= lastChangedAt) { "SupportCase change time is invalid" }
            require((state == SupportCaseState.CLOSED) == (closedAt != null)) { "SupportCase close state is invalid" }
            require(closedAt == null || (closedAt >= openedAt && closedAt <= lastChangedAt)) {
                "SupportCase close time is invalid"
            }
            return SupportCase(
                id = id,
                requesterType = requesterType,
                requesterReference = requesterReference,
                category = category,
                priority = priority,
                openedAt = openedAt,
                assigneeId = assigneeId,
                state = state,
                version = version,
                closedAt = closedAt,
                lastChangedAt = lastChangedAt,
            )
        }

        private const val OTHER_REASON_MIN_LENGTH = 3
    }
}

internal data class SupportCaseStateTransition(
    val previousState: SupportCaseState,
    val currentState: SupportCaseState,
    val caseVersion: Long,
    val occurredAt: Instant,
    val actorId: UUID,
)

internal data class SupportCaseAssignmentChange(
    val previousAssigneeId: UUID,
    val currentAssigneeId: UUID,
    val caseVersion: Long,
    val occurredAt: Instant,
    val actorId: UUID,
)

private fun String.isValidReference(): Boolean = trim().length in 1..200 && none { it.code < 0x20 || it.code == 0x7f }

private fun String.isValidReason(): Boolean = trim().length in 1..500 && none { it.code < 0x20 || it.code == 0x7f }

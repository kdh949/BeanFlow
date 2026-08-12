package io.github.kdh949.beanflow.support.internal.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID

internal enum class BreakGlassReasonCode {
    IMMEDIATE_SAFETY,
    ACTIVE_FRAUD,
    PRIVACY_INCIDENT,
}

internal enum class BreakGlassState {
    APPROVAL_PENDING,
    ACTIVE,
    DENIED,
    REVIEW_PENDING,
    REVIEWED,
    EXPIRED,
    REVOKED,
}

internal enum class BreakGlassReviewDecision {
    CONFIRMED,
    ESCALATED,
}

internal class BreakGlassRequest private constructor(
    val id: UUID,
    val caseId: UUID,
    val subjectLinkId: UUID,
    val subjectType: VerificationSubjectType,
    val subjectId: UUID,
    val requesterId: UUID,
    val field: SupportPersonalDataField,
    val purpose: VerificationPurpose,
    val reasonCode: BreakGlassReasonCode,
    val requestedAt: Instant,
    initialState: BreakGlassState,
    initialExpiresAt: Instant?,
    initialApproverId: UUID?,
    initialRevealedAt: Instant?,
    initialReviewerId: UUID?,
) {
    var state: BreakGlassState = initialState
        private set
    var expiresAt: Instant? = initialExpiresAt
        private set
    var approverId: UUID? = initialApproverId
        private set
    var revealedAt: Instant? = initialRevealedAt
        private set
    var reviewerId: UUID? = initialReviewerId
        private set

    init {
        require(field.subjectType == subjectType) { "Break-glass field does not belong to its subject type" }
        require(purpose in EMERGENCY_PURPOSES) { "Break-glass purpose is invalid" }
        require(reasonCode.matches(purpose)) { "Break-glass reason and purpose do not match" }
        validateState()
    }

    fun approve(
        actorId: UUID,
        occurredAt: Instant,
    ) {
        check(state == BreakGlassState.APPROVAL_PENDING) { "Break-glass request is not awaiting approval" }
        require(actorId != requesterId) { "Break-glass approver must differ from requester" }
        require(occurredAt >= requestedAt) { "Break-glass time cannot move backward" }
        approverId = actorId
        expiresAt = occurredAt.plus(ACTIVE_TTL)
        state = BreakGlassState.ACTIVE
    }

    fun deny(actorId: UUID) {
        check(state == BreakGlassState.APPROVAL_PENDING) { "Break-glass request is not awaiting approval" }
        require(actorId != requesterId) { "Break-glass approver must differ from requester" }
        approverId = actorId
        state = BreakGlassState.DENIED
    }

    fun reserveReveal(
        actorId: UUID,
        binding: DataAccessBinding,
        requestedField: SupportPersonalDataField,
        occurredAt: Instant,
    ) {
        refresh(occurredAt)
        check(state == BreakGlassState.ACTIVE) { "Break-glass request is not active" }
        require(actorId == requesterId) { "Break-glass request belongs to another operator" }
        require(
            binding.caseId == caseId &&
                binding.subjectLinkId == subjectLinkId &&
                binding.subjectId == subjectId &&
                binding.purpose == purpose,
        ) { "Break-glass request cannot be reused for another binding" }
        require(requestedField == field) { "Break-glass request permits exactly one field" }
        revealedAt = occurredAt
        state = BreakGlassState.REVIEW_PENDING
    }

    fun review(
        actorId: UUID,
        decision: BreakGlassReviewDecision,
        occurredAt: Instant,
    ) {
        check(state == BreakGlassState.REVIEW_PENDING) { "Break-glass request is not awaiting post review" }
        require(actorId != requesterId && actorId != approverId) {
            "Break-glass reviewer must differ from requester and approver"
        }
        require(occurredAt >= requireNotNull(revealedAt)) { "Break-glass review time cannot move backward" }
        reviewerId = actorId
        state = BreakGlassState.REVIEWED
        @Suppress("UNUSED_VARIABLE")
        val recordedDecision = decision
    }

    fun refresh(now: Instant): BreakGlassState {
        val boundary = expiresAt
        if (state == BreakGlassState.ACTIVE && boundary != null && !now.isBefore(boundary)) state = BreakGlassState.EXPIRED
        return state
    }

    fun revoke() {
        if (state == BreakGlassState.APPROVAL_PENDING || state == BreakGlassState.ACTIVE) state = BreakGlassState.REVOKED
    }

    private fun validateState() {
        when (state) {
            BreakGlassState.APPROVAL_PENDING -> {
                require(expiresAt == null && approverId == null && revealedAt == null && reviewerId == null)
            }

            BreakGlassState.ACTIVE,
            BreakGlassState.EXPIRED,
            -> {
                require(expiresAt != null && approverId != null && revealedAt == null && reviewerId == null)
            }

            BreakGlassState.REVIEW_PENDING -> {
                require(expiresAt != null && approverId != null && revealedAt != null && reviewerId == null)
            }

            BreakGlassState.REVIEWED -> {
                require(expiresAt != null && approverId != null && revealedAt != null && reviewerId != null)
            }

            BreakGlassState.DENIED -> {
                require(approverId != null && expiresAt == null && revealedAt == null && reviewerId == null)
            }

            BreakGlassState.REVOKED -> {
                Unit
            }
        }
    }

    internal companion object {
        val ACTIVE_TTL: Duration = Duration.ofMinutes(2)
        private val EMERGENCY_PURPOSES =
            setOf(VerificationPurpose.SAFETY_RESPONSE, VerificationPurpose.FRAUD_INVESTIGATION, VerificationPurpose.PRIVACY_INCIDENT)

        fun request(
            id: UUID,
            caseId: UUID,
            subjectLinkId: UUID,
            subjectType: VerificationSubjectType,
            subjectId: UUID,
            requesterId: UUID,
            field: SupportPersonalDataField,
            purpose: VerificationPurpose,
            reasonCode: BreakGlassReasonCode,
            requestedAt: Instant,
        ): BreakGlassRequest =
            BreakGlassRequest(
                id,
                caseId,
                subjectLinkId,
                subjectType,
                subjectId,
                requesterId,
                field,
                purpose,
                reasonCode,
                requestedAt,
                BreakGlassState.APPROVAL_PENDING,
                null,
                null,
                null,
                null,
            )

        fun restore(
            id: UUID,
            caseId: UUID,
            subjectLinkId: UUID,
            subjectType: VerificationSubjectType,
            subjectId: UUID,
            requesterId: UUID,
            field: SupportPersonalDataField,
            purpose: VerificationPurpose,
            reasonCode: BreakGlassReasonCode,
            requestedAt: Instant,
            state: BreakGlassState,
            expiresAt: Instant?,
            approverId: UUID?,
            revealedAt: Instant?,
            reviewerId: UUID?,
        ): BreakGlassRequest =
            BreakGlassRequest(
                id,
                caseId,
                subjectLinkId,
                subjectType,
                subjectId,
                requesterId,
                field,
                purpose,
                reasonCode,
                requestedAt,
                state,
                expiresAt,
                approverId,
                revealedAt,
                reviewerId,
            )
    }
}

private fun BreakGlassReasonCode.matches(purpose: VerificationPurpose): Boolean =
    when (this) {
        BreakGlassReasonCode.IMMEDIATE_SAFETY -> purpose == VerificationPurpose.SAFETY_RESPONSE
        BreakGlassReasonCode.ACTIVE_FRAUD -> purpose == VerificationPurpose.FRAUD_INVESTIGATION
        BreakGlassReasonCode.PRIVACY_INCIDENT -> purpose == VerificationPurpose.PRIVACY_INCIDENT
    }

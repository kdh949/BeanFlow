package io.github.kdh949.beanflow.support.internal.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID

internal enum class VerificationLevel {
    UNVERIFIED,
    BASIC,
    ENHANCED,
}

internal enum class VerificationState {
    PENDING,
    VERIFIED,
    LOCKED,
    EXPIRED,
    REVOKED,
}

internal enum class VerificationSubjectType {
    CUSTOMER,
    STORE,
    DELIVERY,
}

internal enum class VerificationPurpose {
    CONTACT_CONFIRMATION,
    CASE_RESOLUTION,
    SAFETY_RESPONSE,
    FRAUD_INVESTIGATION,
    PRIVACY_INCIDENT,
}

internal enum class VerificationActionScope {
    PERSONAL_DATA_REVEAL,
}

internal enum class VerificationChannel {
    IN_APP,
    REGISTERED_PHONE,
    REGISTERED_EMAIL,
}

internal class VerificationSession private constructor(
    val id: UUID,
    val caseId: UUID,
    val subjectLinkId: UUID,
    val subjectType: VerificationSubjectType,
    val subjectId: UUID,
    val actorId: UUID,
    val purpose: VerificationPurpose,
    val actionScope: VerificationActionScope,
    val requestedLevel: VerificationLevel,
    val startedAt: Instant,
    val expiresAt: Instant,
    initialState: VerificationState,
    initialInvalidAttempts: Int,
    verifiedChannels: Set<VerificationChannel>,
) {
    var state: VerificationState = initialState
        private set
    var invalidAttempts: Int = initialInvalidAttempts
        private set
    private val successfulChannels = verifiedChannels.toMutableSet()

    val achievedLevel: VerificationLevel
        get() = if (state == VerificationState.VERIFIED) requestedLevel else VerificationLevel.UNVERIFIED

    init {
        require(requestedLevel != VerificationLevel.UNVERIFIED) { "A verification session must request BASIC or ENHANCED" }
        require(expiresAt == startedAt.plus(SESSION_TTL)) { "Verification session lifetime must be fifteen minutes" }
        require(invalidAttempts in 0..MAX_INVALID_ATTEMPTS) { "Verification invalid-attempt count is invalid" }
        validateState()
    }

    fun recordVerifiedChannel(
        channel: VerificationChannel,
        occurredAt: Instant,
    ): VerificationState {
        requirePendingAt(occurredAt)
        successfulChannels += channel
        val requiredChannelCount = if (requestedLevel == VerificationLevel.BASIC) 1 else 2
        if (successfulChannels.size >= requiredChannelCount) state = VerificationState.VERIFIED
        return state
    }

    fun recordInvalidAttempt(occurredAt: Instant): Instant? {
        requirePendingAt(occurredAt)
        invalidAttempts += 1
        if (invalidAttempts < MAX_INVALID_ATTEMPTS) return null
        state = VerificationState.LOCKED
        return occurredAt.plus(LOCKOUT_TTL)
    }

    fun satisfies(
        requiredLevel: VerificationLevel,
        now: Instant,
    ): Boolean {
        refresh(now)
        if (state != VerificationState.VERIFIED) return false
        return when (requiredLevel) {
            VerificationLevel.UNVERIFIED -> true
            VerificationLevel.BASIC -> requestedLevel == VerificationLevel.BASIC || requestedLevel == VerificationLevel.ENHANCED
            VerificationLevel.ENHANCED -> requestedLevel == VerificationLevel.ENHANCED
        }
    }

    fun matches(
        caseId: UUID,
        subjectLinkId: UUID,
        subjectId: UUID,
        purpose: VerificationPurpose,
    ): Boolean =
        this.caseId == caseId &&
            this.subjectLinkId == subjectLinkId &&
            this.subjectId == subjectId &&
            this.purpose == purpose

    fun revoke() {
        if (state == VerificationState.PENDING || state == VerificationState.VERIFIED) state = VerificationState.REVOKED
    }

    fun refresh(now: Instant): VerificationState {
        expireAtBoundary(now)
        return state
    }

    private fun requirePendingAt(now: Instant) {
        expireAtBoundary(now)
        check(state == VerificationState.PENDING) { "Verification session is terminal" }
    }

    private fun expireAtBoundary(now: Instant) {
        if ((state == VerificationState.PENDING || state == VerificationState.VERIFIED) && !now.isBefore(expiresAt)) {
            state = VerificationState.EXPIRED
        }
    }

    private fun validateState() {
        when (state) {
            VerificationState.PENDING -> require(successfulChannels.size < requiredChannelCount())

            VerificationState.VERIFIED -> require(successfulChannels.size >= requiredChannelCount())

            VerificationState.LOCKED -> require(invalidAttempts == MAX_INVALID_ATTEMPTS)

            VerificationState.EXPIRED,
            VerificationState.REVOKED,
            -> Unit
        }
    }

    private fun requiredChannelCount(): Int = if (requestedLevel == VerificationLevel.BASIC) 1 else 2

    internal fun verifiedChannels(): Set<VerificationChannel> = successfulChannels.toSet()

    internal companion object {
        val SESSION_TTL: Duration = Duration.ofMinutes(15)
        val LOCKOUT_TTL: Duration = Duration.ofMinutes(30)
        const val MAX_INVALID_ATTEMPTS = 5

        fun start(
            id: UUID,
            caseId: UUID,
            subjectLinkId: UUID,
            subjectType: VerificationSubjectType,
            subjectId: UUID,
            actorId: UUID,
            purpose: VerificationPurpose,
            actionScope: VerificationActionScope,
            requestedLevel: VerificationLevel,
            startedAt: Instant,
        ): VerificationSession =
            VerificationSession(
                id = id,
                caseId = caseId,
                subjectLinkId = subjectLinkId,
                subjectType = subjectType,
                subjectId = subjectId,
                actorId = actorId,
                purpose = purpose,
                actionScope = actionScope,
                requestedLevel = requestedLevel,
                startedAt = startedAt,
                expiresAt = startedAt.plus(SESSION_TTL),
                initialState = VerificationState.PENDING,
                initialInvalidAttempts = 0,
                verifiedChannels = emptySet(),
            )

        fun restore(
            id: UUID,
            caseId: UUID,
            subjectLinkId: UUID,
            subjectType: VerificationSubjectType,
            subjectId: UUID,
            actorId: UUID,
            purpose: VerificationPurpose,
            actionScope: VerificationActionScope,
            requestedLevel: VerificationLevel,
            startedAt: Instant,
            expiresAt: Instant,
            state: VerificationState,
            invalidAttempts: Int,
            verifiedChannels: Set<VerificationChannel>,
        ): VerificationSession =
            VerificationSession(
                id,
                caseId,
                subjectLinkId,
                subjectType,
                subjectId,
                actorId,
                purpose,
                actionScope,
                requestedLevel,
                startedAt,
                expiresAt,
                state,
                invalidAttempts,
                verifiedChannels,
            )
    }
}

internal enum class ChallengeState {
    PENDING_ISSUE,
    ISSUED,
    ISSUE_UNKNOWN,
    VERIFYING,
    VERIFIED,
    INVALID,
    VERIFICATION_UNKNOWN,
    EXPIRED,
    REVOKED,
}

internal enum class ChallengeOutcome {
    VERIFIED,
    INVALID,
    UNKNOWN,
}

internal class VerificationChallenge private constructor(
    val id: UUID,
    val sessionId: UUID,
    val channel: VerificationChannel,
    val requestedAt: Instant,
    val expiresAt: Instant,
    initialState: ChallengeState,
    initialProviderReference: String?,
) {
    var state: ChallengeState = initialState
        private set
    var providerReference: String? = initialProviderReference
        private set

    init {
        require(expiresAt == requestedAt.plus(CHALLENGE_TTL)) { "Verification challenge lifetime must be five minutes" }
        validateState()
    }

    fun completeIssue(
        opaqueProviderReference: String,
        occurredAt: Instant,
    ): ChallengeState {
        check(state == ChallengeState.PENDING_ISSUE) { "Verification challenge issue is already terminal" }
        require(occurredAt >= requestedAt) { "Challenge issue time cannot move backward" }
        val normalized = opaqueProviderReference.trim()
        require(normalized.isNotEmpty() && normalized.length <= 1000 && normalized.none(Char::isISOControl)) {
            "Opaque provider reference is invalid"
        }
        providerReference = normalized
        state = if (occurredAt.isBefore(expiresAt)) ChallengeState.ISSUED else ChallengeState.EXPIRED
        return state
    }

    fun markIssueUnknown(): ChallengeState {
        check(state == ChallengeState.PENDING_ISSUE) { "Verification challenge issue is already terminal" }
        state = ChallengeState.ISSUE_UNKNOWN
        return state
    }

    fun claimVerification(now: Instant): ChallengeState {
        refresh(now)
        check(state == ChallengeState.ISSUED) { "Verification challenge is not verifiable" }
        state = ChallengeState.VERIFYING
        return state
    }

    fun complete(
        outcome: ChallengeOutcome,
        occurredAt: Instant,
    ): ChallengeState {
        check(state == ChallengeState.VERIFYING) { "Verification challenge is not awaiting an outcome" }
        require(occurredAt >= requestedAt) { "Challenge outcome time cannot move backward" }
        if (!occurredAt.isBefore(expiresAt)) {
            state = ChallengeState.VERIFICATION_UNKNOWN
            return state
        }
        state =
            when (outcome) {
                ChallengeOutcome.VERIFIED -> ChallengeState.VERIFIED
                ChallengeOutcome.INVALID -> ChallengeState.INVALID
                ChallengeOutcome.UNKNOWN -> ChallengeState.VERIFICATION_UNKNOWN
            }
        return state
    }

    fun revoke() {
        if (state == ChallengeState.PENDING_ISSUE || state == ChallengeState.ISSUED || state == ChallengeState.VERIFYING) {
            state = ChallengeState.REVOKED
        }
    }

    fun refresh(now: Instant): ChallengeState {
        if (state == ChallengeState.ISSUED && !now.isBefore(expiresAt)) state = ChallengeState.EXPIRED
        return state
    }

    private fun validateState() {
        if (state == ChallengeState.ISSUED || state == ChallengeState.VERIFYING || state in TERMINAL_PROVIDER_STATES) {
            require(!providerReference.isNullOrBlank()) { "Issued challenge requires an opaque provider reference" }
        }
    }

    internal companion object {
        val CHALLENGE_TTL: Duration = Duration.ofMinutes(5)
        private val TERMINAL_PROVIDER_STATES =
            setOf(ChallengeState.VERIFIED, ChallengeState.INVALID, ChallengeState.VERIFICATION_UNKNOWN, ChallengeState.EXPIRED)

        fun request(
            id: UUID,
            sessionId: UUID,
            channel: VerificationChannel,
            requestedAt: Instant,
        ): VerificationChallenge =
            VerificationChallenge(
                id = id,
                sessionId = sessionId,
                channel = channel,
                requestedAt = requestedAt,
                expiresAt = requestedAt.plus(CHALLENGE_TTL),
                initialState = ChallengeState.PENDING_ISSUE,
                initialProviderReference = null,
            )

        fun restore(
            id: UUID,
            sessionId: UUID,
            channel: VerificationChannel,
            requestedAt: Instant,
            expiresAt: Instant,
            state: ChallengeState,
            providerReference: String?,
        ): VerificationChallenge = VerificationChallenge(id, sessionId, channel, requestedAt, expiresAt, state, providerReference)
    }
}

package io.github.kdh949.beanflow.support.internal.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID

internal enum class DataAccessRisk {
    BASIC,
    SENSITIVE,
}

internal enum class SupportPersonalDataField(
    val subjectType: VerificationSubjectType,
    val risk: DataAccessRisk,
) {
    CUSTOMER_DISPLAY_NAME(VerificationSubjectType.CUSTOMER, DataAccessRisk.BASIC),
    CUSTOMER_PRIMARY_PHONE(VerificationSubjectType.CUSTOMER, DataAccessRisk.SENSITIVE),
    CUSTOMER_PRIMARY_EMAIL(VerificationSubjectType.CUSTOMER, DataAccessRisk.SENSITIVE),
    STORE_LEGAL_DISPLAY_NAME(VerificationSubjectType.STORE, DataAccessRisk.BASIC),
    STORE_SUPPORT_PHONE(VerificationSubjectType.STORE, DataAccessRisk.SENSITIVE),
    STORE_SUPPORT_EMAIL(VerificationSubjectType.STORE, DataAccessRisk.SENSITIVE),
    COURIER_DISPLAY_NAME(VerificationSubjectType.DELIVERY, DataAccessRisk.BASIC),
    COURIER_PROVIDER_REFERENCE(VerificationSubjectType.DELIVERY, DataAccessRisk.SENSITIVE),
    COURIER_RELAY_PHONE(VerificationSubjectType.DELIVERY, DataAccessRisk.SENSITIVE),
    COURIER_RELAY_EMAIL(VerificationSubjectType.DELIVERY, DataAccessRisk.SENSITIVE),
}

internal enum class DataAccessReasonCode {
    CASE_HANDLING,
    CONTACT_CONFIRMATION,
    FRAUD_INVESTIGATION,
    SAFETY_RESPONSE,
    PRIVACY_INCIDENT,
}

internal enum class DataAccessGrantState {
    REQUESTED,
    APPROVAL_PENDING,
    ACTIVE,
    DENIED,
    CONSUMED,
    EXPIRED,
    REVOKED,
}

internal data class DataAccessBinding(
    val caseId: UUID,
    val subjectLinkId: UUID,
    val subjectId: UUID,
    val purpose: VerificationPurpose,
)

internal class DataAccessGrant private constructor(
    val id: UUID,
    val caseId: UUID,
    val subjectLinkId: UUID,
    val subjectType: VerificationSubjectType,
    val subjectId: UUID,
    val requesterId: UUID,
    val purpose: VerificationPurpose,
    fields: Set<SupportPersonalDataField>,
    val reasonCode: DataAccessReasonCode,
    val requestedAt: Instant,
    initialState: DataAccessGrantState,
    initialExpiresAt: Instant?,
    initialReservedReveals: Int,
    initialApproverId: UUID?,
) {
    val fields: Set<SupportPersonalDataField> = fields.toSet()
    val risk: DataAccessRisk = if (fields.any { it.risk == DataAccessRisk.SENSITIVE }) DataAccessRisk.SENSITIVE else DataAccessRisk.BASIC
    val maxReveals: Int = if (risk == DataAccessRisk.BASIC) BASIC_REVEAL_BUDGET else SENSITIVE_REVEAL_BUDGET
    var state: DataAccessGrantState = initialState
        private set
    var expiresAt: Instant? = initialExpiresAt
        private set
    var reservedReveals: Int = initialReservedReveals
        private set
    var approverId: UUID? = initialApproverId
        private set

    init {
        require(this.fields.isNotEmpty() && this.fields.size <= MAX_FIELDS) { "DataAccessGrant requires one to four fields" }
        require(this.fields.all { it.subjectType == subjectType }) { "DataAccessGrant field does not belong to its subject type" }
        require(reservedReveals in 0..maxReveals) { "DataAccessGrant reveal budget is invalid" }
        validateState()
    }

    fun qualify(
        verifiedLevel: VerificationLevel,
        occurredAt: Instant,
    ) {
        check(state == DataAccessGrantState.REQUESTED) { "DataAccessGrant is not awaiting verification" }
        require(occurredAt >= requestedAt) { "DataAccessGrant time cannot move backward" }
        val required = if (risk == DataAccessRisk.BASIC) VerificationLevel.BASIC else VerificationLevel.ENHANCED
        check(verifiedLevel.satisfies(required)) { "Verification level is insufficient for DataAccessGrant" }
        if (risk == DataAccessRisk.BASIC) {
            activate(occurredAt, BASIC_TTL)
        } else {
            state = DataAccessGrantState.APPROVAL_PENDING
        }
    }

    fun approve(
        actorId: UUID,
        occurredAt: Instant,
    ) {
        check(state == DataAccessGrantState.APPROVAL_PENDING) { "DataAccessGrant is not awaiting approval" }
        require(actorId != requesterId) { "DataAccessGrant approver must differ from requester" }
        require(occurredAt >= requestedAt) { "DataAccessGrant approval time cannot move backward" }
        approverId = actorId
        activate(occurredAt, SENSITIVE_TTL)
    }

    fun deny(actorId: UUID) {
        check(state == DataAccessGrantState.APPROVAL_PENDING) { "DataAccessGrant is not awaiting approval" }
        require(actorId != requesterId) { "DataAccessGrant approver must differ from requester" }
        approverId = actorId
        state = DataAccessGrantState.DENIED
    }

    fun reserveReveal(
        requestedFields: Set<SupportPersonalDataField>,
        binding: DataAccessBinding,
        now: Instant,
    ) {
        expireAtBoundary(now)
        check(state == DataAccessGrantState.ACTIVE) { "DataAccessGrant is not active" }
        require(matches(binding)) { "DataAccessGrant cannot be reused for another case, subject, or purpose" }
        require(requestedFields.isNotEmpty() && fields.containsAll(requestedFields)) { "Reveal field is outside DataAccessGrant scope" }
        reservedReveals += 1
        if (reservedReveals == maxReveals) state = DataAccessGrantState.CONSUMED
    }

    fun revoke() {
        if (state == DataAccessGrantState.REQUESTED || state == DataAccessGrantState.APPROVAL_PENDING || state == DataAccessGrantState.ACTIVE) {
            state = DataAccessGrantState.REVOKED
        }
    }

    fun matches(binding: DataAccessBinding): Boolean =
        binding.caseId == caseId &&
            binding.subjectLinkId == subjectLinkId &&
            binding.subjectId == subjectId &&
            binding.purpose == purpose

    private fun activate(
        occurredAt: Instant,
        ttl: Duration,
    ) {
        expiresAt = occurredAt.plus(ttl)
        state = DataAccessGrantState.ACTIVE
    }

    private fun expireAtBoundary(now: Instant) {
        val boundary = expiresAt
        if (state == DataAccessGrantState.ACTIVE && boundary != null && !now.isBefore(boundary)) {
            state = DataAccessGrantState.EXPIRED
        }
    }

    private fun validateState() {
        when (state) {
            DataAccessGrantState.REQUESTED,
            DataAccessGrantState.APPROVAL_PENDING,
            DataAccessGrantState.DENIED,
            DataAccessGrantState.REVOKED,
            -> Unit

            DataAccessGrantState.ACTIVE,
            DataAccessGrantState.CONSUMED,
            DataAccessGrantState.EXPIRED,
            -> requireNotNull(expiresAt) { "Activated DataAccessGrant requires an expiry" }
        }
        if (state == DataAccessGrantState.APPROVAL_PENDING) require(risk == DataAccessRisk.SENSITIVE)
        if (approverId != null) require(risk == DataAccessRisk.SENSITIVE)
    }

    internal companion object {
        val BASIC_TTL: Duration = Duration.ofMinutes(10)
        val SENSITIVE_TTL: Duration = Duration.ofMinutes(5)
        const val BASIC_REVEAL_BUDGET = 3
        const val SENSITIVE_REVEAL_BUDGET = 1
        const val MAX_FIELDS = 4

        fun request(
            id: UUID,
            caseId: UUID,
            subjectLinkId: UUID,
            subjectType: VerificationSubjectType,
            subjectId: UUID,
            requesterId: UUID,
            purpose: VerificationPurpose,
            fields: Set<SupportPersonalDataField>,
            reasonCode: DataAccessReasonCode,
            requestedAt: Instant,
        ): DataAccessGrant =
            DataAccessGrant(
                id,
                caseId,
                subjectLinkId,
                subjectType,
                subjectId,
                requesterId,
                purpose,
                fields,
                reasonCode,
                requestedAt,
                DataAccessGrantState.REQUESTED,
                null,
                0,
                null,
            )

        fun restore(
            id: UUID,
            caseId: UUID,
            subjectLinkId: UUID,
            subjectType: VerificationSubjectType,
            subjectId: UUID,
            requesterId: UUID,
            purpose: VerificationPurpose,
            fields: Set<SupportPersonalDataField>,
            reasonCode: DataAccessReasonCode,
            requestedAt: Instant,
            state: DataAccessGrantState,
            expiresAt: Instant?,
            reservedReveals: Int,
            approverId: UUID?,
        ): DataAccessGrant =
            DataAccessGrant(
                id,
                caseId,
                subjectLinkId,
                subjectType,
                subjectId,
                requesterId,
                purpose,
                fields,
                reasonCode,
                requestedAt,
                state,
                expiresAt,
                reservedReveals,
                approverId,
            )
    }
}

private fun VerificationLevel.satisfies(required: VerificationLevel): Boolean =
    when (required) {
        VerificationLevel.UNVERIFIED -> true
        VerificationLevel.BASIC -> this == VerificationLevel.BASIC || this == VerificationLevel.ENHANCED
        VerificationLevel.ENHANCED -> this == VerificationLevel.ENHANCED
    }

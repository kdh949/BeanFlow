package io.github.kdh949.beanflow.operations.api

import java.util.UUID

enum class OrdinaryPointAccrualPolicyScopeType {
    GLOBAL,
    STORE,
}

enum class OrdinaryPointAccrualPolicyState {
    OVERRIDE,
    INHERIT_GLOBAL,
}

enum class PointAccrualRoundingMode {
    FLOOR,
    HALF_UP,
}

enum class PointAccrualIssuerType {
    PLATFORM,
    BRAND,
    STORE,
}

enum class OrdinaryPointAccrualExpiryRule {
    EXACT_DURATION_FROM_COMPLETION,
    SEOUL_CALENDAR_DAYS_FROM_COMPLETION,
}

enum class OrdinaryPointAccrualPolicySelectionSource {
    STORE_OVERRIDE,
    GLOBAL_INHERITED,
    GLOBAL_NO_OVERRIDE,
}

data class OrdinaryPointAccrualPolicySnapshot(
    val policyVersionId: Long,
    val scopeType: OrdinaryPointAccrualPolicyScopeType,
    val scopeReference: UUID,
    val accrualRateBps: Int,
    val roundingMode: PointAccrualRoundingMode,
    val issuerType: PointAccrualIssuerType,
    val issuerReference: String,
    val expiryRule: OrdinaryPointAccrualExpiryRule,
    val validityDays: Int,
    val canonicalPolicyHash: String,
) {
    init {
        require(policyVersionId > 0) { "Policy version ID must be positive" }
        require(
            (scopeType == OrdinaryPointAccrualPolicyScopeType.GLOBAL && scopeReference == GLOBAL_SCOPE_REFERENCE) ||
                (scopeType == OrdinaryPointAccrualPolicyScopeType.STORE && scopeReference != GLOBAL_SCOPE_REFERENCE),
        ) { "Policy scope type and reference do not match" }
        require(accrualRateBps in 0..10_000) { "Accrual rate must be between 0 and 10000 bps" }
        val normalizedIssuerReference = issuerReference.trim()
        require(
            issuerReference == normalizedIssuerReference &&
                normalizedIssuerReference.length in 1..240 &&
                normalizedIssuerReference.none { it.code < 0x20 || it.code == 0x7f },
        ) { "Issuer reference must be trimmed and contain between 1 and 240 non-control characters" }
        require(validityDays in 1..3650) { "Validity must be between 1 and 3650 days" }
        require(canonicalPolicyHash.matches(Regex("[0-9a-f]{64}"))) {
            "Canonical policy hash must be a lowercase SHA-256 value"
        }
    }

    companion object {
        val GLOBAL_SCOPE_REFERENCE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
    }
}

data class SelectedOrdinaryPointAccrualPolicy(
    val policy: OrdinaryPointAccrualPolicySnapshot,
    val selectionSource: OrdinaryPointAccrualPolicySelectionSource,
)

interface OrdinaryPointAccrualPolicyOperations {
    fun selectForOrder(storeId: UUID): SelectedOrdinaryPointAccrualPolicy
}

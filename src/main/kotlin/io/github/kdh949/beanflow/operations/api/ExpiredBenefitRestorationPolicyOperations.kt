package io.github.kdh949.beanflow.operations.api

import java.time.Instant
import java.util.UUID

enum class ExpiredBenefitRestorationMode {
    COMPENSATE_WITH_NEW_ISSUANCE,
    PRESERVE_ORIGINAL_EXPIRY,
}

data class ExpiredBenefitRestorationPolicySnapshot(
    val policyVersion: Long,
    val mode: ExpiredBenefitRestorationMode,
    val compensationValidityDays: Int,
    val effectiveAt: Instant,
    val updatedBy: UUID,
    val reason: String,
)

data class UpdateExpiredBenefitRestorationPolicyCommand(
    val actorId: UUID,
    val idempotencyKey: String,
    val expectedPolicyVersion: Long,
    val mode: ExpiredBenefitRestorationMode,
    val compensationValidityDays: Int,
    val reason: String,
    val now: Instant,
)

interface ExpiredBenefitRestorationPolicyOperations {
    fun current(): ExpiredBenefitRestorationPolicySnapshot

    fun update(command: UpdateExpiredBenefitRestorationPolicyCommand): ExpiredBenefitRestorationPolicySnapshot
}

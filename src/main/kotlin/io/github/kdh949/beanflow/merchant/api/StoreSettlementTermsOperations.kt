package io.github.kdh949.beanflow.merchant.api

import java.time.Instant
import java.util.UUID

data class StoreSettlementTermsSnapshot(
    val termsVersionId: UUID,
    val storeId: UUID,
    val sourceReference: String,
    val feeRateBps: Int,
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
)

interface StoreSettlementTermsOperations {
    fun findApplicable(
        storeId: UUID,
        effectiveAt: Instant,
    ): StoreSettlementTermsSnapshot
}

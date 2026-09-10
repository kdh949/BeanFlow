package io.github.kdh949.beanflow.merchant.api

import java.time.Instant
import java.util.UUID

data class RegisterStoreSettlementTermsCommand(
    val actorId: UUID,
    val storeId: UUID,
    val key: String,
    val sourceReference: String,
    val feeRateBps: Int,
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
    val expectedRevision: Long,
    val reason: String,
    val now: Instant,
)

data class ManagedStoreSettlementTerms(
    val terms: StoreSettlementTermsSnapshot,
    val revision: Long,
)

data class StoreSettlementTermsRows(
    val items: List<StoreSettlementTermsSnapshot>,
    val revision: Long,
)

data class StoreSettlementTermsChange(
    val commandId: UUID,
    val response: ManagedStoreSettlementTerms,
    val replayed: Boolean,
)

/** Caller provides active authorization and Audit in the same transaction. */
interface StoreSettlementTermsManagementOperations {
    fun get(
        storeId: UUID,
        termsVersionId: UUID,
    ): ManagedStoreSettlementTerms

    fun list(
        storeId: UUID,
        afterFrom: Instant?,
        afterId: UUID?,
        limit: Int,
    ): StoreSettlementTermsRows

    fun register(command: RegisterStoreSettlementTermsCommand): StoreSettlementTermsChange
}

package io.github.kdh949.beanflow.promotion.api

import java.time.Instant
import java.util.UUID

enum class GoodwillCouponResponsibility {
    PLATFORM,
    STORE,
    SHARED,
}

data class IssueGoodwillCouponCommand(
    val compensationRequestId: UUID,
    val customerId: UUID,
    val storeId: UUID,
    val couponTemplateId: UUID,
    val amountKrw: Long,
    val responsibility: GoodwillCouponResponsibility,
    val platformShareBps: Int,
    val storeShareBps: Int,
    val policyVersionId: UUID,
    val sourceReference: String,
    val payloadHash: String,
    val issuedAt: Instant,
)

data class GoodwillCouponIssuanceResult(
    val issuanceRecordId: UUID,
    val couponIssuanceId: UUID,
    val campaignId: UUID,
    val sourceReference: String,
    val expiresAt: Instant,
    val replayed: Boolean,
)

data class GoodwillCouponTemplateView(
    val templateId: UUID,
    val amountKrw: Long,
    val validityDays: Int,
)

interface GoodwillCouponOperations {
    fun findTemplate(templateId: UUID): GoodwillCouponTemplateView?

    /** Must join the caller's local DB transaction; terms are selected by immutable template id. */
    fun issue(command: IssueGoodwillCouponCommand): GoodwillCouponIssuanceResult
}

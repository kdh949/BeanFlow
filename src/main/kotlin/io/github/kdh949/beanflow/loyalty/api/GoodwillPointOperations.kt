package io.github.kdh949.beanflow.loyalty.api

import java.time.Instant
import java.util.UUID

enum class GoodwillPointFundingIssuer {
    PLATFORM,
    STORE,
}

data class GoodwillPointFundingLeg(
    val issuerType: GoodwillPointFundingIssuer,
    val storeId: UUID?,
    val amountKrw: Long,
)

data class IssueGoodwillPointsCommand(
    val compensationRequestId: UUID,
    val customerId: UUID,
    val totalAmountKrw: Long,
    val fundingLegs: List<GoodwillPointFundingLeg>,
    val policyVersionId: UUID,
    val sourceReference: String,
    val payloadHash: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
)

data class GoodwillPointIssuanceResult(
    val issuanceId: UUID,
    val pointAccountId: UUID,
    val lotIds: List<UUID>,
    val sourceReference: String,
    val replayed: Boolean,
)

interface GoodwillPointOperations {
    /** Must join the caller's local DB transaction; no independent commit is allowed. */
    fun issue(command: IssueGoodwillPointsCommand): GoodwillPointIssuanceResult
}

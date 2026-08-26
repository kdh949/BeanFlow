package io.github.kdh949.beanflow.loyalty.api

import java.time.Instant
import java.util.UUID

data class PointQuoteAllocation(
    val pointLotId: UUID,
    val lotVersion: Long,
    val expiresAt: Instant,
    val issuerType: PointIssuerType,
    val issuerReference: String,
    val availableAmountKrw: Long,
    val allocationKrw: Long,
)

data class PointQuoteSnapshot(
    val pointAccountId: UUID,
    val accountVersion: Long,
    val availablePointsKrw: Long,
    val requestedPointsKrw: Long,
    val allocations: List<PointQuoteAllocation>,
)

interface PointQuoteOperations {
    fun inspect(
        customerId: UUID,
        amountKrw: Long,
    ): PointQuoteSnapshot?

    fun lockForOrderCreation(
        customerId: UUID,
        amountKrw: Long,
    ): PointQuoteSnapshot?
}

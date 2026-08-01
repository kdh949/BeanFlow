package io.github.kdh949.beanflow.operations.api

import java.time.Instant
import java.util.UUID

data class OrdinaryPointAccrualPolicyVersionView(
    val policyVersionId: Long,
    val scopeType: OrdinaryPointAccrualPolicyScopeType,
    val scopeReference: UUID,
    val state: OrdinaryPointAccrualPolicyState,
    val accrualRateBps: Int?,
    val roundingMode: PointAccrualRoundingMode?,
    val issuerType: PointAccrualIssuerType?,
    val issuerReference: String?,
    val expiryRule: OrdinaryPointAccrualExpiryRule?,
    val validityDays: Int?,
    val effectiveAt: Instant,
    val actorType: AuditActorType,
    val actorReference: String,
    val reason: String,
)

data class OrdinaryPointAccrualPolicyPage<T>(
    val items: List<T>,
    val nextCursor: String?,
)

data class ReadOrdinaryPointAccrualPolicyCommand(
    val actorId: UUID,
    val accessReason: String,
    val now: Instant,
)

data class ListOrdinaryPointAccrualPolicyVersionsCommand(
    val actorId: UUID,
    val accessReason: String,
    val cursor: String?,
    val limit: Int?,
    val now: Instant,
)

data class ListStorePointAccrualPolicyHeadsCommand(
    val actorId: UUID,
    val accessReason: String,
    val state: OrdinaryPointAccrualPolicyState?,
    val cursor: String?,
    val limit: Int?,
    val now: Instant,
)

data class StoreOrdinaryPointAccrualPolicyView(
    val storeId: UUID,
    val explicitHead: OrdinaryPointAccrualPolicyVersionView?,
    val effectivePolicy: OrdinaryPointAccrualPolicySnapshot,
    val selectionSource: OrdinaryPointAccrualPolicySelectionSource,
)

interface OrdinaryPointAccrualPolicyQueryOperations {
    fun currentGlobal(command: ReadOrdinaryPointAccrualPolicyCommand): OrdinaryPointAccrualPolicyVersionView

    fun globalHistory(
        command: ListOrdinaryPointAccrualPolicyVersionsCommand,
    ): OrdinaryPointAccrualPolicyPage<OrdinaryPointAccrualPolicyVersionView>

    fun storeHeads(command: ListStorePointAccrualPolicyHeadsCommand): OrdinaryPointAccrualPolicyPage<OrdinaryPointAccrualPolicyVersionView>

    fun currentStore(
        storeId: UUID,
        command: ReadOrdinaryPointAccrualPolicyCommand,
    ): StoreOrdinaryPointAccrualPolicyView

    fun storeHistory(
        storeId: UUID,
        command: ListOrdinaryPointAccrualPolicyVersionsCommand,
    ): OrdinaryPointAccrualPolicyPage<OrdinaryPointAccrualPolicyVersionView>
}

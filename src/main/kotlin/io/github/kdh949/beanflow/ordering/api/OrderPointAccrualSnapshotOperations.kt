package io.github.kdh949.beanflow.ordering.api

import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicySelectionSource
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicySnapshot
import java.time.Instant
import java.util.UUID

enum class OrderPointAccrualSourceState {
    LEGACY_NOT_APPLICABLE,
    SNAPSHOTTED,
}

data class OrderPointAccrualUnitSnapshot(
    val orderLineId: UUID,
    val lineSequence: Int,
    val unitPosition: Int,
    val cashPayableKrw: Long,
    val accruedAmountKrw: Long,
)

data class OrderPointAccrualSnapshot(
    val orderId: UUID,
    val policy: OrdinaryPointAccrualPolicySnapshot,
    val selectionSource: OrdinaryPointAccrualPolicySelectionSource,
    val orderPayableKrw: Long,
    val grossAccrualAmountKrw: Long,
    val snapshotSchemaVersion: Int,
    val canonicalSnapshotHash: String,
    val createdAt: Instant,
    val units: List<OrderPointAccrualUnitSnapshot>,
)

data class OrderPointAccrualSource(
    val orderId: UUID,
    val sourceState: OrderPointAccrualSourceState,
    val snapshot: OrderPointAccrualSnapshot?,
)

interface OrderPointAccrualSnapshotOperations {
    fun read(orderId: UUID): OrderPointAccrualSource
}

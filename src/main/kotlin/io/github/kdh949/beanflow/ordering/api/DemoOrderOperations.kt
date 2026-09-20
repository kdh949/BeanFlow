package io.github.kdh949.beanflow.ordering.api

import java.time.Instant
import java.util.UUID

data class DemoOrderSnapshot(
    val orderReference: String,
    val status: String,
    val pickupNumber: String,
    val pickupWindowStart: Instant?,
    val acceptanceDeadlineAt: Instant?,
)

interface DemoOrderOperations {
    fun createSample(
        customerId: UUID,
        storeId: UUID,
        menuId: UUID,
    ): DemoOrderSnapshot

    fun inspect(
        customerId: UUID,
        storeId: UUID,
        reference: String,
    ): DemoOrderSnapshot
}

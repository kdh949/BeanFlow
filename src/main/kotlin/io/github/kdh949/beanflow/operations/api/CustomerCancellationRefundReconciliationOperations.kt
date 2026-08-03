package io.github.kdh949.beanflow.operations.api

import java.time.Instant
import java.util.UUID

data class ScheduleCustomerCancellationRefundLookupCommand(
    val orderId: UUID,
    val cancellationOrderVersion: Long,
    val now: Instant,
)

data class ScheduledCustomerCancellationRefundLookup(
    val previousRefundState: String,
)

interface CustomerCancellationRefundReconciliationOperations {
    fun scheduleLookup(command: ScheduleCustomerCancellationRefundLookupCommand): ScheduledCustomerCancellationRefundLookup
}

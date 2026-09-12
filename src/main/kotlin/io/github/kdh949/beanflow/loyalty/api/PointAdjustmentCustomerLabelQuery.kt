package io.github.kdh949.beanflow.loyalty.api

import java.util.UUID

/** Minimal owner projection for a previously selected customer; callers enforce their purpose-specific grants. */
interface PointAdjustmentCustomerLabelQuery {
    fun find(customerId: UUID): PointAdjustmentCustomerLabel?
}

data class PointAdjustmentCustomerLabel(
    val customerId: UUID,
    val maskedLoginId: String,
    val maskedDisplayName: String,
)

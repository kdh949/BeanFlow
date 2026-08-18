package io.github.kdh949.beanflow.ordering.internal

import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID

internal data class PaymentConfirmationRequest(
    @field:NotNull
    val paymentMethodId: UUID?,
)

internal data class PaymentConfirmationResponse(
    val paymentId: UUID,
    val orderReference: String,
    val type: String,
    val approvalState: String,
    val approvedAmountKrw: Long?,
    val currency: String,
    val recovery: PaymentRecoveryResponse?,
    val updatedAt: Instant,
    val correlationId: String,
)

internal data class PaymentRecoveryResponse(
    val state: String,
    val lastUpdatedAt: Instant?,
)

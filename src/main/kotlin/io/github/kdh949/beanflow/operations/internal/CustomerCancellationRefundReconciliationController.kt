package io.github.kdh949.beanflow.operations.internal

import com.fasterxml.jackson.annotation.JsonAnySetter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OperatorActor
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class CustomerCancellationRefundReconciliationRequest(
    @field:Size(min = 1, max = 500)
    val reason: String,
) {
    @JsonAnySetter
    fun rejectUnknownField(
        @Suppress("UNUSED_PARAMETER") name: String,
        @Suppress("UNUSED_PARAMETER") value: Any?,
    ): Unit = throw IllegalArgumentException("Unknown refund reconciliation request field")
}

internal data class CustomerCancellationRefundReconciliationResponse(
    val operationId: UUID,
    val orderId: UUID,
    val cancellationOrderVersion: Long,
    val state: String,
    val scheduledAt: Instant,
)

internal data class ScheduleCustomerCancellationRefundReconciliationCommand(
    val actorId: UUID,
    val orderId: UUID,
    val idempotencyKey: String,
    val reason: String,
    val now: Instant,
)

@Validated
@RestController
@RequestMapping("/api/v1/operations/orders")
internal class CustomerCancellationRefundReconciliationController(
    private val service: CustomerCancellationRefundReconciliationService,
    private val clock: Clock,
) {
    @PostMapping("/{orderId}/customer-cancellation-refund-reconciliations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun schedule(
        actor: OperatorActor,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @PathVariable orderId: UUID,
        @Valid @RequestBody request: CustomerCancellationRefundReconciliationRequest,
    ): CustomerCancellationRefundReconciliationResponse =
        service.schedule(
            ScheduleCustomerCancellationRefundReconciliationCommand(
                actorId = actorId(actor),
                orderId = orderId,
                idempotencyKey = idempotencyKey,
                reason = request.reason,
                now = clock.instant(),
            ),
        )

    private fun actorId(actor: OperatorActor): UUID =
        try {
            actor.actorId
        } catch (_: RuntimeException) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Authenticated subject is not a valid operator actor ID")
        }
}

package io.github.kdh949.beanflow.dispute.internal

import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.MerchantActor
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

internal data class CreateSettlementDisputeRequest(
    val expectedAdjustmentKrw: Long,
    @field:NotBlank @field:Size(max = 1000)
    val reason: String,
    @field:NotEmpty
    val evidenceReferences: List<
        @Size(min = 1, max = 500)
        String,
    >,
    val previousDisputeId: UUID? = null,
)

@Validated
@RestController
@RequestMapping("/api/v1/settlement-items/{itemId}/disputes")
internal class SettlementDisputeController(
    private val service: SettlementDisputeFilingService,
    private val correlationIds: CorrelationIdSource,
) {
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    fun create(
        actor: MerchantActor,
        @PathVariable itemId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: CreateSettlementDisputeRequest,
    ): ResponseEntity<SettlementDisputeResponse> {
        val response =
            service.file(
                FileSettlementDisputeCommand(
                    actorId = actor.actorId,
                    actorRoles = setOf(StoreActorRole.OWNER),
                    settlementItemId = itemId,
                    idempotencyKey = idempotencyKey,
                    expectedAdjustmentKrw = request.expectedAdjustmentKrw,
                    reason = request.reason,
                    evidenceReferences = request.evidenceReferences,
                    previousDisputeId = request.previousDisputeId,
                    correlationId = correlationIds.currentOrCreate(),
                ),
            )
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }
}

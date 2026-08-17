package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.shared.api.MerchantActor
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.MediaType
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

internal data class MerchantRefundLineRequest(
    @field:Min(0)
    val lineSequence: Int,
    @field:Min(1)
    val quantity: Long,
)

internal data class MerchantRefundPreviewRequest(
    /**
     * Omitted means nothing is selected yet and the response is the refundable
     * catalog. It never means a full refund, so an explicit empty array is a
     * client mistake rather than a selection.
     */
    @field:Size(min = 1)
    val lines: List<@Valid MerchantRefundLineRequest>? = null,
)

internal data class MerchantRefundRequest(
    @field:NotEmpty
    val lines: List<@Valid MerchantRefundLineRequest>,
    @field:Pattern(regexp = "[0-9a-f]{64}")
    val previewVersion: String,
    @field:Size(min = 1, max = 500)
    val reason: String,
)

@Validated
@RestController
@RequestMapping("/api/v1/stores/{storeId}/orders/{orderReference}")
internal class MerchantRefundController(
    private val service: MerchantRefundService,
) {
    @PostMapping("/refund-previews")
    @PreAuthorize("hasRole('MERCHANT')")
    fun preview(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @PathVariable orderReference: String,
        @Valid @RequestBody(required = false) request: MerchantRefundPreviewRequest?,
    ): MerchantRefundPreviewResponse =
        service.preview(
            MerchantRefundPreviewQuery(
                actorId = actor.actorId,
                storeId = storeId,
                orderReference = orderReference,
                lines = request?.lines.orEmpty().map { PartialRefundLineSelection(it.lineSequence, it.quantity) },
            ),
        )

    @PostMapping("/refunds")
    @PreAuthorize("hasRole('MERCHANT')")
    fun refund(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @PathVariable orderReference: String,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: MerchantRefundRequest,
    ): ResponseEntity<String> {
        val result =
            service.execute(
                MerchantRefundCommand(
                    actorId = actor.actorId,
                    storeId = storeId,
                    orderReference = orderReference,
                    idempotencyKey = idempotencyKey,
                    lines = request.lines.map { PartialRefundLineSelection(it.lineSequence, it.quantity) },
                    previewVersion = request.previewVersion,
                    reason = request.reason,
                ),
            )
        return ResponseEntity.status(result.status).contentType(MediaType.APPLICATION_JSON).body(result.body)
    }
}

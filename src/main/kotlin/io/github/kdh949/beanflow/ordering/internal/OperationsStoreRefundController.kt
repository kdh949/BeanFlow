package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.shared.api.OperatorActor
import jakarta.validation.Valid
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

@Validated
@RestController
@RequestMapping("/api/v1/operations/stores/{storeId}/orders/{orderReference}")
internal class OperationsStoreRefundController(
    private val service: MerchantRefundService,
) {
    @PostMapping("/refund-previews")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun preview(
        actor: OperatorActor,
        @PathVariable storeId: UUID,
        @PathVariable orderReference: String,
        @Valid @RequestBody(required = false) request: MerchantRefundPreviewRequest?,
    ): MerchantRefundPreviewResponse =
        service.previewOperations(
            MerchantRefundPreviewQuery(
                actorId = actor.actorId,
                storeId = storeId,
                orderReference = orderReference,
                lines = request?.lines.orEmpty().map { PartialRefundLineSelection(it.lineSequence, it.quantity) },
            ),
        )

    @PostMapping("/refunds")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun refund(
        actor: OperatorActor,
        @PathVariable storeId: UUID,
        @PathVariable orderReference: String,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: MerchantRefundRequest,
    ): ResponseEntity<String> {
        val result =
            service.executeOperations(
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

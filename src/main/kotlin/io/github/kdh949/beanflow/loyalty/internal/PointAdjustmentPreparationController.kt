package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.shared.api.OperatorActor
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

internal data class PreparePointAdjustmentRequest(
    val accountId: UUID,
    @field:Valid val request: PointAdjustmentRequest,
)

internal data class DismissPointAdjustmentPreparationRequest(
    val expectedState: PointAdjustmentPreparationState,
)

@RestController
@RequestMapping("/api/v1/operations/point-adjustment-preparations")
@PreAuthorize("hasRole('PLATFORM_OPERATOR')")
internal class PointAdjustmentPreparationController(
    private val service: PointAdjustmentPreparationService,
) {
    @GetMapping("/current")
    fun current(actor: OperatorActor): ResponseEntity<CurrentPointAdjustmentPreparation> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.current(actor.actorId))

    @PostMapping
    fun prepare(
        actor: OperatorActor,
        @Valid @RequestBody body: PreparePointAdjustmentRequest,
    ): ResponseEntity<PointAdjustmentPreparationView> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.prepare(actor.actorId, body.accountId, body.request))

    @DeleteMapping("/{preparationId}")
    fun dismiss(
        actor: OperatorActor,
        @PathVariable preparationId: UUID,
        @RequestBody body: DismissPointAdjustmentPreparationRequest,
    ): ResponseEntity<Void> {
        service.dismiss(actor.actorId, preparationId, body.expectedState)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }
}

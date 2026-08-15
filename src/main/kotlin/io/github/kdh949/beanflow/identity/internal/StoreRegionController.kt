package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.merchant.api.StoreRegionAssignment
import io.github.kdh949.beanflow.shared.api.MerchantActor
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class AssignStoreRegionRequest(
    @field:Pattern(regexp = "^[0-9]{10}$")
    val regionCode: String,
    @field:Size(min = 1, max = 200)
    val reason: String,
)

data class StoreRegionResponse(
    val storeId: UUID,
    val regionCode: String,
    val regionFullName: String,
) {
    companion object {
        fun of(assignment: StoreRegionAssignment) =
            StoreRegionResponse(assignment.storeId, assignment.region.code, assignment.region.fullName)
    }
}

/**
 * The store owner's region command (ADR-112 4절).
 *
 * Unlike the operator brand surface there is no platform-wide permission grant: the authority comes
 * from owning this store, which [StoreRegionCommandService] checks inside the transaction. A
 * `STORE_STAFF` member of the same store and the owner of another store are both `403`.
 */
@Validated
@RestController
@RequestMapping("/api/v1")
internal class StoreRegionController(
    private val service: StoreRegionCommandService,
) {
    @PutMapping("/stores/{storeId}/region")
    fun assign(
        actor: MerchantActor,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @PathVariable storeId: UUID,
        @Valid @RequestBody request: AssignStoreRegionRequest,
    ): StoreRegionResponse =
        StoreRegionResponse.of(
            service.assign(
                StoreRegionCommandContext(actor.actorId, idempotencyKey, request.reason),
                storeId,
                request.regionCode,
            ),
        )
}

package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.merchant.api.StoreOrderingPolicySnapshot
import io.github.kdh949.beanflow.shared.api.MerchantActor
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class ReplaceStoreOrderingPolicyRequest(
    val acceptingOrders: Boolean,
    val pickupEnabled: Boolean,
    @field:Min(0)
    val expectedVersion: Long,
)

data class StoreOrderingPolicyResponse(
    val storeId: UUID,
    val acceptingOrders: Boolean,
    val pickupEnabled: Boolean,
    val version: Long,
    val updatedAt: Instant,
) {
    companion object {
        fun of(policy: StoreOrderingPolicySnapshot) =
            StoreOrderingPolicyResponse(
                policy.storeId,
                policy.acceptingOrders,
                policy.pickupEnabled,
                policy.version,
                policy.updatedAt,
            )
    }
}

@Validated
@RestController
@RequestMapping("/api/v1/stores/{storeId}/ordering-policy")
internal class StoreOrderingPolicyController(
    private val service: StoreOrderingPolicyApplicationService,
) {
    @GetMapping
    fun find(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
    ): StoreOrderingPolicyResponse = StoreOrderingPolicyResponse.of(service.find(actor.actorId, storeId))

    @PutMapping
    fun replace(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: ReplaceStoreOrderingPolicyRequest,
    ): StoreOrderingPolicyResponse =
        StoreOrderingPolicyResponse.of(
            service.replace(
                StoreOrderingPolicyCommandContext(actor.actorId, idempotencyKey),
                storeId,
                request.acceptingOrders,
                request.pickupEnabled,
                request.expectedVersion,
            ),
        )
}

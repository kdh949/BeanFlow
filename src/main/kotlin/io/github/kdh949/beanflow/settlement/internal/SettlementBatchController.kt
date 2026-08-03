package io.github.kdh949.beanflow.settlement.internal

import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Validated
@RestController
@RequestMapping("/api/v1/stores/{storeId}/settlements")
internal class SettlementBatchController(
    private val service: SettlementBatchQueryService,
) {
    @GetMapping
    @PreAuthorize("hasRole('STORE_OWNER')")
    fun list(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable storeId: UUID,
        @RequestParam(required = false) @Size(min = 1, max = 2048) cursor: String?,
        @RequestParam(required = false) @Min(1) @Max(100) limit: Int?,
    ): SettlementBatchPageResponse {
        val actorId =
            try {
                UUID.fromString(jwt.subject)
            } catch (_: IllegalArgumentException) {
                throw DomainFailure(FailureCode.INVALID_REQUEST, "Authenticated subject is not a valid actor ID")
            }
        val roles =
            jwt.getClaimAsStringList("roles").orEmpty().mapNotNullTo(linkedSetOf()) {
                if (it == "STORE_OWNER") StoreActorRole.OWNER else null
            }
        if (roles.isEmpty()) throw DomainFailure(FailureCode.ACCESS_DENIED, "Store owner role is required")
        return service.list(ListSettlementBatchesQuery(actorId, roles, storeId, cursor, limit))
    }
}

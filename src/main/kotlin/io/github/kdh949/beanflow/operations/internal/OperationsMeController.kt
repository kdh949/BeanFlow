package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.OperatorDisplay
import io.github.kdh949.beanflow.shared.api.OperatorActor
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

internal data class OperationsMeResponse(
    val actorType: String,
    val operatorId: UUID,
    val roles: Set<String>,
    val display: OperatorDisplay,
)

@RestController
@RequestMapping("/api/v1/operations")
internal class OperationsMeController(
    private val directory: OperatorDirectoryService,
) {
    @GetMapping("/me")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun me(actor: OperatorActor) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            OperationsMeResponse(
                actorType = "OPERATOR",
                operatorId = actor.actorId,
                roles = actor.roles,
                display = directory.observe(actor.actorId, actor.loginIdentity?.loginName, actor.loginIdentity?.tokenIssuedAt),
            ),
        )
}

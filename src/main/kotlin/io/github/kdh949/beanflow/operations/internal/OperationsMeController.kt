package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.shared.api.OperatorActor
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

internal data class OperationsMeResponse(
    val actorType: String,
    val operatorId: UUID,
    val roles: Set<String>,
)

@RestController
@RequestMapping("/api/v1/operations")
internal class OperationsMeController {
    @GetMapping("/me")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun me(actor: OperatorActor): OperationsMeResponse =
        OperationsMeResponse(
            actorType = "OPERATOR",
            operatorId = actor.actorId,
            roles = actor.roles,
        )
}

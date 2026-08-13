package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.OperatorCompensationQueryOperations
import io.github.kdh949.beanflow.operations.api.OperatorCompensationView
import io.github.kdh949.beanflow.operations.api.ReadOperatorCompensationCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OperatorActor
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

@Validated
@RestController
@RequestMapping("/api/v1/operations/orders")
internal class OperatorCompensationController(
    private val query: OperatorCompensationQueryOperations,
    private val clock: Clock,
) {
    @GetMapping("/{orderId}/compensation")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun get(
        actor: OperatorActor,
        @PathVariable orderId: UUID,
        @RequestHeader("X-Access-Reason") @Size(min = 1, max = 200) accessReason: String,
    ): OperatorCompensationView =
        query.read(
            ReadOperatorCompensationCommand(
                actorId = actorId(actor),
                orderId = orderId,
                accessReason = accessReason,
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

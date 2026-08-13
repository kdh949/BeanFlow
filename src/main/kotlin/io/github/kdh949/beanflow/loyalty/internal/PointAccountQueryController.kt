package io.github.kdh949.beanflow.loyalty.internal

import com.fasterxml.jackson.annotation.JsonInclude
import io.github.kdh949.beanflow.loyalty.api.ListPointTransactionsCommand
import io.github.kdh949.beanflow.loyalty.api.PointAccountQueryOperations
import io.github.kdh949.beanflow.loyalty.api.PointAccountReadActor
import io.github.kdh949.beanflow.loyalty.api.PointAccountReadActorType
import io.github.kdh949.beanflow.loyalty.api.PointTransactionPage
import io.github.kdh949.beanflow.loyalty.api.ReadPointAccountCommand
import io.github.kdh949.beanflow.shared.api.CustomerActor
import io.github.kdh949.beanflow.shared.api.OperatorActor
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class PointAccountPageInfoResponse(
    val nextCursor: String?,
)

internal data class PointTransactionPageResponse(
    val items: List<io.github.kdh949.beanflow.loyalty.api.PointTransactionView>,
    val page: PointAccountPageInfoResponse,
)

@Validated
@RestController
@RequestMapping("/api/v1/point-accounts")
internal class PointAccountQueryController(
    private val queries: PointAccountQueryOperations,
    private val clock: Clock,
) {
    @GetMapping("/{accountId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun get(
        actor: CustomerActor,
        @PathVariable accountId: UUID,
    ) = queries.get(
        ReadPointAccountCommand(
            actor = PointAccountReadActor(actor.actorId, PointAccountReadActorType.CUSTOMER),
            accountId = accountId,
            accessReason = null,
            now = clock.instant(),
        ),
    )

    @GetMapping("/{accountId}/transactions")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun transactions(
        actor: CustomerActor,
        @PathVariable accountId: UUID,
        @RequestParam(required = false) @Size(max = 2048) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): PointTransactionPageResponse =
        queries
            .listTransactions(
                ListPointTransactionsCommand(
                    actor = PointAccountReadActor(actor.actorId, PointAccountReadActorType.CUSTOMER),
                    accountId = accountId,
                    accessReason = null,
                    cursor = cursor,
                    limit = limit,
                    now = clock.instant(),
                ),
            ).toResponse()
}

@Validated
@RestController
@RequestMapping("/api/v1/operations/point-accounts")
internal class OperationsPointAccountQueryController(
    private val queries: PointAccountQueryOperations,
    private val clock: Clock,
) {
    @GetMapping("/{accountId}")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun get(
        actor: OperatorActor,
        @PathVariable accountId: UUID,
        @RequestHeader("X-Access-Reason") @NotBlank @Size(max = 500) accessReason: String,
    ) = queries.get(
        ReadPointAccountCommand(
            actor = PointAccountReadActor(actor.actorId, PointAccountReadActorType.PLATFORM_OPERATOR),
            accountId = accountId,
            accessReason = accessReason,
            now = clock.instant(),
        ),
    )

    @GetMapping("/{accountId}/transactions")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun transactions(
        actor: OperatorActor,
        @PathVariable accountId: UUID,
        @RequestHeader("X-Access-Reason") @NotBlank @Size(max = 500) accessReason: String,
        @RequestParam(required = false) @Size(max = 2048) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): PointTransactionPageResponse =
        queries
            .listTransactions(
                ListPointTransactionsCommand(
                    actor = PointAccountReadActor(actor.actorId, PointAccountReadActorType.PLATFORM_OPERATOR),
                    accountId = accountId,
                    accessReason = accessReason,
                    cursor = cursor,
                    limit = limit,
                    now = clock.instant(),
                ),
            ).toResponse()
}

private fun PointTransactionPage.toResponse() = PointTransactionPageResponse(items, PointAccountPageInfoResponse(nextCursor))

package io.github.kdh949.beanflow.loyalty.internal

import com.fasterxml.jackson.annotation.JsonInclude
import io.github.kdh949.beanflow.loyalty.api.ListPointTransactionsCommand
import io.github.kdh949.beanflow.loyalty.api.PointAccountQueryOperations
import io.github.kdh949.beanflow.loyalty.api.PointAccountReadActor
import io.github.kdh949.beanflow.loyalty.api.PointAccountReadActorType
import io.github.kdh949.beanflow.loyalty.api.PointTransactionPage
import io.github.kdh949.beanflow.loyalty.api.ReadPointAccountCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import jakarta.validation.constraints.Size
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
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
    fun get(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable accountId: UUID,
        @RequestHeader(value = "X-Access-Reason", required = false) accessReason: String?,
    ) = queries.get(
        ReadPointAccountCommand(
            actor = actor(jwt),
            accountId = accountId,
            accessReason = accessReason,
            now = clock.instant(),
        ),
    )

    @GetMapping("/{accountId}/transactions")
    fun transactions(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable accountId: UUID,
        @RequestHeader(value = "X-Access-Reason", required = false) accessReason: String?,
        @RequestParam(required = false) @Size(max = 2048) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): PointTransactionPageResponse =
        queries
            .listTransactions(
                ListPointTransactionsCommand(
                    actor = actor(jwt),
                    accountId = accountId,
                    accessReason = accessReason,
                    cursor = cursor,
                    limit = limit,
                    now = clock.instant(),
                ),
            ).toResponse()

    private fun PointTransactionPage.toResponse() = PointTransactionPageResponse(items, PointAccountPageInfoResponse(nextCursor))

    private fun actor(jwt: Jwt): PointAccountReadActor {
        val actorId =
            try {
                UUID.fromString(jwt.subject)
            } catch (_: RuntimeException) {
                throw DomainFailure(FailureCode.ACCESS_DENIED, "Authenticated subject is not a valid point account actor ID")
            }
        val roles = jwt.getClaimAsStringList("roles").orEmpty()
        return when {
            "PLATFORM_OPERATOR" in roles -> PointAccountReadActor(actorId, PointAccountReadActorType.PLATFORM_OPERATOR)
            "CUSTOMER" in roles -> PointAccountReadActor(actorId, PointAccountReadActorType.CUSTOMER)
            else -> throw DomainFailure(FailureCode.ACCESS_DENIED, "Customer or platform operator role is required")
        }
    }
}

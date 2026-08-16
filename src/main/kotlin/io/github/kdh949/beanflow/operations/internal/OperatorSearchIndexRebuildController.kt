package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OperatorActor
import io.github.kdh949.beanflow.shared.api.StoreSearchIndexRebuildResult
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

internal data class SearchIndexRebuildRequest(
    @field:Size(min = 1, max = 200)
    val reason: String,
)

internal data class SearchIndexRebuildResponse(
    val indexedStoreCount: Int,
    val skippedStoreCount: Int,
    val failedStoreIds: List<UUID>,
    val complete: Boolean,
)

@Validated
@RestController
@RequestMapping("/api/v1/operations/search-index")
internal class OperatorSearchIndexRebuildController(
    private val service: OperatorSearchIndexRebuildService,
    private val clock: Clock,
) {
    @PostMapping("/rebuild")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun rebuild(
        actor: OperatorActor,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: SearchIndexRebuildRequest,
    ): SearchIndexRebuildResponse =
        service
            .rebuild(
                OperatorSearchIndexRebuildCommand(
                    actorId = actorId(actor),
                    idempotencyKey = idempotencyKey,
                    reason = request.reason,
                    now = clock.instant(),
                ),
            ).toResponse()

    private fun actorId(actor: OperatorActor): UUID =
        try {
            actor.actorId
        } catch (_: IllegalArgumentException) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Authenticated subject is not a valid operator ID")
        }
}

/**
 * `targetStoreCount` stays internal. The published response reports what the pass did, and
 * `complete` carries the snapshot-scoped meaning of [StoreSearchIndexRebuildResult.completeSnapshot].
 */
private fun StoreSearchIndexRebuildResult.toResponse() =
    SearchIndexRebuildResponse(
        indexedStoreCount = indexedStoreCount,
        skippedStoreCount = skippedStoreCount,
        failedStoreIds = failedStoreIds,
        complete = completeSnapshot,
    )

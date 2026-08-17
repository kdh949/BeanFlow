package io.github.kdh949.beanflow.dispute.internal

import com.fasterxml.jackson.annotation.JsonInclude
import io.github.kdh949.beanflow.identity.api.StoreAccessOperations
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.HexFormat
import java.util.UUID

internal data class ListStoreDisputesQuery(
    val actorId: UUID,
    val storeId: UUID,
    val state: SettlementDisputeState?,
    val cursor: String?,
    val limit: Int?,
)

/**
 * What the store owner needs to track their own filing. Internal reprocessing
 * cases, worker failures and actor credentials stay out of this projection.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class SettlementDisputeSummaryResponse(
    val disputeId: UUID,
    val settlementItemId: UUID,
    val state: SettlementDisputeState,
    val expectedAdjustmentKrw: Long,
    val heldAmountKrw: Long,
    val filedAt: Instant,
    val decidedAt: Instant?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class SettlementDisputePageInfoResponse(
    val nextCursor: String?,
)

internal data class SettlementDisputePageResponse(
    val items: List<SettlementDisputeSummaryResponse>,
    val page: SettlementDisputePageInfoResponse,
)

@Service
internal class SettlementDisputeQueryService(
    private val storeAccess: StoreAccessOperations,
    private val repository: SettlementDisputeQueryRepository,
    private val signedCursorCodec: SignedCursorCodec,
    private val clock: Clock,
    private val metrics: SettlementDisputeQueryMetrics,
) {
    @Transactional(readOnly = true)
    fun list(query: ListStoreDisputesQuery): SettlementDisputePageResponse =
        try {
            val limit = normalizeLimit(query.limit)
            requireActiveOwner(query.actorId, query.storeId)
            val scope = cursorScope(query.storeId, query.state)
            val after = query.cursor?.let { signedCursorCodec.verify(it, scope).sort }
            val fetched = repository.findPage(query.storeId, query.state, after, limit + 1)
            val items = fetched.take(limit)
            val nextCursor =
                if (fetched.size > limit) {
                    val last = items.last()
                    signedCursorCodec.issue(
                        scope,
                        SettlementDisputeSort(last.filedAt, last.disputeId),
                        clock.instant().plus(CURSOR_TTL),
                    )
                } else {
                    null
                }
            metrics.record(SettlementDisputeQueryOutcome.SUCCEEDED, items.size)
            SettlementDisputePageResponse(
                items = items.map { it.toResponse() },
                page = SettlementDisputePageInfoResponse(nextCursor),
            )
        } catch (failure: DomainFailure) {
            metrics.record(failure.toOutcome())
            throw failure
        } catch (_: DataAccessException) {
            metrics.record(SettlementDisputeQueryOutcome.DEPENDENCY_UNAVAILABLE)
            // A query failure is retryable, never an owner with no disputes.
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Settlement dispute query persistence is unavailable",
            )
        }

    private fun requireActiveOwner(
        actorId: UUID,
        storeId: UUID,
    ) {
        val actor = storeAccess.requireStoreAccess(actorId, storeId, setOf(StoreActorRole.OWNER))
        if (actor.role != StoreActorRole.OWNER) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Active owner membership is required")
        }
    }

    /** The signature binds the page to its store and state filter. */
    private fun cursorScope(
        storeId: UUID,
        state: SettlementDisputeState?,
    ): SignedCursorScope<SettlementDisputeSort> =
        SignedCursorScope(
            endpoint = CURSOR_ENDPOINT,
            filterHash = sha256("$CURSOR_ENDPOINT|storeId=$storeId|state=${state?.name ?: "ALL"}"),
            sortAdapter = SORT_ADAPTER,
        )

    private fun normalizeLimit(limit: Int?): Int {
        val normalized = limit ?: DEFAULT_LIMIT
        if (normalized !in 1..MAX_LIMIT) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Settlement dispute limit must be between 1 and 100")
        }
        return normalized
    }

    private fun SettlementDisputeProjection.toResponse() =
        SettlementDisputeSummaryResponse(
            disputeId = disputeId,
            settlementItemId = settlementItemId,
            state = state,
            expectedAdjustmentKrw = expectedAdjustmentKrw,
            heldAmountKrw = heldAmountKrw,
            filedAt = filedAt,
            decidedAt = decidedAt,
        )

    private fun DomainFailure.toOutcome(): SettlementDisputeQueryOutcome =
        when (code) {
            FailureCode.INVALID_REQUEST -> SettlementDisputeQueryOutcome.INVALID_INPUT
            FailureCode.ACCESS_DENIED -> SettlementDisputeQueryOutcome.DENIED
            FailureCode.RESOURCE_NOT_FOUND -> SettlementDisputeQueryOutcome.NOT_FOUND
            else -> SettlementDisputeQueryOutcome.DEPENDENCY_UNAVAILABLE
        }

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
        )

    private companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
        const val CURSOR_ENDPOINT = "dispute/store-disputes"
        val CURSOR_TTL: Duration = Duration.ofMinutes(15)
        val SORT_ADAPTER =
            object : CursorSortAdapter<SettlementDisputeSort> {
                override fun encode(sort: SettlementDisputeSort): List<String> = listOf(sort.filedAt.toString(), sort.disputeId.toString())

                override fun decode(values: List<String>): SettlementDisputeSort? {
                    if (values.size != 2) return null
                    return try {
                        val filedAt = Instant.parse(values[0])
                        val disputeId = UUID.fromString(values[1])
                        if (filedAt.toString() != values[0] || disputeId.toString() != values[1]) {
                            null
                        } else {
                            SettlementDisputeSort(filedAt, disputeId)
                        }
                    } catch (_: DateTimeParseException) {
                        null
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
            }
    }
}

internal enum class SettlementDisputeQueryOutcome {
    SUCCEEDED,
    INVALID_INPUT,
    DENIED,
    NOT_FOUND,
    DEPENDENCY_UNAVAILABLE,
}

@Component
internal class SettlementDisputeQueryMetrics(
    private val meterRegistry: MeterRegistry,
) {
    fun record(
        outcome: SettlementDisputeQueryOutcome,
        pageSize: Int? = null,
    ) {
        meterRegistry.counter("beanflow.dispute.store_query.count", "outcome", outcome.name).increment()
        pageSize?.let {
            meterRegistry.summary("beanflow.dispute.store_query.page_size").record(it.toDouble())
        }
    }
}

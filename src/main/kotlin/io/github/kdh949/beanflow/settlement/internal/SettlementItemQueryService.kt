package io.github.kdh949.beanflow.settlement.internal

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

internal data class ListSettlementItemsQuery(
    val actorId: UUID,
    val actorRoles: Set<StoreActorRole>,
    val storeId: UUID,
    val settlementBatchId: UUID,
    val cursor: String?,
    val limit: Int?,
)

internal data class SettlementItemPageResponse(
    val items: List<SettlementItemResponse>,
    val page: SettlementPageInfoResponse,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class SettlementPageInfoResponse(
    val nextCursor: String?,
)

internal data class SettlementItemResponse(
    val settlementItemId: UUID,
    val settlementBatchId: UUID,
    val orderId: UUID,
    val completedAt: Instant,
    val grossPaidKrw: Long,
    val feeKrw: Long,
    val benefitCostKrw: Long,
    val netSettlementKrw: Long,
    val currency: String,
)

@Service
internal class SettlementItemQueryService(
    private val storeAccess: StoreAccessOperations,
    private val repository: SettlementItemQueryRepository,
    private val signedCursorCodec: SignedCursorCodec,
    private val clock: Clock,
    private val metrics: SettlementItemQueryMetrics,
) {
    @Transactional(readOnly = true)
    fun list(query: ListSettlementItemsQuery): SettlementItemPageResponse =
        try {
            val limit = normalizeLimit(query.limit)
            storeAccess.requireStoreAccess(query.actorId, query.storeId, query.actorRoles)
            val batchStoreId = repository.findBatchStoreId(query.settlementBatchId) ?: notFound()
            if (batchStoreId != query.storeId) notFound()
            val scope = cursorScope(query.storeId, query.settlementBatchId)
            val after = query.cursor?.let { signedCursorCodec.verify(it, scope).sort }
            val fetched = repository.findPage(query.settlementBatchId, after, limit + 1)
            val items = fetched.take(limit)
            val nextCursor =
                if (fetched.size > limit) {
                    val last = items.last()
                    signedCursorCodec.issue(
                        scope,
                        SettlementItemSort(last.completedAt, last.settlementItemId),
                        clock.instant().plus(CURSOR_TTL),
                    )
                } else {
                    null
                }
            metrics.record(SettlementItemQueryOutcome.SUCCEEDED, items.size)
            SettlementItemPageResponse(
                items = items.map { it.toResponse() },
                page = SettlementPageInfoResponse(nextCursor),
            )
        } catch (failure: DomainFailure) {
            metrics.record(failure.toOutcome())
            throw failure
        } catch (_: DataAccessException) {
            metrics.record(SettlementItemQueryOutcome.DEPENDENCY_UNAVAILABLE)
            dependency("Settlement item query persistence is unavailable")
        }

    private fun cursorScope(
        storeId: UUID,
        settlementBatchId: UUID,
    ): SignedCursorScope<SettlementItemSort> =
        SignedCursorScope(
            endpoint = CURSOR_ENDPOINT,
            filterHash = sha256("$CURSOR_ENDPOINT|storeId=$storeId|settlementBatchId=$settlementBatchId"),
            sortAdapter = SORT_ADAPTER,
        )

    private fun normalizeLimit(limit: Int?): Int {
        val normalized = limit ?: DEFAULT_LIMIT
        if (normalized !in 1..MAX_LIMIT) invalid("Settlement item limit must be between 1 and 100")
        return normalized
    }

    private fun SettlementItemProjection.toResponse() =
        SettlementItemResponse(
            settlementItemId,
            settlementBatchId,
            orderId,
            completedAt,
            grossPaidKrw,
            feeKrw,
            benefitCostKrw,
            netSettlementKrw,
            currency,
        )

    private fun DomainFailure.toOutcome(): SettlementItemQueryOutcome =
        when (code) {
            FailureCode.INVALID_REQUEST -> SettlementItemQueryOutcome.INVALID_INPUT
            FailureCode.ACCESS_DENIED -> SettlementItemQueryOutcome.DENIED
            FailureCode.RESOURCE_NOT_FOUND -> SettlementItemQueryOutcome.NOT_FOUND
            else -> SettlementItemQueryOutcome.DEPENDENCY_UNAVAILABLE
        }

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
        )

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    private fun notFound(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Settlement batch was not found")

    private fun dependency(message: String): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)

    private companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
        const val CURSOR_ENDPOINT = "settlement/store-batch-items"
        val CURSOR_TTL: Duration = Duration.ofMinutes(15)
        val SORT_ADAPTER =
            object : CursorSortAdapter<SettlementItemSort> {
                override fun encode(sort: SettlementItemSort): List<String> =
                    listOf(sort.completedAt.toString(), sort.settlementItemId.toString())

                override fun decode(values: List<String>): SettlementItemSort? {
                    if (values.size != 2) return null
                    return try {
                        val completedAt = Instant.parse(values[0])
                        val itemId = UUID.fromString(values[1])
                        if (completedAt.toString() != values[0] || itemId.toString() != values[1]) {
                            null
                        } else {
                            SettlementItemSort(completedAt, itemId)
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

internal enum class SettlementItemQueryOutcome {
    SUCCEEDED,
    INVALID_INPUT,
    DENIED,
    NOT_FOUND,
    DEPENDENCY_UNAVAILABLE,
}

@Component
internal class SettlementItemQueryMetrics(
    private val meterRegistry: MeterRegistry,
) {
    fun record(
        outcome: SettlementItemQueryOutcome,
        pageSize: Int? = null,
    ) {
        meterRegistry
            .counter(
                "beanflow.settlement.item_query.count",
                "outcome",
                outcome.name,
            ).increment()
        pageSize?.let {
            meterRegistry.summary("beanflow.settlement.item_query.page_size").record(it.toDouble())
        }
    }
}

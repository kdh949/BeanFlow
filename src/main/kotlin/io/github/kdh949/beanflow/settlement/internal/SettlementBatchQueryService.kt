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
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.HexFormat
import java.util.UUID

internal data class ListSettlementBatchesQuery(
    val actorId: UUID,
    val actorRoles: Set<StoreActorRole>,
    val storeId: UUID,
    val cursor: String?,
    val limit: Int?,
)

internal data class SettlementBatchPageResponse(
    val items: List<SettlementBatchResponse>,
    val page: SettlementBatchPageInfoResponse,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class SettlementBatchPageInfoResponse(
    val nextCursor: String?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class SettlementBatchResponse(
    val settlementBatchId: UUID,
    val storeId: UUID,
    val settlementDate: LocalDate,
    val state: SettlementBatchState,
    val grossPaidKrw: Long,
    val feeKrw: Long,
    val benefitCostKrw: Long,
    val adjustmentKrw: Long,
    val netSettlementKrw: Long,
    val currency: String,
    val confirmedAt: Instant?,
)

@Service
internal class SettlementBatchQueryService(
    private val storeAccess: StoreAccessOperations,
    private val repository: SettlementBatchQueryRepository,
    private val signedCursorCodec: SignedCursorCodec,
    private val clock: Clock,
    private val metrics: SettlementBatchQueryMetrics,
) {
    @Transactional(readOnly = true)
    fun list(query: ListSettlementBatchesQuery): SettlementBatchPageResponse =
        try {
            val limit = normalizeLimit(query.limit)
            storeAccess.requireStoreAccess(query.actorId, query.storeId, query.actorRoles)
            val scope = cursorScope(query.storeId)
            val after = query.cursor?.let { signedCursorCodec.verify(it, scope).sort }
            val fetched = repository.findPage(query.storeId, after, limit + 1)
            val items = fetched.take(limit)
            val nextCursor =
                if (fetched.size > limit) {
                    val last = items.last()
                    signedCursorCodec.issue(
                        scope,
                        SettlementBatchSort(last.settlementDate, last.settlementBatchId),
                        clock.instant().plus(CURSOR_TTL),
                    )
                } else {
                    null
                }
            metrics.record("SUCCEEDED", items.size)
            SettlementBatchPageResponse(
                items = items.map { it.toResponse() },
                page = SettlementBatchPageInfoResponse(nextCursor),
            )
        } catch (failure: DomainFailure) {
            metrics.record(failure.code.name)
            throw failure
        } catch (failure: DataAccessException) {
            metrics.record("DEPENDENCY_UNAVAILABLE")
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "SettlementBatch query persistence is unavailable",
            ).also { it.initCause(failure) }
        }

    private fun cursorScope(storeId: UUID): SignedCursorScope<SettlementBatchSort> =
        SignedCursorScope(
            endpoint = CURSOR_ENDPOINT,
            filterHash = sha256("$CURSOR_ENDPOINT|storeId=$storeId"),
            sortAdapter = SORT_ADAPTER,
        )

    private fun normalizeLimit(limit: Int?): Int {
        val normalized = limit ?: DEFAULT_LIMIT
        if (normalized !in 1..MAX_LIMIT) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "SettlementBatch limit must be between 1 and 100")
        }
        return normalized
    }

    private fun SettlementBatchProjection.toResponse(): SettlementBatchResponse =
        SettlementBatchResponse(
            settlementBatchId = settlementBatchId,
            storeId = storeId,
            settlementDate = settlementDate,
            state = state,
            grossPaidKrw = grossPaidKrw,
            feeKrw = feeKrw,
            benefitCostKrw = benefitCostKrw,
            adjustmentKrw = adjustmentKrw,
            netSettlementKrw = netSettlementKrw,
            currency = "KRW",
            confirmedAt = confirmedAt,
        )

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
        )

    private companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
        const val CURSOR_ENDPOINT = "settlement/store-batches"
        val CURSOR_TTL: Duration = Duration.ofMinutes(15)
        val SORT_ADAPTER =
            object : CursorSortAdapter<SettlementBatchSort> {
                override fun encode(sort: SettlementBatchSort): List<String> =
                    listOf(sort.settlementDate.toString(), sort.settlementBatchId.toString())

                override fun decode(values: List<String>): SettlementBatchSort? {
                    if (values.size != 2) return null
                    return try {
                        val date = LocalDate.parse(values[0])
                        val batchId = UUID.fromString(values[1])
                        if (date.toString() != values[0] || batchId.toString() != values[1]) {
                            null
                        } else {
                            SettlementBatchSort(date, batchId)
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

@Component
internal class SettlementBatchQueryMetrics(
    private val meterRegistry: MeterRegistry,
) {
    fun record(
        outcome: String,
        pageSize: Int? = null,
    ) {
        meterRegistry.counter("beanflow.settlement.batch_query.count", "outcome", outcome).increment()
        pageSize?.let { meterRegistry.summary("beanflow.settlement.batch_query.page_size").record(it.toDouble()) }
    }
}

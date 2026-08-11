package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.SUPPORT_TIMELINE_COMPARATOR
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineFact
import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineQuery
import io.github.kdh949.beanflow.shared.api.SupportTimelineBoundary
import io.github.kdh949.beanflow.shared.api.SupportTimelineSource
import io.github.kdh949.beanflow.shared.api.SupportTimelineState
import io.github.kdh949.beanflow.shared.api.SupportTimelineType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

internal data class SupportTimelineItemResource(
    val itemId: UUID,
    val source: SupportTimelineSource,
    val type: SupportTimelineType,
    val state: SupportTimelineState,
    val summary: String,
    val amountKrw: Long?,
    val occurredAt: Instant,
)

internal data class SupportTimelinePageResource(
    val items: List<SupportTimelineItemResource>,
    val nextCursor: String?,
)

internal data class AuthorizedTimelineScope(
    val caseId: UUID,
    val orderIds: Set<UUID>,
)

@Service
internal class SupportTimelineAuthorization(
    private val permissions: OperatorPermissionAuthorization,
    private val cases: SupportCaseJpaRepository,
    private val queries: SupportTimelineQueryRepository,
) {
    @Transactional
    fun authorizeCase(
        actorId: UUID,
        caseId: UUID,
    ): AuthorizedTimelineScope {
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
        requireCase(caseId)
        val orderIds = queries.findActiveOrderIds(caseId, SupportOwnerTimelineQuery.MAX_ORDER_IDS + 1)
        if (orderIds.size > SupportOwnerTimelineQuery.MAX_ORDER_IDS) {
            throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, "SupportCase has too many active Order links")
        }
        return AuthorizedTimelineScope(caseId, orderIds.toSet())
    }

    @Transactional
    fun authorizeOrder(
        actorId: UUID,
        caseId: UUID,
        orderId: UUID,
    ): AuthorizedTimelineScope {
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_ORDER_READ)
        requireCase(caseId)
        if (!queries.hasActiveOrderLink(caseId, orderId)) denied()
        return AuthorizedTimelineScope(caseId, setOf(orderId))
    }

    @Transactional
    fun recheckCase(
        actorId: UUID,
        expected: AuthorizedTimelineScope,
    ) {
        val current = authorizeCase(actorId, expected.caseId)
        if (current.orderIds != expected.orderIds) denied()
    }

    @Transactional
    fun recheckOrder(
        actorId: UUID,
        expected: AuthorizedTimelineScope,
    ) {
        authorizeOrder(actorId, expected.caseId, expected.orderIds.single())
    }

    private fun requireCase(caseId: UUID) {
        if (cases.findLockedById(caseId) == null) {
            throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "SupportCase was not found")
        }
    }

    private fun denied(): Nothing = throw DomainFailure(FailureCode.ACCESS_DENIED, "Linked SupportCase scope is required")
}

@Service
internal class SupportTimelineApplicationService(
    private val authorization: SupportTimelineAuthorization,
    private val localQueries: SupportTimelineQueryRepository,
    private val ownerQueries: SupportTimelineOwnerQueries,
    private val cursors: SignedCursorCodec,
    private val clock: Clock,
) {
    fun listCase(
        actorId: UUID,
        caseId: UUID,
        sources: Set<SupportTimelineSource>,
        types: Set<SupportTimelineType>,
        cursor: String?,
        limit: Int?,
    ): SupportTimelinePageResource {
        val scope = authorization.authorizeCase(actorId, caseId)
        val pageLimit = normalizedLimit(limit)
        val cursorScope = caseCursorScope(caseId, sources, types)
        val after = cursor?.let { cursors.verify(it, cursorScope).sort }
        val fetched =
            buildList {
                if (sources.isEmpty() || SupportTimelineSource.SUPPORT in sources) {
                    addAll(localQueries.findLocalFacts(caseId, after, types, pageLimit + 1))
                }
                if (scope.orderIds.isNotEmpty()) {
                    addAll(
                        ownerQueries.findFacts(
                            SupportOwnerTimelineQuery(scope.orderIds, after, pageLimit + 1, types),
                            sources,
                        ),
                    )
                }
            }.sortedWith(SUPPORT_TIMELINE_COMPARATOR)
                .take(pageLimit + 1)
        authorization.recheckCase(actorId, scope)
        return page(fetched, pageLimit, cursorScope)
    }

    fun listOrder(
        actorId: UUID,
        caseId: UUID,
        orderId: UUID,
        sources: Set<SupportTimelineSource>,
        types: Set<SupportTimelineType>,
        cursor: String?,
        limit: Int?,
    ): SupportTimelinePageResource {
        if (SupportTimelineSource.SUPPORT in sources) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "SUPPORT is not an Order timeline source")
        }
        val scope = authorization.authorizeOrder(actorId, caseId, orderId)
        val pageLimit = normalizedLimit(limit)
        val cursorScope = orderCursorScope(caseId, orderId, sources, types)
        val after = cursor?.let { cursors.verify(it, cursorScope).sort }
        val fetched =
            ownerQueries.findFacts(
                SupportOwnerTimelineQuery(setOf(orderId), after, pageLimit + 1, types),
                if (sources.isEmpty()) ORDER_SOURCES else sources,
            )
        authorization.recheckOrder(actorId, scope)
        return page(fetched, pageLimit, cursorScope)
    }

    private fun page(
        fetched: List<SupportOwnerTimelineFact>,
        limit: Int,
        cursorScope: SignedCursorScope<SupportTimelineBoundary>,
    ): SupportTimelinePageResource {
        val items = fetched.take(limit)
        val nextCursor =
            if (fetched.size > limit) {
                val last = items.last()
                cursors.issue(
                    cursorScope,
                    SupportTimelineBoundary(last.occurredAt, last.source, last.itemId),
                    clock.instant().plus(CURSOR_TTL),
                )
            } else {
                null
            }
        return SupportTimelinePageResource(items.map(::resource), nextCursor)
    }

    private fun resource(fact: SupportOwnerTimelineFact): SupportTimelineItemResource =
        SupportTimelineItemResource(
            itemId = fact.itemId,
            source = fact.source,
            type = fact.type,
            state = fact.state,
            summary = "${fact.type.name}:${fact.state.name}",
            amountKrw = fact.amountKrw,
            occurredAt = fact.occurredAt,
        )

    private fun normalizedLimit(limit: Int?): Int {
        val normalized = limit ?: DEFAULT_LIMIT
        if (normalized !in 1..MAX_LIMIT) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Timeline limit must be between 1 and 100")
        }
        return normalized
    }

    private fun caseCursorScope(
        caseId: UUID,
        sources: Set<SupportTimelineSource>,
        types: Set<SupportTimelineType>,
    ): SignedCursorScope<SupportTimelineBoundary> =
        SignedCursorScope(
            endpoint = CASE_ENDPOINT,
            filterHash = hash(canonicalFilters(CASE_ENDPOINT, caseId, null, sources, types)),
            sortAdapter = TIMELINE_SORT_ADAPTER,
        )

    private fun orderCursorScope(
        caseId: UUID,
        orderId: UUID,
        sources: Set<SupportTimelineSource>,
        types: Set<SupportTimelineType>,
    ): SignedCursorScope<SupportTimelineBoundary> =
        SignedCursorScope(
            endpoint = ORDER_ENDPOINT,
            filterHash = hash(canonicalFilters(ORDER_ENDPOINT, caseId, orderId, sources, types)),
            sortAdapter = TIMELINE_SORT_ADAPTER,
        )

    private fun canonicalFilters(
        endpoint: String,
        caseId: UUID,
        orderId: UUID?,
        sources: Set<SupportTimelineSource>,
        types: Set<SupportTimelineType>,
    ): String {
        val orderProperty = orderId?.let { ",\"orderId\":\"$it\"" }.orEmpty()
        val sourceArray = sources.map { it.name }.sorted().joinToString(",") { "\"$it\"" }
        val typeArray = types.map { it.name }.sorted().joinToString(",") { "\"$it\"" }
        return "{\"endpoint\":\"$endpoint\",\"caseId\":\"$caseId\"$orderProperty," +
            "\"sources\":[$sourceArray],\"types\":[$typeArray]}"
    }

    private fun hash(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
        const val CASE_ENDPOINT = "support-case-timeline"
        const val ORDER_ENDPOINT = "support-order-timeline"
        val CURSOR_TTL: Duration = Duration.ofMinutes(15)
        val ORDER_SOURCES = SupportTimelineSource.entries.toSet() - SupportTimelineSource.SUPPORT
        val TIMELINE_SORT_ADAPTER =
            object : CursorSortAdapter<SupportTimelineBoundary> {
                override fun encode(sort: SupportTimelineBoundary): List<String> =
                    listOf(
                        sort.occurredAt.toString(),
                        sort.source.rank
                            .toString()
                            .padStart(2, '0'),
                        sort.itemId.toString(),
                    )

                override fun decode(values: List<String>): SupportTimelineBoundary? {
                    if (values.size != 3 || !values[1].matches(Regex("^[0-9]{2}$"))) return null
                    return try {
                        val occurredAt = Instant.parse(values[0])
                        val rank = values[1].toInt()
                        val source = SupportTimelineSource.entries.singleOrNull { it.rank == rank } ?: return null
                        val itemId = UUID.fromString(values[2])
                        if (itemId.toString() != values[2]) return null
                        SupportTimelineBoundary(occurredAt, source, itemId)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
            }
    }
}

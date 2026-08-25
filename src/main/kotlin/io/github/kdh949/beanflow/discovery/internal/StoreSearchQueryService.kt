package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.discovery.api.SearchStoresCommand
import io.github.kdh949.beanflow.discovery.api.StoreSearchItemView
import io.github.kdh949.beanflow.discovery.api.StoreSearchMenuView
import io.github.kdh949.beanflow.discovery.api.StoreSearchPage
import io.github.kdh949.beanflow.discovery.api.StoreSearchQueryOperations
import io.github.kdh949.beanflow.fulfillment.api.PickupAvailabilityQueryOperations
import io.github.kdh949.beanflow.fulfillment.api.PickupAvailabilityView
import io.github.kdh949.beanflow.merchant.api.StoreDiscoveryDisplayProjection
import io.github.kdh949.beanflow.merchant.api.StoreDiscoveryQueryOperations
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.SearchTextNormalizer
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import io.github.kdh949.beanflow.shared.api.StoreSearchTermKind
import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.PersistenceException
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionException
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class PreparedStoreSearch(
    val limit: Int,
    val tokens: List<String>,
    val query: StoreSearchCandidateQuery,
    val pickupAvailableOnly: Boolean,
    val now: Instant,
    val cursorScope: SignedCursorScope<StoreSearchSortTuple>,
    val cursorExpiresAt: Instant,
    val distanceAvailable: Boolean,
)

@Service
internal class StoreSearchQueryService(
    private val validation: StoreSearchQueryValidation,
    private val reads: StoreSearchReadTransaction,
    private val metrics: StoreSearchQueryMetrics,
) : StoreSearchQueryOperations {
    override fun search(command: SearchStoresCommand): StoreSearchPage {
        val startedAt = System.nanoTime()
        val prepared =
            try {
                validation.prepare(command)
            } catch (failure: DomainFailure) {
                // sort 자체가 잘못된 요청도 tag가 필요하므로 기본값으로 기록한다. 태그 어휘는
                // 닫혀 있고 검색어·좌표는 어느 태그에도 들어가지 않는다.
                metrics.record(failure.toOutcome(), StoreSearchSort.RELEVANCE, startedAt)
                throw failure
            }
        val sort = prepared.query.sort
        return try {
            reads.search(prepared).also { page ->
                metrics.record(
                    StoreSearchQueryOutcome.SUCCEEDED,
                    sort,
                    startedAt,
                    page.items.size,
                    prepared.tokens.size,
                    exhausted = page.nextCursor == null,
                )
            }
        } catch (failure: DomainFailure) {
            metrics.record(failure.toOutcome(), sort, startedAt)
            throw failure
        } catch (failure: TransactionException) {
            metrics.record(StoreSearchQueryOutcome.DEPENDENCY_UNAVAILABLE, sort, startedAt)
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Store search transaction could not complete",
            ).also { it.initCause(failure) }
        }
    }
}

/**
 * One read-only transaction around the index.
 *
 * A missing `pg_trgm` extension or an unreadable index table becomes an explicit 503. It is never
 * turned into an empty successful page or a sequential fallback scan: "검색은 되는데 결과가 늘
 * 0건"인 상태와 장애를 구분하지 못하게 되기 때문이다.
 */
@Component
internal class StoreSearchReadTransaction(
    private val repository: StoreSearchCandidateRepository,
    private val availability: PickupAvailabilityQueryOperations,
    private val stores: StoreDiscoveryQueryOperations,
    private val signedCursorCodec: SignedCursorCodec,
    private val imageViews: StorefrontImageViewResolver,
) {
    @Transactional(readOnly = true)
    fun search(prepared: PreparedStoreSearch): StoreSearchPage {
        val fetched =
            try {
                repository.pinSimilarityThreshold()
                repository.findCandidates(prepared.query)
            } catch (failure: DataAccessException) {
                indexUnavailable(failure)
            } catch (failure: PersistenceException) {
                indexUnavailable(failure)
            }
        // 검사 대상은 probe row를 뺀 앞 limit개다. 가용성도 후보 수와 무관하게 한 번만 묻는다.
        val examined = fetched.take(prepared.limit)
        val pickupWindows =
            availability.findEarliestAvailableSlots(
                examined.map(StoreSearchCandidate::storeId),
                prepared.now,
            )
        val scanned =
            scanCandidates(fetched, prepared.limit) { candidate ->
                !prepared.pickupAvailableOnly || candidate.pickupAvailable(pickupWindows)
            }
        val page = scanned.items
        val storeIds = page.map(StoreSearchCandidate::storeId)
        val displayByStoreId = customerDisplays(storeIds)
        val menus =
            try {
                repository
                    .findMatchedMenus(storeIds, prepared.tokens, MAX_MATCHED_MENUS)
                    .groupBy(StoreSearchMenuRow::storeId)
            } catch (failure: DataAccessException) {
                indexUnavailable(failure)
            }
        val displayTerms =
            try {
                repository.findDisplayTerms(storeIds).groupBy(StoreSearchTermText::storeId)
            } catch (failure: DataAccessException) {
                indexUnavailable(failure)
            }
        val nextCursor =
            scanned.boundary?.let { boundary ->
                signedCursorCodec.issue(
                    prepared.cursorScope,
                    StoreSearchSortTuple(boundary.relevanceRank, boundary.distanceMicrometers, boundary.storeId),
                    prepared.cursorExpiresAt,
                )
            }
        return StoreSearchPage(
            items =
                page.map { candidate ->
                    candidate.toView(
                        distanceAvailable = prepared.distanceAvailable,
                        pickupWindow = if (candidate.orderingAvailable) pickupWindows[candidate.storeId] else null,
                        customerDisplay = requireNotNull(displayByStoreId[candidate.storeId]).customerDisplay,
                        now = prepared.now,
                        menus = menus[candidate.storeId].orEmpty(),
                        terms = displayTerms[candidate.storeId].orEmpty(),
                        imageViews = imageViews,
                    )
                },
            nextCursor = nextCursor,
            distanceAvailable = prepared.distanceAvailable,
        )
    }

    private fun indexUnavailable(cause: Throwable): Nothing =
        throw DomainFailure(
            FailureCode.DEPENDENCY_UNAVAILABLE,
            "Store search index is unavailable",
        ).also { it.initCause(cause) }

    private fun customerDisplays(storeIds: List<UUID>): Map<UUID, StoreDiscoveryDisplayProjection> {
        val projections =
            try {
                stores.findVisibleStores(storeIds)
            } catch (failure: DataAccessException) {
                indexUnavailable(failure)
            } catch (failure: TransactionException) {
                indexUnavailable(failure)
            }
        val byStoreId = projections.associateBy(StoreDiscoveryDisplayProjection::storeId)
        if (!byStoreId.keys.containsAll(storeIds)) {
            indexUnavailable(IllegalStateException("Search candidate has no current Merchant display projection"))
        }
        return byStoreId
    }

    internal companion object {
        /** ADR-103 A5. */
        const val MAX_MATCHED_MENUS = 3
    }
}

/**
 * ADR-103: the public flag is the owner state **and** a reservable slot. A store that stopped
 * accepting orders is not "pickup available" merely because tomorrow's slot row still has seats.
 */
private fun StoreSearchCandidate.pickupAvailable(pickupWindows: Map<UUID, PickupAvailabilityView>): Boolean =
    orderingAvailable && storeId in pickupWindows

private fun StoreSearchCandidate.toView(
    distanceAvailable: Boolean,
    pickupWindow: PickupAvailabilityView?,
    customerDisplay: io.github.kdh949.beanflow.merchant.api.StoreCustomerDisplayProjection,
    now: Instant,
    menus: List<StoreSearchMenuRow>,
    terms: List<StoreSearchTermText>,
    imageViews: StorefrontImageViewResolver,
): StoreSearchItemView =
    StoreSearchItemView(
        storeId = storeId,
        name = name,
        brandName = terms.firstOrNull { it.kind == StoreSearchTermKind.BRAND_NAME }?.displayText,
        regionName = regionName(terms),
        matchReason = matchedKinds,
        // 좌표가 없으면 거리 항은 상수 0이므로 표시 거리로 내보내지 않는다.
        distanceMeters = if (distanceAvailable) distanceMicrometers / MICROMETERS_PER_METER else null,
        orderingAvailable = orderingAvailable,
        pickupAvailable = pickupWindow != null,
        nextPickupWindow = pickupWindow?.toCustomerView(),
        customerDisplay = customerDisplay.toCustomerView(now),
        matchedMenus = menus.map { StoreSearchMenuView(it.menuId, it.name) },
        image = imageViews.resolve(imageThumbnailKey),
    )

/** 상위 계층부터 이어 붙인다. 계층이 하나도 없는 매장은 지역명을 내보내지 않는다. */
private fun regionName(terms: List<StoreSearchTermText>): String? {
    val levels =
        REGION_LEVELS.mapNotNull { kind -> terms.firstOrNull { it.kind == kind }?.displayText }
    return levels.takeIf { it.isNotEmpty() }?.joinToString(" ")
}

private val REGION_LEVELS =
    listOf(
        StoreSearchTermKind.REGION_SIDO,
        StoreSearchTermKind.REGION_SIGUNGU,
        StoreSearchTermKind.REGION_EUPMYEONDONG,
        StoreSearchTermKind.REGION_RI,
    )

private const val MICROMETERS_PER_METER = 1_000_000L

/**
 * Validates and canonicalizes the public search contract before the index is touched.
 *
 * Failure messages never contain the search text, the coordinate or the cursor, so a rejected
 * request cannot leak either through the API error body, an application log or a trace.
 */
@Component
internal class StoreSearchQueryValidation(
    private val signedCursorCodec: SignedCursorCodec,
) {
    fun prepare(command: SearchStoresCommand): PreparedStoreSearch {
        val sort = sort(command.sort)
        val tokens = tokens(command.query)
        val limit = limit(command.limit)
        val pickupAvailableOnly = flag(command.pickupAvailable, "pickupAvailable")
        val openOnly = flag(command.openOnly, "openOnly")
        val cursor = cursor(command.cursor)
        val coordinates = coordinates(command.latitude, command.longitude)
        val radiusMeters = radiusMeters(command.radiusMeters, coordinates)
        if (sort == StoreSearchSort.DISTANCE && coordinates == null) {
            invalid("Distance sorting requires a latitude and longitude pair")
        }
        val scope =
            SignedCursorScope(
                endpoint = sort.cursorEndpoint,
                filterHash = filterHash(sort, tokens, pickupAvailableOnly, openOnly, coordinates, radiusMeters),
                sortAdapter = sort.sortAdapter,
            )
        val after = cursor?.let { signedCursorCodec.verify(it, scope).sort }
        return PreparedStoreSearch(
            limit = limit,
            tokens = tokens,
            query =
                StoreSearchCandidateQuery(
                    tokens = tokens,
                    sort = sort,
                    latitude = coordinates?.latitude,
                    longitude = coordinates?.longitude,
                    radiusMeters = radiusMeters,
                    openOnly = openOnly,
                    after = after,
                    // One extra row decides whether a next page exists without a second query.
                    limit = limit + 1,
                ),
            pickupAvailableOnly = pickupAvailableOnly,
            now = command.now,
            cursorScope = scope,
            cursorExpiresAt = command.now.plus(CURSOR_TTL),
            distanceAvailable = coordinates != null,
        )
    }

    private fun sort(raw: String?): StoreSearchSort {
        if (raw == null) return StoreSearchSort.RELEVANCE
        return when (raw) {
            "relevance" -> StoreSearchSort.RELEVANCE
            "distance" -> StoreSearchSort.DISTANCE
            else -> invalid("Sort must be relevance or distance")
        }
    }

    /**
     * 정규화한 뒤 길이와 토큰 수를 검사한다. 색인과 같은 함수를 쓰지 않으면 검색이 장애 없이
     * 조용히 0건이 되므로 여기서 다른 변환을 하지 않는다(구현 불변식 13).
     */
    private fun tokens(raw: String?): List<String> {
        if (raw == null) invalid("Query is required")
        if (raw.length > MAX_RAW_QUERY_LENGTH) invalid(QUERY_LENGTH_MESSAGE)
        val normalized = SearchTextNormalizer.normalize(raw)
        val length = normalized.codePointCount(0, normalized.length)
        if (length < MIN_QUERY_LENGTH || length > MAX_QUERY_LENGTH) invalid(QUERY_LENGTH_MESSAGE)
        val tokens = SearchTextNormalizer.tokenize(raw)
        if (tokens.size > MAX_TOKENS) invalid("Query must contain at most $MAX_TOKENS tokens")
        return tokens
    }

    private fun limit(raw: String?): Int {
        if (raw == null) return DEFAULT_LIMIT
        if (raw.length > MAX_INTEGER_LENGTH || !INTEGER_PATTERN.matches(raw)) invalid(LIMIT_MESSAGE)
        val limit = raw.toIntOrNull() ?: invalid(LIMIT_MESSAGE)
        if (limit !in 1..MAX_LIMIT) invalid(LIMIT_MESSAGE)
        return limit
    }

    private fun flag(
        raw: String?,
        name: String,
    ): Boolean =
        when (raw) {
            null -> false
            "true" -> true
            "false" -> false
            else -> invalid("$name must be true or false")
        }

    private fun cursor(raw: String?): String? {
        if (raw == null) return null
        if (raw.isEmpty() || raw.length > MAX_CURSOR_LENGTH) invalid("Cursor is invalid")
        return raw
    }

    private fun coordinates(
        latitude: String?,
        longitude: String?,
    ): Coordinates? {
        if (latitude == null && longitude == null) return null
        if (latitude == null || longitude == null) invalid("Latitude and longitude must be supplied together")
        return Coordinates(
            latitude = coordinate(latitude, MIN_LATITUDE, MAX_LATITUDE, "Latitude"),
            longitude = coordinate(longitude, MIN_LONGITUDE, MAX_LONGITUDE, "Longitude"),
        )
    }

    private fun coordinate(
        raw: String,
        minimum: BigDecimal,
        maximum: BigDecimal,
        name: String,
    ): BigDecimal {
        val message = "$name must be a finite decimal between ${minimum.toPlainString()} and ${maximum.toPlainString()}"
        if (raw.length > MAX_COORDINATE_LENGTH || !COORDINATE_INPUT_PATTERN.matches(raw)) invalid(message)
        val value =
            try {
                BigDecimal(raw)
            } catch (_: NumberFormatException) {
                invalid(message)
            }
        if (value < minimum || value > maximum) invalid(message)
        return value
    }

    private fun radiusMeters(
        raw: String?,
        coordinates: Coordinates?,
    ): Int? {
        if (raw == null) return null
        // 반경만 오면 400이다. 좌표 없이 반경을 받으면 어느 점을 기준으로 자를지 정할 수 없다.
        if (coordinates == null) invalid("Radius requires a latitude and longitude pair")
        if (raw.length > MAX_INTEGER_LENGTH || !INTEGER_PATTERN.matches(raw)) invalid(RADIUS_MESSAGE)
        val radius = raw.toIntOrNull() ?: invalid(RADIUS_MESSAGE)
        if (radius !in MIN_RADIUS_METERS..MAX_RADIUS_METERS) invalid(RADIUS_MESSAGE)
        return radius
    }

    /**
     * ADR-070 canonical filter binding.
     *
     * 정규화 토큰 배열을 **입력 순서 그대로** 담고 raw 검색어와 raw 좌표 text는 넣지 않는다.
     * 정렬을 바꾸거나 필터를 바꾸면 digest가 달라져 이전 cursor가 400이 된다.
     */
    private fun filterHash(
        sort: StoreSearchSort,
        tokens: List<String>,
        pickupAvailableOnly: Boolean,
        openOnly: Boolean,
        coordinates: Coordinates?,
        radiusMeters: Int?,
    ): String {
        val builder = StringBuilder()
        builder.append("""{"endpoint":${jsonString(sort.cursorEndpoint)}""")
        builder.append(""","tokens":[${tokens.joinToString(",") { jsonString(it) }}]""")
        builder.append(""","sort":${jsonString(sort.name)}""")
        builder.append(""","pickupAvailable":$pickupAvailableOnly""")
        builder.append(""","openOnly":$openOnly""")
        if (coordinates != null) {
            builder.append(""","latitude":${jsonString(canonicalDecimal(coordinates.latitude))}""")
            builder.append(""","longitude":${jsonString(canonicalDecimal(coordinates.longitude))}""")
        }
        if (radiusMeters != null) builder.append(""","radiusMeters":$radiusMeters""")
        builder.append("}")
        return HexFormat
            .of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(builder.toString().toByteArray(StandardCharsets.UTF_8)))
    }

    private fun canonicalDecimal(value: BigDecimal): String {
        val plain = value.stripTrailingZeros().toPlainString()
        val canonical = if (plain == "-0") "0" else plain
        check(DECIMAL_PATTERN.matches(canonical)) { "Canonical coordinate is not a plain decimal" }
        return canonical
    }

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    internal data class Coordinates(
        val latitude: BigDecimal,
        val longitude: BigDecimal,
    )

    internal companion object {
        const val DEFAULT_LIMIT = 20

        /** 공통 `DiscoveryLimit`을 따르고 이 endpoint를 위해 상한을 바꾸지 않는다. */
        const val MAX_LIMIT = 50
        const val MIN_QUERY_LENGTH = 2
        const val MAX_QUERY_LENGTH = 50

        /** 정규화 전에 거르는 상한. 정규화 자체를 임의로 긴 입력에 태우지 않는다. */
        const val MAX_RAW_QUERY_LENGTH = 200
        const val MAX_TOKENS = 5
        const val MAX_CURSOR_LENGTH = 2048
        const val MAX_COORDINATE_LENGTH = 32
        const val MAX_INTEGER_LENGTH = 10
        const val MIN_RADIUS_METERS = 1
        const val MAX_RADIUS_METERS = 10_000

        const val QUERY_LENGTH_MESSAGE =
            "Query must be between $MIN_QUERY_LENGTH and $MAX_QUERY_LENGTH characters after trimming"
        const val LIMIT_MESSAGE = "Limit must be an integer between 1 and $MAX_LIMIT"
        const val RADIUS_MESSAGE = "Radius must be an integer between $MIN_RADIUS_METERS and $MAX_RADIUS_METERS meters"

        val MIN_LATITUDE: BigDecimal = BigDecimal("-90")
        val MAX_LATITUDE: BigDecimal = BigDecimal("90")
        val MIN_LONGITUDE: BigDecimal = BigDecimal("-180")
        val MAX_LONGITUDE: BigDecimal = BigDecimal("180")
        val CURSOR_TTL: Duration = Duration.ofHours(24)

        val COORDINATE_INPUT_PATTERN = Regex("[+-]?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]{1,4})?")
        val DECIMAL_PATTERN = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")
        val INTEGER_PATTERN = Regex("-?(0|[1-9][0-9]*)")
        val UNSIGNED_INTEGER_PATTERN = Regex("0|[1-9][0-9]*")

        /**
         * Escapes one canonical-form string.
         *
         * 토큰은 사용자 입력에서 온 문자열이라 `"`나 `\`를 담을 수 있다. 그대로 이어 붙이면 서로
         * 다른 토큰 집합이 같은 canonical form을 만들어 cursor가 다른 검색에 재사용될 수 있다.
         */
        fun jsonString(value: String): String {
            val builder = StringBuilder(value.length + 2)
            builder.append('"')
            value.forEach { character ->
                when {
                    character == '"' -> builder.append("\\\"")
                    character == '\\' -> builder.append("\\\\")
                    character < ' ' -> builder.append("\\u").append("%04x".format(character.code))
                    else -> builder.append(character)
                }
            }
            builder.append('"')
            return builder.toString()
        }

        private fun decodeStoreId(value: String): UUID? {
            val storeId =
                try {
                    UUID.fromString(value)
                } catch (_: IllegalArgumentException) {
                    return null
                }
            return storeId.takeIf { it.toString() == value }
        }

        private fun decodeUnsignedLong(value: String): Long? {
            if (!UNSIGNED_INTEGER_PATTERN.matches(value)) return null
            return value.toLongOrNull()?.takeIf { it.toString() == value }
        }

        /** `(relevanceRank, distanceMicrometers, storeId)` — 좌표가 없으면 거리 항이 상수 `0`이다. */
        val RELEVANCE_SORT_ADAPTER =
            object : CursorSortAdapter<StoreSearchSortTuple> {
                override fun encode(sort: StoreSearchSortTuple): List<String> =
                    listOf(sort.relevanceRank.toString(), sort.distanceMicrometers.toString(), sort.storeId.toString())

                override fun decode(values: List<String>): StoreSearchSortTuple? {
                    if (values.size != 3) return null
                    val relevanceRank = decodeUnsignedLong(values[0]) ?: return null
                    val distanceMicrometers = decodeUnsignedLong(values[1]) ?: return null
                    val storeId = decodeStoreId(values[2]) ?: return null
                    return StoreSearchSortTuple(relevanceRank, distanceMicrometers, storeId)
                }
            }

        /** `(distanceMicrometers, storeId)`. 관련도 항은 이 정렬의 keyset에 참여하지 않는다. */
        val DISTANCE_SORT_ADAPTER =
            object : CursorSortAdapter<StoreSearchSortTuple> {
                override fun encode(sort: StoreSearchSortTuple): List<String> =
                    listOf(sort.distanceMicrometers.toString(), sort.storeId.toString())

                override fun decode(values: List<String>): StoreSearchSortTuple? {
                    if (values.size != 2) return null
                    val distanceMicrometers = decodeUnsignedLong(values[0]) ?: return null
                    val storeId = decodeStoreId(values[1]) ?: return null
                    return StoreSearchSortTuple(0, distanceMicrometers, storeId)
                }
            }
    }
}

/**
 * ADR-070 registers one cursor scope per sort, so a cursor issued for one ordering is rejected by
 * the other instead of paging through a different tuple.
 */
internal val StoreSearchSort.cursorEndpoint: String
    get() =
        when (this) {
            StoreSearchSort.RELEVANCE -> "stores-search-relevance"
            StoreSearchSort.DISTANCE -> "stores-search-distance"
        }

internal val StoreSearchSort.sortAdapter: CursorSortAdapter<StoreSearchSortTuple>
    get() =
        when (this) {
            StoreSearchSort.RELEVANCE -> StoreSearchQueryValidation.RELEVANCE_SORT_ADAPTER
            StoreSearchSort.DISTANCE -> StoreSearchQueryValidation.DISTANCE_SORT_ADAPTER
        }

internal enum class StoreSearchQueryOutcome {
    SUCCEEDED,
    INVALID_INPUT,
    DEPENDENCY_UNAVAILABLE,
}

/**
 * Closed vocabularies only. The query text, its tokens, coordinates, store ids and the request URI
 * are never used as metric tags; only the *number* of tokens is recorded (구현 불변식 17).
 */
@Component
internal class StoreSearchQueryMetrics(
    private val meterRegistry: MeterRegistry,
) {
    fun record(
        outcome: StoreSearchQueryOutcome,
        sort: StoreSearchSort,
        startedAtNanos: Long,
        pageSize: Int? = null,
        tokenCount: Int? = null,
        /**
         * True when this page issued no `nextCursor`. 가용성 필터가 중간 page를 비울 수 있으므로
         * 빈 page 자체는 "0건 검색"이 아니다. 뒤에 검사할 후보가 남은 page까지 세면 이 지표가
         * 드러내려는 상태를 오히려 덮는다.
         */
        exhausted: Boolean = true,
    ) {
        meterRegistry.counter("beanflow.discovery.search.count", "outcome", outcome.name).increment()
        meterRegistry
            .timer("beanflow.discovery.search.latency", "outcome", outcome.name, "sort", sort.name)
            .record(System.nanoTime() - startedAtNanos, TimeUnit.NANOSECONDS)
        if (pageSize != null) {
            meterRegistry.summary("beanflow.discovery.search.page.size").record(pageSize.toDouble())
            // "검색은 되는데 결과가 늘 0건"인 상태를 장애와 구분해 드러낸다.
            if (pageSize == 0 && exhausted) {
                meterRegistry.counter("beanflow.discovery.search.empty", "sort", sort.name).increment()
            }
        }
        if (tokenCount != null) {
            meterRegistry.summary("beanflow.discovery.search.tokens").record(tokenCount.toDouble())
        }
    }
}

private fun DomainFailure.toOutcome(): StoreSearchQueryOutcome =
    when (code) {
        FailureCode.INVALID_REQUEST -> StoreSearchQueryOutcome.INVALID_INPUT
        else -> StoreSearchQueryOutcome.DEPENDENCY_UNAVAILABLE
    }

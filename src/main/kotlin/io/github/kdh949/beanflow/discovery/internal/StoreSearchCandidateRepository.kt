package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.shared.api.StoreSearchTermKind
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.UUID

internal enum class StoreSearchSort {
    RELEVANCE,
    DISTANCE,
}

/**
 * The all-ASC keyset tuple of the last row of the previous page (ADR-070 2026-08-15 amendment).
 *
 * `distance` 정렬은 [relevanceRank]를 쓰지 않는다. 두 정렬이 같은 tuple 타입을 공유하는 이유는
 * cursor scope가 갈라지는 지점을 adapter 하나로 좁혀 두기 위해서다.
 */
internal data class StoreSearchSortTuple(
    val relevanceRank: Long,
    val distanceMicrometers: Long,
    val storeId: UUID,
)

/**
 * A validated, canonical search. Tokens are already normalized by `SearchTextNormalizer` and the
 * coordinate is already range-checked; the repository never sees the customer's raw input.
 */
internal data class StoreSearchCandidateQuery(
    val tokens: List<String>,
    val sort: StoreSearchSort,
    val latitude: BigDecimal?,
    val longitude: BigDecimal?,
    val radiusMeters: Int?,
    val openOnly: Boolean,
    val after: StoreSearchSortTuple?,
    val limit: Int,
)

internal data class StoreSearchCandidate(
    val storeId: UUID,
    val name: String,
    val relevanceRank: Long,
    val distanceMicrometers: Long,
    val open: Boolean,
    /**
     * `acceptingOrders && pickupEnabled` — the owner half of `pickupAvailable` and exactly what the
     * `openOnly` filter matches (ADR-103 A6).
     *
     * The public `pickupAvailable` is this conjoined with Fulfillment's slot-existence answer. The
     * field is deliberately not called `pickupAvailable`: this query cannot see slots, and naming
     * it after the public flag is how the weaker meaning reached the response before Milestone 6.
     */
    val pickupCapable: Boolean,
    val matchedKinds: Set<StoreSearchTermKind>,
    val imageThumbnailKey: String? = null,
)

internal data class StoreSearchMenuRow(
    val storeId: UUID,
    val menuId: UUID,
    val name: String,
)

internal data class StoreSearchTermText(
    val storeId: UUID,
    val kind: StoreSearchTermKind,
    val displayText: String,
)

/**
 * The store search candidate query (ADR-103 A2 to A6).
 *
 * 색인 테이블만 텍스트 원천으로 쓰고 매장·브랜드·지역·메뉴 테이블을 4-way 조인하지 않는다.
 * 토큰 배열을 `unnest(...) WITH ORDINALITY`로 전개해 토큰별 최고 가중 점수를 구하고,
 * 토큰 수와 같은 개수가 맞은 매장만 남겨 AND 의미론을 표현한다. 반경은 그렇게 좁혀진 후보에만
 * 적용하므로 GIN trigram 인덱스와 GiST 인덱스가 각자의 단계에서 쓰인다.
 *
 * 이 SQL이 `merchant_store_discovery_profile`과 `merchant_store`를 직접 읽는 것은
 * ADR-112 5절이 정한 `discovery → merchant` 방향이며, `ordering`이 같은 프로필 테이블을 직접
 * 조인하는 기존 선례와 같다. 반대 방향(`merchant`가 색인 테이블을 읽는 것)은 ADR-112가 금지한
 * 순환이므로 후보 질의를 `merchant`에 두지 않았다.
 */
@Repository
internal class StoreSearchCandidateRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    /**
     * Pins the trigram threshold to this transaction.
     *
     * `%`만으로는 세션 GUC `pg_trgm.similarity_threshold`에 결과가 좌우된다. 질의 안의 명시
     * 비교는 임계값보다 느슨한 세션만 막아 주고 더 엄격한 세션은 막지 못하므로, 두 방향을 함께
     * 닫기 위해 transaction 지역 설정과 명시 비교를 같이 쓴다.
     */
    fun pinSimilarityThreshold() {
        jdbcTemplate.queryForObject(
            "SELECT set_config('pg_trgm.similarity_threshold', ?, true)",
            String::class.java,
            SIMILARITY_THRESHOLD.toPlainString(),
        )
    }

    fun findCandidates(query: StoreSearchCandidateQuery): List<StoreSearchCandidate> {
        val located = query.latitude != null && query.longitude != null
        val distance =
            if (located) "floor(ST_Distance(profile.location, $QUERY_POINT) * $MICROMETERS_PER_METER)::bigint" else "0::bigint"
        val radiusPredicate = if (located && query.radiusMeters != null) "ST_DWithin(profile.location, $QUERY_POINT, ?)" else "TRUE"
        // 매장 상태 술어는 상수 SQL이라 파라미터가 없다. openOnly가 false면 닫힌 매장도 남는다.
        val openOnlyPredicate = if (query.openOnly) "(store.accepting_orders AND store.pickup_enabled)" else "TRUE"
        val keyset =
            when {
                query.after == null -> {
                    ""
                }

                query.sort == StoreSearchSort.RELEVANCE -> {
                    " WHERE (candidate.relevance_rank, candidate.distance_micrometers, candidate.store_id) > (?, ?, ?)"
                }

                else -> {
                    " WHERE (candidate.distance_micrometers, candidate.store_id) > (?, ?)"
                }
            }
        val order =
            if (query.sort == StoreSearchSort.RELEVANCE) {
                "candidate.relevance_rank, candidate.distance_micrometers, candidate.store_id"
            } else {
                "candidate.distance_micrometers, candidate.store_id"
            }
        val sql =
            """
            $TOKEN_MATCH_CTE,
            candidate AS (
                  SELECT scored.store_id AS store_id,
                         profile.name AS name,
                         ($RELEVANCE_SCALE - floor(scored.relevance * $RELEVANCE_SCALE))::bigint AS relevance_rank,
                         $distance AS distance_micrometers,
                         store.accepting_orders AS accepting_orders,
                         store.pickup_enabled AS pickup_enabled,
                         store.image_thumbnail_key AS image_thumbnail_key,
                         reason.kinds AS kinds
                    FROM scored
                    JOIN reason ON reason.store_id = scored.store_id
                    JOIN merchant_store_discovery_profile profile ON profile.store_id = scored.store_id
                    JOIN merchant_store store ON store.id = scored.store_id
                   WHERE $radiusPredicate
                     AND $openOnlyPredicate
                 )
            SELECT candidate.store_id, candidate.name, candidate.relevance_rank, candidate.distance_micrometers,
                   candidate.accepting_orders, candidate.pickup_enabled, candidate.image_thumbnail_key, candidate.kinds
              FROM candidate
            $keyset
             ORDER BY $order
             LIMIT ?
            """.trimIndent()
        return jdbcTemplate.query({ connection ->
            val statement = connection.prepareStatement(sql)
            val binder = ParameterBinder(connection, statement)
            binder.bindTokens(query.tokens)
            binder.bindDecimal(SIMILARITY_THRESHOLD)
            // AND 의미론. 토큰 수만큼 맞은 매장만 남는다(구현 불변식 4).
            binder.bindInt(query.tokens.size)
            if (located) binder.bindPoint(query.longitude!!, query.latitude!!)
            if (located && query.radiusMeters != null) {
                binder.bindPoint(query.longitude!!, query.latitude!!)
                binder.bindInt(query.radiusMeters)
            }
            query.after?.let { after ->
                if (query.sort == StoreSearchSort.RELEVANCE) binder.bindLong(after.relevanceRank)
                binder.bindLong(after.distanceMicrometers)
                binder.bindUuid(after.storeId)
            }
            binder.bindInt(query.limit)
            statement
        }, CANDIDATE_ROW_MAPPER)
    }

    /**
     * The matched menus of an already-decided page (ADR-103 A5).
     *
     * 매장을 순회하지 않고 page의 매장 ID 배열 하나로 전 매장의 메뉴 term을 한 번에 조회한다.
     * 매장당 상한은 `ROW_NUMBER()`로 자른다.
     */
    fun findMatchedMenus(
        storeIds: List<UUID>,
        tokens: List<String>,
        perStoreLimit: Int,
    ): List<StoreSearchMenuRow> {
        if (storeIds.isEmpty()) return emptyList()
        val sql =
            """
            WITH token AS (
                  SELECT t.value AS value, t.pattern AS pattern
                    FROM unnest(?::text[], ?::text[]) AS t(value, pattern)
                 ),
            matched AS (
                  SELECT term.store_id AS store_id,
                         term.source_id AS menu_id,
                         term.display_text AS display_text,
                         max($TOKEN_SCORE) AS score
                    FROM discovery_store_search_term term
                    JOIN token ON $TOKEN_MATCH_PREDICATE
                   WHERE term.term_kind = 'MENU_NAME'
                     AND term.store_id = ANY(?::uuid[])
                   GROUP BY term.store_id, term.source_id, term.display_text
                 ),
            ranked AS (
                  SELECT matched.*,
                         ROW_NUMBER() OVER (
                             PARTITION BY matched.store_id
                             ORDER BY matched.score DESC, matched.display_text ASC, matched.menu_id ASC
                         ) AS position
                    FROM matched
                 )
            SELECT ranked.store_id, ranked.menu_id, ranked.display_text
              FROM ranked
             WHERE ranked.position <= ?
             ORDER BY ranked.store_id, ranked.position
            """.trimIndent()
        return jdbcTemplate.query({ connection ->
            val statement = connection.prepareStatement(sql)
            val binder = ParameterBinder(connection, statement)
            binder.bindTokens(tokens)
            binder.bindDecimal(SIMILARITY_THRESHOLD)
            binder.bindUuids(storeIds)
            binder.bindInt(perStoreLimit)
            statement
        }, MENU_ROW_MAPPER)
    }

    /**
     * The brand and region display text of an already-decided page.
     *
     * 브랜드·지역 이름을 `merchant_brand`·`merchant_region`이 아니라 색인 테이블에서 읽는다.
     * 색인 갱신이 원 커맨드와 같은 transaction이므로 두 값은 언제나 같고, 이렇게 하면 조회 경로가
     * 색인 테이블 하나만 텍스트 원천으로 쓴다는 규칙이 깨지지 않는다.
     */
    fun findDisplayTerms(storeIds: List<UUID>): List<StoreSearchTermText> {
        if (storeIds.isEmpty()) return emptyList()
        val kinds = DISPLAY_TERM_KINDS.joinToString(", ") { "'${it.name}'" }
        val sql =
            """
            SELECT term.store_id, term.term_kind, term.display_text
              FROM discovery_store_search_term term
             WHERE term.store_id = ANY(?::uuid[])
               AND term.term_kind IN ($kinds)
            """.trimIndent()
        return jdbcTemplate.query({ connection ->
            val statement = connection.prepareStatement(sql)
            ParameterBinder(connection, statement).bindUuids(storeIds)
            statement
        }, TERM_TEXT_ROW_MAPPER)
    }

    /** Positional binding for statements that mix arrays with scalars. */
    private class ParameterBinder(
        private val connection: Connection,
        private val statement: PreparedStatement,
    ) {
        private var index = 1

        fun bindTokens(tokens: List<String>) {
            statement.setArray(index++, connection.createArrayOf("text", tokens.toTypedArray()))
            statement.setArray(index++, connection.createArrayOf("text", tokens.map(::likePattern).toTypedArray()))
        }

        fun bindUuids(values: List<UUID>) {
            statement.setArray(index++, connection.createArrayOf("uuid", values.toTypedArray()))
        }

        fun bindPoint(
            longitude: BigDecimal,
            latitude: BigDecimal,
        ) {
            statement.setBigDecimal(index++, longitude)
            statement.setBigDecimal(index++, latitude)
        }

        fun bindInt(value: Int) {
            statement.setInt(index++, value)
        }

        fun bindLong(value: Long) {
            statement.setLong(index++, value)
        }

        fun bindUuid(value: UUID) {
            statement.setObject(index++, value)
        }

        fun bindDecimal(value: BigDecimal) {
            statement.setBigDecimal(index++, value)
        }
    }

    internal companion object {
        /** ADR-103 A2. */
        val SIMILARITY_THRESHOLD: BigDecimal = BigDecimal("0.3")

        /** ADR-070: `relevanceRank = RELEVANCE_SCALE - floor(relevance * RELEVANCE_SCALE)`. */
        const val RELEVANCE_SCALE = 1_000_000L
        const val MICROMETERS_PER_METER = 1_000_000L

        val DISPLAY_TERM_KINDS =
            listOf(
                StoreSearchTermKind.BRAND_NAME,
                StoreSearchTermKind.REGION_SIDO,
                StoreSearchTermKind.REGION_SIGUNGU,
                StoreSearchTermKind.REGION_EUPMYEONDONG,
                StoreSearchTermKind.REGION_RI,
            )

        /**
         * `%`, `_`, `\`는 wildcard가 아니라 literal이다(구현 불변식 3). 그래서 검색어의 세 문자를
         * escape한 뒤에야 `LIKE`에 넣는다. `strpos` 대신 `LIKE '%...%'`를 쓰는 이유는 후자가
         * `gin_trgm_ops` 인덱스를 탈 수 있기 때문이다.
         */
        fun likePattern(token: String): String {
            val escaped =
                token
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_")
            return "%$escaped%"
        }

        private const val QUERY_POINT =
            "ST_SetSRID(ST_MakePoint(?::double precision, ?::double precision), 4326)::geography"

        private const val EXACT_PREDICATE = """term.term_normalized LIKE token.pattern ESCAPE '\'"""

        /**
         * `%`는 GIN 인덱스를 타기 위한 것이고 뒤의 명시 비교가 임계값을 확정한다.
         */
        private const val TOKEN_MATCH_PREDICATE =
            "$EXACT_PREDICATE OR (term.term_normalized % token.value AND similarity(term.term_normalized, token.value) >= ?)"

        /** substring 매칭의 유사도는 `1.0`이므로 가중치가 곧 점수다(ADR-103 A4). */
        private const val TOKEN_SCORE =
            """
            CASE
                WHEN $EXACT_PREDICATE THEN term.weight
                ELSE term.weight * similarity(term.term_normalized, token.value)::numeric
            END
            """

        /**
         * 토큰별 최고 가중 점수와 매칭된 term 종류를 구하는 공통 앞부분이다.
         *
         * `token_rule`이 토큰마다 substring 매칭 존재 여부를 먼저 정하고 `selected`가 그 경로의
         * 행만 남긴다. 이것이 "substring 우선, 걸리지 않은 토큰에만 유사도 보완"이며, 같은
         * 필터가 `matchReason`에도 적용돼 구제되지 않은 유사도 매칭이 이유로 보고되지 않는다.
         */
        private val TOKEN_MATCH_CTE =
            """
            WITH token AS (
                  SELECT t.value AS value, t.pattern AS pattern, t.ordinality AS ordinality
                    FROM unnest(?::text[], ?::text[]) WITH ORDINALITY AS t(value, pattern, ordinality)
                 ),
            term_match AS (
                  SELECT term.store_id AS store_id,
                         token.ordinality AS ordinality,
                         term.term_kind AS term_kind,
                         $EXACT_PREDICATE AS exact,
                         $TOKEN_SCORE AS score
                    FROM discovery_store_search_term term
                    JOIN token ON $TOKEN_MATCH_PREDICATE
                 ),
            token_rule AS (
                  SELECT term_match.store_id AS store_id,
                         term_match.ordinality AS ordinality,
                         bool_or(term_match.exact) AS prefer_exact
                    FROM term_match
                   GROUP BY term_match.store_id, term_match.ordinality
                 ),
            selected AS (
                  SELECT term_match.store_id AS store_id,
                         term_match.ordinality AS ordinality,
                         term_match.term_kind AS term_kind,
                         term_match.score AS score
                    FROM term_match
                    JOIN token_rule
                      ON token_rule.store_id = term_match.store_id
                     AND token_rule.ordinality = term_match.ordinality
                   WHERE term_match.exact = token_rule.prefer_exact
                 ),
            scored AS (
                  SELECT token_best.store_id AS store_id, avg(token_best.max_score) AS relevance
                    FROM (
                          SELECT selected.store_id AS store_id,
                                 selected.ordinality AS ordinality,
                                 max(selected.score) AS max_score
                            FROM selected
                           GROUP BY selected.store_id, selected.ordinality
                         ) AS token_best
                   GROUP BY token_best.store_id
                  HAVING count(*) = ?
                 ),
            reason AS (
                  SELECT selected.store_id AS store_id, array_agg(DISTINCT selected.term_kind) AS kinds
                    FROM selected
                   GROUP BY selected.store_id
                 )
            """.trimIndent()

        private val CANDIDATE_ROW_MAPPER =
            RowMapper { row, _ ->
                val acceptingOrders = row.getBoolean("accepting_orders")
                StoreSearchCandidate(
                    storeId = row.getObject("store_id", UUID::class.java),
                    name = row.getString("name"),
                    relevanceRank = row.getLong("relevance_rank"),
                    distanceMicrometers = row.getLong("distance_micrometers"),
                    open = acceptingOrders,
                    pickupCapable = acceptingOrders && row.getBoolean("pickup_enabled"),
                    matchedKinds =
                        (row.getArray("kinds").array as Array<*>)
                            .map { StoreSearchTermKind.valueOf(it as String) }
                            .toSet(),
                    imageThumbnailKey = row.getString("image_thumbnail_key"),
                )
            }

        private val MENU_ROW_MAPPER =
            RowMapper { row, _ ->
                StoreSearchMenuRow(
                    storeId = row.getObject("store_id", UUID::class.java),
                    menuId = row.getObject("menu_id", UUID::class.java),
                    name = row.getString("display_text"),
                )
            }

        private val TERM_TEXT_ROW_MAPPER =
            RowMapper { row, _ ->
                StoreSearchTermText(
                    storeId = row.getObject("store_id", UUID::class.java),
                    kind = StoreSearchTermKind.valueOf(row.getString("term_kind")),
                    displayText = row.getString("display_text"),
                )
            }
    }
}

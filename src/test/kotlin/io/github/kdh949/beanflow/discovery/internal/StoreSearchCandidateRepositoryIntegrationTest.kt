package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.shared.api.SearchTextNormalizer
import io.github.kdh949.beanflow.shared.api.StoreSearchIndexOperations
import io.github.kdh949.beanflow.shared.api.StoreSearchTermKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.util.UUID

/**
 * The search candidate query against PostgreSQL 17 with `pg_trgm`.
 *
 * 이 테스트는 서비스 계층을 거치지 않고 SQL의 의미론만 고정한다. 다중 토큰 AND, substring 우선과
 * 유사도 보완, 가중치 순서, 관련도 양자화가 여기서 측정되고, cursor 발급·검증과 요청 검증은
 * `StoreSearchQueryIntegrationTest`가 맡는다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "beanflow.search-index-coverage.initial-delay-ms=3600000",
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class StoreSearchCandidateRepositoryIntegrationTest {
    @Autowired
    private lateinit var repository: StoreSearchCandidateRepository

    @Autowired
    private lateinit var index: StoreSearchIndexOperations

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val transactions by lazy { TransactionTemplate(transactionManager) }

    private val fixture by lazy { StoreSearchIndexTestFixture(jdbc, index, transactions) }

    @BeforeEach
    fun clearStoresAndTerms() {
        fixture.clear()
    }

    @Test
    fun `a single token matches a store once no matter how many of its terms match`() {
        val store =
            indexStore(
                name = "스타벅스 강남점",
                menus = listOf("스타벅스 라떼", "스타벅스 콜드브루", "아메리카노"),
            )
        indexStore(name = "블루보틀 삼청점")

        val candidates = search("스타벅스")

        assertThat(candidates.map { it.storeId }).containsExactly(store)
        // 매장명(1.00)이 메뉴명(0.70)보다 높은 점수를 내므로 관련도는 매장명 쪽이 정한다.
        assertThat(candidates.single().relevanceRank).isZero()
        assertThat(candidates.single().matchedKinds)
            .containsExactlyInAnyOrder(StoreSearchTermKind.STORE_NAME, StoreSearchTermKind.MENU_NAME)
    }

    @Test
    fun `every token must match for the store to be a candidate`() {
        val both =
            indexStore(
                name = "커피집 1호점",
                brandName = "스타벅스",
                region = listOf(StoreSearchTermKind.REGION_SIGUNGU to "강남구"),
            )
        indexStore(name = "커피집 2호점", brandName = "스타벅스")
        indexStore(name = "커피집 3호점", region = listOf(StoreSearchTermKind.REGION_SIGUNGU to "강남구"))

        val candidates = search("강남 스타벅스")

        // 두 토큰이 서로 다른 종류의 term에 걸린 매장만 남는다. 한 토큰만 맞은 매장은 빠진다.
        assertThat(candidates.map { it.storeId }).containsExactly(both)
        assertThat(candidates.single().matchedKinds)
            .containsExactlyInAnyOrder(StoreSearchTermKind.REGION_SIGUNGU, StoreSearchTermKind.BRAND_NAME)
        // avg(지역 0.80, 브랜드 0.90) = 0.85.
        assertThat(candidates.single().relevanceRank).isEqualTo(150_000L)
    }

    @Test
    fun `similarity only rescues a token that no substring matched`() {
        // 한글 짧은 이름은 trigram이 거의 겹치지 않아 오타 구제가 성립하지 않는다. 구제가 실제로
        // 일어나는 경우를 고정해야 하므로 trigram이 겹치는 라틴 표기 상호를 쓴다.
        val store = indexStore(name = TYPO_TARGET)

        // 오타 토큰은 substring으로 아무 term에도 걸리지 않는다.
        assertThat(similarityBetween(TYPO_TARGET, TYPO_TOKEN)).isGreaterThanOrEqualTo(0.3f)
        val rescued = search(TYPO_TOKEN)

        assertThat(rescued.map { it.storeId }).containsExactly(store)
        // 유사도 경로는 가중치에 유사도를 곱하므로 substring 매칭보다 낮은 점수다.
        assertThat(rescued.single().relevanceRank).isGreaterThan(0L)
        assertThat(search(TYPO_TARGET).single().relevanceRank).isZero()
    }

    @Test
    fun `a substring match keeps a stronger similarity match from raising the score`() {
        // 같은 매장 안에서 토큰이 메뉴명에는 substring으로 걸리고 매장명과는 유사도로만 가깝다.
        val store = indexStore(name = "americanno", menus = listOf("iced americano"))
        // 매장명 쪽 가중 유사도가 메뉴명 가중치 0.70보다 크다. 그래도 채택되지 않아야 한다.
        assertThat(similarityBetween("americanno", "americano")).isGreaterThan(0.7f)

        val candidates = search("americano")

        assertThat(candidates.map { it.storeId }).containsExactly(store)
        // substring이 걸린 토큰에는 유사도 경로를 더하지 않으므로 점수는 메뉴명 가중치 그대로다.
        assertThat(candidates.single().relevanceRank).isEqualTo(300_000L)
        assertThat(candidates.single().matchedKinds).containsExactly(StoreSearchTermKind.MENU_NAME)
    }

    @Test
    fun `the similarity threshold is the query's own and not the session's`() {
        val store = indexStore(name = TYPO_TARGET)
        val similarity = similarityBetween(TYPO_TARGET, TYPO_TOKEN)
        assertThat(similarity).isBetween(0.3f, 0.9f)

        // 세션 임계값을 위아래로 흔들어도 같은 결과여야 한다. 낮은 쪽은 질의 안의 명시 비교가,
        // 높은 쪽은 transaction 지역 설정이 막는다.
        assertThat(searchWithSessionThreshold(TYPO_TOKEN, "0.05").map { it.storeId }).containsExactly(store)
        assertThat(searchWithSessionThreshold(TYPO_TOKEN, "0.9").map { it.storeId }).containsExactly(store)
    }

    @Test
    fun `a token below the threshold rescues nothing`() {
        indexStore(name = "스타벅스 강남점")
        val token = "지하철역"
        assertThat(similarityBetween("스타벅스 강남점", token)).isLessThan(0.3f)

        assertThat(search(token)).isEmpty()
    }

    @Test
    fun `term weight decides the order when two stores match the same way`() {
        val byStoreName = indexStore(name = "아메리카노 전문점")
        val byBrand = indexStore(name = "커피집 1호점", brandName = "아메리카노")
        val byRegion = indexStore(name = "커피집 2호점", region = listOf(StoreSearchTermKind.REGION_EUPMYEONDONG to "아메리카노"))
        val byMenu = indexStore(name = "커피집 3호점", menus = listOf("아메리카노"))

        val candidates = search("아메리카노")

        assertThat(candidates.map { it.storeId }).containsExactly(byStoreName, byBrand, byRegion, byMenu)
        assertThat(candidates.map { it.relevanceRank }).containsExactly(0L, 100_000L, 200_000L, 300_000L)
    }

    @Test
    fun `wildcard characters are literal`() {
        val literal = indexStore(name = "100% 아라비카")
        indexStore(name = "아메리카노 전문점")

        // '%'가 wildcard였다면 '아'로 끝나는 이름이 모두 걸린다. literal이므로 아무것도 걸리지 않는다.
        assertThat(search("%아")).isEmpty()
        assertThat(search("__")).isEmpty()
        // literal '%'는 진짜로 '%'를 담은 이름을 찾는다.
        assertThat(search("00%").map { it.storeId }).containsExactly(literal)
    }

    @Test
    fun `the query path finds text the normalizer changed on the way in`() {
        val wide = indexStore(name = "  Ｓｔａｒ\u3000버클  ")

        // 색인은 정규화된 문자열을 담고 질의도 같은 함수를 쓴다. 두 경로가 갈라지면 0건이 된다.
        assertThat(search("ＳＴＡＲ").map { it.storeId }).containsExactly(wide)
        assertThat(search("star 버클").map { it.storeId }).containsExactly(wide)
    }

    @Test
    fun `relevance rank stays inside its range and rises as relevance falls`() {
        indexStore(name = "아메리카노 전문점")
        indexStore(name = "커피집", menus = listOf("아메리카노"))

        val ranks = search("아메리카노").map { it.relevanceRank }

        assertThat(ranks).allSatisfy { rank -> assertThat(rank).isBetween(0L, 1_000_000L) }
        assertThat(ranks).isSorted()
    }

    @Test
    fun `distance sort orders by distance and the radius filter drops the far store`() {
        val near = indexStore(name = "스타벅스 강남점", longitude = 127.0361, latitude = 37.5006)
        val far = indexStore(name = "스타벅스 춘천점", longitude = 127.7298, latitude = 37.8813)

        val unbounded = candidates(candidateQuery("스타벅스", sort = StoreSearchSort.DISTANCE, located = true))
        assertThat(unbounded.map { it.storeId }).containsExactly(near, far)
        assertThat(unbounded.map { it.distanceMicrometers }).isSorted()

        val bounded =
            candidates(candidateQuery("스타벅스", sort = StoreSearchSort.DISTANCE, located = true, radiusMeters = 10_000))
        assertThat(bounded.map { it.storeId }).containsExactly(near)
    }

    @Test
    fun `a relevance search without coordinates reports a constant zero distance`() {
        indexStore(name = "스타벅스 강남점")

        assertThat(search("스타벅스").single().distanceMicrometers).isZero()
    }

    @Test
    fun `openOnly keeps only stores that accept orders and have pickup enabled`() {
        val open = indexStore(name = "스타벅스 강남점")
        val closed = indexStore(name = "스타벅스 역삼점", acceptingOrders = false)
        val pickupOff = indexStore(name = "스타벅스 삼성점", pickupEnabled = false)

        // 기본값은 닫힌 매장도 포함하고 상태를 플래그로 알린다(ADR-103 A6).
        val all = search("스타벅스")
        assertThat(all.map { it.storeId }).containsExactlyInAnyOrder(open, closed, pickupOff)
        assertThat(all.single { it.storeId == closed }.open).isFalse()
        assertThat(all.single { it.storeId == pickupOff }.pickupAvailable).isFalse()

        val filtered = candidates(candidateQuery("스타벅스", openOnly = true))
        assertThat(filtered.map { it.storeId }).containsExactly(open)
    }

    @Test
    fun `a store is findable by both its eupmyeondong and its ri`() {
        val store =
            indexStore(
                // 매장명에 리 이름을 넣지 않는다. 넣으면 매칭 이유가 리인지 매장명인지 갈리지 않는다.
                name = "커피집 1호점",
                region =
                    listOf(
                        StoreSearchTermKind.REGION_SIDO to "강원특별자치도",
                        StoreSearchTermKind.REGION_SIGUNGU to "춘천시",
                        StoreSearchTermKind.REGION_EUPMYEONDONG to "동면",
                        StoreSearchTermKind.REGION_RI to "감정리",
                    ),
            )

        assertThat(search("동면").single().storeId).isEqualTo(store)
        assertThat(search("동면").single().matchedKinds).containsExactly(StoreSearchTermKind.REGION_EUPMYEONDONG)
        assertThat(search("감정리").single().storeId).isEqualTo(store)
        assertThat(search("감정리").single().matchedKinds).containsExactly(StoreSearchTermKind.REGION_RI)
    }

    @Test
    fun `a ri name that repeats across the country is separated by the radius filter`() {
        val chuncheon =
            indexStore(
                name = "춘천 상리점",
                longitude = 127.7298,
                latitude = 37.8813,
                region = listOf(StoreSearchTermKind.REGION_RI to "상리"),
            )
        val gyeongju =
            indexStore(
                name = "경주 상리점",
                longitude = 129.2247,
                latitude = 35.8562,
                region = listOf(StoreSearchTermKind.REGION_RI to "상리"),
            )

        assertThat(search("상리").map { it.storeId }).containsExactlyInAnyOrder(chuncheon, gyeongju)

        val nearChuncheon =
            candidates(
                candidateQuery(
                    "상리",
                    sort = StoreSearchSort.DISTANCE,
                    located = true,
                    longitude = BigDecimal("127.7298"),
                    latitude = BigDecimal("37.8813"),
                    radiusMeters = 10_000,
                ),
            )
        assertThat(nearChuncheon.map { it.storeId }).containsExactly(chuncheon)
    }

    @Test
    fun `matched menus are capped per store and ordered deterministically`() {
        val store = indexStore(name = "커피집", menus = listOf("라떼 D", "라떼 C", "라떼 B", "라떼 A"))
        val other = indexStore(name = "라떼 전문점")

        val menus =
            transactions.execute {
                repository.pinSimilarityThreshold()
                repository.findMatchedMenus(listOf(store, other), listOf("라떼"), 3)
            }!!

        // 네 메뉴가 같은 가중 유사도로 동점이므로 메뉴명 오름차순이 순서를 정한다.
        assertThat(menus.filter { it.storeId == store }.map { it.name }).containsExactly("라떼 A", "라떼 B", "라떼 C")
        // 매장명으로만 걸린 매장은 매칭 메뉴가 없고 결과에서 빠지지도 않는다.
        assertThat(menus.filter { it.storeId == other }).isEmpty()
    }

    @Test
    fun `brand and region display text comes back for the decided page`() {
        val store =
            indexStore(
                name = "커피집 감정리점",
                brandName = "스타벅스",
                region =
                    listOf(
                        StoreSearchTermKind.REGION_SIDO to "강원특별자치도",
                        StoreSearchTermKind.REGION_SIGUNGU to "춘천시",
                        StoreSearchTermKind.REGION_EUPMYEONDONG to "동면",
                        StoreSearchTermKind.REGION_RI to "감정리",
                    ),
                menus = listOf("아메리카노"),
            )

        val terms = repository.findDisplayTerms(listOf(store))

        assertThat(terms.map { it.kind to it.displayText }).containsExactlyInAnyOrder(
            StoreSearchTermKind.BRAND_NAME to "스타벅스",
            StoreSearchTermKind.REGION_SIDO to "강원특별자치도",
            StoreSearchTermKind.REGION_SIGUNGU to "춘천시",
            StoreSearchTermKind.REGION_EUPMYEONDONG to "동면",
            StoreSearchTermKind.REGION_RI to "감정리",
        )
    }

    @Test
    fun `paging by the keyset tuple never skips or repeats a store even when relevance ties`() {
        // 다섯 매장이 모두 매장명 substring으로 걸려 관련도가 완전히 동점이다. 그때 page를
        // 가르는 것은 tuple의 마지막 항인 매장 ID뿐이다.
        repeat(5) { index -> indexStore(name = "스타벅스 ${index}호점") }
        val all = search("스타벅스")
        assertThat(all.map { it.relevanceRank }.distinct()).hasSize(1)

        val walked = mutableListOf<UUID>()
        var after: StoreSearchSortTuple? = null
        repeat(3) {
            val page = candidates(candidateQuery("스타벅스", after = after, limit = 2))
            walked += page.map { it.storeId }
            after = page.lastOrNull()?.let { StoreSearchSortTuple(it.relevanceRank, it.distanceMicrometers, it.storeId) }
        }

        assertThat(walked).containsExactlyElementsOf(all.map { it.storeId })
        assertThat(walked.distinct()).hasSize(5)
    }

    private fun search(text: String): List<StoreSearchCandidate> = candidates(candidateQuery(text))

    /** 실제 조회 경로와 같이 하나의 transaction 안에서 임계값을 고정하고 질의한다. */
    private fun candidates(query: StoreSearchCandidateQuery): List<StoreSearchCandidate> =
        transactions.execute {
            repository.pinSimilarityThreshold()
            repository.findCandidates(query)
        }!!

    private fun searchWithSessionThreshold(
        text: String,
        sessionThreshold: String,
    ): List<StoreSearchCandidate> =
        transactions.execute {
            jdbc.execute("SET LOCAL pg_trgm.similarity_threshold = $sessionThreshold")
            repository.pinSimilarityThreshold()
            repository.findCandidates(candidateQuery(text))
        }!!

    private fun candidateQuery(
        text: String,
        sort: StoreSearchSort = StoreSearchSort.RELEVANCE,
        located: Boolean = false,
        longitude: BigDecimal = BigDecimal("127.0361"),
        latitude: BigDecimal = BigDecimal("37.5006"),
        radiusMeters: Int? = null,
        openOnly: Boolean = false,
        after: StoreSearchSortTuple? = null,
        limit: Int = 50,
    ) = StoreSearchCandidateQuery(
        tokens = SearchTextNormalizer.tokenize(text),
        sort = sort,
        latitude = if (located) latitude else null,
        longitude = if (located) longitude else null,
        radiusMeters = radiusMeters,
        openOnly = openOnly,
        after = after,
        limit = limit,
    )

    private fun similarityBetween(
        indexed: String,
        token: String,
    ): Float =
        jdbc.queryForObject(
            "SELECT similarity(?, ?)",
            Float::class.java,
            SearchTextNormalizer.normalize(indexed),
            SearchTextNormalizer.normalize(token),
        )!!

    private fun indexStore(
        name: String,
        longitude: Double = StoreSearchIndexTestFixture.SEOUL_LONGITUDE,
        latitude: Double = StoreSearchIndexTestFixture.SEOUL_LATITUDE,
        brandName: String? = null,
        region: List<Pair<StoreSearchTermKind, String>> = emptyList(),
        menus: List<String> = emptyList(),
        acceptingOrders: Boolean = true,
        pickupEnabled: Boolean = true,
    ): UUID = fixture.indexStore(name, longitude, latitude, brandName, region, menus, acceptingOrders, pickupEnabled)

    private companion object {
        /** trigram이 충분히 겹쳐 유사도 구제가 실제로 일어나는 쌍. */
        const val TYPO_TARGET = "starbucks"
        const val TYPO_TOKEN = "starbuks"
    }
}

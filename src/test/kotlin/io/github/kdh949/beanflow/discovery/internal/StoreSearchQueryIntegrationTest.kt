package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.discovery.api.SearchStoresCommand
import io.github.kdh949.beanflow.discovery.api.StoreSearchItemView
import io.github.kdh949.beanflow.discovery.api.StoreSearchQueryOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import io.github.kdh949.beanflow.shared.api.StoreSearchIndexOperations
import io.github.kdh949.beanflow.shared.api.StoreSearchTermKind
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * The public search contract: validation, the two signed cursor scopes and page composition.
 *
 * SQL의 매칭·정렬 의미론은 `StoreSearchCandidateRepositoryIntegrationTest`가 고정한다. 여기서는
 * 잘못된 요청이 색인에 닿기 전에 거절되는지, cursor가 다른 검색으로 넘어가지 않는지, page가
 * 매칭 메뉴·브랜드·지역과 함께 조립되는지를 본다.
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
internal class StoreSearchQueryIntegrationTest {
    @Autowired
    private lateinit var search: StoreSearchQueryOperations

    @Autowired
    private lateinit var validation: StoreSearchQueryValidation

    @Autowired
    private lateinit var signedCursorCodec: SignedCursorCodec

    @Autowired
    private lateinit var index: StoreSearchIndexOperations

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var clock: Clock

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val fixture by lazy {
        StoreSearchIndexTestFixture(jdbc, index, TransactionTemplate(transactionManager))
    }

    @BeforeEach
    fun clearStoresAndTerms() {
        fixture.clear()
    }

    @Test
    fun `a query outside its length or token limits never reaches the index`() {
        listOf(
            command(query = null),
            command(query = " "),
            command(query = "가"),
            command(query = "가".repeat(51)),
            command(query = "가 ".repeat(6)),
            command(query = "가".repeat(300)),
        ).forEach { request ->
            assertThatThrownBy { search.search(request) }
                .isInstanceOfSatisfying(DomainFailure::class.java) { failure ->
                    assertThat(failure.code).isEqualTo(FailureCode.INVALID_REQUEST)
                }
        }
    }

    @Test
    fun `sort, coordinate pair, radius, openOnly and limit are all checked before the query runs`() {
        listOf(
            command(sort = "popularity"),
            command(sort = "distance"),
            command(latitude = "37.5006"),
            command(longitude = "127.0361"),
            command(radiusMeters = "1000"),
            command(latitude = "91", longitude = "127.0361"),
            command(latitude = "37.5006", longitude = "181"),
            command(latitude = "37.5006", longitude = "127.0361", radiusMeters = "0"),
            command(latitude = "37.5006", longitude = "127.0361", radiusMeters = "10001"),
            command(openOnly = "yes"),
            command(limit = "0"),
            command(limit = "51"),
            command(limit = "abc"),
            command(cursor = ""),
            command(cursor = "x".repeat(2049)),
            command(cursor = "not-a-signed-cursor"),
        ).forEach { request ->
            assertThatThrownBy { search.search(request) }
                .describedAs("sort=${request.sort} limit=${request.limit}")
                .isInstanceOfSatisfying(DomainFailure::class.java) { failure ->
                    assertThat(failure.code).isEqualTo(FailureCode.INVALID_REQUEST)
                }
        }
    }

    @Test
    fun `a rejected request never echoes the query text, the coordinate or the cursor`() {
        val secret = "비밀 검색어"

        assertThatThrownBy { search.search(command(query = secret, sort = "popularity")) }
            .isInstanceOfSatisfying(DomainFailure::class.java) { failure ->
                assertThat(failure.message).doesNotContain(secret)
            }
        assertThatThrownBy { search.search(command(latitude = "37.500612345", longitude = null)) }
            .isInstanceOfSatisfying(DomainFailure::class.java) { failure ->
                assertThat(failure.message).doesNotContain("37.500612345")
            }
    }

    @Test
    fun `walking the pages returns every store exactly once even when relevance ties`() {
        // 다섯 매장이 매장명 substring으로만 걸려 관련도가 완전히 동점이다.
        repeat(5) { position -> fixture.indexStore(name = "스타벅스 ${position}호점") }
        val everything = search.search(command(limit = "50")).items.map(StoreSearchItemView::storeId)
        assertThat(everything).hasSize(5)

        val walked = mutableListOf<UUID>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = search.search(command(limit = "2", cursor = cursor))
            walked += page.items.map(StoreSearchItemView::storeId)
            cursor = page.nextCursor
            pages++
        } while (cursor != null && pages < 10)

        assertThat(walked).containsExactlyElementsOf(everything)
        assertThat(walked.distinct()).hasSize(5)
    }

    @Test
    fun `a cursor does not carry across a different sort, filter or query`() {
        fixture.indexStore(name = "스타벅스 1호점")
        fixture.indexStore(name = "스타벅스 2호점")
        val cursor =
            requireNotNull(search.search(command(limit = "1")).nextCursor) { "a second page was expected" }

        // 같은 검색·같은 정렬에서는 통한다.
        assertThat(search.search(command(limit = "1", cursor = cursor)).items).hasSize(1)

        listOf(
            command(limit = "1", cursor = cursor, sort = "distance", latitude = "37.5006", longitude = "127.0361"),
            command(limit = "1", cursor = cursor, openOnly = "true"),
            command(limit = "1", cursor = cursor, query = "블루보틀"),
            command(limit = "1", cursor = cursor, latitude = "37.5006", longitude = "127.0361"),
        ).forEach { request ->
            assertThatThrownBy { search.search(request) }
                .isInstanceOfSatisfying(DomainFailure::class.java) { failure ->
                    assertThat(failure.code).isEqualTo(FailureCode.INVALID_REQUEST)
                }
        }
    }

    @Test
    fun `a cursor from another endpoint or one that has expired is rejected`() {
        fixture.indexStore(name = "스타벅스 1호점")
        val scope = validation.prepare(command()).cursorScope
        val tuple = StoreSearchSortTuple(0, 0, UUID.randomUUID())

        val foreignEndpoint =
            signedCursorCodec.issue(
                SignedCursorScope("stores-nearby", scope.filterHash, scope.sortAdapter),
                tuple,
                clock.instant().plus(Duration.ofHours(1)),
            )
        val shortLived = signedCursorCodec.issue(scope, tuple, clock.instant().plusSeconds(1))

        assertThatThrownBy { search.search(command(cursor = foreignEndpoint)) }
            .isInstanceOf(DomainFailure::class.java)

        Thread.sleep(1_200)
        assertThatThrownBy { search.search(command(cursor = shortLived)) }
            .isInstanceOfSatisfying(DomainFailure::class.java) { failure ->
                assertThat(failure.code).isEqualTo(FailureCode.INVALID_REQUEST)
            }
    }

    @Test
    fun `the item carries its brand, region, match reason and matched menus`() {
        val storeId =
            fixture.indexStore(
                name = "커피집 1호점",
                brandName = "스타벅스",
                region =
                    listOf(
                        StoreSearchTermKind.REGION_SIDO to "강원특별자치도",
                        StoreSearchTermKind.REGION_SIGUNGU to "춘천시",
                        StoreSearchTermKind.REGION_EUPMYEONDONG to "동면",
                        StoreSearchTermKind.REGION_RI to "감정리",
                    ),
                menus = listOf("라떼 D", "라떼 C", "라떼 B", "라떼 A", "아메리카노"),
            )

        val item = search.search(command(query = "라떼")).items.single()

        assertThat(item.storeId).isEqualTo(storeId)
        assertThat(item.brandName).isEqualTo("스타벅스")
        assertThat(item.regionName).isEqualTo("강원특별자치도 춘천시 동면 감정리")
        assertThat(item.matchReason).containsExactly(StoreSearchTermKind.MENU_NAME)
        // 매장당 최대 3개, 동점은 메뉴명 오름차순이다.
        assertThat(item.matchedMenus.map { it.name }).containsExactly("라떼 A", "라떼 B", "라떼 C")
        // 좌표가 없으면 거리 항은 표시하지 않는다.
        assertThat(item.distanceMeters).isNull()
        assertThat(item.open).isTrue()
    }

    @Test
    fun `a store matched by name alone has no matched menus and keeps its place`() {
        val byName = fixture.indexStore(name = "라떼 전문점")
        val byMenu = fixture.indexStore(name = "커피집", menus = listOf("라떼"))

        val items = search.search(command(query = "라떼")).items

        assertThat(items.map { it.storeId }).containsExactly(byName, byMenu)
        assertThat(items.first().matchedMenus).isEmpty()
        assertThat(items.first().brandName).isNull()
        assertThat(items.first().regionName).isNull()
        assertThat(items.last().matchedMenus.map { it.name }).containsExactly("라떼")
    }

    @Test
    fun `distance is reported only when the request carried coordinates`() {
        fixture.indexStore(
            name = "스타벅스 춘천점",
            longitude = StoreSearchIndexTestFixture.CHUNCHEON_LONGITUDE,
            latitude = StoreSearchIndexTestFixture.CHUNCHEON_LATITUDE,
        )

        val withoutCoordinates = search.search(command(query = "스타벅스"))
        assertThat(withoutCoordinates.distanceAvailable).isFalse()
        assertThat(withoutCoordinates.items.single().distanceMeters).isNull()

        val withCoordinates =
            search.search(
                command(
                    query = "스타벅스",
                    sort = "distance",
                    latitude = StoreSearchIndexTestFixture.SEOUL_LATITUDE.toString(),
                    longitude = StoreSearchIndexTestFixture.SEOUL_LONGITUDE.toString(),
                ),
            )
        assertThat(withCoordinates.distanceAvailable).isTrue()
        assertThat(withCoordinates.items.single().distanceMeters).isGreaterThan(0L)

        // 반경 밖은 결과에서 빠진다. 좌표를 준 것과 반경을 준 것은 별개다.
        val bounded =
            search.search(
                command(
                    query = "스타벅스",
                    sort = "distance",
                    latitude = StoreSearchIndexTestFixture.SEOUL_LATITUDE.toString(),
                    longitude = StoreSearchIndexTestFixture.SEOUL_LONGITUDE.toString(),
                    radiusMeters = "10000",
                ),
            )
        assertThat(bounded.items).isEmpty()
    }

    @Test
    fun `no result is an empty page rather than a failure`() {
        fixture.indexStore(name = "스타벅스 1호점")

        val page = search.search(command(query = "존재하지않는이름"))

        assertThat(page.items).isEmpty()
        assertThat(page.nextCursor).isNull()
        assertThat(
            meterRegistry
                .get("beanflow.discovery.search.empty")
                .tag("sort", "RELEVANCE")
                .counter()
                .count(),
        ).isGreaterThanOrEqualTo(1.0)
    }

    @Test
    fun `a search writes no audit record, no event and leaks no query text into a metric tag`() {
        fixture.indexStore(
            name = "스타벅스 1호점",
            region = listOf(StoreSearchTermKind.REGION_SIGUNGU to "강남구"),
        )
        val auditsBefore = countOf("operations_audit_record")
        val eventsBefore = countOf("event_publication")
        val secret = "강남 스타벅스"

        val page = search.search(command(query = secret, limit = "5"))

        assertThat(page.items).hasSize(1)
        assertThat(countOf("operations_audit_record")).isEqualTo(auditsBefore)
        assertThat(countOf("event_publication")).isEqualTo(eventsBefore)
        // 검색어는 색인에도, 로그에도, 태그에도 남지 않는다(구현 불변식 17).
        val tagValues = meterRegistry.meters.flatMap { meter -> meter.id.tags.map { it.value } }
        assertThat(tagValues).noneSatisfy { value ->
            assertThat(value).contains("스타벅스")
        }
        assertThat(meterRegistry.get("beanflow.discovery.search.tokens").summary().count()).isGreaterThanOrEqualTo(1L)
        assertThat(
            meterRegistry
                .get("beanflow.discovery.search.count")
                .tag("outcome", "SUCCEEDED")
                .counter()
                .count(),
        ).isGreaterThanOrEqualTo(1.0)
    }

    private fun countOf(table: String): Long = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!

    private fun command(
        query: String? = "스타벅스",
        sort: String? = null,
        latitude: String? = null,
        longitude: String? = null,
        radiusMeters: String? = null,
        openOnly: String? = null,
        cursor: String? = null,
        limit: String? = null,
    ) = SearchStoresCommand(
        query = query,
        sort = sort,
        latitude = latitude,
        longitude = longitude,
        radiusMeters = radiusMeters,
        openOnly = openOnly,
        cursor = cursor,
        limit = limit,
        now = clock.instant(),
    )
}

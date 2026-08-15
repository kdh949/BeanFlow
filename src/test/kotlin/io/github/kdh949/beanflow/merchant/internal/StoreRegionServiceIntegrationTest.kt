package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.merchant.api.AssignStoreRegionCommand
import io.github.kdh949.beanflow.merchant.api.RegionCatalogQueryOperations
import io.github.kdh949.beanflow.merchant.api.StoreRegionOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.ReplaceBrandSearchTermsCommand
import io.github.kdh949.beanflow.shared.api.ReplaceStoreSearchTermsCommand
import io.github.kdh949.beanflow.shared.api.StoreSearchIndexOperations
import io.github.kdh949.beanflow.shared.api.StoreSearchTermEntry
import io.github.kdh949.beanflow.shared.api.StoreSearchTermKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The store region write path against PostgreSQL 17.
 *
 * A region lives in two places once it is written — `merchant_store_discovery_profile.region_code`
 * and the store's `REGION_*` terms — so the tests that matter are the ones that force the two
 * apart: an index failure must leave the old region behind, and a move between regions of
 * different depth must not leave a stale `REGION_RI` row.
 */
@Import(TestcontainersConfiguration::class, StoreRegionServiceIntegrationTest.FailableIndexConfiguration::class)
@SpringBootTest(
    properties = [
        "beanflow.search-index-coverage.initial-delay-ms=3600000",
        "beanflow.brand-command.retention.initial-delay-ms=3600000",
        "beanflow.store-region-command.retention.initial-delay-ms=3600000",
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class StoreRegionServiceIntegrationTest {
    @TestConfiguration
    internal class FailableIndexConfiguration {
        @Bean
        @Primary
        fun failableStoreSearchIndex(delegate: io.github.kdh949.beanflow.discovery.internal.StoreSearchIndexService) =
            FailableStoreSearchIndex(delegate)
    }

    internal class FailableStoreSearchIndex(
        private val delegate: StoreSearchIndexOperations,
    ) : StoreSearchIndexOperations {
        var failStoreWrites = false

        override fun replaceStoreTerms(command: ReplaceStoreSearchTermsCommand) {
            if (failStoreWrites) throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Forced search index failure")
            delegate.replaceStoreTerms(command)
        }

        override fun replaceBrandTerms(command: ReplaceBrandSearchTermsCommand) = delegate.replaceBrandTerms(command)
    }

    @Autowired
    private lateinit var regions: StoreRegionOperations

    @Autowired
    private lateinit var regionQueries: RegionCatalogQueryOperations

    @Autowired
    private lateinit var searchIndex: StoreSearchIndexOperations

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val transactions by lazy { TransactionTemplate(transactionManager) }

    private val failableIndex get() = searchIndex as FailableStoreSearchIndex

    @BeforeEach
    fun clearStoresAndTerms() {
        failableIndex.failStoreWrites = false
        jdbc.update("DELETE FROM merchant_store_region_command")
        jdbc.update("DELETE FROM discovery_store_search_term")
        jdbc.update("DELETE FROM merchant_menu")
        jdbc.update("DELETE FROM merchant_store_discovery_profile")
        jdbc.update("DELETE FROM merchant_store")
    }

    @Test
    fun `assigning a region writes one term per level the region actually has`() {
        val storeId = insertStore("역삼점")

        val assignment = assign(storeId, YEOKSAM)

        assertThat(assignment.region.fullName).isEqualTo("서울특별시 강남구 역삼동")
        assertThat(regionCode(storeId)).isEqualTo(YEOKSAM)
        assertThat(regionTerms(storeId)).containsExactlyInAnyOrder(
            "REGION_SIDO" to "서울특별시",
            "REGION_SIGUNGU" to "강남구",
            "REGION_EUPMYEONDONG" to "역삼동",
        )
    }

    @Test
    fun `a region with a ri gets four terms and keeps the parent eup myeon name`() {
        val storeId = insertStore("돌산점")

        assign(storeId, GUNNAE_RI)

        // 리 행의 eupmyeondong은 상위 읍·면 이름을 유지한다. 리에 있는 매장이 읍 이름과 리 이름
        // 양쪽으로 검색되는 것이 ADR-112 리 Amendment의 요점이다.
        assertThat(regionTerms(storeId)).containsExactlyInAnyOrder(
            "REGION_SIDO" to "전남광주통합특별시",
            "REGION_SIGUNGU" to "여수시",
            "REGION_EUPMYEONDONG" to "돌산읍",
            "REGION_RI" to "군내리",
        )
    }

    @Test
    fun `moving from a ri region to a dong region removes the stale ri term`() {
        val storeId = insertStore("이사하는 매장")
        assign(storeId, GUNNAE_RI)
        assertThat(regionTerms(storeId)).hasSize(4)

        assign(storeId, YEOKSAM, key = "region-key-move-0001")

        assertThat(regionCode(storeId)).isEqualTo(YEOKSAM)
        assertThat(regionTerms(storeId).map { it.first }).doesNotContain("REGION_RI")
        assertThat(regionTerms(storeId)).hasSize(3)
    }

    @Test
    fun `a sido level code is accepted and yields the single term it has`() {
        val storeId = insertStore("시도만 지정한 매장")

        assign(storeId, SEOUL)

        assertThat(regionTerms(storeId)).containsExactly("REGION_SIDO" to "서울특별시")
    }

    @Test
    fun `a store cannot be left without a region once the coverage gate is in place`() {
        val storeId = insertStore("커버리지 매장")

        // V62가 region_code를 NOT NULL로 올린 뒤에는 지역을 비우는 것 자체가 불가능하다.
        // 그래서 해제 명령이 없고 명령 어휘에도 CLEAR가 없다.
        assertThatThrownBy {
            jdbc.update("UPDATE merchant_store_discovery_profile SET region_code = NULL WHERE store_id = ?", storeId)
        }.isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
    }

    @Test
    fun `assigning a region leaves the store's other terms alone`() {
        val storeId = insertStore("이름 유지 매장")
        insertStoreNameTerm(storeId, "이름 유지 매장")

        assign(storeId, YEOKSAM)

        assertThat(termKinds(storeId)).contains("STORE_NAME")
        assertThat(countTerms(storeId, "STORE_NAME")).isEqualTo(1)
    }

    @Test
    fun `a forced index failure rolls the region back to the one the store already had`() {
        val storeId = insertStore("색인 실패 매장")
        assign(storeId, YEOKSAM)

        failableIndex.failStoreWrites = true
        assertThatThrownBy { assign(storeId, GUNNAE_RI, key = "region-key-fail-0001") }
            .isInstanceOf(DomainFailure::class.java)

        // 색인만 실패하고 region_code가 남았다면 매장은 옮겨 간 지역으로 검색되지 않으면서
        // 프로필에는 그 지역이 적힌 상태가 된다. 지역 자체가 되돌아가야 한다.
        assertThat(regionCode(storeId)).isEqualTo(YEOKSAM)
        assertThat(regionTerms(storeId)).containsExactlyInAnyOrder(
            "REGION_SIDO" to "서울특별시",
            "REGION_SIGUNGU" to "강남구",
            "REGION_EUPMYEONDONG" to "역삼동",
        )
        assertThat(commandCount()).isEqualTo(1)
    }

    @Test
    fun `a repeated command returns the first result without running again`() {
        val storeId = insertStore("재실행 매장")
        val first = assign(storeId, YEOKSAM)

        val second = assign(storeId, YEOKSAM)

        assertThat(second).isEqualTo(first)
        assertThat(commandCount()).isEqualTo(1)
    }

    @Test
    fun `the same key with a different region is a reuse rather than a second assignment`() {
        val storeId = insertStore("키 재사용 매장")
        assign(storeId, YEOKSAM)

        assertThatThrownBy { assign(storeId, GUNNAE_RI) }
            .isInstanceOf(DomainFailure::class.java)
            .extracting { (it as DomainFailure).code }
            .isEqualTo(FailureCode.IDEMPOTENCY_KEY_REUSED)
        assertThat(regionCode(storeId)).isEqualTo(YEOKSAM)
    }

    @Test
    fun `an unknown store and an unknown region code are both rejected`() {
        assertThatThrownBy { assign(UUID.randomUUID(), YEOKSAM) }
            .isInstanceOf(DomainFailure::class.java)
            .extracting { (it as DomainFailure).code }
            .isEqualTo(FailureCode.RESOURCE_NOT_FOUND)

        val storeId = insertStore("없는 지역 매장")
        assertThatThrownBy { assign(storeId, "9999999999", key = "region-key-unknown-01") }
            .isInstanceOf(DomainFailure::class.java)
            .extracting { (it as DomainFailure).code }
            .isEqualTo(FailureCode.RESOURCE_NOT_FOUND)
        assertThat(regionCode(storeId)).isEqualTo(SEOUL)
    }

    @Test
    fun `a malformed region code is rejected before any row is read`() {
        val storeId = insertStore("잘못된 코드 매장")

        assertThatThrownBy { assign(storeId, "116801") }
            .isInstanceOf(DomainFailure::class.java)
            .extracting { (it as DomainFailure).code }
            .isEqualTo(FailureCode.INVALID_REQUEST)
        assertThat(commandCount()).isZero()
    }

    @Test
    fun `the command refuses to run outside a caller transaction`() {
        val storeId = insertStore("독립 transaction 매장")

        assertThatThrownBy {
            regions.assignStoreRegion(AssignStoreRegionCommand(ACTOR, "region-key-no-tx-01", storeId, YEOKSAM, NOW))
        }.isInstanceOf(IllegalTransactionStateException::class.java)
    }

    @Test
    fun `concurrent assignments for one store agree on the region and its terms`() {
        val storeId = insertStore("동시 변경 매장")
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)

        val outcomes =
            try {
                pool
                    .invokeAll(
                        listOf(YEOKSAM, GUNNAE_RI).mapIndexed { index, code ->
                            Callable {
                                barrier.await(10, TimeUnit.SECONDS)
                                assign(storeId, code, key = "region-key-race-000$index")
                            }
                        },
                    ).map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

        // 어느 쪽이 이겼는지는 시점 문제다. 확인할 것은 마지막 지역과 term이 서로 다른 명령의
        // 결과로 섞이지 않는다는 것이다. 행 잠금이 두 명령을 직렬화한다.
        val finalCode = regionCode(storeId)
        assertThat(outcomes.map { it.region.code }).contains(finalCode)
        val expected = regionQueries.find(finalCode!!)!!
        assertThat(regionTerms(storeId).map { it.second })
            .containsExactlyInAnyOrderElementsOf(
                listOf(expected.sido, expected.sigungu, expected.eupmyeondong, expected.ri).filter { it.isNotBlank() },
            )
    }

    @Test
    fun `the catalog pages the whole vocabulary without gaps or duplicates`() {
        val first = regionQueries.search(query = null, afterFullName = null, afterCode = null, limit = 3)
        assertThat(first.regions).hasSize(3)

        val second = regionQueries.search(query = null, afterFullName = first.nextFullName, afterCode = first.nextCode, limit = 3)

        val combined = first.regions + second.regions
        assertThat(combined.map { it.code }).doesNotHaveDuplicates()
        // 정렬은 데이터베이스 collation의 몫이다. 여기서 확인하는 성질은 keyset 비교와 ORDER BY가
        // 같은 순서를 쓰기 때문에 한 번에 읽은 결과와 나눠 읽은 결과가 같다는 것이다.
        assertThat(combined)
            .isEqualTo(regionQueries.search(query = null, afterFullName = null, afterCode = null, limit = 6).regions)
    }

    @Test
    fun `a multi word query requires every word to appear in the full name`() {
        val page = regionQueries.search(query = "서울 강남 역삼", afterFullName = null, afterCode = null, limit = 10)

        assertThat(page.regions.map { it.code }).contains(YEOKSAM)
        assertThat(page.regions).allSatisfy { region ->
            assertThat(region.fullName).contains("서울", "강남", "역삼")
        }
    }

    @Test
    fun `a query that is only wildcards matches nothing instead of everything`() {
        val page = regionQueries.search(query = "%", afterFullName = null, afterCode = null, limit = 10)

        assertThat(page.regions).isEmpty()
    }

    private fun assign(
        storeId: UUID,
        regionCode: String,
        key: String = "region-key-0001",
    ) = transactions.execute {
        regions.assignStoreRegion(AssignStoreRegionCommand(ACTOR, key, storeId, regionCode, NOW))
    }!!

    private fun regionCode(storeId: UUID): String? =
        jdbc
            .queryForList(
                "SELECT region_code FROM merchant_store_discovery_profile WHERE store_id = ?",
                String::class.java,
                storeId,
            ).firstOrNull()

    private fun regionTerms(storeId: UUID): List<Pair<String, String>> =
        jdbc.query(
            """
            SELECT term_kind, display_text
              FROM discovery_store_search_term
             WHERE store_id = ? AND term_kind LIKE 'REGION%'
            """.trimIndent(),
            { row, _ -> row.getString("term_kind") to row.getString("display_text") },
            storeId,
        )

    private fun termKinds(storeId: UUID): List<String> =
        jdbc.query(
            "SELECT DISTINCT term_kind FROM discovery_store_search_term WHERE store_id = ?",
            { row, _ -> row.getString("term_kind") },
            storeId,
        )

    private fun countTerms(
        storeId: UUID,
        kind: String,
    ): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM discovery_store_search_term WHERE store_id = ? AND term_kind = ?",
            Long::class.java,
            storeId,
            kind,
        ) ?: 0

    private fun commandCount(): Long = jdbc.queryForObject("SELECT count(*) FROM merchant_store_region_command", Long::class.java) ?: 0

    private fun insertStoreNameTerm(
        storeId: UUID,
        name: String,
    ) {
        transactions.execute {
            searchIndex.replaceStoreTerms(
                ReplaceStoreSearchTermsCommand(
                    storeId = storeId,
                    kinds = setOf(StoreSearchTermKind.STORE_NAME),
                    terms = listOf(StoreSearchTermEntry(StoreSearchTermKind.STORE_NAME, name)),
                ),
            )
        }
    }

    private fun insertStore(name: String): UUID {
        val storeId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
            storeId,
        )
        jdbc.update(
            """
            INSERT INTO merchant_store_discovery_profile (store_id, name, location, region_code)
            VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
            """.trimIndent(),
            storeId,
            name,
            127.0361,
            37.5006,
            SEOUL,
        )
        return storeId
    }

    private companion object {
        val ACTOR: UUID = UUID.fromString("70000000-0000-0000-0000-000000000002")
        val NOW: Instant = Instant.parse("2026-08-15T00:00:00Z")

        /** 서울특별시 강남구 역삼동 — 리가 없어 term이 3행이다. */
        const val YEOKSAM = "1168010100"

        /** 전남광주통합특별시 여수시 돌산읍 군내리 — 리가 있어 term이 4행이다. */
        const val GUNNAE_RI = "1213025021"

        /** 시도 행. 하위 계층이 비어 있어 term이 1행이다. */
        const val SEOUL = "1100000000"
    }
}

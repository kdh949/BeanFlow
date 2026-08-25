package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.merchant.api.AssignStoreBrandCommand
import io.github.kdh949.beanflow.merchant.api.BrandStatus
import io.github.kdh949.beanflow.merchant.api.ClearStoreBrandCommand
import io.github.kdh949.beanflow.merchant.api.CreateBrandCommand
import io.github.kdh949.beanflow.merchant.api.StoreBrandOperations
import io.github.kdh949.beanflow.merchant.api.StoreBrandQueryOperations
import io.github.kdh949.beanflow.merchant.api.UpdateBrandCommand
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
 * The operator brand write path against PostgreSQL 17.
 *
 * The assertions that matter here are the ones about atomicity. A brand name lives in two places
 * once it is written — `merchant_brand` and every assigned store's `BRAND_NAME` term — and the
 * whole design of ADR-112 5절 exists so those two cannot drift. So the index is deliberately
 * forced to fail in one test, and the check is not that an error surfaced but that the brand row
 * still holds its old name afterwards.
 */
@Import(TestcontainersConfiguration::class, StoreBrandServiceIntegrationTest.FailableIndexConfiguration::class)
@BeanflowIsolatedSpringContext("verifies startup, DDL, or committed state across a transaction boundary")
@SpringBootTest(
    properties = [
        "beanflow.search-index-coverage.initial-delay-ms=3600000",
        "beanflow.brand-command.retention.initial-delay-ms=3600000",
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
    ],
)
internal class StoreBrandServiceIntegrationTest {
    /**
     * Replaces the index port with one that can be told to fail on its next brand write.
     *
     * The delegate is the concrete service rather than the interface so that this bean does not
     * depend on itself.
     */
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
        var failBrandWrites = false

        override fun replaceStoreTerms(command: ReplaceStoreSearchTermsCommand) = delegate.replaceStoreTerms(command)

        override fun replaceBrandTerms(command: ReplaceBrandSearchTermsCommand) {
            if (failBrandWrites) throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Forced search index failure")
            delegate.replaceBrandTerms(command)
        }
    }

    @Autowired
    private lateinit var brands: StoreBrandOperations

    @Autowired
    private lateinit var brandQueries: StoreBrandQueryOperations

    @Autowired
    private lateinit var searchIndex: StoreSearchIndexOperations

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val transactions by lazy { TransactionTemplate(transactionManager) }

    private val failableIndex get() = searchIndex as FailableStoreSearchIndex

    @BeforeEach
    fun clearBrandsStoresAndTerms() {
        failableIndex.failBrandWrites = false
        jdbc.update("DELETE FROM merchant_brand_command")
        jdbc.update("DELETE FROM discovery_store_search_term")
        jdbc.update("UPDATE merchant_store SET brand_id = NULL")
        jdbc.update("DELETE FROM merchant_menu")
        jdbc.update("DELETE FROM merchant_store_discovery_profile")
        jdbc.update("DELETE FROM merchant_store")
        jdbc.update("DELETE FROM merchant_brand")
    }

    @Test
    fun `assigning a brand writes the store's brand term next to the terms it already had`() {
        val storeId = insertStore("강남역점")
        indexStoreName(storeId, "강남역점")
        val brandId = create("스타벅스").brandId

        val assignment = transactions.execute { brands.assignStoreBrand(assign(storeId, brandId)) }!!

        assertThat(assignment.brandId).isEqualTo(brandId)
        assertThat(assignment.brandName).isEqualTo("스타벅스")
        assertThat(storeBrandId(storeId)).isEqualTo(brandId)
        assertThat(terms(storeId)).containsExactlyInAnyOrder(
            "STORE_NAME" to "강남역점",
            "BRAND_NAME" to "스타벅스",
        )
    }

    @Test
    fun `clearing a brand removes only the brand term`() {
        val storeId = insertStore("강남역점")
        indexStoreName(storeId, "강남역점")
        val brandId = create("스타벅스").brandId
        transactions.execute { brands.assignStoreBrand(assign(storeId, brandId)) }

        val cleared = transactions.execute { brands.clearStoreBrand(clear(storeId)) }!!

        assertThat(cleared.brandId).isNull()
        assertThat(cleared.brandName).isNull()
        assertThat(storeBrandId(storeId)).isNull()
        assertThat(terms(storeId)).containsExactly("STORE_NAME" to "강남역점")
    }

    @Test
    fun `renaming a brand replaces every assigned store's term in the same transaction`() {
        val first = insertStore("강남역점")
        val second = insertStore("삼청점")
        val untouched = insertStore("다른 브랜드 매장")
        val brandId = create("스타벅스").brandId
        val otherBrandId = create("블루보틀").brandId
        transactions.execute { brands.assignStoreBrand(assign(first, brandId)) }
        transactions.execute { brands.assignStoreBrand(assign(second, brandId)) }
        transactions.execute { brands.assignStoreBrand(assign(untouched, otherBrandId)) }

        val renamed = transactions.execute { brands.update(rename(brandId, "스타벅스코리아")) }!!

        assertThat(renamed.name).isEqualTo("스타벅스코리아")
        assertThat(renamed.assignedStoreCount).isEqualTo(2)
        assertThat(terms(first)).containsExactly("BRAND_NAME" to "스타벅스코리아")
        assertThat(terms(second)).containsExactly("BRAND_NAME" to "스타벅스코리아")
        // 다른 브랜드 매장은 fan-out 대상이 아니다. 이름 변경이 브랜드 경계를 넘지 않는다.
        assertThat(terms(untouched)).containsExactly("BRAND_NAME" to "블루보틀")
        // 종류 단위 교체이므로 매장마다 BRAND_NAME term은 여전히 한 행이다.
        assertThat(countTerms(first, "BRAND_NAME")).isEqualTo(1)
    }

    @Test
    fun `a failed index update rolls the brand change back instead of leaving it unsearchable`() {
        val storeId = insertStore("강남역점")
        val brandId = create("스타벅스").brandId
        transactions.execute { brands.assignStoreBrand(assign(storeId, brandId)) }
        failableIndex.failBrandWrites = true

        assertThatThrownBy { transactions.execute { brands.update(rename(brandId, "스타벅스코리아")) } }
            .isInstanceOf(DomainFailure::class.java)
            .extracting { (it as DomainFailure).code }
            .isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)

        failableIndex.failBrandWrites = false
        val current = brandQueries.find(brandId)!!
        assertThat(current.name).isEqualTo("스타벅스")
        assertThat(terms(storeId)).containsExactly("BRAND_NAME" to "스타벅스")
        // 원장도 함께 rollback돼야 같은 키로 다시 시도할 수 있다.
        assertThat(commandCount()).isEqualTo(2)
    }

    @Test
    fun `a rename racing a clear of one of its stores leaves no duplicate or orphan brand term`() {
        val kept = insertStore("강남역점")
        val cleared = insertStore("삼청점")
        val brandId = create("스타벅스").brandId
        transactions.execute { brands.assignStoreBrand(assign(kept, brandId)) }
        transactions.execute { brands.assignStoreBrand(assign(cleared, brandId)) }

        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            pool
                .invokeAll(
                    listOf(
                        Callable {
                            barrier.await(10, TimeUnit.SECONDS)
                            runCatching { transactions.execute { brands.update(rename(brandId, "스타벅스코리아")) } }
                        },
                        Callable {
                            barrier.await(10, TimeUnit.SECONDS)
                            runCatching { transactions.execute { brands.clearStoreBrand(clear(cleared)) } }
                        },
                    ),
                ).forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        // 어느 쪽이 먼저 commit되는지는 시점 문제다. 확인할 것은 색인이 브랜드 열과 정확히
        // 일치한다는 것이다. 브랜드를 가진 매장은 현재 이름의 term을 정확히 하나 갖고, 브랜드를
        // 잃은 매장에는 낡은 term이 남지 않는다.
        val currentName = brandQueries.find(brandId)!!.normalizedName
        listOf(kept, cleared).forEach { storeId ->
            val brandTerms = terms(storeId).filter { it.first == "BRAND_NAME" }
            if (storeBrandId(storeId) == null) {
                assertThat(brandTerms).describedAs("cleared store keeps no brand term").isEmpty()
            } else {
                assertThat(brandTerms)
                    .describedAs("assigned store keeps exactly one current term")
                    .containsExactly("BRAND_NAME" to currentName)
            }
        }
    }

    @Test
    fun `a duplicate active brand name is rejected and an archived one frees the name`() {
        val brandId = create("스타벅스").brandId

        assertThatThrownBy { create("  스타벅스  ") }
            .isInstanceOf(DomainFailure::class.java)
            .extracting { (it as DomainFailure).code }
            .isEqualTo(FailureCode.BRAND_NAME_ALREADY_IN_USE)

        transactions.execute { brands.update(archive(brandId)) }
        val reused = create("스타벅스")
        assertThat(reused.brandId).isNotEqualTo(brandId)
        assertThat(reused.status).isEqualTo(BrandStatus.ACTIVE)
    }

    @Test
    fun `a brand that still has stores cannot be archived`() {
        val storeId = insertStore("강남역점")
        val brandId = create("스타벅스").brandId
        transactions.execute { brands.assignStoreBrand(assign(storeId, brandId)) }

        assertThatThrownBy { transactions.execute { brands.update(archive(brandId)) } }
            .isInstanceOf(DomainFailure::class.java)
            .extracting { (it as DomainFailure).code }
            .isEqualTo(FailureCode.BRAND_STATE_CONFLICT)

        transactions.execute { brands.clearStoreBrand(clear(storeId)) }
        assertThat(transactions.execute { brands.update(archive(brandId)) }!!.status).isEqualTo(BrandStatus.ARCHIVED)
    }

    @Test
    fun `the fan-out limit stops both the rename and the assignment that would exceed it`() {
        val brandId = create("스타벅스").brandId
        // 매장 쓰기 API가 없어 매장은 시드와 직접 DML로 생긴다. 상한을 넘긴 상태는 그 경로로
        // 실제로 도달할 수 있으므로 여기서도 그렇게 만든다.
        insertStoresAssignedTo(brandId, StoreBrandService.MAX_BRAND_FANOUT)
        val oneMore = insertStore("한 곳 더")

        assertThatThrownBy { transactions.execute { brands.assignStoreBrand(assign(oneMore, brandId)) } }
            .isInstanceOf(DomainFailure::class.java)
            .extracting { (it as DomainFailure).code }
            .isEqualTo(FailureCode.BRAND_FANOUT_LIMIT_EXCEEDED)
        assertThat(storeBrandId(oneMore)).isNull()

        jdbc.update("UPDATE merchant_store SET brand_id = ? WHERE id = ?", brandId, oneMore)
        assertThatThrownBy { transactions.execute { brands.update(rename(brandId, "스타벅스코리아")) } }
            .isInstanceOf(DomainFailure::class.java)
            .extracting { (it as DomainFailure).code }
            .isEqualTo(FailureCode.BRAND_FANOUT_LIMIT_EXCEEDED)
        assertThat(brandQueries.find(brandId)!!.name).isEqualTo("스타벅스")
        assertThat(countTerms("BRAND_NAME")).isZero()
    }

    @Test
    fun `a repeated command returns the first result and a reused key with another payload is rejected`() {
        val first = create("스타벅스", idempotencyKey = "brand-create-0001")
        val replay = create("스타벅스", idempotencyKey = "brand-create-0001")

        assertThat(replay).isEqualTo(first)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_brand", Long::class.java)).isEqualTo(1)

        assertThatThrownBy { create("블루보틀", idempotencyKey = "brand-create-0001") }
            .isInstanceOf(DomainFailure::class.java)
            .extracting { (it as DomainFailure).code }
            .isEqualTo(FailureCode.IDEMPOTENCY_KEY_REUSED)

        // 같은 키를 다른 종류의 명령에 쓰는 것도 재사용이다.
        val storeId = insertStore("강남역점")
        assertThatThrownBy {
            transactions.execute { brands.assignStoreBrand(assign(storeId, first.brandId, "brand-create-0001")) }
        }.isInstanceOf(DomainFailure::class.java)
            .extracting { (it as DomainFailure).code }
            .isEqualTo(FailureCode.IDEMPOTENCY_KEY_REUSED)
    }

    @Test
    fun `an expected version that no longer matches is a conflict rather than a silent overwrite`() {
        val created = create("스타벅스")
        transactions.execute { brands.update(rename(created.brandId, "스타벅스코리아")) }

        assertThatThrownBy {
            transactions.execute {
                brands.update(
                    UpdateBrandCommand(ACTOR, "brand-stale-0001", created.brandId, "다른 이름", null, created.version, NOW),
                )
            }
        }.isInstanceOf(DomainFailure::class.java)
            .extracting { (it as DomainFailure).code }
            .isEqualTo(FailureCode.BRAND_STATE_CONFLICT)
    }

    @Test
    fun `a name that normalizes to nothing is rejected instead of stored as an unsearchable brand`() {
        assertThatThrownBy { create("　  ") }
            .isInstanceOf(DomainFailure::class.java)
            .extracting { (it as DomainFailure).code }
            .isEqualTo(FailureCode.INVALID_REQUEST)

        // 전각과 대문자는 저장할 때 표시 원본을 유지하고 정규화 이름만 접힌다.
        val wide = create("ＳＴＡＲ")
        assertThat(wide.name).isEqualTo("ＳＴＡＲ")
        assertThat(wide.normalizedName).isEqualTo("star")
    }

    @Test
    fun `a brand write outside a transaction is refused rather than opening one of its own`() {
        assertThatThrownBy { brands.create(CreateBrandCommand(ACTOR, "brand-no-tx-0001", "스타벅스", NOW)) }
            .isInstanceOf(IllegalTransactionStateException::class.java)
    }

    @Test
    fun `paging by normalized name and id neither repeats nor drops a brand`() {
        create("블루보틀")
        create("스타벅스")
        create("이디야")

        // 한글 정렬 순서는 DB collation이 정하므로 특정 순서를 고정하지 않는다. 고정할 것은
        // keyset 비교와 ORDER BY가 같은 collation을 쓴다는 결과, 즉 쪽을 넘겨도 누락과 중복이
        // 없고 한 번에 읽은 순서와 같다는 것이다.
        val whole = brandQueries.list(null, null, 10)
        assertThat(whole.brands).hasSize(3)
        assertThat(whole.nextNormalizedName).isNull()
        assertThat(whole.nextBrandId).isNull()

        val firstPage = brandQueries.list(null, null, 2)
        assertThat(firstPage.brands).hasSize(2)
        assertThat(firstPage.nextNormalizedName).isEqualTo(firstPage.brands.last().normalizedName)

        val secondPage = brandQueries.list(firstPage.nextNormalizedName, firstPage.nextBrandId, 2)
        assertThat(secondPage.brands).hasSize(1)
        assertThat(secondPage.nextNormalizedName).isNull()

        assertThat((firstPage.brands + secondPage.brands).map { it.name })
            .isEqualTo(whole.brands.map { it.name })
    }

    private fun create(
        name: String,
        idempotencyKey: String = "brand-create-${UUID.randomUUID()}".take(64),
    ) = transactions.execute { brands.create(CreateBrandCommand(ACTOR, idempotencyKey, name, NOW)) }!!

    private fun assign(
        storeId: UUID,
        brandId: UUID,
        idempotencyKey: String = "brand-assign-${UUID.randomUUID()}".take(64),
    ) = AssignStoreBrandCommand(ACTOR, idempotencyKey, storeId, brandId, NOW)

    private fun clear(
        storeId: UUID,
        idempotencyKey: String = "brand-clear-${UUID.randomUUID()}".take(64),
    ) = ClearStoreBrandCommand(ACTOR, idempotencyKey, storeId, NOW)

    private fun rename(
        brandId: UUID,
        name: String,
        idempotencyKey: String = "brand-rename-${UUID.randomUUID()}".take(64),
    ) = UpdateBrandCommand(ACTOR, idempotencyKey, brandId, name, null, null, NOW)

    private fun archive(
        brandId: UUID,
        idempotencyKey: String = "brand-archive-${UUID.randomUUID()}".take(64),
    ) = UpdateBrandCommand(ACTOR, idempotencyKey, brandId, null, BrandStatus.ARCHIVED, null, NOW)

    private fun indexStoreName(
        storeId: UUID,
        name: String,
    ) = transactions.executeWithoutResult {
        searchIndex.replaceStoreTerms(
            ReplaceStoreSearchTermsCommand(
                storeId,
                setOf(StoreSearchTermKind.STORE_NAME),
                listOf(StoreSearchTermEntry(StoreSearchTermKind.STORE_NAME, name)),
            ),
        )
    }

    private fun terms(storeId: UUID): List<Pair<String, String>> =
        jdbc.query(
            "SELECT term_kind, term_normalized FROM discovery_store_search_term WHERE store_id = ?",
            { row, _ -> row.getString("term_kind") to row.getString("term_normalized") },
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

    private fun countTerms(kind: String): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM discovery_store_search_term WHERE term_kind = ?",
            Long::class.java,
            kind,
        ) ?: 0

    private fun commandCount(): Long = jdbc.queryForObject("SELECT count(*) FROM merchant_brand_command", Long::class.java) ?: 0

    private fun storeBrandId(storeId: UUID): UUID? =
        jdbc.queryForObject("SELECT brand_id FROM merchant_store WHERE id = ?", UUID::class.java, storeId)

    private fun insertStore(name: String): UUID {
        val storeId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
            storeId,
        )
        jdbc.update(
            """
            INSERT INTO merchant_store_discovery_profile (store_id, name, location, region_code)
            VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, '1168010100')
            """.trimIndent(),
            storeId,
            name,
            127.0361,
            37.5006,
        )
        return storeId
    }

    private fun insertStoresAssignedTo(
        brandId: UUID,
        count: Int,
    ) {
        repeat(count) { index ->
            val storeId = insertStore("상한 시험 매장 $index")
            jdbc.update("UPDATE merchant_store SET brand_id = ? WHERE id = ?", brandId, storeId)
        }
    }

    private companion object {
        val ACTOR: UUID = UUID.fromString("70000000-0000-0000-0000-000000000001")
        val NOW: Instant = Instant.parse("2026-08-15T00:00:00Z")
    }
}

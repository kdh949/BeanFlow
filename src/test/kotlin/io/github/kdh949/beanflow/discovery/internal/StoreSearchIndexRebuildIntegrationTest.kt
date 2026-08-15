package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.ReplaceBrandSearchTermsCommand
import io.github.kdh949.beanflow.shared.api.ReplaceStoreSearchTermsCommand
import io.github.kdh949.beanflow.shared.api.StoreSearchIndexOperations
import io.github.kdh949.beanflow.shared.api.StoreSearchTermEntry
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
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.util.UUID

/**
 * The search index write path against PostgreSQL 17.
 *
 * The point of these assertions is that the index holds exactly what the query path will look for.
 * The normalized text is produced by the Kotlin normalizer on every write, which is why the cases
 * SQL could not reproduce — the dotted capital I and the Greek final sigma — are pinned here.
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
internal class StoreSearchIndexRebuildIntegrationTest {
    @Autowired
    private lateinit var rebuild: StoreSearchIndexRebuildService

    @Autowired
    private lateinit var index: StoreSearchIndexOperations

    @Autowired
    private lateinit var coverage: StoreSearchIndexCoverageMetrics

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val transactions by lazy { TransactionTemplate(transactionManager) }

    @BeforeEach
    fun clearStoresAndTerms() {
        jdbc.update("DELETE FROM discovery_store_search_term")
        jdbc.update("DELETE FROM merchant_menu")
        jdbc.update("DELETE FROM merchant_store_discovery_profile")
        jdbc.update("DELETE FROM merchant_store")
    }

    @Test
    fun `rebuilding indexes every store and its available menus`() {
        val first = insertStore("스타벅스 강남점")
        val availableMenuId = insertMenu(first, "아메리카노", available = true)
        insertMenu(first, "품절 라떼", available = false)
        val second = insertStore("블루보틀 삼청점")

        val result = rebuild.rebuildAll()

        assertThat(result.indexedStoreCount).isEqualTo(2)
        assertThat(result.skippedStoreCount).isZero()
        assertThat(result.failedStoreIds).isEmpty()
        assertThat(result.complete).isTrue()

        assertThat(terms(first)).containsExactlyInAnyOrder(
            Triple("STORE_NAME", "스타벅스 강남점", null),
            Triple("MENU_NAME", "아메리카노", availableMenuId),
        )
        assertThat(terms(second)).containsExactly(Triple("STORE_NAME", "블루보틀 삼청점", null))
        assertThat(weightOf(first, "STORE_NAME")).isEqualTo(BigDecimal("1.00"))
        assertThat(weightOf(first, "MENU_NAME")).isEqualTo(BigDecimal("0.70"))
    }

    @Test
    fun `the coverage gauge reaches one only after every store is indexed`() {
        insertStore("스타벅스 강남점")
        insertStore("블루보틀 삼청점")

        assertThat(coverage.refresh()).isZero()

        rebuild.rebuildAll()

        assertThat(coverage.refresh()).isEqualTo(1.0)
        assertThat(meterRegistry.get("beanflow.discovery.search.index.coverage").gauge().value()).isEqualTo(1.0)

        // 색인을 거치지 않고 들어온 매장은 커버리지를 떨어뜨려 드러난다.
        insertStore("색인되지 않은 매장")
        assertThat(coverage.refresh()).isEqualTo(2.0 / 3.0)
    }

    @Test
    fun `indexed text is normalized by the same function the query path uses`() {
        val wide = insertStore(WIDE_STORE_NAME)
        val turkish = insertStore("İSTANBUL")
        val greek = insertStore("ΟΔΟΣ")

        rebuild.rebuildAll()

        assertThat(normalizedTerm(wide)).isEqualTo("star 버클")
        // SQL lower()는 "istanbul"과 마지막이 \u03C3인 "οδοσ"를 낸다. 색인이 그 값이었다면
        // 질의가 만드는 문자열과 어긋나 두 매장은 이름으로 검색되지 않는다(MD-2026-018).
        assertThat(normalizedTerm(turkish)).isEqualTo("i\u0307stanbul")
        assertThat(normalizedTerm(greek)).isEqualTo("οδο\u03C2")
        // 원문은 손대지 않고 그대로 보관한다.
        assertThat(displayTextOf(wide)).isEqualTo(WIDE_STORE_NAME)
    }

    @Test
    fun `rebuilding twice leaves the same rows and follows a direct name change`() {
        val storeId = insertStore("스타벅스 강남점")
        rebuild.rebuildAll()
        rebuild.rebuildAll()

        assertThat(terms(storeId)).hasSize(1)

        // API 밖에서 바뀐 이름은 재색인 전까지 색인에 반영되지 않는다. 이 한계는 숨기지 않는다.
        jdbc.update("UPDATE merchant_store_discovery_profile SET name = ? WHERE store_id = ?", "스타벅스 역삼점", storeId)
        assertThat(normalizedTerm(storeId)).isEqualTo("스타벅스 강남점")

        rebuild.rebuildAll()
        assertThat(normalizedTerm(storeId)).isEqualTo("스타벅스 역삼점")
    }

    @Test
    fun `a store without a searchable profile is reported as failed without stopping the pass`() {
        val broken = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
            broken,
        )
        val healthy = insertStore("블루보틀 삼청점")

        val result = rebuild.rebuildAll()

        assertThat(result.failedStoreIds).containsExactly(broken)
        assertThat(result.complete).isFalse()
        assertThat(result.indexedStoreCount).isEqualTo(1)
        assertThat(terms(healthy)).hasSize(1)
    }

    @Test
    fun `brand terms are replaced across every store of the brand and removed together`() {
        val first = insertStore("스타벅스 강남점")
        val second = insertStore("스타벅스 역삼점")
        rebuild.rebuildAll()

        transactions.executeWithoutResult {
            index.replaceBrandTerms(ReplaceBrandSearchTermsCommand(listOf(first, second), "스타벅스"))
        }
        assertThat(normalizedTerms(first, "BRAND_NAME")).containsExactly("스타벅스")
        assertThat(normalizedTerms(second, "BRAND_NAME")).containsExactly("스타벅스")
        assertThat(weightOf(first, "BRAND_NAME")).isEqualTo(BigDecimal("0.90"))

        transactions.executeWithoutResult {
            index.replaceBrandTerms(ReplaceBrandSearchTermsCommand(listOf(first, second), "스타박스"))
        }
        assertThat(normalizedTerms(first, "BRAND_NAME")).containsExactly("스타박스")

        transactions.executeWithoutResult {
            index.replaceBrandTerms(ReplaceBrandSearchTermsCommand(listOf(first), null))
        }
        assertThat(normalizedTerms(first, "BRAND_NAME")).isEmpty()
        assertThat(normalizedTerms(second, "BRAND_NAME")).containsExactly("스타박스")
        // 브랜드만 지워지고 매장명 term은 남는다.
        assertThat(normalizedTerms(first, "STORE_NAME")).containsExactly("스타벅스 강남점")
    }

    @Test
    fun `an index write outside a command transaction is rejected`() {
        val storeId = insertStore("스타벅스 강남점")

        assertThatThrownBy {
            index.replaceStoreTerms(
                ReplaceStoreSearchTermsCommand(
                    storeId,
                    setOf(StoreSearchTermKind.STORE_NAME),
                    listOf(StoreSearchTermEntry(StoreSearchTermKind.STORE_NAME, "스타벅스 강남점")),
                ),
            )
        }.isInstanceOf(IllegalTransactionStateException::class.java)
    }

    @Test
    fun `text that normalizes to nothing is rejected instead of silently skipped`() {
        val storeId = insertStore("스타벅스 강남점")

        assertThatThrownBy {
            transactions.executeWithoutResult {
                index.replaceBrandTerms(ReplaceBrandSearchTermsCommand(listOf(storeId), "\u3000"))
            }
        }.isInstanceOfSatisfying(DomainFailure::class.java) { failure ->
            assertThat(failure.code).isEqualTo(FailureCode.INVALID_REQUEST)
        }
    }

    @Test
    fun `region terms are written for every level a store has`() {
        val storeId = insertStore("스타벅스 강남점")
        rebuild.rebuildAll()

        transactions.executeWithoutResult {
            index.replaceStoreTerms(
                ReplaceStoreSearchTermsCommand(
                    storeId,
                    REGION_KINDS,
                    listOf(
                        StoreSearchTermEntry(StoreSearchTermKind.REGION_SIDO, "강원특별자치도"),
                        StoreSearchTermEntry(StoreSearchTermKind.REGION_SIGUNGU, "춘천시"),
                        StoreSearchTermEntry(StoreSearchTermKind.REGION_EUPMYEONDONG, "동면"),
                        StoreSearchTermEntry(StoreSearchTermKind.REGION_RI, "감정리"),
                    ),
                ),
            )
        }
        assertThat(normalizedTerms(storeId, "REGION_RI")).containsExactly("감정리")
        assertThat(weightOf(storeId, "REGION_RI")).isEqualTo(BigDecimal("0.80"))
        assertThat(weightOf(storeId, "REGION_EUPMYEONDONG")).isEqualTo(BigDecimal("0.80"))

        // 리가 없는 지역으로 옮기면 REGION_* term이 3행만 남는다.
        transactions.executeWithoutResult {
            index.replaceStoreTerms(
                ReplaceStoreSearchTermsCommand(
                    storeId,
                    REGION_KINDS,
                    listOf(
                        StoreSearchTermEntry(StoreSearchTermKind.REGION_SIDO, "서울특별시"),
                        StoreSearchTermEntry(StoreSearchTermKind.REGION_SIGUNGU, "강남구"),
                        StoreSearchTermEntry(StoreSearchTermKind.REGION_EUPMYEONDONG, "역삼동"),
                    ),
                ),
            )
        }
        assertThat(normalizedTerms(storeId, "REGION_RI")).isEmpty()
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM discovery_store_search_term WHERE store_id = ? AND term_kind LIKE 'REGION_%'",
                Long::class.java,
                storeId,
            ),
        ).isEqualTo(3)
    }

    private fun terms(storeId: UUID): List<Triple<String, String, UUID?>> =
        jdbc.query(
            "SELECT term_kind, term_normalized, source_id FROM discovery_store_search_term WHERE store_id = ?",
            { row, _ ->
                Triple(row.getString("term_kind"), row.getString("term_normalized"), row.getObject("source_id", UUID::class.java))
            },
            storeId,
        )

    private fun normalizedTerms(
        storeId: UUID,
        kind: String,
    ): List<String> =
        jdbc.query(
            "SELECT term_normalized FROM discovery_store_search_term WHERE store_id = ? AND term_kind = ?",
            { row, _ -> row.getString("term_normalized") },
            storeId,
            kind,
        )

    private fun normalizedTerm(storeId: UUID): String = normalizedTerms(storeId, "STORE_NAME").single()

    private fun displayTextOf(storeId: UUID): String? =
        jdbc.queryForObject(
            "SELECT display_text FROM discovery_store_search_term WHERE store_id = ? AND term_kind = 'STORE_NAME'",
            String::class.java,
            storeId,
        )

    private fun weightOf(
        storeId: UUID,
        kind: String,
    ): BigDecimal? =
        jdbc.queryForObject(
            "SELECT weight FROM discovery_store_search_term WHERE store_id = ? AND term_kind = ? LIMIT 1",
            BigDecimal::class.java,
            storeId,
            kind,
        )

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

    private fun insertMenu(
        storeId: UUID,
        name: String,
        available: Boolean,
    ): UUID {
        val menuId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO merchant_menu (id, store_id, name, base_price_krw, available, version)
            VALUES (?, ?, ?, 4500, ?, 0)
            """.trimIndent(),
            menuId,
            storeId,
            name,
            available,
        )
        return menuId
    }

    private companion object {
        /** 전각 라틴 문자, 표의문자 공백, 양끝 공백이 모두 섞인 이름. */
        const val WIDE_STORE_NAME = "  Ｓｔａｒ\u3000버클  "

        val REGION_KINDS =
            setOf(
                StoreSearchTermKind.REGION_SIDO,
                StoreSearchTermKind.REGION_SIGUNGU,
                StoreSearchTermKind.REGION_EUPMYEONDONG,
                StoreSearchTermKind.REGION_RI,
            )
    }
}

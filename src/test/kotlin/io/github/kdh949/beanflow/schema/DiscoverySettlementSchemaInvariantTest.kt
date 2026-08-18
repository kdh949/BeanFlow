package io.github.kdh949.beanflow.schema

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import io.github.kdh949.beanflow.ordering.internal.OrderCreationDatabaseFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * The V59 search index schema.
 *
 * The migration deliberately inserts nothing: filling the index in SQL would mean writing the
 * normalizer a second time, and `lower()` cannot reproduce the Kotlin one (MD-2026-018). The
 * initial load belongs to `StoreSearchIndexRebuildService`, covered by
 * `StoreSearchIndexRebuildIntegrationTest`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class DiscoverySettlementSchemaInvariantTest : IsolatedPostgresSupport() {
    companion object {
        private val STORE_NAME_WEIGHT = BigDecimal("1.00")
        private val MENU_NAME_WEIGHT = BigDecimal("0.70")
        private val REGION_WEIGHT = BigDecimal("0.80")
    }

    private val jdbc by lazy { JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)) }

    @BeforeAll
    fun migrateFromCleanDatabaseOnce() {
        flyway().migrate()
    }

    @AfterEach
    fun clearTestRows() {
        jdbc.update("DELETE FROM discovery_store_search_term")
        jdbc.update("DELETE FROM merchant_menu")
        jdbc.update(
            """
            WITH deleted_profiles AS (
                DELETE FROM merchant_store_discovery_profile RETURNING store_id
            )
            DELETE FROM merchant_store WHERE id IN (SELECT store_id FROM deleted_profiles)
            """.trimIndent(),
        )
    }

    @Test
    fun `current schema contains the search term indexes`() {
        assertThat(
            jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'discovery_store_search_term' ORDER BY indexname",
                String::class.java,
            ),
        ).containsExactly(
            "discovery_store_search_term_pkey",
            "ix_search_term_store_kind",
            "ix_search_term_trgm",
            "uq_search_term_identity",
        )
        // 후보 탐색이 유사도로 떨어질 때 순차 스캔이 되지 않으려면 trigram 연산자 클래스여야 한다.
        assertThat(indexDefinition("ix_search_term_trgm")).contains("gin", "term_normalized gin_trgm_ops")
    }

    @Test
    fun `the index starts empty because SQL cannot produce the normalized text`() {
        insertStoreWithProfile("스타벅스 강남점")

        assertThat(jdbc.queryForObject("SELECT count(*) FROM discovery_store_search_term", Long::class.java)).isZero()
        // migration이 SQL로 정규화를 흉내 냈다면 여기 행이 생기고, 그 행은 질의가 만드는 문자열과
        // 다를 수 있다. 초기 적재는 재색인이 Kotlin 정규화 함수로 수행한다(MD-2026-018).
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_proc WHERE proname LIKE '%normalize_search_text%'",
                Long::class.java,
            ),
        ).isZero()
    }

    @Test
    fun `term identity is unique per store, kind, source and text`() {
        val storeId = insertStoreWithProfile("스타벅스 강남점")
        insertTerm(storeId, "STORE_NAME", null, "스타벅스 강남점", STORE_NAME_WEIGHT)

        assertThatThrownBy { insertTerm(storeId, "STORE_NAME", null, "스타벅스 강남점", STORE_NAME_WEIGHT) }
            .isInstanceOf(DataIntegrityViolationException::class.java)

        // 같은 이름의 서로 다른 메뉴 두 개는 출처가 달라 함께 존재할 수 있다.
        val firstMenu = insertMenu(storeId, "아메리카노", available = true)
        val secondMenu = insertMenu(storeId, "아메리카노", available = true)
        insertTerm(storeId, "MENU_NAME", firstMenu, "아메리카노", MENU_NAME_WEIGHT)
        insertTerm(storeId, "MENU_NAME", secondMenu, "아메리카노", MENU_NAME_WEIGHT)
        assertThat(countTerms(storeId, "MENU_NAME")).isEqualTo(2)
    }

    @Test
    fun `only menu terms carry a source and weights stay inside the ranking range`() {
        val storeId = insertStoreWithProfile("스타벅스 강남점")
        val menuId = insertMenu(storeId, "아메리카노", available = true)

        assertThatThrownBy { insertTerm(storeId, "STORE_NAME", menuId, "다른 이름", STORE_NAME_WEIGHT) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertTerm(storeId, "MENU_NAME", null, "아메리카노", MENU_NAME_WEIGHT) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertTerm(storeId, "REGION_RI", null, "   ", REGION_WEIGHT) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertTerm(storeId, "REGION_RI", null, "감정리", BigDecimal("0.00")) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertTerm(storeId, "SLOGAN", null, "무언가", STORE_NAME_WEIGHT) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `menu term source must exist on the same store and is removed with its menu`() {
        val firstStoreId = insertStoreWithProfile("스타벅스 강남점")
        val secondStoreId = insertStoreWithProfile("스타벅스 역삼점")
        val firstMenuId = insertMenu(firstStoreId, "아메리카노", available = true)
        val secondMenuId = insertMenu(secondStoreId, "아메리카노", available = true)

        assertThatThrownBy {
            insertTerm(firstStoreId, "MENU_NAME", UUID.randomUUID(), "존재하지 않는 메뉴", MENU_NAME_WEIGHT)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertTerm(firstStoreId, "MENU_NAME", secondMenuId, "다른 매장 메뉴", MENU_NAME_WEIGHT)
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        insertTerm(firstStoreId, "MENU_NAME", firstMenuId, "아메리카노", MENU_NAME_WEIGHT)
        jdbc.update("DELETE FROM merchant_menu WHERE id = ?", firstMenuId)

        assertThat(countTerms(firstStoreId, "MENU_NAME")).isZero()
    }

    @Test
    fun `every ADR-103 term kind is accepted including the ri level`() {
        val storeId = insertStoreWithProfile("스타벅스 강남점")
        val menuId = insertMenu(storeId, "아메리카노", available = true)

        listOf("STORE_NAME", "BRAND_NAME", "REGION_SIDO", "REGION_SIGUNGU", "REGION_EUPMYEONDONG", "REGION_RI")
            .forEach { kind -> insertTerm(storeId, kind, null, "값 $kind", REGION_WEIGHT) }
        insertTerm(storeId, "MENU_NAME", menuId, "아메리카노", MENU_NAME_WEIGHT)

        assertThat(jdbc.queryForObject("SELECT count(*) FROM discovery_store_search_term", Long::class.java)).isEqualTo(7)
    }

    @Test
    fun `deleting a store removes the terms that pointed at it`() {
        val storeId = insertStoreWithProfile("스타벅스 강남점")
        val menuId = insertMenu(storeId, "아메리카노", available = true)
        insertTerm(storeId, "STORE_NAME", null, "스타벅스 강남점", STORE_NAME_WEIGHT)
        insertTerm(storeId, "MENU_NAME", menuId, "아메리카노", MENU_NAME_WEIGHT)

        jdbc.update("DELETE FROM merchant_menu WHERE store_id = ?", storeId)
        jdbc.update("DELETE FROM merchant_store_discovery_profile WHERE store_id = ?", storeId)
        jdbc.update("DELETE FROM merchant_store WHERE id = ?", storeId)

        assertThat(jdbc.queryForObject("SELECT count(*) FROM discovery_store_search_term", Long::class.java)).isZero()
    }

    @Test
    fun `settlement batch transition and adjustment constraints preserve the confirmed ledger`() {
        val storeId = insertSettlementStore()
        val batchId = insertSettlementBatch(storeId)
        val orderId = insertSyntheticCompletedOrder(storeId)
        val itemId = insertSettlementItem(batchId, orderId, storeId)

        assertThatThrownBy { insertSettlementAdjustment(itemId, batchId, storeId, "refund:before-confirmation") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
            .hasStackTraceContaining("requires a confirmed Item")
        assertThatThrownBy {
            jdbc.update("UPDATE settlement_batch SET state = 'CONFIRMED', confirmed_at = now() WHERE id = ?", batchId)
        }.isInstanceOf(DataAccessException::class.java)
            .hasStackTraceContaining("OPEN to CALCULATED")

        calculateSettlementBatch(batchId)
        assertThatThrownBy { jdbc.update("UPDATE settlement_batch SET gross_paid_krw = 999 WHERE id = ?", batchId) }
            .isInstanceOf(DataAccessException::class.java)
            .hasStackTraceContaining("CALCULATED to CONFIRMED")
        confirmSettlementBatch(batchId)

        val adjustmentId = insertSettlementAdjustment(itemId, batchId, storeId, "refund:confirmed")
        assertThatThrownBy { insertSettlementAdjustment(itemId, batchId, storeId, "refund:confirmed") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbc.update("UPDATE settlement_adjustment SET amount_krw = amount_krw - 1 WHERE id = ?", adjustmentId)
        }.isInstanceOf(DataAccessException::class.java)
            .hasStackTraceContaining("SettlementAdjustment is immutable")
        assertThatThrownBy { jdbc.update("DELETE FROM settlement_adjustment WHERE id = ?", adjustmentId) }
            .isInstanceOf(DataAccessException::class.java)
            .hasStackTraceContaining("SettlementAdjustment is immutable")
        assertThatThrownBy { jdbc.update("UPDATE settlement_batch SET confirmed_at = now() WHERE id = ?", batchId) }
            .isInstanceOf(DataAccessException::class.java)
            .hasStackTraceContaining("Confirmed SettlementBatch is immutable")
    }

    @Test
    fun `settlement dispute constraints enforce confirmed item active uniqueness and one refile`() {
        val storeId = insertSettlementStore()
        val batchId = insertSettlementBatch(storeId)
        val orderId = insertSyntheticCompletedOrder(storeId)
        val itemId = insertSettlementItem(batchId, orderId, storeId)

        assertThatThrownBy { insertSettlementDispute(itemId, storeId, "evidence:open") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
            .hasStackTraceContaining("requires a confirmed Item")

        calculateSettlementBatch(batchId)
        confirmSettlementBatch(batchId)
        val firstId = insertSettlementDispute(itemId, storeId, "evidence:first")

        assertThatThrownBy { insertSettlementDispute(itemId, storeId, "evidence:duplicate") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbc.update(
                "UPDATE settlement_dispute SET state = 'REJECTED', held_amount_krw = 0, decided_at = now() WHERE id = ?",
                firstId,
            )
        }.isInstanceOf(DataAccessException::class.java)
            .hasStackTraceContaining("enter review")

        jdbc.update("UPDATE settlement_dispute SET state = 'UNDER_REVIEW', version = version + 1 WHERE id = ?", firstId)
        jdbc.update(
            "UPDATE settlement_dispute SET state = 'REJECTED', held_amount_krw = 0, decided_at = now(), " +
                "version = version + 1 WHERE id = ?",
            firstId,
        )
        assertThatThrownBy { insertSettlementDispute(itemId, storeId, "evidence:first", firstId) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
            .hasStackTraceContaining("refile guard")
        val refileId = insertSettlementDispute(itemId, storeId, "evidence:refile", firstId)

        assertThat(jdbc.queryForObject("SELECT refile_count FROM settlement_dispute WHERE id = ?", Int::class.java, refileId))
            .isEqualTo(1)
        jdbc.update("UPDATE settlement_dispute SET state = 'UNDER_REVIEW', version = version + 1 WHERE id = ?", refileId)
        jdbc.update(
            "UPDATE settlement_dispute SET state = 'WITHDRAWN', held_amount_krw = 0, decided_at = now(), " +
                "version = version + 1 WHERE id = ?",
            refileId,
        )
        assertThatThrownBy { insertSettlementDispute(itemId, storeId, "evidence:second-refile", refileId) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
            .hasStackTraceContaining("refile guard")
        assertThatThrownBy { jdbc.update("DELETE FROM settlement_dispute WHERE id = ?", firstId) }
            .isInstanceOf(DataAccessException::class.java)
            .hasStackTraceContaining("append-preserving")
    }

    private fun insertSettlementStore(): UUID =
        UUID.randomUUID().also {
            jdbc.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                it,
            )
        }

    private fun insertSettlementBatch(storeId: UUID): UUID =
        UUID.randomUUID().also {
            jdbc.update(
                "INSERT INTO settlement_batch (id, store_id, settlement_date, state, created_at, version) " +
                    "VALUES (?, ?, '2026-08-03', 'OPEN', now(), 0)",
                it,
                storeId,
            )
        }

    private fun insertSyntheticCompletedOrder(storeId: UUID): UUID =
        UUID.randomUUID().also { orderId ->
            val publicReference = OrderCreationDatabaseFixture.registerPublicReference(jdbc, orderId)
            jdbc.execute("ALTER TABLE ordering_order DISABLE TRIGGER USER")
            try {
                jdbc.update(
                    """
                    INSERT INTO ordering_order (
                        id, customer_id, store_id, pickup_slot_id,
                        public_reference, pickup_business_date, pickup_sequence,
                        store_name_snapshot, pickup_window_start_snapshot, pickup_window_end_snapshot,
                        state, subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw,
                        currency, reservation_expires_at, paid_at, acceptance_warning_at,
                        acceptance_deadline_at, accepted_at, preparing_at, ready_at, completed_at,
                        created_at, updated_at, version
                    ) VALUES (?, ?, ?, ?, ?, DATE '2026-08-03', ?,
                              'Test Store', '2026-08-03T00:00:00Z', '2026-08-03T00:10:00Z',
                              'COMPLETED', 1000, 100, 100, 800, 'KRW', NULL,
                              '2026-08-03T00:10:00Z', '2026-08-03T00:12:00Z',
                              '2026-08-03T00:13:00Z', '2026-08-03T00:11:00Z',
                              '2026-08-03T00:12:00Z', '2026-08-03T00:13:00Z',
                              '2026-08-03T01:02:03Z', '2026-08-03T00:00:00Z',
                              '2026-08-03T01:02:03Z', 7)
                    """.trimIndent(),
                    orderId,
                    UUID.randomUUID(),
                    storeId,
                    UUID.randomUUID(),
                    publicReference,
                    OrderCreationDatabaseFixture.pickupSequence(orderId),
                )
            } finally {
                jdbc.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
            }
        }

    private fun insertSettlementItem(
        batchId: UUID,
        orderId: UUID,
        storeId: UUID,
    ): UUID =
        UUID.randomUUID().also { itemId ->
            jdbc.update(
                """
                INSERT INTO settlement_item (
                    id, settlement_batch_id, order_id, store_id, item_source,
                    completed_at, settlement_date, currency,
                    gross_paid_krw, fee_rate_bps, fee_krw,
                    coupon_cost_krw, point_cost_krw, benefit_cost_krw,
                    net_settlement_krw, created_at
                ) VALUES (?, ?, ?, ?, ?, '2026-08-03T01:02:03Z', '2026-08-03', 'KRW',
                          1000, 500, 50, 100, 50, 150, 800, now())
                """.trimIndent(),
                itemId,
                batchId,
                orderId,
                storeId,
                "order:$orderId:completed:7",
            )
        }

    private fun calculateSettlementBatch(batchId: UUID) {
        jdbc.update(
            """
            UPDATE settlement_batch
               SET state = 'CALCULATED', item_count = 1, gross_paid_krw = 1000,
                   fee_krw = 50, benefit_cost_krw = 150, item_net_settlement_krw = 800,
                   adjustment_krw = 0, carry_forward_in_krw = 0, carry_forward_out_krw = 0,
                   calculated_at = '2026-08-04T00:00:00Z', version = version + 1
             WHERE id = ?
            """.trimIndent(),
            batchId,
        )
    }

    private fun confirmSettlementBatch(batchId: UUID) {
        jdbc.update(
            "UPDATE settlement_batch SET state = 'CONFIRMED', confirmed_at = '2026-08-04T00:01:00Z', " +
                "version = version + 1 WHERE id = ?",
            batchId,
        )
    }

    private fun insertSettlementAdjustment(
        itemId: UUID,
        batchId: UUID,
        storeId: UUID,
        source: String,
    ): UUID =
        UUID.randomUUID().also { adjustmentId ->
            jdbc.update(
                """
                INSERT INTO settlement_adjustment (
                    id, store_id, settlement_item_id, source_settlement_batch_id,
                    adjustment_source, reason_code, effective_at, order_completed_at,
                    settlement_date, currency, amount_krw, created_at
                ) VALUES (?, ?, ?, ?, ?, 'REFUND_SUCCEEDED', ?, ?, '2026-08-03', 'KRW', -100, ?)
                """.trimIndent(),
                adjustmentId,
                storeId,
                itemId,
                batchId,
                source,
                Timestamp.from(Instant.parse("2026-08-05T00:00:00Z")),
                Timestamp.from(Instant.parse("2026-08-03T01:02:03Z")),
                Timestamp.from(Instant.parse("2026-08-05T00:00:01Z")),
            )
        }

    private fun insertSettlementDispute(
        itemId: UUID,
        storeId: UUID,
        evidence: String,
        previousDisputeId: UUID? = null,
    ): UUID =
        UUID.randomUUID().also { disputeId ->
            jdbc.update(
                """
                INSERT INTO settlement_dispute (
                    id, settlement_item_id, store_id, previous_dispute_id, refile_count,
                    state, expected_adjustment_krw, held_amount_krw, reason,
                    evidence_references, actor_id, operation, idempotency_key, payload_hash,
                    response_status, response_body, correlation_id, filed_at, version
                ) VALUES (?, ?, ?, ?, ?, 'FILED', -100, -100, 'amount mismatch',
                          jsonb_build_array(?::text), ?, 'CREATE_SETTLEMENT_DISPUTE_V1', ?, ?,
                          201, '{}', ?, '2026-08-05T00:00:00Z', 0)
                """.trimIndent(),
                disputeId,
                itemId,
                storeId,
                previousDisputeId,
                if (previousDisputeId == null) 0 else 1,
                evidence,
                UUID.randomUUID(),
                "key:$disputeId",
                "a".repeat(64),
                "correlation:$disputeId",
            )
        }

    private fun indexDefinition(name: String): String =
        checkNotNull(
            jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = ?", String::class.java, name),
        ) { "index $name is missing" }

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

    private fun insertTerm(
        storeId: UUID,
        kind: String,
        sourceId: UUID?,
        termNormalized: String,
        weight: BigDecimal,
    ) {
        jdbc.update(
            """
            INSERT INTO discovery_store_search_term
                (id, store_id, term_kind, source_id, term_normalized, display_text, weight)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            storeId,
            kind,
            sourceId,
            termNormalized,
            termNormalized,
            weight,
        )
    }

    private fun insertStoreWithProfile(name: String): UUID {
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

    private fun flyway(): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .load()
}

@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.settlement.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.LocalDate
import java.util.UUID

@Testcontainers
internal class SettlementFoundationMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:17.6"))
    }

    private val jdbcTemplate by lazy {
        JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @BeforeEach
    fun resetToPreSettlementSchema() {
        flyway(cleanDisabled = false).clean()
        flyway(target = "20").migrate()
    }

    @Test
    fun `incomplete OrderCompletedV1 publication blocks settlement activation`() {
        jdbcTemplate.update(
            """
            INSERT INTO event_publication (
                id, listener_id, event_type, serialized_event, publication_date,
                completion_date, status, completion_attempts, last_resubmission_date
            ) VALUES (?, 'legacy-listener', ?, '{}', now(), NULL, 'PUBLISHED', 1, now())
            """.trimIndent(),
            UUID.randomUUID(),
            "io.github.kdh949.beanflow.eventing.api.OrderCompletedV1",
        )

        assertThatThrownBy { migrateCurrent() }
            .hasStackTraceContaining("OrderCompletedV1")
            .hasStackTraceContaining("incomplete OrderCompletedV1 publication")

        assertThat(tableCount("settlement_batch")).isZero()
        assertThat(tableCount("settlement_item")).isZero()
    }

    @Test
    fun `completed legacy publication does not block clean cutover schema`() {
        jdbcTemplate.update(
            """
            INSERT INTO event_publication (
                id, listener_id, event_type, serialized_event, publication_date,
                completion_date, status, completion_attempts, last_resubmission_date
            ) VALUES (?, 'legacy-listener', ?, '{}', now(), now(), 'COMPLETED', 1, now())
            """.trimIndent(),
            UUID.randomUUID(),
            "io.github.kdh949.beanflow.eventing.api.OrderCompletedV1",
        )

        migrateCurrent()

        assertThat(tableCount("settlement_batch")).isOne()
        assertThat(tableCount("settlement_item")).isOne()
    }

    @Test
    fun `legacy cancelled Order blocks unverified cancellation evidence migration`() {
        val storeId = insertStore()
        insertLegacyCancelledOrder(storeId)

        assertThatThrownBy { migrateCurrent() }
            .hasStackTraceContaining("legacy CANCELLED Order")
            .hasStackTraceContaining("verified cancellation evidence")

        assertThat(columnCount("ordering_order", "cancelled_at")).isZero()
        assertThat(columnCount("ordering_order", "cancellation_cause")).isZero()
        assertThat(tableCount("settlement_batch")).isZero()
    }

    @Test
    fun `cancellation evidence constraints reject missing and misplaced facts`() {
        migrateCurrent()
        val storeId = insertStore()

        assertThatThrownBy { insertLegacyCancelledOrder(storeId) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO ordering_order (
                    id, customer_id, store_id, pickup_slot_id, state,
                    subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw,
                    currency, reservation_expires_at, cancelled_at, cancellation_cause,
                    created_at, updated_at, version
                ) VALUES (?, ?, ?, ?, 'PENDING_PAYMENT', 1000, 0, 0, 1000,
                          'KRW', now() + interval '5 minutes', now(), 'CUSTOMER_REQUEST',
                          now(), now(), 0)
                """.trimIndent(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                storeId,
                UUID.randomUUID(),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `batch and immutable item constraints reinforce settlement invariants`() {
        migrateCurrent()
        val storeId = insertStore()
        val orderId = insertSyntheticCompletedOrder(storeId)
        val batchId = UUID.randomUUID()
        val settlementDate = LocalDate.of(2026, 8, 3)
        insertBatch(batchId, storeId, settlementDate)
        insertItem(UUID.randomUUID(), batchId, orderId, "order:$orderId:completed:7")

        assertThatThrownBy { insertBatch(UUID.randomUUID(), storeId, settlementDate) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO settlement_batch (
                    id, store_id, settlement_date, state, created_at, version
                ) VALUES (?, ?, ?, 'INVALID', now(), 0)
                """.trimIndent(),
                UUID.randomUUID(),
                storeId,
                settlementDate.plusDays(1),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertItem(
                UUID.randomUUID(),
                batchId,
                insertSyntheticCompletedOrder(storeId),
                "order:$orderId:completed:7",
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertItem(UUID.randomUUID(), batchId, orderId, "order:$orderId:completed:8")
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertItem(
                UUID.randomUUID(),
                batchId,
                insertSyntheticCompletedOrder(storeId),
                "order:${UUID.randomUUID()}:completed:9",
                benefitCostKrw = 151,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        val otherStoreId = insertStore()
        assertThatThrownBy {
            insertItem(
                UUID.randomUUID(),
                batchId,
                insertSyntheticCompletedOrder(otherStoreId),
                "order:${UUID.randomUUID()}:completed:10",
                storeId = otherStoreId,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
            .hasStackTraceContaining("store/date scope does not match")
        assertThatThrownBy {
            insertItem(
                UUID.randomUUID(),
                batchId,
                insertSyntheticCompletedOrder(storeId),
                "order:${UUID.randomUUID()}:completed:11",
                settlementDate = settlementDate.plusDays(1),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
            .hasStackTraceContaining("store/date scope does not match")
        assertThatThrownBy {
            jdbcTemplate.update(
                "UPDATE settlement_item SET fee_krw = 51 WHERE order_id = ?",
                orderId,
            )
        }.isInstanceOf(DataAccessException::class.java)
            .hasStackTraceContaining("SettlementItem is immutable")
        assertThatThrownBy {
            jdbcTemplate.update("DELETE FROM settlement_item WHERE order_id = ?", orderId)
        }.isInstanceOf(DataAccessException::class.java)
            .hasStackTraceContaining("SettlementItem is immutable")
        jdbcTemplate.update("UPDATE settlement_batch SET state = 'CALCULATED' WHERE id = ?", batchId)
        assertThatThrownBy {
            insertItem(
                UUID.randomUUID(),
                batchId,
                insertSyntheticCompletedOrder(storeId),
                "order:${UUID.randomUUID()}:completed:12",
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
            .hasStackTraceContaining("closed batch")

        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM pg_indexes
                WHERE tablename = 'settlement_item'
                  AND indexname = 'idx_settlement_item_batch_cursor'
                """.trimIndent(),
                Long::class.java,
            ),
        ).isOne()
    }

    private fun insertStore(): UUID =
        UUID.randomUUID().also {
            jdbcTemplate.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                it,
            )
        }

    private fun insertSyntheticCompletedOrder(storeId: UUID): UUID =
        UUID.randomUUID().also { orderId ->
            jdbcTemplate.execute("ALTER TABLE ordering_order DISABLE TRIGGER USER")
            try {
                jdbcTemplate.update(
                    """
                    INSERT INTO ordering_order (
                        id, customer_id, store_id, pickup_slot_id, state,
                        subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw,
                        currency, reservation_expires_at, paid_at, acceptance_warning_at,
                        acceptance_deadline_at, accepted_at, preparing_at, ready_at, completed_at,
                        created_at, updated_at, version
                    ) VALUES (?, ?, ?, ?, 'COMPLETED', 1000, 100, 100, 800,
                              'KRW', NULL,
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
                )
            } finally {
                jdbcTemplate.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
            }
        }

    private fun insertLegacyCancelledOrder(storeId: UUID): UUID =
        UUID.randomUUID().also { orderId ->
            jdbcTemplate.execute("ALTER TABLE ordering_order DISABLE TRIGGER USER")
            try {
                jdbcTemplate.update(
                    """
                    INSERT INTO ordering_order (
                        id, customer_id, store_id, pickup_slot_id, state,
                        subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw,
                        currency, reservation_expires_at, created_at, updated_at, version
                    ) VALUES (?, ?, ?, ?, 'CANCELLED', 1000, 0, 0, 1000,
                              'KRW', NULL, now(), now(), 0)
                    """.trimIndent(),
                    orderId,
                    UUID.randomUUID(),
                    storeId,
                    UUID.randomUUID(),
                )
            } finally {
                jdbcTemplate.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
            }
        }

    private fun insertBatch(
        batchId: UUID,
        storeId: UUID,
        settlementDate: LocalDate,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO settlement_batch (
                id, store_id, settlement_date, state, created_at, version
            ) VALUES (?, ?, ?, 'OPEN', now(), 0)
            """.trimIndent(),
            batchId,
            storeId,
            settlementDate,
        )
    }

    private fun insertItem(
        itemId: UUID,
        batchId: UUID,
        orderId: UUID,
        itemSource: String,
        benefitCostKrw: Long = 150,
        storeId: UUID? = null,
        settlementDate: LocalDate = LocalDate.of(2026, 8, 3),
    ) {
        val batchStoreId =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT store_id FROM settlement_batch WHERE id = ?",
                    UUID::class.java,
                    batchId,
                ),
            )
        jdbcTemplate.update(
            """
            INSERT INTO settlement_item (
                id, settlement_batch_id, order_id, store_id, item_source,
                completed_at, settlement_date, currency,
                gross_paid_krw, fee_rate_bps, fee_krw,
                coupon_cost_krw, point_cost_krw, benefit_cost_krw,
                net_settlement_krw, created_at
            ) VALUES (?, ?, ?, ?, ?, '2026-08-03T01:02:03Z', ?, 'KRW',
                      1000, 500, 50, 100, 50, ?, 800, now())
            """.trimIndent(),
            itemId,
            batchId,
            orderId,
            storeId ?: batchStoreId,
            itemSource,
            settlementDate,
            benefitCostKrw,
        )
    }

    private fun tableCount(name: String): Long =
        requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = ?",
                Long::class.java,
                name,
            ),
        )

    private fun columnCount(
        tableName: String,
        columnName: String,
    ): Long =
        requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                  FROM information_schema.columns
                 WHERE table_name = ? AND column_name = ?
                """.trimIndent(),
                Long::class.java,
                tableName,
                columnName,
            ),
        )

    private fun migrateCurrent() {
        flyway().migrate()
    }

    private fun flyway(
        target: String? = null,
        cleanDisabled: Boolean = true,
    ): Flyway {
        val configuration =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .cleanDisabled(cleanDisabled)
        if (target != null) configuration.target(target)
        return configuration.load()
    }
}

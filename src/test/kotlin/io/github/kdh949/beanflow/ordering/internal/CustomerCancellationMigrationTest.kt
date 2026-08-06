@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.BEANFLOW_POSTGRES_IMAGE
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Testcontainers
internal class CustomerCancellationMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(BEANFLOW_POSTGRES_IMAGE)
    }

    private val jdbcTemplate by lazy {
        JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @BeforeEach
    fun resetToPlan30Schema() {
        flyway(cleanDisabled = false).clean()
        flyway(target = "22").migrate()
    }

    @Test
    fun `V23 creates the complete command durability schema`() {
        migrateCurrent()

        assertThat(columnCount("ordering_order", "cancellation_reason_code")).isOne()
        assertThat(columnCount("ordering_order", "cancellation_detail")).isOne()
        assertThat(columnCount("payment_refund", "customer_reason_code")).isOne()
        assertThat(tableCount("ordering_cancellation_command_idempotency")).isOne()
        assertThat(tableCount("ordering_acceptance_timeout_work")).isOne()
        assertThat(tableCount("payment_cancellation_recovery_snapshot")).isOne()
        assertThat(indexCount("idx_ordering_acceptance_timeout_work_due")).isOne()
        assertThat(indexCount("idx_ordering_acceptance_timeout_work_claim_expiry")).isOne()
    }

    @Test
    fun `V23 backfills store command retention from original creation time`() {
        val storeId = insertStore()
        val orderId = insertPlan30PaidOrder(storeId)
        val createdAt = Instant.now().minus(Duration.ofDays(100)).truncatedTo(ChronoUnit.MICROS)
        val recordId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO ordering_store_command_idempotency (
                id, actor_id, order_id, operation, idempotency_key, payload_hash,
                response_status, response_body, created_at
            ) VALUES (?, ?, ?, 'STORE_ORDER_TRANSITION_V2', 'store-key-001', ?, 200, '{}', ?)
            """.trimIndent(),
            recordId,
            UUID.randomUUID(),
            orderId,
            "a".repeat(64),
            Timestamp.from(createdAt),
        )

        migrateCurrent()

        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT retention_expires_at FROM ordering_store_command_idempotency WHERE id = ?",
                Instant::class.java,
                recordId,
            ),
        ).isEqualTo(createdAt.plus(Duration.ofDays(90)))
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'ordering_store_command_idempotency'
                  AND column_name = 'retention_expires_at'
                  AND is_nullable = 'NO'
                """.trimIndent(),
                Long::class.java,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `Order cancellation reason constraints distinguish customer and payment decline`() {
        migrateCurrent()
        val storeId = insertStore()

        insertCancelledOrder(
            storeId,
            "CUSTOMER_REQUEST",
            "ORDER_MISTAKE",
            "ordered twice",
        )
        insertCancelledOrder(storeId, "PAYMENT_DECLINED", null, null)

        assertThatThrownBy {
            insertCancelledOrder(storeId, "CUSTOMER_REQUEST", null, null)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertCancelledOrder(storeId, "CUSTOMER_REQUEST", "OTHER", "unsafe\ntext")
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertCancelledOrder(storeId, "PAYMENT_DECLINED", "PAYMENT_ISSUE", null)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `idempotency and timeout work constraints reject invalid terminal evidence`() {
        migrateCurrent()
        val storeId = insertStore()
        val orderId = insertPaidOrder(storeId)

        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO ordering_cancellation_command_idempotency (
                    id, actor_id, order_id, operation, idempotency_key, payload_hash,
                    response_status, response_body, response_version, created_at, retention_expires_at
                ) VALUES (?, ?, ?, 'CUSTOMER_ORDER_CANCELLATION', 'valid-key-001', ?,
                          409, '{}', 1, now(), now() + interval '90 days')
                """.trimIndent(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                orderId,
                "a".repeat(64),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO ordering_acceptance_timeout_work (
                    id, order_id, acceptance_deadline_at, state, completion_outcome,
                    attempt_count, next_attempt_at, source_reference, created_at, updated_at, version
                ) VALUES (?, ?, now(), 'COMPLETED', NULL, 0, NULL, ?, now(), now(), 0)
                """.trimIndent(),
                UUID.randomUUID(),
                orderId,
                "order:$orderId:acceptance-timeout:test",
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `payment recovery snapshot enforces exact cancellation amount tie out`() {
        migrateCurrent()
        val storeId = insertStore()
        val orderId = insertPaidOrder(storeId)
        val paymentId = insertApprovedPayment(orderId)

        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO payment_cancellation_recovery_snapshot (
                    id, payment_id, order_id, cancellation_order_version,
                    approved_amount_krw, succeeded_refund_amount_before_cancellation_krw,
                    cancellation_requested_refund_amount_krw, cancellation_refund_id,
                    refund_source_reference, provider_idempotency_key, correlation_id,
                    created_at, updated_at, version
                ) VALUES (?, ?, ?, 7, 1000, 200, 700, ?, ?, ?, 'migration-test', now(), now(), 0)
                """.trimIndent(),
                UUID.randomUUID(),
                paymentId,
                orderId,
                UUID.randomUUID(),
                "order:$orderId:customer-cancellation:7:payment",
                "refund:customer-cancellation:$orderId:7",
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private fun insertStore(): UUID =
        UUID.randomUUID().also {
            jdbcTemplate.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                it,
            )
        }

    private fun insertCancelledOrder(
        storeId: UUID,
        cause: String,
        reasonCode: String?,
        detail: String?,
    ): UUID = insertOrder(storeId, "CANCELLED", cause, reasonCode, detail)

    private fun insertPaidOrder(storeId: UUID): UUID = insertOrder(storeId, "PAID", null, null, null)

    private fun insertPlan30PaidOrder(storeId: UUID): UUID {
        val orderId = UUID.randomUUID()
        jdbcTemplate.execute("ALTER TABLE ordering_order DISABLE TRIGGER USER")
        try {
            val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
            jdbcTemplate.update(
                """
                INSERT INTO ordering_order (
                    id, customer_id, store_id, pickup_slot_id, state,
                    subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw,
                    currency, reservation_expires_at, paid_at, acceptance_warning_at,
                    acceptance_deadline_at, created_at, updated_at, version
                ) VALUES (?, ?, ?, ?, 'PAID', 1000, 0, 0, 1000, 'KRW', NULL,
                          ?, ?, ?, ?, ?, 0)
                """.trimIndent(),
                orderId,
                UUID.randomUUID(),
                storeId,
                UUID.randomUUID(),
                Timestamp.from(now),
                Timestamp.from(now.plusSeconds(2 * 60)),
                Timestamp.from(now.plusSeconds(3 * 60)),
                Timestamp.from(now),
                Timestamp.from(now),
            )
        } finally {
            jdbcTemplate.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
        }
        return orderId
    }

    private fun insertOrder(
        storeId: UUID,
        state: String,
        cause: String?,
        reasonCode: String?,
        detail: String?,
    ): UUID =
        UUID.randomUUID().also { orderId ->
            jdbcTemplate.execute("ALTER TABLE ordering_order DISABLE TRIGGER USER")
            try {
                jdbcTemplate.update(
                    """
                    INSERT INTO ordering_order (
                        id, customer_id, store_id, pickup_slot_id, state,
                        subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw,
                        currency, reservation_expires_at, paid_at, acceptance_warning_at,
                        acceptance_deadline_at, cancelled_at, cancellation_cause,
                        cancellation_reason_code, cancellation_detail,
                        created_at, updated_at, version
                    ) VALUES (?, ?, ?, ?, ?, 1000, 0, 0, 1000, 'KRW', NULL,
                              CASE WHEN ? = 'PAID' THEN now() ELSE NULL END,
                              CASE WHEN ? = 'PAID' THEN now() + interval '2 minutes' ELSE NULL END,
                              CASE WHEN ? = 'PAID' THEN now() + interval '3 minutes' ELSE NULL END,
                              CASE WHEN ? = 'CANCELLED' THEN now() ELSE NULL END,
                              ?, ?, ?, now(), now(), 7)
                    """.trimIndent(),
                    orderId,
                    UUID.randomUUID(),
                    storeId,
                    UUID.randomUUID(),
                    state,
                    state,
                    state,
                    state,
                    state,
                    cause,
                    reasonCode,
                    detail,
                )
            } finally {
                jdbcTemplate.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
            }
        }

    private fun insertApprovedPayment(orderId: UUID): UUID =
        UUID.randomUUID().also { paymentId ->
            val customerId = UUID.randomUUID()
            val paymentMethodId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO payment_method (
                    id, customer_id, provider, token_reference, display_alias,
                    card_brand, last_four, status, created_at, updated_at, version
                ) VALUES (?, ?, 'TEST_PROVIDER', ?, 'test card', 'VISA', '4242',
                          'ACTIVE', now(), now(), 0)
                """.trimIndent(),
                paymentMethodId,
                customerId,
                "token:$paymentMethodId",
            )
            jdbcTemplate.update(
                """
                INSERT INTO payment_payment (
                    id, order_id, type, approval_state, requested_amount_krw,
                    approved_amount_krw, succeeded_refund_amount_krw, currency,
                    source_reference, provider_transaction_reference, correlation_id,
                    customer_id, payment_method_id,
                    approved_at, created_at, updated_at, version
                ) VALUES (?, ?, 'EXTERNAL', 'APPROVED', 1000, 1000, 0, 'KRW',
                          ?, 'provider-approved', 'migration-test', ?, ?,
                          now(), now(), now(), 0)
                """.trimIndent(),
                paymentId,
                orderId,
                "payment:$orderId",
                customerId,
                paymentMethodId,
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

    private fun indexCount(name: String): Long =
        requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = ?",
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
                SELECT count(*) FROM information_schema.columns
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

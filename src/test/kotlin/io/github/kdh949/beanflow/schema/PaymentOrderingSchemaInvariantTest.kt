@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.schema

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import io.github.kdh949.beanflow.ordering.internal.OrderCreationDatabaseFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal class PaymentOrderingSchemaInvariantTest : IsolatedPostgresSupport() {
    private val jdbc by lazy {
        JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @BeforeAll
    fun migrateCurrentSchema() {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .cleanDisabled(true)
            .load()
            .migrate()
    }

    @Test
    fun `one-time payment attempt is immutable without a PaymentMethod binding`() {
        val paymentId = insertOneTimePayment()
        val providerOrderId = "bf_${paymentId.toString().replace("-", "")}"
        val customerKey = "bf_${"a".repeat(43)}"

        jdbc.update(
            """
            INSERT INTO payment_one_time_attempt (
                payment_id, provider_order_id, customer_key, order_name,
                amount_krw, currency, state, provider_idempotency_key,
                success_url, fail_url, expires_at, created_at, updated_at
            ) VALUES (?, ?, ?, 'BeanFlow 주문', 1000, 'KRW', 'READY', ?,
                      'https://app.example.test/app/payments/success',
                      'https://app.example.test/app/payments/fail', ?, ?, ?)
            """.trimIndent(),
            paymentId,
            providerOrderId,
            customerKey,
            UUID.randomUUID().toString(),
            Timestamp.from(NOW.plusSeconds(600)),
            Timestamp.from(NOW),
            Timestamp.from(NOW),
        )

        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payment_one_time_attempt WHERE payment_id = ?",
                Long::class.java,
                paymentId,
            ),
        ).isOne()
        assertThatThrownBy {
            jdbc.update("UPDATE payment_one_time_attempt SET amount_krw = 2000 WHERE payment_id = ?", paymentId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `one-time payment rejects invalid provider customer callback and amount bindings`() {
        val paymentId = insertOneTimePayment()

        assertThatThrownBy {
            jdbc.update(
                """
                INSERT INTO payment_one_time_attempt (
                    payment_id, provider_order_id, customer_key, order_name,
                    amount_krw, currency, state, provider_idempotency_key,
                    success_url, fail_url, expires_at, created_at, updated_at
                ) VALUES (?, 'short', 'customer', 'Order', -1, 'USD', 'CONFIRMING', ?,
                          'http://invalid', 'http://invalid', ?, ?, ?)
                """.trimIndent(),
                paymentId,
                UUID.randomUUID().toString(),
                Timestamp.from(NOW.minusSeconds(1)),
                Timestamp.from(NOW),
                Timestamp.from(NOW),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `order cancellation reason distinguishes customer intent from payment decline`() {
        val storeId = insertStore()

        insertOrder(storeId, "CANCELLED", "CUSTOMER_REQUEST", "ORDER_MISTAKE", "ordered twice")
        insertOrder(storeId, "CANCELLED", "PAYMENT_DECLINED", null, null)

        assertThatThrownBy { insertOrder(storeId, "CANCELLED", "CUSTOMER_REQUEST", null, null) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertOrder(storeId, "CANCELLED", "CUSTOMER_REQUEST", "OTHER", "unsafe\ntext") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertOrder(storeId, "CANCELLED", "PAYMENT_DECLINED", "PAYMENT_ISSUE", null) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `cancellation idempotency and timeout work reject contradictory terminal evidence`() {
        val storeId = insertStore()
        val orderId = insertOrder(storeId, "PAID", null, null, null)

        assertThatThrownBy {
            jdbc.update(
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
            jdbc.update(
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
    fun `payment recovery snapshot rejects a cancellation amount that does not tie out`() {
        val storeId = insertStore()
        val orderId = insertOrder(storeId, "PAID", null, null, null)
        val paymentId = insertApprovedPayment(orderId)

        assertThatThrownBy {
            jdbc.update(
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

    private fun insertOneTimePayment(): UUID =
        UUID.randomUUID().also { id ->
            jdbc.update(
                """
                INSERT INTO payment_payment (
                    id, order_id, customer_id, payment_method_id, type, approval_state,
                    requested_amount_krw, currency, source_reference, correlation_id,
                    created_at, updated_at
                ) VALUES (?, ?, ?, NULL, 'EXTERNAL', 'READY', 1000, 'KRW', ?, 'one-time-migration', ?, ?)
                """.trimIndent(),
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "one-time:$id",
                Timestamp.from(NOW),
                Timestamp.from(NOW),
            )
        }

    private fun insertStore(): UUID =
        UUID.randomUUID().also {
            jdbc.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                it,
            )
        }

    private fun insertOrder(
        storeId: UUID,
        state: String,
        cause: String?,
        reasonCode: String?,
        detail: String?,
    ): UUID =
        UUID.randomUUID().also { orderId ->
            val publicReference = OrderCreationDatabaseFixture.registerPublicReference(jdbc, orderId)
            val pickupSequence =
                requireNotNull(
                    jdbc.queryForObject(
                        "SELECT count(*) + 1 FROM ordering_order WHERE store_id = ?",
                        Long::class.java,
                        storeId,
                    ),
                )
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
                        acceptance_deadline_at, cancelled_at, cancellation_cause,
                        cancellation_reason_code, cancellation_detail, created_at, updated_at, version
                    ) VALUES (
                        ?, ?, ?, ?, ?, CURRENT_DATE, ?,
                        'Migration Store', now() + interval '10 minutes', now() + interval '20 minutes',
                        ?, 1000, 0, 0, 1000, 'KRW', NULL,
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
                    publicReference,
                    pickupSequence,
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
                jdbc.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
            }
        }

    private fun insertApprovedPayment(orderId: UUID): UUID =
        UUID.randomUUID().also { paymentId ->
            val customerId = UUID.randomUUID()
            val paymentMethodId = UUID.randomUUID()
            jdbc.update(
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
            jdbc.update(
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

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-10T03:00:00Z")
    }
}

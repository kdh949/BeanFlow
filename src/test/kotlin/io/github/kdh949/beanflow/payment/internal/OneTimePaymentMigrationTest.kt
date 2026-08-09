@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.payment.internal

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
import java.time.Instant
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
internal class OneTimePaymentMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(BEANFLOW_POSTGRES_IMAGE)

        val NOW: Instant = Instant.parse("2026-08-10T03:00:00Z")
    }

    private val jdbcTemplate by lazy {
        JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @BeforeEach
    fun resetToV37() {
        flyway(cleanDisabled = false).clean()
        flyway(target = "37").migrate()
        flyway().migrate()
    }

    @Test
    fun `V38 adds an immutable one time attempt without a PaymentMethod binding`() {
        val paymentId = insertOneTimePayment()
        val providerOrderId = "bf_${paymentId.toString().replace("-", "")}"
        val customerKey = "bf_${"a".repeat(43)}"

        jdbcTemplate.update(
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
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_one_time_attempt WHERE payment_id = ?",
                Long::class.java,
                paymentId,
            ),
        ).isOne()
        assertThatThrownBy {
            jdbcTemplate.update(
                "UPDATE payment_one_time_attempt SET amount_krw = 2000 WHERE payment_id = ?",
                paymentId,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `V38 rejects invalid provider order customer key callback and amount bindings`() {
        val paymentId = insertOneTimePayment()

        assertThatThrownBy {
            jdbcTemplate.update(
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

    private fun insertOneTimePayment(): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
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
        return id
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
        target?.let(configuration::target)
        return configuration.load()
    }
}

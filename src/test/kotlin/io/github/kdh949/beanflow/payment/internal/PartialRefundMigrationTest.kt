package io.github.kdh949.beanflow.payment.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.UUID

@Testcontainers
internal class PartialRefundMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:17.6"))
    }

    private val jdbcTemplate by lazy {
        JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @BeforeEach
    fun resetToV14() {
        flyway(cleanDisabled = false).clean()
        flyway(target = "14").migrate()
    }

    @Test
    fun `empty legacy refund table activates V15 final schema`() {
        flyway().migrate()

        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'public' AND table_name IN (
                    'payment_refund_line_request', 'payment_refund_line_allocation',
                    'payment_refund_point_request', 'payment_refund_point_allocation',
                    'payment_refund_restoration_work', 'loyalty_partial_refund_restoration'
                 )
                """.trimIndent(),
                Long::class.java,
            ),
        ).isEqualTo(6)
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM information_schema.columns
                 WHERE table_name = 'payment_refund'
                   AND column_name IN (
                       'requested_points_krw', 'request_attempt_count',
                       'lookup_attempt_count', 'next_action',
                       'point_restoration_policy_version_id'
                   )
                """.trimIndent(),
                Long::class.java,
            ),
        ).isEqualTo(5)
    }

    @Test
    fun `legacy refund without exact line evidence fails closed`() {
        val paymentId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO payment_payment (
                id, order_id, type, approval_state, approved_amount_krw, currency,
                benefit_snapshot_reference, source_reference, correlation_id,
                approved_at, updated_at, requested_amount_krw, created_at
            ) VALUES (?, ?, 'BENEFIT_ONLY', 'APPROVED', 0, 'KRW', ?, ?, ?, now(), now(), 0, now())
            """.trimIndent(),
            paymentId,
            orderId,
            "benefit:$orderId",
            "payment:$paymentId",
            "migration-test",
        )
        jdbcTemplate.update(
            """
            INSERT INTO payment_refund (
                id, payment_id, order_id, requested_amount_krw, reason, state,
                provider_idempotency_key, source_reference, attempt_count,
                next_attempt_at, created_at, updated_at
            ) VALUES (?, ?, ?, 1, 'STORE_ORDER_REJECTED', 'REQUESTED', ?, ?, 0, now(), now(), now())
            """.trimIndent(),
            UUID.randomUUID(),
            paymentId,
            orderId,
            "provider:${UUID.randomUUID()}",
            "legacy:${UUID.randomUUID()}",
        )

        assertThatThrownBy { flyway().migrate() }
            .hasStackTraceContaining("legacy payment_refund rows have no verifiable line allocation")
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM information_schema.columns
                 WHERE table_name = 'payment_refund' AND column_name = 'requested_points_krw'
                """.trimIndent(),
                Long::class.java,
            ),
        ).isZero()
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

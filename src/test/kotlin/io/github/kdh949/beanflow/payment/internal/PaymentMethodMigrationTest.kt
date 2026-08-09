@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.BEANFLOW_POSTGRES_IMAGE
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
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
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
internal class PaymentMethodMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(BEANFLOW_POSTGRES_IMAGE)

        val MIGRATION_TIME: Instant = Instant.parse("2026-08-09T01:00:00Z")
    }

    private val jdbcTemplate by lazy {
        JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @BeforeEach
    fun resetToPrePaymentMethodLifecycleSchema() {
        flyway(cleanDisabled = false).clean()
        flyway(target = "36").migrate()
    }

    @Test
    fun `V37 preserves legacy non Toss methods and backfills immutable external payment snapshot`() {
        val customerId = UUID.randomUUID()
        val activeMethodId = insertMethod(customerId, "LOCAL_SCRIPTED", "legacy-active", "ACTIVE")
        val revokedMethodId = insertMethod(customerId, "LOCAL_SCRIPTED", "legacy-revoked", "REVOKED")
        val paymentId = insertExternalPayment(customerId, activeMethodId)

        migrateCurrent()

        assertThat(methodColumn<String>(activeMethodId, "provider_customer_reference")).isNull()
        assertThat(methodColumn<Boolean>(activeMethodId, "is_default")).isFalse()
        assertThat(methodColumn<String>(revokedMethodId, "status")).isEqualTo("DEACTIVATED")
        assertThat(
            jdbcTemplate.queryForMap(
                """
                SELECT payment_method_id, provider, token_reference, provider_customer_reference
                  FROM payment_provider_request_snapshot
                 WHERE payment_id = ?
                """.trimIndent(),
                paymentId,
            ),
        ).containsEntry("payment_method_id", activeMethodId)
            .containsEntry("provider", "LOCAL_SCRIPTED")
            .containsEntry("token_reference", "legacy-active")
            .containsEntry("provider_customer_reference", null)
        assertThat(tableCount()).isEqualTo(5)

        assertThatThrownBy {
            jdbcTemplate.update(
                "UPDATE payment_provider_request_snapshot SET token_reference = 'changed' WHERE payment_id = ?",
                paymentId,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbcTemplate.update("DELETE FROM payment_provider_request_snapshot WHERE payment_id = ?", paymentId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `V37 fails closed when an existing Toss method has no verified provider customer reference`() {
        insertMethod(UUID.randomUUID(), "TOSS_PAYMENTS", "unverified-token", "ACTIVE")

        assertThatThrownBy { migrateCurrent() }
            .isInstanceOf(FlywayException::class.java)
            .hasStackTraceContaining("existing TOSS_PAYMENTS payment method has no verified provider customer reference")
        assertThat(columnCount("payment_method", "provider_customer_reference")).isZero()
    }

    @Test
    fun `V37 fails closed on cross owner provider token ambiguity`() {
        insertMethod(UUID.randomUUID(), "LOCAL_SCRIPTED", "shared-token", "ACTIVE")
        insertMethod(UUID.randomUUID(), "LOCAL_SCRIPTED", "shared-token", "ACTIVE")

        assertThatThrownBy { migrateCurrent() }
            .isInstanceOf(FlywayException::class.java)
            .hasStackTraceContaining("payment method provider token is bound to multiple owners")
        assertThat(columnCount("payment_method", "provider_customer_reference")).isZero()
    }

    @Test
    fun `V37 fails closed when an external payment method binding is missing`() {
        val methodId = insertMethod(UUID.randomUUID(), "LOCAL_SCRIPTED", "orphaned-token", "ACTIVE")
        val paymentId = insertExternalPayment(UUID.randomUUID(), methodId)
        jdbcTemplate.execute("ALTER TABLE payment_method DISABLE TRIGGER ALL")
        try {
            jdbcTemplate.update("DELETE FROM payment_method WHERE id = ?", methodId)
        } finally {
            jdbcTemplate.execute("ALTER TABLE payment_method ENABLE TRIGGER ALL")
        }

        assertThatThrownBy { migrateCurrent() }
            .isInstanceOf(FlywayException::class.java)
            .hasStackTraceContaining("external payment has no unambiguous payment method binding")
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_payment WHERE id = ?",
                Long::class.java,
                paymentId,
            ),
        ).isOne()
    }

    @Test
    fun `V37 enforces provider default and terminal retention invariants`() {
        migrateCurrent()
        val customerId = UUID.randomUUID()
        val first = insertCurrentMethod(customerId, "toss-token-1", "bf_${"a".repeat(43)}", isDefault = true)

        assertThatThrownBy {
            insertCurrentMethod(customerId, "toss-token-2", "bf_${"b".repeat(43)}", isDefault = true)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertCurrentMethod(
                UUID.randomUUID(),
                "toss-token-3",
                "bf_${"c".repeat(43)}",
                status = "DEACTIVATED",
                isDefault = true,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertCurrentMethod(UUID.randomUUID(), "toss-token-4", null)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertCurrentMethod(
                UUID.randomUUID(),
                "legacy-token",
                "bf_${"d".repeat(43)}",
                provider = "LOCAL_SCRIPTED",
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        val terminalAt = MIGRATION_TIME
        insertRegistration(
            customerId = customerId,
            methodId = UUID.randomUUID(),
            status = "COMPLETED",
            terminalAt = terminalAt,
            retentionExpiresAt = terminalAt.plus(Duration.ofDays(90)),
        )
        assertThatThrownBy {
            insertRegistration(
                customerId = customerId,
                methodId = UUID.randomUUID(),
                status = "COMPLETED",
                terminalAt = terminalAt,
                retentionExpiresAt = terminalAt.plus(Duration.ofDays(89)),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(jdbcTemplate.update("UPDATE payment_method SET status = 'ACTIVE' WHERE id = ?", first)).isOne()
    }

    private fun insertMethod(
        customerId: UUID,
        provider: String,
        token: String,
        status: String,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO payment_method (
                id, customer_id, provider, token_reference, display_alias,
                card_brand, last_four, status, created_at, updated_at
            ) VALUES (?, ?, ?, ?, 'Legacy card', 'VISA', '4242', ?, ?, ?)
            """.trimIndent(),
            id,
            customerId,
            provider,
            token,
            status,
            Timestamp.from(MIGRATION_TIME),
            Timestamp.from(MIGRATION_TIME),
        )
        return id
    }

    private fun insertCurrentMethod(
        customerId: UUID,
        token: String,
        providerReference: String?,
        provider: String = "TOSS_PAYMENTS",
        status: String = "ACTIVE",
        isDefault: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO payment_method (
                id, customer_id, provider, token_reference, provider_customer_reference,
                display_alias, card_brand, last_four, status, is_default, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, 'Card', 'VISA', '4242', ?, ?, ?, ?)
            """.trimIndent(),
            id,
            customerId,
            provider,
            token,
            providerReference,
            status,
            isDefault,
            Timestamp.from(MIGRATION_TIME),
            Timestamp.from(MIGRATION_TIME),
        )
        return id
    }

    private fun insertExternalPayment(
        customerId: UUID,
        methodId: UUID,
    ): UUID {
        val paymentId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO payment_payment (
                id, order_id, customer_id, payment_method_id, type, approval_state,
                requested_amount_krw, currency, source_reference, correlation_id,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, 'EXTERNAL', 'READY', 1000, 'KRW', ?, ?, ?, ?)
            """.trimIndent(),
            paymentId,
            UUID.randomUUID(),
            customerId,
            methodId,
            "migration-test:$paymentId",
            "migration-test",
            Timestamp.from(MIGRATION_TIME),
            Timestamp.from(MIGRATION_TIME),
        )
        return paymentId
    }

    private fun insertRegistration(
        customerId: UUID,
        methodId: UUID,
        status: String,
        terminalAt: Instant?,
        retentionExpiresAt: Instant?,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO payment_method_registration (
                id, actor_id, operation, idempotency_key, customer_id,
                intended_payment_method_id, provider, authorization_key_hash, payload_hash,
                display_alias, provider_customer_reference, status,
                first_response_status, first_response_body,
                started_at, updated_at, terminal_at, retention_expires_at
            ) VALUES (?, ?, 'REGISTER_PAYMENT_METHOD_V1', ?, ?, ?, 'TOSS_PAYMENTS', ?, ?,
                      'Card', ?, ?, 201, '{}', ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            customerId,
            "migration-${UUID.randomUUID()}",
            customerId,
            methodId,
            "a".repeat(64),
            "b".repeat(64),
            "bf_${"e".repeat(43)}",
            status,
            Timestamp.from(MIGRATION_TIME.minusSeconds(1)),
            Timestamp.from(MIGRATION_TIME),
            terminalAt?.let(Timestamp::from),
            retentionExpiresAt?.let(Timestamp::from),
        )
    }

    private inline fun <reified T : Any> methodColumn(
        methodId: UUID,
        column: String,
    ): T? = jdbcTemplate.queryForObject("SELECT $column FROM payment_method WHERE id = ?", T::class.java, methodId)

    private fun tableCount(): Long =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM information_schema.tables
             WHERE table_schema = 'public' AND table_name IN (
                 'payment_method_registration', 'payment_method_default_command',
                 'payment_method_deactivation', 'payment_provider_notification_inbox',
                 'payment_provider_request_snapshot'
             )
            """.trimIndent(),
            Long::class.java,
        )!!

    private fun columnCount(
        table: String,
        column: String,
    ): Long =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM information_schema.columns
             WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
            """.trimIndent(),
            Long::class.java,
            table,
            column,
        )!!

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
        target?.let(configuration::target)
        return configuration.load()
    }
}

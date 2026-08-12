@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.support.internal

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
internal class SupportProfileChangeMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(BEANFLOW_POSTGRES_IMAGE)

        val NOW: Instant = Instant.parse("2026-08-13T00:00:00Z")
    }

    private val jdbcTemplate by lazy {
        JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @BeforeEach
    fun migrateFresh() {
        flyway(cleanDisabled = false).clean()
        flyway().migrate()
    }

    @Test
    fun `V48 creates Support workflow and owner-local history reset and notification targets`() {
        assertThat(appliedVersions()).contains("48")
        assertThat(
            jdbcTemplate.queryForList(
                """
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name IN (
                     'support_profile_change', 'support_profile_change_notification',
                     'support_profile_change_idempotency',
                     'identity_customer_profile_change_history', 'identity_customer_profile_reset_intent',
                     'identity_customer_profile_notification_target',
                     'merchant_store_profile_change_history', 'merchant_store_profile_reset_intent',
                     'merchant_store_profile_notification_target',
                     'delivery_courier_profile_change_history', 'delivery_courier_profile_reset_intent',
                     'delivery_courier_profile_notification_target'
                   )
                 ORDER BY table_name
                """.trimIndent(),
                String::class.java,
            ),
        ).hasSize(12)
    }

    @Test
    fun `Support workflow persists digest and masked metadata but no raw payload or secret`() {
        assertThat(
            jdbcTemplate.queryForList(
                """
                SELECT table_name || '.' || column_name
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name LIKE '%profile%'
                   AND column_name ~ '(^|_)(raw|password|otp|mfa|pan|cvc|token|secret)($|_)'
                 ORDER BY table_name, ordinal_position
                """.trimIndent(),
                String::class.java,
            ),
        ).isEmpty()
        assertThat(columns("support_profile_change"))
            .contains("payload_digest", "expected_profile_version", "masked_before", "masked_after")
            .doesNotContain("payload_json", "new_value", "old_value")
    }

    @Test
    fun `R4 rows accept intent metadata but reject a secret-shaped purpose and invalid target kind`() {
        val customerId = UUID.randomUUID()
        insertCustomerProfile(customerId)
        val historyId = UUID.randomUUID()
        insertCustomerHistory(historyId, customerId, "CUSTOMER_CREDENTIAL_RESET", 0, 0)
        jdbcTemplate.update(
            """
            INSERT INTO identity_customer_profile_reset_intent (
                id, customer_id, profile_change_history_id, intent_type, state, created_at
            ) VALUES (?, ?, ?, 'CREDENTIAL_RESET', 'REQUESTED', ?)
            """.trimIndent(),
            UUID.randomUUID(),
            customerId,
            historyId,
            Timestamp.from(NOW),
        )

        assertThatThrownBy {
            insertCustomerHistory(UUID.randomUUID(), customerId, "PASSWORD_DIRECT_SET", 0, 0)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO identity_customer_profile_notification_target (
                    id, profile_change_history_id, target_kind, channel_type, destination_ciphertext,
                    destination_key_version, destination_aad_version, masked_destination, created_at
                ) VALUES (?, ?, 'FALLBACK', 'PHONE', 'vault:v7:phone', 7, 1, '***-****-5678', ?)
                """.trimIndent(),
                UUID.randomUUID(),
                historyId,
                Timestamp.from(NOW),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `S60 action constraints admit only PROFILE_CHANGE with PROFILE_CHANGE_REQUEST target`() {
        val definition =
            jdbcTemplate.queryForObject(
                """
                SELECT string_agg(pg_get_constraintdef(oid), ' ' ORDER BY conname)
                  FROM pg_constraint
                 WHERE conrelid IN ('support_action_request'::regclass, 'support_action_revision'::regclass)
                """.trimIndent(),
                String::class.java,
            )
        assertThat(definition).contains("PROFILE_CHANGE", "PROFILE_CHANGE_REQUEST")
    }

    private fun insertCustomerProfile(customerId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO identity_customer_support_profile (
                customer_id, display_name_ciphertext, display_name_key_version, display_name_aad_version,
                masked_display_name, primary_phone_ciphertext, primary_phone_key_version,
                primary_phone_aad_version, masked_primary_phone, created_at, updated_at, version
            ) VALUES (?, 'vault:v7:name', 7, 1, '김*현', 'vault:v7:phone', 7, 1,
                      '***-****-5678', ?, ?, 0)
            """.trimIndent(),
            customerId,
            Timestamp.from(NOW),
            Timestamp.from(NOW),
        )
    }

    private fun insertCustomerHistory(
        historyId: UUID,
        customerId: UUID,
        purpose: String,
        previousVersion: Long,
        currentVersion: Long,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO identity_customer_profile_change_history (
                id, customer_id, support_profile_change_id, purpose, risk_class,
                previous_version, current_version, masked_before, masked_after, changed_at
            ) VALUES (?, ?, ?, ?, 'R4', ?, ?, '***-****-5678', 'reset requested', ?)
            """.trimIndent(),
            historyId,
            customerId,
            UUID.randomUUID(),
            purpose,
            previousVersion,
            currentVersion,
            Timestamp.from(NOW),
        )
    }

    private fun columns(table: String): List<String> =
        jdbcTemplate.queryForList(
            """
            SELECT column_name FROM information_schema.columns
             WHERE table_schema = 'public' AND table_name = ?
             ORDER BY ordinal_position
            """.trimIndent(),
            String::class.java,
            table,
        ).filterNotNull()

    private fun appliedVersions(): List<String> =
        jdbcTemplate.queryForList(
            "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
            String::class.java,
        ).filterNotNull()

    private fun flyway(cleanDisabled: Boolean = true): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .cleanDisabled(cleanDisabled)
            .load()
}

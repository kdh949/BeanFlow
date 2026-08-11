@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.shared.internal

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
internal class ProtectedSupportProfileMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(BEANFLOW_POSTGRES_IMAGE)

        val NOW: Instant = Instant.parse("2026-08-11T00:00:00Z")
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
    fun `V41 creates three owner profiles separate exact indexes and a PII-free Support rate guard`() {
        assertThat(appliedVersions()).contains("41")
        assertThat(
            jdbcTemplate.queryForList(
                """
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name IN (
                     'identity_customer_support_profile', 'identity_customer_support_profile_exact_index',
                     'merchant_store_support_profile', 'merchant_store_support_profile_exact_index',
                     'delivery_external_courier_support_profile', 'delivery_external_courier_support_profile_exact_index',
                     'support_subject_search_rate_window'
                   )
                 ORDER BY table_name
                """.trimIndent(),
                String::class.java,
            ),
        ).hasSize(7)
        assertThat(
            jdbcTemplate.queryForList(
                """
                SELECT table_name || '.' || column_name
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name IN (
                     'identity_customer_support_profile', 'merchant_store_support_profile',
                     'delivery_external_courier_support_profile', 'support_subject_search_rate_window'
                   )
                   AND column_name ~ '(^|_)(pan|cvc|cvv|otp|password|token|secret)($|_)'
                """.trimIndent(),
                String::class.java,
            ),
        ).isEmpty()
        assertThat(
            jdbcTemplate.queryForList(
                """
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'support_subject_search_rate_window'
                 ORDER BY ordinal_position
                """.trimIndent(),
                String::class.java,
            ),
        ).containsExactly("actor_id", "window_started_at", "attempt_count", "updated_at")
    }

    @Test
    fun `owner profile constraints bind ciphertext versions require masked contacts and preserve owner foreign keys`() {
        val customerId = UUID.randomUUID()
        insertCustomer(customerId)

        assertThatThrownBy {
            insertCustomer(UUID.randomUUID(), displayCiphertext = "vault:v8:display", displayVersion = 7)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertCustomer(UUID.randomUUID(), phoneCiphertext = null, phoneVersion = null, maskedPhone = null, includeEmail = false)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertCustomer(UUID.randomUUID(), maskedPhone = "+82-10-1234-5678")
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO merchant_store_support_profile (
                    store_id, legal_display_name_ciphertext, legal_display_name_key_version,
                    legal_display_name_aad_version, masked_display_name,
                    support_phone_ciphertext, support_phone_key_version, support_phone_aad_version,
                    masked_support_phone, created_at, updated_at
                ) VALUES (?, 'vault:v7:legal', 7, 1, '매*점', 'vault:v7:phone', 7, 1,
                          '***-****-5678', ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                Timestamp.from(NOW),
                Timestamp.from(NOW),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `exact index permits shared contact candidates but rejects malformed digest version and duplicate subject rows`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        insertCustomer(first)
        insertCustomer(second)
        val digest = ByteArray(32) { 9 }

        insertCustomerIndex(first, "PHONE", 3, digest)
        insertCustomerIndex(second, "PHONE", 3, digest)
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM identity_customer_support_profile_exact_index
                 WHERE criterion_type = 'PHONE' AND index_key_version = 3 AND blind_index = ?
                """.trimIndent(),
                Long::class.java,
                digest,
            ),
        ).isEqualTo(2)
        assertThatThrownBy { insertCustomerIndex(first, "PHONE", 3, digest) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertCustomerIndex(first, "PHONE", 0, digest) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertCustomerIndex(first, "PHONE", 4, ByteArray(31)) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertCustomerIndex(first, "ADDRESS", 4, digest) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `rate window enforces a UTC five-minute boundary and count cap without search data`() {
        val actorId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO support_subject_search_rate_window (actor_id, window_started_at, attempt_count, updated_at)
            VALUES (?, TIMESTAMPTZ '2026-08-11 00:00:00Z', 30, TIMESTAMPTZ '2026-08-11 00:01:00Z')
            """.trimIndent(),
            actorId,
        )
        assertThatThrownBy {
            jdbcTemplate.update(
                "UPDATE support_subject_search_rate_window SET attempt_count = 31 WHERE actor_id = ?",
                actorId,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO support_subject_search_rate_window (actor_id, window_started_at, attempt_count, updated_at)
                VALUES (?, TIMESTAMPTZ '2026-08-11 00:02:00Z', 1, TIMESTAMPTZ '2026-08-11 00:02:00Z')
                """.trimIndent(),
                UUID.randomUUID(),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private fun insertCustomer(
        customerId: UUID,
        displayCiphertext: String = "vault:v7:display",
        displayVersion: Int = 7,
        phoneCiphertext: String? = "vault:v7:phone",
        phoneVersion: Int? = 7,
        maskedPhone: String? = "***-****-5678",
        includeEmail: Boolean = true,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO identity_customer_support_profile (
                customer_id, display_name_ciphertext, display_name_key_version, display_name_aad_version,
                masked_display_name,
                primary_phone_ciphertext, primary_phone_key_version, primary_phone_aad_version, masked_primary_phone,
                primary_email_ciphertext, primary_email_key_version, primary_email_aad_version, masked_primary_email,
                created_at, updated_at
            ) VALUES (?, ?, ?, 1, '홍*동', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            customerId,
            displayCiphertext,
            displayVersion,
            phoneCiphertext,
            phoneVersion,
            phoneVersion?.let { 1 },
            maskedPhone,
            if (includeEmail) "vault:v7:email" else null,
            if (includeEmail) 7 else null,
            if (includeEmail) 1 else null,
            if (includeEmail) "h***@e***.com" else null,
            Timestamp.from(NOW),
            Timestamp.from(NOW),
        )
    }

    private fun insertCustomerIndex(
        customerId: UUID,
        criterionType: String,
        keyVersion: Int,
        digest: ByteArray,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO identity_customer_support_profile_exact_index (
                customer_id, criterion_type, index_key_version, blind_index, created_at
            ) VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            customerId,
            criterionType,
            keyVersion,
            digest,
            Timestamp.from(NOW),
        )
    }

    private fun appliedVersions(): List<String?> =
        jdbcTemplate.queryForList(
            "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL",
            String::class.java,
        )

    private fun flyway(cleanDisabled: Boolean = true): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .cleanDisabled(cleanDisabled)
            .load()
}

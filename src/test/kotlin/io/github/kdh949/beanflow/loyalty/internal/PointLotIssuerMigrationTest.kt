@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.BeanflowApplication
import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

internal class PointLotIssuerMigrationTest : IsolatedPostgresSupport() {
    companion object {
    }

    private val jdbcTemplate by lazy {
        JdbcTemplate(
            DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password),
        )
    }

    @BeforeEach
    fun resetToLegacySchema() {
        flyway(cleanDisabled = false).clean()
        flyway(target = "13").migrate()
    }

    @Test
    fun `empty legacy database receives final issuer not-null checks`() {
        migrateCurrent()

        val accountId = UUID.randomUUID()
        val lotId = UUID.randomUUID()
        insertAccount(accountId)

        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO loyalty_point_lot (
                    id, point_account_id, available_amount_krw, reserved_amount_krw, expires_at
                ) VALUES (?, ?, 100, 0, TIMESTAMPTZ '2030-01-01 00:00:00+00')
                """.trimIndent(),
                lotId,
                accountId,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO loyalty_point_lot (
                    id, point_account_id, available_amount_krw, reserved_amount_krw, expires_at,
                    issuer_type, issuer_reference
                ) VALUES (?, ?, 100, 0, TIMESTAMPTZ '2030-01-01 00:00:00+00', 'PLATFORM', '   ')
                """.trimIndent(),
                UUID.randomUUID(),
                accountId,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO loyalty_point_lot (
                    id, point_account_id, available_amount_krw, reserved_amount_krw, expires_at,
                    issuer_type, issuer_reference
                ) VALUES (?, ?, 100, 0, TIMESTAMPTZ '2030-01-01 00:00:00+00', 'UNKNOWN', 'unknown:1')
                """.trimIndent(),
                UUID.randomUUID(),
                accountId,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        jdbcTemplate.update(
            """
            INSERT INTO loyalty_point_lot (
                id, point_account_id, available_amount_krw, reserved_amount_krw, expires_at,
                issuer_type, issuer_reference
            ) VALUES (?, ?, 100, 0, TIMESTAMPTZ '2030-01-01 00:00:00+00', 'STORE', 'store:verified')
            """.trimIndent(),
            lotId,
            accountId,
        )

        assertThatThrownBy {
            jdbcTemplate.update(
                "UPDATE loyalty_point_lot SET issuer_reference = 'store:changed' WHERE id = ?",
                lotId,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        jdbcTemplate.update(
            "UPDATE loyalty_point_lot SET available_amount_krw = 90 WHERE id = ?",
            lotId,
        )
        assertThat(jdbcTemplate.queryForObject("SELECT available_amount_krw FROM loyalty_point_lot WHERE id = ?", Long::class.java, lotId))
            .isEqualTo(90)
    }

    @Test
    fun `verified legacy mapping backfills the exact issuer snapshot`() {
        val accountId = UUID.randomUUID()
        val lotId = UUID.randomUUID()
        insertAccount(accountId)
        insertLegacyLot(lotId, accountId)
        createVerifiedMappingRelation()
        jdbcTemplate.update(
            """
            INSERT INTO loyalty_point_lot_issuer_precheck (
                point_lot_id, issuer_type, issuer_reference, source_reference, verified_at
            ) VALUES (
                ?, 'BRAND', 'brand:verified-42', 'archive:issuer-ledger:42',
                TIMESTAMPTZ '2026-08-01 00:00:00+00'
            )
            """.trimIndent(),
            lotId,
        )

        migrateCurrent()

        val issuer =
            jdbcTemplate.queryForMap(
                "SELECT issuer_type, issuer_reference FROM loyalty_point_lot WHERE id = ?",
                lotId,
            )
        assertThat(issuer).containsEntry("issuer_type", "BRAND")
        assertThat(issuer).containsEntry("issuer_reference", "brand:verified-42")
    }

    @Test
    fun `missing legacy mapping fails without a guessed platform backfill`() {
        val accountId = UUID.randomUUID()
        val lotId = UUID.randomUUID()
        insertAccount(accountId)
        insertLegacyLot(lotId, accountId)

        assertThatThrownBy { migrateCurrent() }
            .hasStackTraceContaining("PointLot issuer precheck failed")

        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_name = 'loyalty_point_lot' AND column_name IN ('issuer_type', 'issuer_reference')
                """.trimIndent(),
                Long::class.java,
            ),
        ).isZero()
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM loyalty_point_lot WHERE id = ?",
                Long::class.java,
                lotId,
            ),
        ).isOne()
    }

    @Test
    fun `invalid source-evidenced mapping fails closed`() {
        val accountId = UUID.randomUUID()
        val lotId = UUID.randomUUID()
        insertAccount(accountId)
        insertLegacyLot(lotId, accountId)
        createVerifiedMappingRelation()
        jdbcTemplate.update(
            """
            INSERT INTO loyalty_point_lot_issuer_precheck (
                point_lot_id, issuer_type, issuer_reference, source_reference, verified_at
            ) VALUES (
                ?, 'PLATFORM', 'platform:must-not-be-defaulted', '',
                TIMESTAMPTZ '2026-08-01 00:00:00+00'
            )
            """.trimIndent(),
            lotId,
        )

        assertThatThrownBy { migrateCurrent() }
            .hasStackTraceContaining("PointLot issuer precheck failed")
    }

    @Test
    fun `unresolvable legacy issuer blocks Spring application startup`() {
        val accountId = UUID.randomUUID()
        insertAccount(accountId)
        insertLegacyLot(UUID.randomUUID(), accountId)

        assertThatThrownBy {
            SpringApplicationBuilder(BeanflowApplication::class.java)
                .web(WebApplicationType.NONE)
                .properties(
                    "spring.datasource.url=${postgres.jdbcUrl}",
                    "spring.datasource.username=${postgres.username}",
                    "spring.datasource.password=${postgres.password}",
                    "beanflow.security.jwk-set-uri=https://issuer.example/jwks",
                ).run()
        }.hasStackTraceContaining("PointLot issuer precheck failed")
    }

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
        if (target != null) {
            configuration.target(target)
        }
        return configuration.load()
    }

    private fun insertAccount(accountId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO loyalty_point_account (id, customer_id, available_points_krw, reserved_points_krw)
            VALUES (?, ?, 100, 0)
            """.trimIndent(),
            accountId,
            UUID.randomUUID(),
        )
    }

    private fun insertLegacyLot(
        lotId: UUID,
        accountId: UUID,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO loyalty_point_lot (
                id, point_account_id, available_amount_krw, reserved_amount_krw, expires_at
            ) VALUES (?, ?, 100, 0, TIMESTAMPTZ '2030-01-01 00:00:00+00')
            """.trimIndent(),
            lotId,
            accountId,
        )
    }

    private fun createVerifiedMappingRelation() {
        jdbcTemplate.execute(
            """
            CREATE TABLE loyalty_point_lot_issuer_precheck (
                point_lot_id uuid PRIMARY KEY,
                issuer_type varchar(16) NOT NULL,
                issuer_reference varchar(240) NOT NULL,
                source_reference varchar(240) NOT NULL,
                verified_at timestamptz NOT NULL
            )
            """.trimIndent(),
        )
    }
}

@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

internal class CouponBurdenMigrationTest : IsolatedPostgresSupport() {
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
        flyway(target = "18").migrate()
    }

    @Test
    fun `active legacy campaign blocks migration without a guessed burden`() {
        insertLegacyCampaign(active = true)

        assertThatThrownBy { migrateCurrent() }
            .hasStackTraceContaining("Coupon burden activation failed")

        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_name = 'promotion_campaign' AND column_name = 'cost_bearer'
                """.trimIndent(),
                Long::class.java,
            ),
        ).isZero()
    }

    @Test
    fun `inactive legacy campaign remains unresolved and cannot be activated`() {
        val campaignId = insertLegacyCampaign(active = false)
        migrateCurrent()

        assertThatThrownBy {
            jdbcTemplate.update(
                "UPDATE promotion_campaign SET active = true WHERE id = ?",
                campaignId,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `database rejects invalid shared campaign shares`() {
        migrateCurrent()

        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO promotion_campaign (
                    id, store_id, active, discount_type, fixed_amount_krw, rate_bps,
                    minimum_eligible_subtotal_krw, maximum_discount_krw, all_menus_eligible,
                    cost_bearer, platform_share_bps, store_share_bps
                ) VALUES (?, ?, true, 'FIXED_KRW', 100, NULL, 0, NULL, true, 'SHARED', 4000, 5000)
                """.trimIndent(),
                UUID.randomUUID(),
                UUID.randomUUID(),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private fun insertLegacyCampaign(active: Boolean): UUID {
        val campaignId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO promotion_campaign (
                id, store_id, active, discount_type, fixed_amount_krw, rate_bps,
                minimum_eligible_subtotal_krw, maximum_discount_krw, all_menus_eligible
            ) VALUES (?, ?, ?, 'FIXED_KRW', 100, NULL, 0, NULL, true)
            """.trimIndent(),
            campaignId,
            UUID.randomUUID(),
            active,
        )
        return campaignId
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
}

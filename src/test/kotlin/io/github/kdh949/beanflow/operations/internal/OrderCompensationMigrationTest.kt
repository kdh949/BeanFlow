package io.github.kdh949.beanflow.operations.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.UUID

@Testcontainers
internal class OrderCompensationMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:17.6"))
    }

    private val jdbcTemplate by lazy {
        JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @Test
    fun `zero-row prechecks migrate to the complete shared compensation shape`() {
        resetTo("7")

        flyway().migrate()

        assertThat(
            columnCount(
                "operations_order_compensation_case",
                "terminal_order_version",
                "trigger",
                "source_reference",
            ),
        ).isEqualTo(3)
        assertThat(
            columnCount(
                "fulfillment_pickup_reservation",
                "restoration_source_reference",
                "restoration_trigger",
            ),
        ).isEqualTo(2)
        assertThat(
            columnCount(
                "promotion_coupon_reservation",
                "restoration_trigger",
                "restoration_policy_version_id",
                "restoration_disposition",
            ),
        ).isEqualTo(3)
        assertThat(
            columnCount(
                "loyalty_point_reservation",
                "restoration_trigger",
                "restoration_policy_version_id",
            ),
        ).isEqualTo(2)
        assertThat(tableCount("promotion_compensation_coupon_terms_snapshot")).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM operations_expired_benefit_policy_head",
                Long::class.java,
            ),
        ).isEqualTo(5)
        jdbcTemplate.update(
            """
            INSERT INTO operations_operator_permission_grant (
                actor_id, permission, state, granted_at, version, audit_source_reference
            ) VALUES (?, 'ORDER_COMPENSATION_READ', 'ACTIVE', now(), 1, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            "migration-test:${UUID.randomUUID()}",
        )
    }

    @Test
    fun `V8 rejects an injected legacy rejection compensation row without guessing a backfill`() {
        resetTo("7")
        jdbcTemplate.execute("CREATE TABLE operations_rejection_compensation_case (id uuid PRIMARY KEY)")
        jdbcTemplate.execute("CREATE TABLE operations_rejection_compensation_step (id uuid PRIMARY KEY)")
        jdbcTemplate.update(
            "INSERT INTO operations_rejection_compensation_case (id) VALUES (?)",
            UUID.randomUUID(),
        )

        assertThatThrownBy { flyway(target = "8").migrate() }
            .hasStackTraceContaining("V8 OrderCompensation clean-cutover precheck failed")
        assertThat(tableCount("operations_order_compensation_case")).isZero()
    }

    @Test
    fun `V9 rejects an injected legacy rejection release row without guessing a trigger`() {
        resetTo("8")
        jdbcTemplate.execute("ALTER TABLE fulfillment_pickup_reservation DROP CONSTRAINT chk_pickup_reservation_state")
        val slotId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO fulfillment_pickup_slot (
                id, store_id, starts_at, ends_at, capacity, reserved_count, confirmed_count, version
            ) VALUES (?, ?, now(), now() + interval '1 hour', 1, 0, 0, 0)
            """.trimIndent(),
            slotId,
            UUID.randomUUID(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO fulfillment_pickup_reservation (
                id, order_id, slot_id, state, expires_at, source_reference, created_at, updated_at, version
            ) VALUES (?, ?, ?, 'RELEASED_BY_REJECTION', now() + interval '1 hour', ?, now(), now(), 0)
            """.trimIndent(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            slotId,
            "legacy-pickup:${UUID.randomUUID()}",
        )

        assertThatThrownBy { flyway(target = "9").migrate() }
            .hasStackTraceContaining("V9 order termination clean-cutover precheck failed")
        assertThat(columnCount("fulfillment_pickup_reservation", "restoration_trigger")).isZero()
    }

    @Test
    fun `V22 rejects an injected legacy benefit restoration row without guessing policy metadata`() {
        resetTo("21")
        val accountId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO loyalty_point_account (
                id, customer_id, available_points_krw, reserved_points_krw, version
            ) VALUES (?, ?, 0, 0, 0)
            """.trimIndent(),
            accountId,
            UUID.randomUUID(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO loyalty_point_reservation (
                id, order_id, point_account_id, amount_krw, state, reservation_expires_at,
                source_reference, created_at, updated_at, restoration_source_reference, version
            ) VALUES (?, ?, ?, 1, 'RESTORED', now(), ?, now(), now(), ?, 0)
            """.trimIndent(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            accountId,
            "legacy-point-reservation:${UUID.randomUUID()}",
            "legacy-restoration:${UUID.randomUUID()}",
        )

        assertThatThrownBy { flyway(target = "22").migrate() }
            .hasStackTraceContaining("V22 benefit restoration clean-cutover precheck failed")
        assertThat(columnCount("loyalty_point_reservation", "restoration_trigger")).isZero()
    }

    private fun resetTo(target: String) {
        flyway(cleanDisabled = false).clean()
        flyway(target = target).migrate()
    }

    private fun columnCount(
        table: String,
        vararg columns: String,
    ): Long =
        columns
            .count { column ->
                requireNotNull(
                    jdbcTemplate.queryForObject(
                        """
                        SELECT count(*) FROM information_schema.columns
                         WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                        """.trimIndent(),
                        Long::class.java,
                        table,
                        column,
                    ),
                ) == 1L
            }.toLong()

    private fun tableCount(table: String): Long =
        requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'public' AND table_name = ?
                """.trimIndent(),
                Long::class.java,
                table,
            ),
        )

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

package io.github.kdh949.beanflow.loyalty.internal

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
import org.testcontainers.utility.DockerImageName
import java.util.UUID

@Testcontainers
internal class PointAdjustmentMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:17.6"))
    }

    private val jdbcTemplate by lazy {
        JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @BeforeEach
    fun resetToV30() {
        flyway(cleanDisabled = false).clean()
        flyway(target = "30").migrate()
    }

    @Test
    fun `V31 deterministically backfills current transaction balance effects`() {
        val accountId = insertAccount()
        val lotId = insertLot(accountId)
        jdbcTemplate.execute("ALTER TABLE loyalty_point_transaction DROP CONSTRAINT chk_point_transaction_restoration_metadata")
        val types =
            linkedMapOf(
                "ACCRUAL" to "CREDIT",
                "RESTORE" to "CREDIT",
                "COMPENSATION" to "CREDIT",
                "USE" to "DEBIT",
                "EXPIRATION" to "DEBIT",
                "RECOVERY" to "DEBIT",
                "RESTORE_SKIPPED_EXPIRED" to "NONE",
            )
        types.keys.forEach { type -> insertLegacyTransaction(accountId, lotId, type) }

        migrateV31()

        val actual =
            jdbcTemplate.query(
                "SELECT type, balance_effect FROM loyalty_point_transaction ORDER BY type",
            ) { resultSet, _ -> resultSet.getString("type") to resultSet.getString("balance_effect") }
                .toMap()
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(types)
    }

    @Test
    fun `V31 enforces adjustment effect source and terminal idempotency contract`() {
        migrateV31()
        val accountId = insertAccount()
        val lotId = insertLot(accountId)
        val adjustmentId = UUID.randomUUID()

        insertAdjustment(accountId, lotId, adjustmentId, "CREDIT")
        insertAdjustment(accountId, lotId, UUID.randomUUID(), "DEBIT")

        assertThatThrownBy { insertAdjustment(accountId, lotId, UUID.randomUUID(), "NONE") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO loyalty_point_transaction (
                    id, point_account_id, point_lot_id, amount_krw, type,
                    balance_effect, source_reference, occurred_at
                ) VALUES (?, ?, ?, 10, 'ADJUSTMENT', 'CREDIT', 'point-adjustment:invalid', now())
                """.trimIndent(),
                UUID.randomUUID(),
                accountId,
                lotId,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO loyalty_point_transaction (
                    id, point_account_id, point_lot_id, amount_krw, type,
                    balance_effect, source_reference, occurred_at
                ) VALUES (?, ?, ?, 10, 'USE', 'CREDIT', ?, now())
                """.trimIndent(),
                UUID.randomUUID(),
                accountId,
                lotId,
                "migration:wrong-effect:${UUID.randomUUID()}",
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        val actorId = UUID.randomUUID()
        val recordId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO loyalty_point_adjustment_command_idempotency (
                id, actor_id, point_account_id, operation, idempotency_key, payload_hash,
                response_status, response_body, response_version, created_at, retention_expires_at
            ) VALUES (
                ?, ?, ?, 'POINT_ADJUSTMENT', 'migration-key-0001', repeat('a', 64),
                201, '{"account":{}}', 1,
                TIMESTAMPTZ '2026-08-04 00:00:00+00', TIMESTAMPTZ '2026-11-02 00:00:00+00'
            )
            """.trimIndent(),
            recordId,
            actorId,
            accountId,
        )
        assertThatThrownBy {
            jdbcTemplate.update(
                "UPDATE loyalty_point_adjustment_command_idempotency SET response_version = 2 WHERE id = ?",
                recordId,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO loyalty_point_adjustment_command_idempotency (
                    id, actor_id, point_account_id, operation, idempotency_key, payload_hash,
                    response_status, response_body, response_version, created_at, retention_expires_at
                ) VALUES (
                    ?, ?, ?, 'POINT_ADJUSTMENT', 'migration-key-0002', repeat('b', 64),
                    201, '{}', 1,
                    TIMESTAMPTZ '2026-08-04 00:00:00+00', TIMESTAMPTZ '2026-11-01 23:59:59+00'
                )
                """.trimIndent(),
                UUID.randomUUID(),
                actorId,
                accountId,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM pg_indexes
                 WHERE tablename = 'loyalty_point_adjustment_command_idempotency'
                   AND indexdef LIKE '%(retention_expires_at, id)%'
                """.trimIndent(),
                Long::class.java,
            ),
        ).isOne()
    }

    @Test
    fun `V31 refuses activation when issuer provenance is corrupted`() {
        val accountId = insertAccount()
        val lotId = insertLot(accountId)
        jdbcTemplate.execute("DROP TRIGGER point_lot_issuer_snapshot_immutable ON loyalty_point_lot")
        jdbcTemplate.execute("ALTER TABLE loyalty_point_lot DROP CONSTRAINT chk_point_lot_issuer_reference")
        jdbcTemplate.update("UPDATE loyalty_point_lot SET issuer_reference = ' ' WHERE id = ?", lotId)

        assertThatThrownBy { migrateV31() }
            .hasStackTraceContaining("PointLot issuer provenance is unresolved")
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM information_schema.columns
                 WHERE table_name = 'loyalty_point_transaction' AND column_name = 'balance_effect'
                """.trimIndent(),
                Long::class.java,
            ),
        ).isZero()
    }

    private fun insertAccount(): UUID {
        val accountId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO loyalty_point_account (
                id, customer_id, available_points_krw, reserved_points_krw, recovery_pending_krw
            ) VALUES (?, ?, 0, 0, 0)
            """.trimIndent(),
            accountId,
            UUID.randomUUID(),
        )
        return accountId
    }

    private fun insertLot(accountId: UUID): UUID {
        val lotId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO loyalty_point_lot (
                id, point_account_id, available_amount_krw, reserved_amount_krw,
                expires_at, issuer_type, issuer_reference
            ) VALUES (?, ?, 0, 0, TIMESTAMPTZ '2030-01-01 00:00:00+00', 'PLATFORM', 'migration:test')
            """.trimIndent(),
            lotId,
            accountId,
        )
        return lotId
    }

    private fun insertLegacyTransaction(
        accountId: UUID,
        lotId: UUID,
        type: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO loyalty_point_transaction (
                id, point_account_id, point_lot_id, amount_krw, type, source_reference, occurred_at
            ) VALUES (?, ?, ?, 10, ?, ?, now())
            """.trimIndent(),
            UUID.randomUUID(),
            accountId,
            lotId,
            type,
            "migration:$type:${UUID.randomUUID()}",
        )
    }

    private fun insertAdjustment(
        accountId: UUID,
        lotId: UUID,
        adjustmentId: UUID,
        effect: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO loyalty_point_transaction (
                id, point_account_id, point_lot_id, amount_krw, type,
                balance_effect, source_reference, occurred_at
            ) VALUES (?, ?, ?, 10, 'ADJUSTMENT', ?, ?, now())
            """.trimIndent(),
            UUID.randomUUID(),
            accountId,
            lotId,
            effect,
            "point-adjustment:$adjustmentId:lot:$lotId",
        )
    }

    private fun migrateV31() {
        flyway(target = "31").migrate()
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

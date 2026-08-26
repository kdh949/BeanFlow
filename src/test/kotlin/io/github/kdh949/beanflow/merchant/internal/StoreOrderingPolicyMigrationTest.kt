package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Instant
import java.util.UUID

internal class StoreOrderingPolicyMigrationTest : IsolatedPostgresSupport() {
    private val jdbc by lazy { JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)) }

    @BeforeEach
    fun cleanDatabase() {
        flyway(cleanDisabled = false).clean()
    }

    @Test
    fun `V69 backfills existing Store policy at version zero with a deterministic timestamp`() {
        flyway(target = "68").migrate()
        val existingStore = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, false, true, 0)",
            existingStore,
        )
        flyway().migrate()

        val row =
            jdbc.queryForMap(
                "SELECT accepting_orders, pickup_enabled, ordering_policy_version, ordering_policy_updated_at " +
                    "FROM merchant_store WHERE id = ?",
                existingStore,
            )

        assertThat(row)
            .containsEntry("accepting_orders", false)
            .containsEntry("pickup_enabled", true)
            .containsEntry("ordering_policy_version", 0L)
        assertThat((row["ordering_policy_updated_at"] as java.sql.Timestamp).toInstant()).isEqualTo(Instant.EPOCH)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success AND version = '69'",
                Long::class.java,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `V69 constrains the policy command replay ledger and registers its audit action`() {
        flyway().migrate()
        val actorId = UUID.randomUUID()
        val storeId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
            storeId,
        )

        jdbc.update(
            """
            INSERT INTO merchant_store_ordering_policy_command
                (id, actor_id, operation, idempotency_key, payload_hash, store_id, response_json,
                 created_at, retention_expires_at)
            VALUES (?, ?, 'REPLACE_STORE_ORDERING_POLICY_V1', 'ordering-policy-key-001', ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            actorId,
            "a".repeat(64),
            storeId,
            """{"storeId":"$storeId","version":1}""",
            java.sql.Timestamp.from(Instant.parse("2026-08-27T00:00:00Z")),
            java.sql.Timestamp.from(Instant.parse("2026-11-25T00:00:00Z")),
        )

        assertThat(
            jdbc.queryForList(
                "SELECT action FROM operations_audit_action_category WHERE action = 'STORE_ORDERING_POLICY_UPDATED'",
                String::class.java,
            ),
        ).containsExactly("STORE_ORDERING_POLICY_UPDATED")

        assertThatThrownBy {
            jdbc.update(
                """
                INSERT INTO merchant_store_ordering_policy_command
                    (id, actor_id, operation, idempotency_key, payload_hash, store_id, response_json,
                     created_at, retention_expires_at)
                VALUES (?, ?, 'REPLACE_STORE_ORDERING_POLICY_V1', 'ordering-policy-key-001', ?, ?, '{}', now(), now() + interval '90 days')
                """.trimIndent(),
                UUID.randomUUID(),
                actorId,
                "b".repeat(64),
                storeId,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `expired policy commands are deleted in bounded batches while live replay remains`() {
        flyway().migrate()
        val storeId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
            storeId,
        )
        insertCommand(storeId, "expired-policy-key-001", Instant.parse("2025-01-01T00:00:00Z"))
        insertCommand(storeId, "expired-policy-key-002", Instant.parse("2025-01-02T00:00:00Z"))
        insertCommand(storeId, "live-policy-key-0000001", Instant.parse("2026-08-01T00:00:00Z"))
        val cleanup = StoreOrderingPolicyCommandRetentionCleanup(jdbc)
        val now = Instant.parse("2026-08-27T00:00:00Z")

        assertThat(cleanup.deleteExpired(now, batchSize = 1)).isOne()
        assertThat(cleanup.deleteExpired(now, batchSize = 1)).isOne()
        assertThat(cleanup.deleteExpired(now, batchSize = 1)).isZero()
        assertThat(
            jdbc.queryForList(
                "SELECT idempotency_key FROM merchant_store_ordering_policy_command ORDER BY idempotency_key",
                String::class.java,
            ),
        ).containsExactly("live-policy-key-0000001")
        assertThatThrownBy { cleanup.deleteExpired(now, batchSize = 1_001) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun insertCommand(
        storeId: UUID,
        key: String,
        createdAt: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO merchant_store_ordering_policy_command
                (id, actor_id, operation, idempotency_key, payload_hash, store_id, response_json,
                 created_at, retention_expires_at)
            VALUES (?, ?, 'REPLACE_STORE_ORDERING_POLICY_V1', ?, ?, ?, '{}', ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            key,
            "a".repeat(64),
            storeId,
            java.sql.Timestamp.from(createdAt),
            java.sql.Timestamp.from(createdAt.plusSeconds(90L * 24 * 60 * 60)),
        )
    }

    private fun flyway(
        cleanDisabled: Boolean = true,
        target: String? = null,
    ): Flyway {
        val configuration =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .cleanDisabled(cleanDisabled)
        if (target != null) configuration.target(target)
        return configuration.load()
    }
}

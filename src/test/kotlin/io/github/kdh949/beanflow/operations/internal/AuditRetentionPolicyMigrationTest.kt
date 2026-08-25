package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

internal class AuditRetentionPolicyMigrationTest : IsolatedPostgresSupport() {
    companion object {
        val databaseSequence = AtomicInteger()
    }

    @Test
    fun `V39 classifies known legacy rows with preserve expiry provenance without changing their exact expiry`() {
        val dataSource = database("backfill")
        flyway(dataSource).target("38").load().migrate()
        val jdbc = JdbcTemplate(dataSource)
        val originalExpiry = Instant.parse("2037-03-04T05:06:07.123456Z")
        insertAudit(jdbc, "STOCK_RESERVED", originalExpiry)

        flyway(dataSource).target("39").load().migrate()

        val row =
            jdbc.queryForMap(
                """
                SELECT audit.audit_category, audit.retention_class, audit.retention_policy_version_id,
                       audit.retention_provenance, audit.retention_expires_at,
                       policy.duration_basis, policy.duration_value
                  FROM operations_audit_record audit
                  JOIN operations_retention_policy_version policy
                    ON policy.policy_version_id = audit.retention_policy_version_id
                """.trimIndent(),
            )
        assertThat(row["audit_category"]).isEqualTo("ORDER_AND_FULFILLMENT")
        assertThat(row["retention_class"]).isEqualTo("FINANCIAL_AUDIT")
        assertThat(row["retention_policy_version_id"]).isEqualTo(12L)
        assertThat(row["retention_provenance"]).isEqualTo("LEGACY_MIGRATION_CLASSIFICATION")
        assertThat(row["duration_basis"]).isEqualTo("PRESERVE_STORED_EXPIRY")
        assertThat(row["duration_value"]).isEqualTo(0)
        assertThat((row["retention_expires_at"] as Timestamp).toInstant()).isEqualTo(originalExpiry)
    }

    @Test
    fun `V39 allows an old binary audit insert through the database compatibility bridge`() {
        val jdbc = migrated("compatibility")

        insertAudit(jdbc, "STOCK_RESERVED", Instant.parse("2031-01-01T00:00:00Z"))

        val row =
            jdbc.queryForMap(
                """
                SELECT audit.audit_category, audit.retention_class, audit.retention_provenance,
                       policy.duration_basis
                  FROM operations_audit_record audit
                  JOIN operations_retention_policy_version policy
                    ON policy.policy_version_id = audit.retention_policy_version_id
                """.trimIndent(),
            )
        assertThat(row["audit_category"]).isEqualTo("ORDER_AND_FULFILLMENT")
        assertThat(row["retention_class"]).isEqualTo("FINANCIAL_AUDIT")
        assertThat(row["retention_provenance"]).isEqualTo("DATABASE_COMPATIBILITY_SNAPSHOT")
        assertThat(row["duration_basis"]).isEqualTo("SEOUL_CALENDAR_YEARS")
    }

    @Test
    fun `V39 aborts instead of assigning an unknown legacy audit action`() {
        val dataSource = database("unmapped")
        flyway(dataSource).target("38").load().migrate()
        val jdbc = JdbcTemplate(dataSource)
        insertAudit(jdbc, "LEGACY_UNKNOWN_ACTION", Instant.parse("2031-01-01T00:00:00Z"))

        assertThatThrownBy { flyway(dataSource).target("39").load().migrate() }
            .isInstanceOf(FlywayException::class.java)
            .hasMessageContaining("unmapped actions: LEGACY_UNKNOWN_ACTION")
        assertThat(
            jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success AND version = '39'",
                String::class.java,
            ),
        ).isEmpty()
    }

    private fun insertAudit(
        jdbc: JdbcTemplate,
        action: String,
        expiry: Instant,
    ) = jdbc.update(
        """
        INSERT INTO operations_audit_record (
            id, actor_id, actor_type, action, target_type, target_id, occurred_at, reason,
            before_summary, after_summary, correlation_id, source_reference, retention_expires_at
        ) VALUES (?, 'SYSTEM', 'SYSTEM', ?, 'MIGRATION_FIXTURE', ?, ?, 'MIGRATION_FIXTURE',
            '{}', '{}', ?, ?, ?)
        """.trimIndent(),
        UUID.randomUUID(),
        action,
        UUID.randomUUID(),
        Timestamp.from(Instant.parse("2026-08-01T00:00:00Z")),
        UUID.randomUUID().toString(),
        "migration:${UUID.randomUUID()}",
        Timestamp.from(expiry),
    )

    private fun migrated(name: String): JdbcTemplate =
        database(name).let { dataSource ->
            flyway(dataSource).load().migrate()
            JdbcTemplate(dataSource)
        }

    private fun flyway(dataSource: DataSource) = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")

    private fun database(prefix: String): DataSource {
        val name = "s10_${prefix}_${databaseSequence.incrementAndGet()}"
        return DriverManagerDataSource(
            postgres.createAdditionalDatabase(name),
            postgres.username,
            postgres.password,
        )
    }
}

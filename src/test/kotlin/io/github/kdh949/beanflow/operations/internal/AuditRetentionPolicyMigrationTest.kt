package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.BEANFLOW_POSTGRES_IMAGE
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
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
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

@Testcontainers(disabledWithoutDocker = true)
internal class AuditRetentionPolicyMigrationTest {
    companion object {
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(BEANFLOW_POSTGRES_IMAGE)

        val databaseSequence = AtomicInteger()
    }

    @Test
    fun `fresh PostgreSQL migration creates immutable policies audit constraints index and complete permissions`() {
        val jdbc = migrated("fresh")

        assertThat(
            jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String::class.java,
            ),
        ).isEqualTo("45")
        assertThat(count(jdbc, "operations_retention_policy_version")).isEqualTo(15)
        assertThat(count(jdbc, "operations_retention_policy_head")).isEqualTo(10)
        assertThat(
            jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns " +
                    "WHERE table_name = 'operations_audit_record' AND is_nullable = 'YES'",
                String::class.java,
            ),
        ).contains("audit_category", "retention_class", "retention_policy_version_id", "retention_provenance")
        assertThat(
            jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'operations_audit_record'::regclass",
                String::class.java,
            ),
        ).contains(
            "fk_audit_action_category",
            "fk_audit_retention_policy_version",
            "chk_audit_retention_class",
            "chk_audit_retention_provenance",
        )
        assertThat(
            jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_audit_retention'",
                String::class.java,
            ),
        ).contains("retention_expires_at", "id")

        OperatorPermission.entries.forEachIndexed { index, permission ->
            jdbc.update(
                """
                INSERT INTO operations_operator_permission_grant (
                    actor_id, permission, state, granted_at, revoked_at, version, audit_source_reference
                ) VALUES (?, ?, 'ACTIVE', now(), null, 1, ?)
                """.trimIndent(),
                UUID.nameUUIDFromBytes("permission:$index".toByteArray()),
                permission.name,
                "migration-permission:$index",
            )
        }
        assertThat(count(jdbc, "operations_operator_permission_grant"))
            .isEqualTo(OperatorPermission.entries.size.toLong())

        assertThatThrownBy {
            jdbc.update(
                "UPDATE operations_retention_policy_version SET duration_value = 4 WHERE policy_version_id = 1",
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbc.update(
                "UPDATE operations_audit_action_category SET audit_category = 'PII_ACCESS' WHERE action = 'STOCK_RESERVED'",
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertClassifiedAudit(jdbc, "UNDECLARED_ACTION")
        }.isInstanceOf(DataIntegrityViolationException::class.java)
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

    private fun insertClassifiedAudit(
        jdbc: JdbcTemplate,
        action: String,
    ) = jdbc.update(
        """
        INSERT INTO operations_audit_record (
            id, actor_id, actor_type, audit_category, action, target_type, target_id, occurred_at, reason,
            before_summary, after_summary, correlation_id, source_reference, retention_expires_at,
            retention_class, retention_policy_version_id, retention_provenance
        ) VALUES (?, 'SYSTEM', 'SYSTEM', 'FINANCIAL_TRANSACTION', ?, 'MIGRATION_FIXTURE', ?, now(),
            'MIGRATION_FIXTURE', '{}', '{}', ?, ?, now() + interval '5 years', 'FINANCIAL_AUDIT', 1,
            'APPEND_SNAPSHOT')
        """.trimIndent(),
        UUID.randomUUID(),
        action,
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        "migration:${UUID.randomUUID()}",
    )

    private fun count(
        jdbc: JdbcTemplate,
        table: String,
    ): Long = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!

    private fun migrated(name: String): JdbcTemplate =
        database(name).let { dataSource ->
            flyway(dataSource).load().migrate()
            JdbcTemplate(dataSource)
        }

    private fun flyway(dataSource: DataSource) = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")

    private fun database(prefix: String): DataSource {
        val name = "s10_${prefix}_${databaseSequence.incrementAndGet()}"
        JdbcTemplate(dataSource(postgres.databaseName)).execute("""CREATE DATABASE "$name" TEMPLATE template1""")
        return dataSource(name)
    }

    private fun dataSource(databaseName: String): DataSource {
        val withoutQuery = postgres.jdbcUrl.substringBefore('?')
        val query = postgres.jdbcUrl.substringAfter('?', "")
        val url = withoutQuery.substringBeforeLast('/') + "/" + databaseName + if (query.isEmpty()) "" else "?$query"
        return DriverManagerDataSource(url, postgres.username, postgres.password)
    }
}

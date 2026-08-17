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
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The V60 brand-command replay ledger and the operator permission it introduces.
 *
 * The ledger is what makes a retried brand command return the first response instead of running a
 * second time. Every constraint here exists so that a malformed replay row fails at write time
 * rather than being discovered when a retry silently creates a duplicate brand.
 */
internal class MerchantBrandCommandMigrationTest : IsolatedPostgresSupport() {
    companion object {
        private const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }

    private val jdbc by lazy { JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)) }

    @BeforeEach
    fun migrateFromCleanDatabase() {
        flyway(cleanDisabled = false).clean()
        flyway().migrate()
    }

    @Test
    fun `V60 creates the command ledger with its replay key and retention index`() {
        assertThat(
            jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'merchant_brand_command' ORDER BY indexname",
                String::class.java,
            ),
        ).containsExactly(
            "ix_merchant_brand_command_retention",
            "merchant_brand_command_actor_id_idempotency_key_key",
            "merchant_brand_command_pkey",
        )
        assertThat(
            jdbc.queryForObject("SELECT max(CAST(version AS integer)) FROM flyway_schema_history WHERE success", Int::class.java),
        ).isEqualTo(60)
    }

    @Test
    fun `the replay key spans the actor and key alone so a reused key cannot change command`() {
        insertCommand(ACTOR, "brand-key-0001", "CREATE_BRAND", HASH)

        // 같은 키를 다른 명령에 쓰는 것도 재사용이다. command_type이 payload_hash에 들어가므로
        // 애플리케이션은 이 행을 찾아 payload 불일치로 409를 낸다.
        assertThatThrownBy { insertCommand(ACTOR, "brand-key-0001", "ASSIGN_STORE_BRAND", HASH) }
            .isInstanceOf(DataIntegrityViolationException::class.java)

        // 다른 운영자는 같은 키를 쓸 수 있다. 멱등성 범위는 actor 단위다.
        insertCommand(UUID.fromString("70000000-0000-0000-0000-0000000000ff"), "brand-key-0001", "CREATE_BRAND", HASH)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_brand_command", Long::class.java)).isEqualTo(2)
    }

    @Test
    fun `malformed ledger rows are rejected instead of stored`() {
        assertThatThrownBy { insertCommand(ACTOR, "short", "CREATE_BRAND", HASH) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertCommand(ACTOR, " brand-key-0002 ", "CREATE_BRAND", HASH) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        // 제어 문자는 Kotlin escape로 쓴다. source에 그대로 넣으면 저장소의 secret scan이
        // 추적 텍스트 파일의 NUL 바이트를 거절한다.
        assertThatThrownBy { insertCommand(ACTOR, "brand-key\u0001-0003", "CREATE_BRAND", HASH) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertCommand(ACTOR, "brand-key-0004", "CREATE_BRAND", "not-a-sha-256") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertCommand(ACTOR, "brand-key-0005", "ARCHIVE_EVERYTHING", HASH) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertCommand(ACTOR, "brand-key-0006", "CREATE_BRAND", HASH, responseJson = "   ") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertCommand(ACTOR, "brand-key-0007", "CREATE_BRAND", HASH, retentionDays = 30) }
            .isInstanceOf(DataIntegrityViolationException::class.java)

        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_brand_command", Long::class.java)).isZero()
    }

    @Test
    fun `the operator permission vocabulary gains brand management and rejects unknown names`() {
        grant("STORE_BRAND_MANAGE")

        assertThatThrownBy { grant("STORE_BRAND_ADMIN") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(
            jdbc.queryForList(
                "SELECT permission FROM operations_operator_permission_grant WHERE actor_id = ?",
                String::class.java,
                ACTOR,
            ),
        ).containsExactly("STORE_BRAND_MANAGE")
    }

    @Test
    fun `the four brand audit actions are registered so an audit append is not rejected`() {
        assertThat(
            jdbc.query(
                """
                SELECT action, audit_category
                  FROM operations_audit_action_category
                 WHERE action LIKE 'BRAND%' OR action LIKE 'STORE_BRAND%'
                """.trimIndent(),
                { row, _ -> row.getString("action") to row.getString("audit_category") },
            ),
        ).containsExactlyInAnyOrder(
            "BRAND_CREATED" to "OPERATIONS_POLICY",
            "BRAND_UPDATED" to "OPERATIONS_POLICY",
            "STORE_BRAND_ASSIGNED" to "OPERATIONS_POLICY",
            "STORE_BRAND_CLEARED" to "OPERATIONS_POLICY",
        )
    }

    private fun grant(permission: String) {
        jdbc.update(
            """
            INSERT INTO operations_operator_permission_grant
                (actor_id, permission, state, granted_at, version, audit_source_reference)
            VALUES (?, ?, 'ACTIVE', now(), 1, ?)
            """.trimIndent(),
            ACTOR,
            permission,
            "brand-migration-test:$permission",
        )
    }

    private fun insertCommand(
        actorId: UUID,
        idempotencyKey: String,
        commandType: String,
        payloadHash: String,
        responseJson: String = """{"brandId":"00000000-0000-0000-0000-000000000001"}""",
        retentionDays: Long = 90,
    ) {
        val createdAt = Instant.parse("2026-08-15T00:00:00Z")
        jdbc.update(
            """
            INSERT INTO merchant_brand_command
                (id, actor_id, command_type, idempotency_key, payload_hash, response_json, created_at, retention_expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            actorId,
            commandType,
            idempotencyKey,
            payloadHash,
            responseJson,
            Timestamp.from(createdAt),
            Timestamp.from(createdAt.plus(retentionDays, ChronoUnit.DAYS)),
        )
    }

    private fun flyway(cleanDisabled: Boolean = true): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .target("60")
            .cleanDisabled(cleanDisabled)
            .load()
}

private val ACTOR: UUID = UUID.fromString("70000000-0000-0000-0000-000000000001")

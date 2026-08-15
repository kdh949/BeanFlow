package io.github.kdh949.beanflow.merchant.internal

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
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The V61 region-command replay ledger, its region-catalog index and the audit action it registers.
 *
 * Region assignment is a store owner's command, so the ledger is separate from the operator brand
 * ledger even though the two have the same shape: `(actor_id, idempotency_key)` uniqueness only
 * means something within one set of actors.
 */
@Testcontainers(disabledWithoutDocker = true)
internal class MerchantStoreRegionCommandMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(BEANFLOW_POSTGRES_IMAGE)

        private const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }

    private val jdbc by lazy { JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)) }

    @BeforeEach
    fun migrateFromCleanDatabase() {
        flyway(cleanDisabled = false).clean()
        flyway().migrate()
    }

    @Test
    fun `V61 creates the command ledger with its replay key and retention index`() {
        assertThat(
            jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'merchant_store_region_command' ORDER BY indexname",
                String::class.java,
            ),
        ).containsExactly(
            "ix_merchant_store_region_command_retention",
            "merchant_store_region_command_actor_id_idempotency_key_key",
            "merchant_store_region_command_pkey",
        )
        assertThat(
            jdbc.queryForObject("SELECT max(CAST(version AS integer)) FROM flyway_schema_history WHERE success", Int::class.java),
        ).isEqualTo(61)
    }

    @Test
    fun `the replay key spans the actor and key alone`() {
        insertCommand(ACTOR, "region-key-0001")

        assertThatThrownBy { insertCommand(ACTOR, "region-key-0001") }
            .isInstanceOf(DataIntegrityViolationException::class.java)

        // 다른 매장주는 같은 키를 쓸 수 있다. 멱등성 범위는 actor 단위다.
        insertCommand(UUID.fromString("70000000-0000-0000-0000-0000000000ff"), "region-key-0001")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_store_region_command", Long::class.java)).isEqualTo(2)
    }

    @Test
    fun `malformed ledger rows are rejected instead of stored`() {
        assertThatThrownBy { insertCommand(ACTOR, "short") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertCommand(ACTOR, " region-key-0002 ") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        // 제어 문자는 Kotlin escape로 쓴다. source에 그대로 넣으면 저장소의 secret scan이
        // 추적 텍스트 파일의 NUL 바이트를 거절한다.
        assertThatThrownBy { insertCommand(ACTOR, "region-key\u0001-0003") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertCommand(ACTOR, "region-key-0004", payloadHash = "not-a-sha-256") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        // 지역은 비울 수 없으므로 해제 명령은 어휘에 없다.
        assertThatThrownBy { insertCommand(ACTOR, "region-key-0005", commandType = "CLEAR_STORE_REGION") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertCommand(ACTOR, "region-key-0006", responseJson = "   ") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertCommand(ACTOR, "region-key-0007", retentionDays = 30) }
            .isInstanceOf(DataIntegrityViolationException::class.java)

        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_store_region_command", Long::class.java)).isZero()
    }

    @Test
    fun `the region audit action is registered so an audit append is not rejected`() {
        assertThat(
            jdbc.query(
                "SELECT action, audit_category FROM operations_audit_action_category WHERE action LIKE 'STORE_REGION%'",
                { row, _ -> row.getString("action") to row.getString("audit_category") },
            ),
        ).containsExactly("STORE_REGION_ASSIGNED" to "OPERATIONS_POLICY")
    }

    @Test
    fun `the region catalog is indexed on the cursor sort tuple`() {
        assertThat(
            jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_merchant_region_full_name'",
                String::class.java,
            ),
        ).singleElement()
            .asString()
            .contains("(full_name, code)")
    }

    private fun insertCommand(
        actorId: UUID,
        idempotencyKey: String,
        commandType: String = "ASSIGN_STORE_REGION",
        payloadHash: String = HASH,
        responseJson: String = """{"storeId":"00000000-0000-0000-0000-000000000001"}""",
        retentionDays: Long = 90,
    ) {
        val createdAt = Instant.parse("2026-08-15T00:00:00Z")
        jdbc.update(
            """
            INSERT INTO merchant_store_region_command
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
            .target("61")
            .cleanDisabled(cleanDisabled)
            .load()
}

private val ACTOR: UUID = UUID.fromString("70000000-0000-0000-0000-000000000002")

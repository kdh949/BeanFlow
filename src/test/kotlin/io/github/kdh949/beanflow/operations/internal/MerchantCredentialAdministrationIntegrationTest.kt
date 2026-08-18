package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.identity.internal.AuthenticationScopeHmac
import io.github.kdh949.beanflow.identity.internal.LoginAttemptActorType
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest(
    properties = [
        "beanflow.authentication.attempt-retention-initial-delay-ms=3600000",
        "beanflow.merchant-credential-retention.initial-delay-ms=3600000",
    ],
)
internal class MerchantCredentialAdministrationIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val scopeHmac: AuthenticationScopeHmac,
) {
    private val operatorId = UUID.fromString("40000000-0000-0000-0000-000000000040")
    private lateinit var storeId: UUID

    @BeforeEach
    fun cleanAndGrant() {
        dropAuditFailureTrigger()
        jdbc.execute(
            """
            TRUNCATE TABLE
                identity_login_attempt,
                identity_store_membership,
                identity_merchant_account,
                identity_customer_account,
                operations_merchant_credential_command_idempotency,
                operations_operator_permission_grant,
                operations_audit_record,
                merchant_store_discovery_profile,
                merchant_store
            CASCADE
            """.trimIndent(),
        )
        grant()
        storeId = UUID.randomUUID()
        jdbc.update("INSERT INTO merchant_store (id, accepting_orders, pickup_enabled) VALUES (?, true, true)", storeId)
        jdbc.update(
            "INSERT INTO merchant_store_discovery_profile (store_id, name, location, region_code) " +
                "VALUES (?, 'BeanFlow Operations Test', ST_GeogFromText('SRID=4326;POINT(127.0 37.5)'), '1168010100')",
            storeId,
        )
    }

    @AfterEach
    fun cleanupTrigger() = dropAuditFailureTrigger()

    @Test
    fun `create returns one-time secret and atomically stores only hash membership outcome and audit`() {
        val response =
            create("MERCHANT.OPS", "merchant-create-0001")
                .andExpect(status().isCreated)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.loginId").value("merchant.ops"))
                .andExpect(jsonPath("$.accountState").value("INITIAL_PASSWORD"))
                .andExpect(jsonPath("$.membership.storeId").value(storeId.toString()))
                .andExpect(jsonPath("$.membership.role").value("OWNER"))
                .andReturn()
        val body = objectMapper.readTree(response.response.contentAsString)
        val accountId = UUID.fromString(body["merchantAccountId"].textValue())
        val temporaryPassword = body["temporaryPassword"].textValue()
        assertThat(temporaryPassword).hasSize(32).matches("^[A-Za-z0-9_-]{32}$")

        val account = jdbc.queryForMap("SELECT login_id, password_hash, state FROM identity_merchant_account WHERE id = ?", accountId)
        assertThat(account["login_id"]).isEqualTo("merchant.ops")
        assertThat(account["password_hash"].toString()).startsWith("\$argon2id\$").doesNotContain(temporaryPassword)
        assertThat(count("identity_store_membership")).isEqualTo(1)
        assertThat(count("operations_merchant_credential_command_idempotency")).isEqualTo(1)
        val audit = jdbc.queryForMap("SELECT action, actor_type, before_summary, after_summary FROM operations_audit_record")
        assertThat(audit["action"]).isEqualTo("MERCHANT_ACCOUNT_CREATED")
        assertThat(audit["actor_type"]).isEqualTo("PLATFORM_OPERATOR")
        assertThat(audit.values.joinToString()).doesNotContain(temporaryPassword, "argon2id")

        mockMvc
            .perform(
                get("/api/v1/operations/merchant-accounts")
                    .with(operatorJwt())
                    .queryParam("loginId", "MERCHANT.OPS")
                    .header("X-Access-Reason", "Account delivery confirmation"),
            ).andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.merchantAccountId").value(accountId.toString()))
            .andExpect(jsonPath("$.temporaryPassword").doesNotExist())
            .andExpect(jsonPath("$.memberships[0].role").value("OWNER"))
        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM operations_audit_record WHERE action = 'MERCHANT_ACCOUNT_READ'", Long::class.java),
        ).isEqualTo(1)

        mockMvc
            .perform(
                get("/api/v1/operations/merchant-accounts")
                    .with(operatorJwt())
                    .queryParam("loginId", "merchant.ops")
                    .header("X-Access-Reason", "Second delivery confirmation"),
            ).andExpect(status().isOk)
        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM operations_audit_record WHERE action = 'MERCHANT_ACCOUNT_READ'", Long::class.java),
        ).isEqualTo(2)
    }

    @Test
    fun `merchant login ID uses customer format but a separate uniqueness namespace`() {
        insertCustomerAccount("shared.login")
        seedBlockedLoginAttempt("shared.login", Instant.now().truncatedTo(ChronoUnit.MICROS))

        create("SHARED.LOGIN", "merchant-create-shared").andExpect(status().isCreated)
        assertThat(count("identity_customer_account")).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT login_id FROM identity_merchant_account", String::class.java))
            .isEqualTo("shared.login")
        assertThat(count("identity_login_attempt")).isZero()

        create("shared.login", "merchant-create-duplicate")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("LOGIN_ID_UNAVAILABLE"))
        listOf("four", ".leading", "trailing.", "invalid+character").forEachIndexed { index, loginId ->
            create(loginId, "merchant-invalid-${index.toString().padStart(2, '0')}")
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }
        createWithRole("NEW_ROLE", "merchant-invalid-role")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        assertThat(count("identity_merchant_account")).isEqualTo(1)
    }

    @Test
    fun `secret command replay never regenerates secret and payload mismatch is distinct`() {
        val created = create("replay.merchant", "merchant-create-replay").andExpect(status().isCreated).andReturn()
        val accountId = UUID.fromString(objectMapper.readTree(created.response.contentAsString)["merchantAccountId"].textValue())

        create("replay.merchant", "merchant-create-replay")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("TEMPORARY_PASSWORD_NOT_REPLAYABLE"))
            .andExpect(jsonPath("$.targetReference").isNotEmpty)
            .andExpect(jsonPath("$.temporaryPassword").doesNotExist())
        create("different.merchant", "merchant-create-replay")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))

        mockMvc
            .perform(
                get("/api/v1/operations/merchant-accounts")
                    .with(operatorJwt())
                    .queryParam("loginId", "replay.merchant")
                    .header("X-Access-Reason", "Resolve unknown create outcome"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.merchantAccountId").value(accountId.toString()))
            .andExpect(jsonPath("$.temporaryPassword").doesNotExist())
        postReason("/api/v1/operations/merchant-accounts/$accountId/temporary-password-resets", "merchant-reset-after-loss")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.temporaryPassword").isNotEmpty)

        assertThat(count("identity_merchant_account")).isEqualTo(1)
        assertThat(count("operations_merchant_credential_command_idempotency")).isEqualTo(2)
        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM operations_audit_record WHERE action = 'MERCHANT_ACCOUNT_CREATED'", Long::class.java),
        ).isEqualTo(1)
    }

    @Test
    fun `concurrent same-key creation has one side effect and no replayed secret`() {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val responses =
                (1..2).map {
                    executor.submit<MockHttpServletResponse> {
                        ready.countDown()
                        check(start.await(5, TimeUnit.SECONDS))
                        create("concurrent.merchant", "merchant-create-concurrent").andReturn().response
                    }
                }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            val results = responses.map { it.get(20, TimeUnit.SECONDS) }
            assertThat(results.map { it.status }).containsExactlyInAnyOrder(201, 409)
            assertThat(results.single { it.status == 409 }.contentAsString)
                .contains("TEMPORARY_PASSWORD_NOT_REPLAYABLE")
                .doesNotContain("temporaryPassword")
            assertThat(count("identity_merchant_account")).isEqualTo(1)
            assertThat(count("identity_store_membership")).isEqualTo(1)
            assertThat(count("operations_merchant_credential_command_idempotency")).isEqualTo(1)
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `reset and lock release clear account and login attempt locks with their replay contracts`() {
        val created = create("reset.merchant", "merchant-create-reset").andExpect(status().isCreated).andReturn()
        val accountId = UUID.fromString(objectMapper.readTree(created.response.contentAsString)["merchantAccountId"].textValue())
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        jdbc.update(
            "UPDATE identity_merchant_account SET locked_until = ?, credential_version = credential_version + 1 WHERE id = ?",
            Timestamp.from(now.plusSeconds(900)),
            accountId,
        )
        seedBlockedLoginAttempt("reset.merchant", now)

        val reset =
            postReason("/api/v1/operations/merchant-accounts/$accountId/temporary-password-resets", "merchant-reset-0001")
                .andExpect(status().isOk)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.accountState").value("INITIAL_PASSWORD"))
                .andReturn()
        val secret = objectMapper.readTree(reset.response.contentAsString)["temporaryPassword"].textValue()
        assertThat(secret).hasSize(32)
        assertThat(jdbc.queryForMap("SELECT locked_until, state FROM identity_merchant_account WHERE id = ?", accountId))
            .containsEntry("state", "INITIAL_PASSWORD")
            .containsEntry("locked_until", null)
        assertThat(count("identity_login_attempt")).isZero()

        postReason("/api/v1/operations/merchant-accounts/$accountId/temporary-password-resets", "merchant-reset-0001")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("TEMPORARY_PASSWORD_NOT_REPLAYABLE"))

        seedBlockedLoginAttempt("reset.merchant", now)
        jdbc.update("UPDATE identity_merchant_account SET locked_until = ? WHERE id = ?", Timestamp.from(now.plusSeconds(900)), accountId)
        postReason("/api/v1/operations/merchant-accounts/$accountId/lock-releases", "merchant-release-0001")
            .andExpect(status().isNoContent)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        postReason("/api/v1/operations/merchant-accounts/$accountId/lock-releases", "merchant-release-0001")
            .andExpect(status().isNoContent)
        assertThat(
            jdbc.queryForObject("SELECT locked_until FROM identity_merchant_account WHERE id = ?", Instant::class.java, accountId),
        ).isNull()
        assertThat(count("identity_login_attempt")).isZero()
        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM operations_audit_record WHERE action = 'MERCHANT_LOCK_RELEASED'", Long::class.java),
        ).isEqualTo(1)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM operations_audit_record WHERE action = 'MERCHANT_TEMPORARY_PASSWORD_RESET'",
                Long::class.java,
            ),
        ).isEqualTo(1)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM operations_merchant_credential_command_idempotency WHERE operation = 'RESET_TEMPORARY_PASSWORD'",
                Long::class.java,
            ),
        ).isEqualTo(1)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM operations_merchant_credential_command_idempotency WHERE operation = 'RELEASE_LOCK'",
                Long::class.java,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `permission store and audit failures leave no orphan account membership or outcome`() {
        jdbc.update("UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now()")
        create("forbidden.merchant", "merchant-create-deny").andExpect(status().isForbidden)
        assertCreationTablesEmpty()

        grant(regrant = true)
        val absentStore = UUID.randomUUID()
        create("nostore.merchant", "merchant-create-nostore", absentStore).andExpect(status().isNotFound)
        assertCreationTablesEmpty()

        installAuditFailureTrigger()
        create("auditfail.merchant", "merchant-create-auditfail").andExpect(status().isServiceUnavailable)
        assertCreationTablesEmpty()
    }

    @Test
    fun `exact read requires reason grant and audit commit`() {
        create("read.merchant", "merchant-create-read").andExpect(status().isCreated)
        mockMvc
            .perform(
                get("/api/v1/operations/merchant-accounts")
                    .with(operatorJwt())
                    .queryParam("loginId", "absent.merchant")
                    .header("X-Access-Reason", "Confirm absent account"),
            ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("MERCHANT_ACCOUNT_NOT_FOUND"))
        mockMvc
            .perform(get("/api/v1/operations/merchant-accounts").with(operatorJwt()).queryParam("loginId", "read.merchant"))
            .andExpect(status().isBadRequest)
        jdbc.update("UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now()")
        mockMvc
            .perform(
                get("/api/v1/operations/merchant-accounts")
                    .with(operatorJwt())
                    .queryParam("loginId", "read.merchant")
                    .header("X-Access-Reason", "Denied read"),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `exact read and reset fail closed when their audit cannot commit`() {
        val created = create("audit.boundary", "merchant-create-boundary").andExpect(status().isCreated).andReturn()
        val accountId = UUID.fromString(objectMapper.readTree(created.response.contentAsString)["merchantAccountId"].textValue())
        val before =
            jdbc.queryForMap(
                "SELECT password_hash, credential_version, state FROM identity_merchant_account WHERE id = ?",
                accountId,
            )

        installAuditFailureTrigger("MERCHANT_ACCOUNT_READ")
        mockMvc
            .perform(
                get("/api/v1/operations/merchant-accounts")
                    .with(operatorJwt())
                    .queryParam("loginId", "audit.boundary")
                    .header("X-Access-Reason", "Required boundary read"),
            ).andExpect(status().isServiceUnavailable)
        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM operations_audit_record WHERE action = 'MERCHANT_ACCOUNT_READ'", Long::class.java),
        ).isZero()

        dropAuditFailureTrigger()
        installAuditFailureTrigger("MERCHANT_TEMPORARY_PASSWORD_RESET")
        postReason("/api/v1/operations/merchant-accounts/$accountId/temporary-password-resets", "merchant-reset-boundary")
            .andExpect(status().isServiceUnavailable)
        assertThat(
            jdbc.queryForMap("SELECT password_hash, credential_version, state FROM identity_merchant_account WHERE id = ?", accountId),
        ).containsAllEntriesOf(before)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM operations_merchant_credential_command_idempotency WHERE operation = 'RESET_TEMPORARY_PASSWORD'",
                Long::class.java,
            ),
        ).isZero()
    }

    @Test
    fun `retention cleanup is inclusive bounded and terminal rows are immutable`() {
        val cutoff = Instant.parse("2026-08-13T00:00:00Z")
        repeat(102) { index ->
            insertOutcome(
                createdAt = cutoff.minus(90, ChronoUnit.DAYS).minus((index + 1).toLong(), ChronoUnit.MICROS),
                key = "retention-due-${index.toString().padStart(3, '0')}",
            )
        }
        insertOutcome(cutoff.minus(90, ChronoUnit.DAYS), "retention-at-boundary")
        insertOutcome(cutoff.minus(90, ChronoUnit.DAYS).plus(1, ChronoUnit.MICROS), "retention-after-boundary")

        val repository = MerchantCredentialRetentionRepository(jdbc)
        assertThat(repository.purgeDue(cutoff.minus(1, ChronoUnit.MICROS), 100).deletedCount).isEqualTo(100)
        assertThat(repository.purgeDue(cutoff, 100).deletedCount).isEqualTo(3)
        assertThat(repository.purgeDue(cutoff.plus(1, ChronoUnit.MICROS), 100).deletedCount).isEqualTo(1)
        assertThat(count("operations_merchant_credential_command_idempotency")).isZero()

        insertOutcome(cutoff.minus(90, ChronoUnit.DAYS), "retention-immutable")
        org.assertj.core.api.Assertions
            .assertThatThrownBy {
                jdbc.update("UPDATE operations_merchant_credential_command_idempotency SET outcome = 'LOCK_RELEASED'")
            }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
    }

    private fun create(
        loginId: String,
        key: String,
        targetStoreId: UUID = storeId,
    ) = mockMvc.perform(
        post("/api/v1/operations/merchant-accounts")
            .with(operatorJwt())
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"loginId":"$loginId","displayName":"Test Merchant","storeId":"$targetStoreId",
                 "membershipRole":"OWNER","reason":"New store onboarding"}
                """.trimIndent(),
            ),
    )

    private fun createWithRole(
        role: String,
        key: String,
    ) = mockMvc.perform(
        post("/api/v1/operations/merchant-accounts")
            .with(operatorJwt())
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"loginId":"role.merchant","displayName":"Test Merchant","storeId":"$storeId",
                 "membershipRole":"$role","reason":"New store onboarding"}
                """.trimIndent(),
            ),
    )

    private fun postReason(
        path: String,
        key: String,
    ) = mockMvc.perform(
        post(path)
            .with(operatorJwt())
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"reason":"Credential recovery"}"""),
    )

    private fun operatorJwt() =
        jwt()
            .jwt { it.subject(operatorId.toString()).claim("roles", listOf("PLATFORM_OPERATOR")) }
            .authorities(SimpleGrantedAuthority("ROLE_PLATFORM_OPERATOR"))

    private fun grant(regrant: Boolean = false) {
        if (regrant) {
            jdbc.update(
                "UPDATE operations_operator_permission_grant SET state = 'ACTIVE', granted_at = now(), revoked_at = null, version = version + 1",
            )
            return
        }
        jdbc.update(
            """
            INSERT INTO operations_operator_permission_grant
                (actor_id, permission, state, granted_at, revoked_at, version, audit_source_reference)
            VALUES (?, ?, 'ACTIVE', now(), null, 1, ?)
            """.trimIndent(),
            operatorId,
            OperatorPermission.MERCHANT_CREDENTIAL_MANAGE.name,
            "test-grant:$operatorId",
        )
    }

    private fun seedBlockedLoginAttempt(
        loginId: String,
        now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO identity_login_attempt
                (id, actor_type, scope_type, scope_hmac, window_start, failure_count, blocked_until, updated_at)
            VALUES (?, 'MERCHANT', 'LOGIN_ID', ?, ?, 5, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            scopeHmac.loginId(LoginAttemptActorType.MERCHANT, loginId),
            Timestamp.from(now),
            Timestamp.from(now.plusSeconds(900)),
            Timestamp.from(now),
        )
    }

    private fun installAuditFailureTrigger(action: String = "MERCHANT_ACCOUNT_CREATED") {
        jdbc.execute(
            """
            CREATE OR REPLACE FUNCTION fail_merchant_credential_audit() RETURNS trigger AS ${'$'}${'$'}
            BEGIN
              IF NEW.action = '$action' THEN RAISE EXCEPTION 'forced audit failure'; END IF;
              RETURN NEW;
            END;
            ${'$'}${'$'} LANGUAGE plpgsql
            """.trimIndent(),
        )
        jdbc.execute(
            """
            CREATE TRIGGER fail_merchant_credential_audit_trigger BEFORE INSERT ON operations_audit_record
            FOR EACH ROW EXECUTE FUNCTION fail_merchant_credential_audit()
            """.trimIndent(),
        )
    }

    private fun insertOutcome(
        createdAt: Instant,
        key: String,
    ) {
        jdbc.update(
            """
            INSERT INTO operations_merchant_credential_command_idempotency
                (id, operator_id, operation, idempotency_key, payload_hash, merchant_account_id,
                 outcome, created_at, retention_expires_at)
            VALUES (?, ?, 'CREATE', ?, ?, ?, 'ACCOUNT_CREATED', ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            operatorId,
            key,
            "a".repeat(64),
            UUID.randomUUID(),
            Timestamp.from(createdAt),
            Timestamp.from(createdAt.plus(90, ChronoUnit.DAYS)),
        )
    }

    private fun insertCustomerAccount(loginId: String) {
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        jdbc.update(
            """
            INSERT INTO identity_customer_account
                (id, login_id, password_hash, credential_version, display_name, state,
                 locked_until, created_at, updated_at, version)
            VALUES (?, ?, ?, 0, 'Customer Namespace Test', 'ACTIVE', null, ?, ?, 0)
            """.trimIndent(),
            UUID.randomUUID(),
            loginId,
            "\$argon2id\$v=19\$m=19456,t=2,p=1\$fixture\$fixture",
            Timestamp.from(now),
            Timestamp.from(now),
        )
    }

    private fun dropAuditFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_merchant_credential_audit_trigger ON operations_audit_record")
        jdbc.execute("DROP FUNCTION IF EXISTS fail_merchant_credential_audit()")
    }

    private fun assertCreationTablesEmpty() {
        assertThat(count("identity_merchant_account")).isZero()
        assertThat(count("identity_store_membership")).isZero()
        assertThat(count("operations_merchant_credential_command_idempotency")).isZero()
    }

    private fun count(table: String): Long = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!
}

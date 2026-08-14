package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class, MerchantAuthenticationTestClockConfiguration::class)
@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "beanflow.toss.client-key=test_ck_merchant_authentication",
        "beanflow.authentication.attempt-retention-initial-delay-ms=3600000",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class MerchantAuthenticationIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val passwords: CustomerPasswordSecurity,
    @Autowired private val clock: MutableMerchantAuthenticationClock,
) {
    @BeforeEach
    fun cleanDatabase() {
        clock.set(DEFAULT_NOW)
        dropAuditFailureTrigger()
        dropSessionFailureTrigger()
        dropSessionCleanupFailureTrigger()
        jdbc.execute(
            """
            TRUNCATE TABLE
                spring_session_attributes,
                spring_session,
                identity_login_attempt,
                identity_store_membership,
                identity_merchant_account,
                operations_audit_record,
                merchant_store_discovery_profile,
                merchant_store
            CASCADE
            """.trimIndent(),
        )
    }

    @AfterEach
    fun cleanupTriggers() {
        dropAuditFailureTrigger()
        dropSessionFailureTrigger()
        dropSessionCleanupFailureTrigger()
    }

    @Test
    fun `initial password session is gated then password change activates merchant and rotates session`() {
        val accountId = seedInitialAccount("merchant.user", CURRENT_PASSWORD, DEFAULT_NOW.plus(24, ChronoUnit.HOURS))
        val storeId = seedStore("BeanFlow Gangnam")
        seedMembership(accountId, storeId, "OWNER", "ACTIVE")
        val csrf = issueCsrf()

        val login =
            login(csrf, "MERCHANT.USER", CURRENT_PASSWORD)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.actorType").value("MERCHANT"))
                .andExpect(jsonPath("$.accountState").value("INITIAL_PASSWORD"))
                .andReturn()
        val initialSession = requireNotNull(login.response.getCookie(SESSION_COOKIE))

        mockMvc
            .perform(get("/api/v1/merchant/me").cookie(initialSession))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.merchantId").value(accountId.toString()))
        mockMvc
            .perform(get("/api/v1/merchant/me/stores").cookie(initialSession))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("INITIAL_PASSWORD_CHANGE_REQUIRED"))
        mockMvc
            .perform(get("/api/v1/stores/$storeId/orders").cookie(initialSession))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("INITIAL_PASSWORD_CHANGE_REQUIRED"))
        val inaccessibleId = UUID.randomUUID()
        mockMvc
            .perform(
                patch("/api/v1/store-orders/$inaccessibleId/status")
                    .cookie(initialSession, csrf)
                    .header(CSRF_HEADER, csrf.value)
                    .header("Idempotency-Key", "initial-gate-transition")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"targetState":"ACCEPTED","reason":null}"""),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("INITIAL_PASSWORD_CHANGE_REQUIRED"))
        mockMvc
            .perform(
                post("/api/v1/payments/$inaccessibleId/refunds")
                    .cookie(initialSession, csrf)
                    .header(CSRF_HEADER, csrf.value)
                    .header("Idempotency-Key", "initial-gate-refund")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"reason":"Initial gate proof"}"""),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("INITIAL_PASSWORD_CHANGE_REQUIRED"))
        mockMvc
            .perform(get("/api/v1/stores/$storeId/settlements").cookie(initialSession))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("INITIAL_PASSWORD_CHANGE_REQUIRED"))

        val changed =
            mockMvc
                .perform(
                    post("/api/v1/auth/merchant/password-changes")
                        .cookie(initialSession, csrf)
                        .header(CSRF_HEADER, csrf.value)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"currentPassword":"$CURRENT_PASSWORD","newPassword":"$NEW_PASSWORD"}"""),
                ).andExpect(status().isNoContent)
                .andReturn()
        val activeSession = requireNotNull(changed.response.getCookie(SESSION_COOKIE))
        assertThat(activeSession.value).isNotEqualTo(initialSession.value)

        mockMvc.perform(get("/api/v1/merchant/me").cookie(initialSession)).andExpect(status().isUnauthorized)
        mockMvc
            .perform(get("/api/v1/merchant/me/stores").cookie(activeSession))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].storeId").value(storeId.toString()))
            .andExpect(jsonPath("$[0].storeName").value("BeanFlow Gangnam"))
            .andExpect(jsonPath("$[0].membershipRole").value("OWNER"))

        assertThat(jdbc.queryForMap("SELECT state, credential_version FROM identity_merchant_account WHERE id = '$accountId'"))
            .containsEntry("state", "ACTIVE")
            .containsEntry("credential_version", 1L)
        val audit =
            jdbc.queryForMap(
                "SELECT actor_type, action, before_summary, after_summary FROM operations_audit_record " +
                    "WHERE target_id = '$accountId' AND action = 'MERCHANT_PASSWORD_CHANGED'",
            )
        assertThat(audit["actor_type"]).isEqualTo("MERCHANT")
        assertThat(audit.values.joinToString()).doesNotContain(CURRENT_PASSWORD, NEW_PASSWORD, "argon2")
    }

    @Test
    fun `same password is rejected without credential side effects for initial and active accounts`() {
        val initialAccountId = seedInitialAccount("same.initial", CURRENT_PASSWORD, DEFAULT_NOW.plus(24, ChronoUnit.HOURS))
        val activeAccountId = seedActiveAccount("same.active", CURRENT_PASSWORD)
        val csrf = issueCsrf()
        val initialSession = requireNotNull(login(csrf, "same.initial", CURRENT_PASSWORD).andReturn().response.getCookie(SESSION_COOKIE))
        val activeSession = requireNotNull(login(csrf, "same.active", CURRENT_PASSWORD).andReturn().response.getCookie(SESSION_COOKIE))

        assertSamePasswordRejectedWithoutSideEffects(initialAccountId, csrf, initialSession, "INITIAL_PASSWORD")
        assertSamePasswordRejectedWithoutSideEffects(activeAccountId, csrf, activeSession, "ACTIVE")
    }

    @Test
    fun `temporary password expiry is enforced at the exact boundary for login and existing session`() {
        val expiresAt = DEFAULT_NOW.plusSeconds(60)
        seedInitialAccount("expiry.user", CURRENT_PASSWORD, expiresAt)
        val csrf = issueCsrf()
        val session = requireNotNull(login(csrf, "expiry.user", CURRENT_PASSWORD).andReturn().response.getCookie(SESSION_COOKIE))

        clock.set(expiresAt.minusNanos(1))
        mockMvc.perform(get("/api/v1/merchant/me").cookie(session)).andExpect(status().isOk)

        clock.set(expiresAt)
        mockMvc.perform(get("/api/v1/merchant/me").cookie(session)).andExpect(status().isUnauthorized)
        mockMvc
            .perform(
                post("/api/v1/auth/merchant/password-changes")
                    .cookie(session, csrf)
                    .header(CSRF_HEADER, csrf.value)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"currentPassword":"$CURRENT_PASSWORD","newPassword":"$NEW_PASSWORD"}"""),
            ).andExpect(status().isUnauthorized)
        login(csrf, "expiry.user", CURRENT_PASSWORD)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
        assertThat(
            jdbc.queryForObject(
                "SELECT state FROM identity_merchant_account WHERE login_id = 'expiry.user'",
                String::class.java,
            ),
        ).isEqualTo("EXPIRED")

        clock.set(expiresAt.plusNanos(1))
        login(csrf, "expiry.user", CURRENT_PASSWORD).andExpect(status().isUnauthorized)
    }

    @Test
    fun `fifth failure locks without changing lifecycle and exact lock expiry restores initial login`() {
        seedInitialAccount("locked.merchant", CURRENT_PASSWORD, DEFAULT_NOW.plus(24, ChronoUnit.HOURS))
        val csrf = issueCsrf()

        repeat(5) { login(csrf, "locked.merchant", WRONG_PASSWORD).andExpect(status().isUnauthorized) }
        assertThat(
            jdbc.queryForMap(
                "SELECT state, credential_version, locked_until FROM identity_merchant_account WHERE login_id = 'locked.merchant'",
            ),
        ).containsEntry("state", "INITIAL_PASSWORD").containsEntry("credential_version", 1L)
        login(csrf, "locked.merchant", CURRENT_PASSWORD).andExpect(status().isUnauthorized)

        clock.set(DEFAULT_NOW.plus(15, ChronoUnit.MINUTES))
        login(csrf, "locked.merchant", CURRENT_PASSWORD)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accountState").value("INITIAL_PASSWORD"))
    }

    @Test
    fun `concurrent merchant failures preserve every attempt and lock exactly at five`() {
        val accountId = seedInitialAccount("concurrent.lock", CURRENT_PASSWORD, DEFAULT_NOW.plus(24, ChronoUnit.HOURS))
        val csrf = issueCsrf()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(5)
        try {
            val futures =
                (1..5).map {
                    pool.submit<Int> {
                        check(start.await(5, TimeUnit.SECONDS))
                        login(csrf, "concurrent.lock", WRONG_PASSWORD).andReturn().response.status
                    }
                }
            start.countDown()
            assertThat(futures.map { it.get(20, TimeUnit.SECONDS) }).containsOnly(401)
        } finally {
            start.countDown()
            pool.shutdownNow()
        }

        assertThat(
            jdbc.queryForObject(
                "SELECT failure_count FROM identity_login_attempt WHERE actor_type = 'MERCHANT' AND scope_type = 'LOGIN_ID'",
                Int::class.java,
            ),
        ).isEqualTo(5)
        assertThat(jdbc.queryForObject("SELECT locked_until FROM identity_merchant_account WHERE id = ?", Instant::class.java, accountId))
            .isEqualTo(DEFAULT_NOW.plus(15, ChronoUnit.MINUTES))
    }

    @Test
    fun `active account lock expiry restores active lifecycle without state transition`() {
        val accountId = seedActiveAccount("active.lock", CURRENT_PASSWORD)
        jdbc.update(
            "UPDATE identity_merchant_account SET locked_until = ?, credential_version = 1 WHERE id = ?",
            Timestamp.from(DEFAULT_NOW.plus(15, ChronoUnit.MINUTES)),
            accountId,
        )
        val csrf = issueCsrf()
        clock.set(DEFAULT_NOW.plus(15, ChronoUnit.MINUTES).minusNanos(1))
        login(csrf, "active.lock", CURRENT_PASSWORD).andExpect(status().isUnauthorized)
        clock.set(DEFAULT_NOW.plus(15, ChronoUnit.MINUTES))
        login(csrf, "active.lock", CURRENT_PASSWORD)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accountState").value("ACTIVE"))
        assertThat(jdbc.queryForMap("SELECT state, locked_until FROM identity_merchant_account WHERE id = ?", accountId))
            .containsEntry("state", "ACTIVE")
            .containsEntry("locked_until", null)
    }

    @Test
    fun `store list returns active memberships only`() {
        val accountId = seedActiveAccount("active.merchant", CURRENT_PASSWORD)
        val activeStore = seedStore("Active Store")
        val revokedStore = seedStore("Revoked Store")
        seedMembership(accountId, activeStore, "STAFF", "ACTIVE")
        seedMembership(accountId, revokedStore, "OWNER", "REVOKED")
        val csrf = issueCsrf()
        val session = requireNotNull(login(csrf, "active.merchant", CURRENT_PASSWORD).andReturn().response.getCookie(SESSION_COOKIE))

        mockMvc
            .perform(get("/api/v1/merchant/me/stores").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].storeId").value(activeStore.toString()))
            .andExpect(jsonPath("$[0].membershipRole").value("STAFF"))
    }

    @Test
    fun `password change audit failure rolls credential transition back`() {
        val accountId = seedInitialAccount("audit.failure", CURRENT_PASSWORD, DEFAULT_NOW.plus(24, ChronoUnit.HOURS))
        val csrf = issueCsrf()
        val session = requireNotNull(login(csrf, "audit.failure", CURRENT_PASSWORD).andReturn().response.getCookie(SESSION_COOKIE))
        createAuditFailureTrigger()

        mockMvc
            .perform(
                post("/api/v1/auth/merchant/password-changes")
                    .cookie(session, csrf)
                    .header(CSRF_HEADER, csrf.value)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"currentPassword":"$CURRENT_PASSWORD","newPassword":"$NEW_PASSWORD"}"""),
            ).andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))

        assertThat(jdbc.queryForMap("SELECT state, credential_version FROM identity_merchant_account WHERE id = '$accountId'"))
            .containsEntry("state", "INITIAL_PASSWORD")
            .containsEntry("credential_version", 0L)
        assertThat(passwords.matches(CURRENT_PASSWORD, accountHash(accountId))).isTrue()
        assertThat(passwords.matches(NEW_PASSWORD, accountHash(accountId))).isFalse()
    }

    @Test
    fun `new session failure does not undo changed credential and old session remains unauthorized`() {
        val accountId = seedInitialAccount("session.failure", CURRENT_PASSWORD, DEFAULT_NOW.plus(24, ChronoUnit.HOURS))
        val csrf = issueCsrf()
        val session = requireNotNull(login(csrf, "session.failure", CURRENT_PASSWORD).andReturn().response.getCookie(SESSION_COOKIE))
        createSessionFailureTrigger()

        mockMvc
            .perform(
                post("/api/v1/auth/merchant/password-changes")
                    .cookie(session, csrf)
                    .header(CSRF_HEADER, csrf.value)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"currentPassword":"$CURRENT_PASSWORD","newPassword":"$NEW_PASSWORD"}"""),
            ).andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))

        assertThat(jdbc.queryForMap("SELECT state, credential_version FROM identity_merchant_account WHERE id = '$accountId'"))
            .containsEntry("state", "ACTIVE")
            .containsEntry("credential_version", 1L)
        assertThat(passwords.matches(NEW_PASSWORD, accountHash(accountId))).isTrue()
        mockMvc.perform(get("/api/v1/merchant/me").cookie(session)).andExpect(status().isUnauthorized)

        dropSessionFailureTrigger()
        login(csrf, "session.failure", NEW_PASSWORD).andExpect(status().isOk)
    }

    @Test
    fun `old session remains unauthorized when its physical cleanup fails after password change`() {
        seedInitialAccount("cleanup.failure", CURRENT_PASSWORD, DEFAULT_NOW.plus(24, ChronoUnit.HOURS))
        val csrf = issueCsrf()
        val oldSession = requireNotNull(login(csrf, "cleanup.failure", CURRENT_PASSWORD).andReturn().response.getCookie(SESSION_COOKIE))
        val changingSession =
            requireNotNull(login(csrf, "cleanup.failure", CURRENT_PASSWORD).andReturn().response.getCookie(SESSION_COOKIE))
        mockMvc
            .perform(
                post("/api/v1/auth/merchant/password-changes")
                    .cookie(changingSession, csrf)
                    .header(CSRF_HEADER, csrf.value)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"currentPassword":"$CURRENT_PASSWORD","newPassword":"$NEW_PASSWORD"}"""),
            ).andExpect(status().isNoContent)

        createSessionCleanupFailureTrigger()
        val sessionsBeforeRejectedCleanup =
            jdbc.queryForObject("SELECT count(*) FROM spring_session", Long::class.java)
        mockMvc
            .perform(get("/api/v1/merchant/me").cookie(oldSession))
            .andExpect(status().isUnauthorized)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session", Long::class.java))
            .isEqualTo(sessionsBeforeRejectedCleanup)
    }

    private fun issueCsrf(): Cookie =
        requireNotNull(
            mockMvc
                .perform(get("/api/v1/auth/merchant/csrf"))
                .andExpect(status().isNoContent)
                .andReturn()
                .response
                .getCookie(CSRF_COOKIE),
        )

    private fun login(
        csrf: Cookie,
        loginId: String,
        password: String,
    ): ResultActions =
        mockMvc.perform(
            post("/api/v1/auth/merchant/sessions")
                .cookie(csrf)
                .header(CSRF_HEADER, csrf.value)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"loginId":"$loginId","password":"$password"}"""),
        )

    private fun seedInitialAccount(
        loginId: String,
        password: String,
        expiresAt: Instant,
    ): UUID = seedAccount(loginId, password, "INITIAL_PASSWORD", expiresAt, null)

    private fun seedActiveAccount(
        loginId: String,
        password: String,
    ): UUID = seedAccount(loginId, password, "ACTIVE", null, DEFAULT_NOW)

    private fun seedAccount(
        loginId: String,
        password: String,
        state: String,
        temporaryPasswordExpiresAt: Instant?,
        passwordChangedAt: Instant?,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO identity_merchant_account
                (id, login_id, password_hash, credential_version, display_name, state,
                 temporary_password_expires_at, password_changed_at, locked_until,
                 created_at, updated_at, version)
            VALUES (?, ?, ?, 0, 'Demo Merchant', ?, ?, ?, NULL, ?, ?, 0)
            """.trimIndent(),
            id,
            loginId,
            passwords.encode(password),
            state,
            temporaryPasswordExpiresAt?.let(Timestamp::from),
            passwordChangedAt?.let(Timestamp::from),
            Timestamp.from(DEFAULT_NOW.minusSeconds(1)),
            Timestamp.from(DEFAULT_NOW),
        )
        return id
    }

    private fun assertSamePasswordRejectedWithoutSideEffects(
        accountId: UUID,
        csrf: Cookie,
        session: Cookie,
        expectedState: String,
    ) {
        val sessionsBefore = jdbc.queryForObject("SELECT count(*) FROM spring_session", Long::class.java)
        mockMvc
            .perform(
                post("/api/v1/auth/merchant/password-changes")
                    .cookie(session, csrf)
                    .header(CSRF_HEADER, csrf.value)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"currentPassword":"$CURRENT_PASSWORD","newPassword":"$CURRENT_PASSWORD"}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("PASSWORD_POLICY_VIOLATION"))
            .andExpect { result -> assertThat(result.response.getCookie(SESSION_COOKIE)).isNull() }

        assertThat(jdbc.queryForMap("SELECT state, credential_version FROM identity_merchant_account WHERE id = ?", accountId))
            .containsEntry("state", expectedState)
            .containsEntry("credential_version", 0L)
        assertThat(passwords.matches(CURRENT_PASSWORD, accountHash(accountId))).isTrue()
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM operations_audit_record WHERE target_id = ? AND action = 'MERCHANT_PASSWORD_CHANGED'",
                Long::class.java,
                accountId,
            ),
        ).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session", Long::class.java)).isEqualTo(sessionsBefore)
        mockMvc.perform(get("/api/v1/merchant/me").cookie(session)).andExpect(status().isOk)
    }

    private fun seedStore(name: String): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)", id)
        jdbc.update(
            "INSERT INTO merchant_store_discovery_profile (store_id, name, location) " +
                "VALUES (?, ?, ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography)",
            id,
            name,
        )
        return id
    }

    private fun seedMembership(
        accountId: UUID,
        storeId: UUID,
        role: String,
        status: String,
    ) {
        jdbc.update(
            """
            INSERT INTO identity_store_membership
                (id, actor_id, store_id, membership_role, status, created_at, updated_at, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0)
            """.trimIndent(),
            UUID.randomUUID(),
            accountId,
            storeId,
            role,
            status,
            Timestamp.from(DEFAULT_NOW),
            Timestamp.from(DEFAULT_NOW),
        )
    }

    private fun accountHash(accountId: UUID): String =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT password_hash FROM identity_merchant_account WHERE id = ?",
                String::class.java,
                accountId,
            ),
        )

    private fun createAuditFailureTrigger() {
        jdbc.execute(
            """
            CREATE FUNCTION test_fail_merchant_password_audit() RETURNS trigger AS ${'$'}${'$'}
            BEGIN
              IF NEW.action = 'MERCHANT_PASSWORD_CHANGED' THEN
                RAISE EXCEPTION 'forced merchant password audit failure';
              END IF;
              RETURN NEW;
            END;
            ${'$'}${'$'} LANGUAGE plpgsql
            """.trimIndent(),
        )
        jdbc.execute(
            "CREATE TRIGGER test_fail_merchant_password_audit BEFORE INSERT ON operations_audit_record " +
                "FOR EACH ROW EXECUTE FUNCTION test_fail_merchant_password_audit()",
        )
    }

    private fun dropAuditFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_merchant_password_audit ON operations_audit_record")
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_merchant_password_audit()")
    }

    private fun dropSessionFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_merchant_session_insert ON spring_session")
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_merchant_session_insert()")
    }

    private fun dropSessionCleanupFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_merchant_session_delete ON spring_session")
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_merchant_session_delete()")
    }

    private fun createSessionCleanupFailureTrigger() {
        jdbc.execute(
            """
            CREATE FUNCTION test_fail_merchant_session_delete() RETURNS trigger AS ${'$'}${'$'}
            BEGIN RAISE EXCEPTION 'forced merchant session cleanup failure'; END;
            ${'$'}${'$'} LANGUAGE plpgsql
            """.trimIndent(),
        )
        jdbc.execute(
            "CREATE TRIGGER test_fail_merchant_session_delete BEFORE DELETE ON spring_session " +
                "FOR EACH ROW EXECUTE FUNCTION test_fail_merchant_session_delete()",
        )
    }

    private fun createSessionFailureTrigger() {
        jdbc.execute(
            """
            CREATE FUNCTION test_fail_merchant_session_insert() RETURNS trigger AS ${'$'}${'$'}
            BEGIN RAISE EXCEPTION 'forced merchant session insert failure'; END;
            ${'$'}${'$'} LANGUAGE plpgsql
            """.trimIndent(),
        )
        jdbc.execute(
            "CREATE TRIGGER test_fail_merchant_session_insert BEFORE INSERT ON spring_session " +
                "FOR EACH ROW EXECUTE FUNCTION test_fail_merchant_session_insert()",
        )
    }

    private companion object {
        const val CSRF_COOKIE = "BEANFLOW_MERCHANT_XSRF"
        const val SESSION_COOKIE = "BEANFLOW_MERCHANT_SESSION"
        const val CSRF_HEADER = "X-BEANFLOW-CSRF"
        const val CURRENT_PASSWORD = "merchant-current-password-2026"
        const val NEW_PASSWORD = "merchant-new-password-2026"
        const val WRONG_PASSWORD = "merchant-wrong-password-2026"
        val DEFAULT_NOW: Instant = Instant.parse("2026-08-13T00:00:00Z")
    }
}

@TestConfiguration(proxyBeanMethods = false)
internal class MerchantAuthenticationTestClockConfiguration {
    @Bean
    @Primary
    fun merchantAuthenticationClock(): MutableMerchantAuthenticationClock = MutableMerchantAuthenticationClock()
}

internal class MutableMerchantAuthenticationClock : Clock() {
    @Volatile
    private var current: Instant = Instant.parse("2026-08-13T00:00:00Z")

    fun set(value: Instant) {
        current = value
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)

    override fun instant(): Instant = current
}

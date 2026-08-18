package io.github.kdh949.beanflow.identity.internal

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.internal.OrderCreationDatabaseFixture
import io.github.kdh949.beanflow.ordering.internal.OrderCreationFixture
import io.github.kdh949.beanflow.shared.api.BrowserActorType
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.internal.principalName
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
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
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

@Import(TestcontainersConfiguration::class, CustomerAuthenticationTestClockConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies startup, DDL, or committed state across a transaction boundary")
@SpringBootTest(
    properties = [
        "beanflow.toss.client-key=test_ck_customer_authentication",
        "beanflow.authentication.attempt-retention-initial-delay-ms=3600000",
    ],
)
internal class CustomerAuthenticationIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val service: CustomerAccountApplicationService,
    @Autowired private val attempts: LoginAttemptRepository,
    @Autowired private val hmac: AuthenticationScopeHmac,
    @Autowired private val passwordSecurity: CustomerPasswordSecurity,
    @Autowired private val clock: MutableCustomerAuthenticationClock,
    @Autowired private val dataSource: DataSource,
    @Autowired transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    @BeforeEach
    fun cleanDatabase() {
        clock.set(DEFAULT_NOW)
        dropPointFailureTrigger()
        dropPointConflictTrigger()
        dropSessionFailureTrigger()
        OrderCreationDatabaseFixture.clean(jdbc)
        jdbc.execute(
            """
            TRUNCATE TABLE
                spring_session_attributes,
                spring_session,
                identity_login_attempt,
                identity_customer_account,
                loyalty_point_account
            CASCADE
            """.trimIndent(),
        )
    }

    @AfterEach
    fun cleanupTriggers() {
        dropPointFailureTrigger()
        dropPointConflictTrigger()
        dropSessionFailureTrigger()
    }

    @Test
    fun `registration provisions zero point account then login rotates session and logout deletes it`() {
        val csrf = issueCsrf()
        register(csrf, "  Demo.User-1  ", VALID_PASSWORD, "데모 고객")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.loginId").value("demo.user-1"))

        val account =
            jdbc.queryForMap(
                """
                SELECT a.id, a.login_id, a.password_hash, p.available_points_krw,
                       p.reserved_points_krw, p.recovery_pending_krw
                  FROM identity_customer_account a
                  JOIN loyalty_point_account p ON p.customer_id = a.id
                """.trimIndent(),
            )
        assertThat(account["login_id"]).isEqualTo("demo.user-1")
        assertThat(account["password_hash"].toString()).startsWith("\$argon2id\$").doesNotContain(VALID_PASSWORD)
        assertThat(listOf(account["available_points_krw"], account["reserved_points_krw"], account["recovery_pending_krw"]))
            .containsOnly(0L)

        val login = login(csrf, "DEMO.USER-1", VALID_PASSWORD).andExpect(status().isOk).andReturn()
        val session = requireNotNull(login.response.getCookie("BEANFLOW_CUSTOMER_SESSION"))
        assertThat(session.secure).isTrue()
        assertThat(session.isHttpOnly).isTrue()
        login.response.contentAsString.also { body ->
            assertThat(body).contains("\"actorType\":\"CUSTOMER\"").contains("\"displayName\":\"데모 고객\"")
            assertThat(body).doesNotContain(VALID_PASSWORD)
        }

        mockMvc
            .perform(get("/api/v1/me").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.displayName").value("데모 고객"))
        mockMvc
            .perform(delete("/api/v1/auth/customer/sessions/current").cookie(session, csrf).header(CSRF_HEADER, csrf.value))
            .andExpect(status().isNoContent)
            .andExpect(cookie().maxAge("BEANFLOW_CUSTOMER_SESSION", 0))
        mockMvc.perform(get("/api/v1/me").cookie(session)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `absent and wrong password responses are identical and attempts contain only HMAC`() {
        val csrf = issueCsrf()
        register(csrf, "known.user", VALID_PASSWORD, "Known")
        val (responses, logMessages) =
            captureIdentityLogs {
                listOf(
                    login(csrf, "known.user", WRONG_PASSWORD).andExpect(status().isUnauthorized).andReturn(),
                    login(csrf, "absent.user", WRONG_PASSWORD).andExpect(status().isUnauthorized).andReturn(),
                )
            }
        val (wrong, absent) = responses
        listOf(wrong, absent).forEach { result ->
            assertThat(result.response.contentAsString)
                .contains("\"code\":\"AUTHENTICATION_FAILED\"")
                .contains("\"message\":\"Authentication failed\"")
                .doesNotContain("known.user", "absent.user", WRONG_PASSWORD)
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_login_attempt", Long::class.java)).isEqualTo(3)
        assertThat(
            jdbc.queryForList("SELECT scope_hmac FROM identity_login_attempt", String::class.java),
        ).allMatch { it?.matches(Regex("^[0-9a-f]{64}$")) == true }
        assertThat(logMessages).doesNotContain(VALID_PASSWORD, WRONG_PASSWORD)
    }

    @Test
    fun `fifth failure locks account increments credential version and invalidates existing session`() {
        val csrf = issueCsrf()
        register(csrf, "lock.user", VALID_PASSWORD, "Locked Customer")
        val session = requireNotNull(login(csrf, "lock.user", VALID_PASSWORD).andReturn().response.getCookie("BEANFLOW_CUSTOMER_SESSION"))

        repeat(5) { login(csrf, "lock.user", WRONG_PASSWORD).andExpect(status().isUnauthorized) }

        val locked =
            jdbc.queryForMap(
                "SELECT state, credential_version, locked_until FROM identity_customer_account WHERE login_id = 'lock.user'",
            )
        assertThat(locked["state"]).isEqualTo("LOCKED")
        assertThat(locked["credential_version"]).isEqualTo(1L)
        assertThat(locked["locked_until"]).isNotNull()
        login(csrf, "lock.user", VALID_PASSWORD).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/api/v1/me").cookie(session)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `point account write failure rolls customer registration back and returns 503`() {
        jdbc.execute(
            """
            CREATE FUNCTION test_fail_point_provision() RETURNS trigger AS ${'$'}${'$'}
            BEGIN RAISE EXCEPTION 'forced point provisioning failure'; END;
            ${'$'}${'$'} LANGUAGE plpgsql
            """.trimIndent(),
        )
        jdbc.execute(
            "CREATE TRIGGER test_fail_point_provision BEFORE INSERT ON loyalty_point_account " +
                "FOR EACH ROW EXECUTE FUNCTION test_fail_point_provision()",
        )
        val csrf = issueCsrf()

        register(csrf, "rollback.user", VALID_PASSWORD, "Rollback")
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))

        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_customer_account", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty_point_account", Long::class.java)).isZero()
    }

    @Test
    fun `preexisting point account conflict rolls the complete registration back`() {
        jdbc.execute(
            """
            CREATE FUNCTION test_precreate_point_account() RETURNS trigger AS ${'$'}${'$'}
            BEGIN
              INSERT INTO loyalty_point_account
                  (id, customer_id, available_points_krw, reserved_points_krw, recovery_pending_krw)
              VALUES (NEW.id, NEW.id, 0, 0, 0);
              RETURN NEW;
            END;
            ${'$'}${'$'} LANGUAGE plpgsql
            """.trimIndent(),
        )
        jdbc.execute(
            "CREATE TRIGGER test_precreate_point_account AFTER INSERT ON identity_customer_account " +
                "FOR EACH ROW EXECUTE FUNCTION test_precreate_point_account()",
        )
        val csrf = issueCsrf()

        register(csrf, "point.conflict", VALID_PASSWORD, "Point Conflict")
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))

        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_customer_account", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty_point_account", Long::class.java)).isZero()
    }

    @Test
    fun `concurrent canonical registration commits exactly one account and one point account`() {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val futures =
            listOf("Race.User", " race.user ").map { loginId ->
                executor.submit<String> {
                    start.await()
                    try {
                        service.register(CustomerRegistration(loginId, VALID_PASSWORD, "Race Customer"))
                        "CREATED"
                    } catch (failure: DomainFailure) {
                        failure.code.name
                    }
                }
            }
        start.countDown()
        val outcomes = futures.map { it.get() }
        executor.shutdown()

        assertThat(outcomes).containsExactlyInAnyOrder("CREATED", "LOGIN_ID_UNAVAILABLE")
        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM identity_customer_account WHERE login_id = 'race.user'", Long::class.java),
        ).isOne()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty_point_account", Long::class.java)).isOne()
    }

    @Test
    fun `absent ID attack does not survive registration and expired account lock activates only on valid login`() {
        val csrf = issueCsrf()
        repeat(5) { login(csrf, "future.user", WRONG_PASSWORD).andExpect(status().isUnauthorized) }
        register(csrf, "future.user", VALID_PASSWORD, "Future Customer").andExpect(status().isCreated)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM identity_login_attempt WHERE actor_type = 'CUSTOMER' AND scope_type = 'LOGIN_ID'",
                Long::class.java,
            ),
        ).isZero()

        login(csrf, "future.user", VALID_PASSWORD).andExpect(status().isOk)
        jdbc.update(
            """
            UPDATE identity_customer_account
               SET state = 'LOCKED', locked_until = ?,
                   credential_version = credential_version + 1, updated_at = ?
             WHERE login_id = 'future.user'
            """.trimIndent(),
            Timestamp.from(DEFAULT_NOW.minusSeconds(1)),
            Timestamp.from(DEFAULT_NOW),
        )
        login(csrf, "future.user", VALID_PASSWORD).andExpect(status().isOk)
        assertThat(jdbc.queryForMap("SELECT state, locked_until FROM identity_customer_account WHERE login_id = 'future.user'"))
            .containsEntry("state", "ACTIVE")
            .containsEntry("locked_until", null)
    }

    @Test
    fun `account lock remains effective until its exact fixed clock boundary`() {
        service.register(CustomerRegistration("boundary.user", VALID_PASSWORD, "Boundary Customer"))
        val boundary = DEFAULT_NOW.plus(15, ChronoUnit.MINUTES)
        jdbc.update(
            """
            UPDATE identity_customer_account
               SET state = 'LOCKED', locked_until = ?, credential_version = credential_version + 1, updated_at = ?
             WHERE login_id = 'boundary.user'
            """.trimIndent(),
            Timestamp.from(boundary),
            Timestamp.from(DEFAULT_NOW),
        )

        clock.set(boundary.minus(1, ChronoUnit.MICROS))
        assertAuthenticationFailure { service.login("boundary.user", VALID_PASSWORD, "198.51.100.10", null) }

        clock.set(boundary)
        assertThat(service.login("boundary.user", VALID_PASSWORD, "198.51.100.10", null).displayName)
            .isEqualTo("Boundary Customer")
    }

    @Test
    fun `IP thirtieth failure returns 429 independently from login ID counter`() {
        val csrf = issueCsrf()
        register(csrf, "rate.user", VALID_PASSWORD, "Rate Customer")
        val now = clock.instant()
        jdbc.update(
            """
            INSERT INTO identity_login_attempt
                (id, actor_type, scope_type, scope_hmac, window_start, failure_count, blocked_until, updated_at)
            VALUES (?, 'CUSTOMER', 'IP', ?, ?, 29, NULL, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            hmac.ip(LoginAttemptActorType.CUSTOMER, "127.0.0.1"),
            Timestamp.from(now),
            Timestamp.from(now),
        )

        login(csrf, "rate.user", WRONG_PASSWORD)
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_RATE_LIMITED"))
            .andExpect { result -> assertThat(result.response.getHeader("Retry-After")?.toLong()).isBetween(1, 900) }
        assertThat(
            jdbc.queryForObject(
                "SELECT failure_count FROM identity_login_attempt WHERE scope_type = 'LOGIN_ID'",
                Int::class.java,
            ),
        ).isOne()
    }

    @Test
    fun `attempt windows reset at boundary and retention deletes bounded batches`() {
        val now = Instant.parse("2026-08-13T00:00:00Z")
        val loginHmac = "a".repeat(64)
        val ipHmac = "b".repeat(64)
        repeat(5) { index -> recordFailure(loginHmac, ipHmac, now.plusSeconds(index.toLong())) }
        var row = jdbc.queryForMap("SELECT failure_count, blocked_until FROM identity_login_attempt WHERE scope_type = 'LOGIN_ID'")
        assertThat(row["failure_count"]).isEqualTo(5)
        val blockedUntil = (row["blocked_until"] as Timestamp).toInstant()
        recordFailure(loginHmac, ipHmac, blockedUntil.minus(1, ChronoUnit.MICROS))
        row = jdbc.queryForMap("SELECT failure_count, blocked_until FROM identity_login_attempt WHERE scope_type = 'LOGIN_ID'")
        assertThat(row["failure_count"]).isEqualTo(5)
        recordFailure(loginHmac, ipHmac, blockedUntil)
        row = jdbc.queryForMap("SELECT failure_count, blocked_until FROM identity_login_attempt WHERE scope_type = 'LOGIN_ID'")
        assertThat(row["failure_count"]).isEqualTo(1)
        assertThat(row["blocked_until"]).isNull()

        val old = now.minus(25, ChronoUnit.HOURS)
        repeat(101) { index ->
            jdbc.update(
                """
                INSERT INTO identity_login_attempt
                    (id, actor_type, scope_type, scope_hmac, window_start, failure_count, blocked_until, updated_at)
                VALUES (?, 'MERCHANT', 'LOGIN_ID', ?, ?, 1, NULL, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                "%064x".format(index + 1000),
                Timestamp.from(old),
                Timestamp.from(old),
            )
        }
        assertThat(transactions.execute { attempts.deleteExpired(now.minus(24, ChronoUnit.HOURS), 100) }).isEqualTo(100)
        assertThat(transactions.execute { attempts.deleteExpired(now.minus(24, ChronoUnit.HOURS), 100) }).isEqualTo(1)
    }

    @Test
    fun `concurrent failures do not lose counts or create more than one lock transition`() {
        service.register(CustomerRegistration("parallel.user", VALID_PASSWORD, "Parallel Customer"))
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(6)
        val futures =
            (1..6).map { index ->
                executor.submit<String> {
                    start.await()
                    try {
                        service.login("parallel.user", WRONG_PASSWORD, "198.51.100.$index", null)
                        "UNEXPECTED"
                    } catch (failure: DomainFailure) {
                        failure.code.name
                    }
                }
            }
        start.countDown()
        assertThat(futures.map { it.get() }).containsOnly("AUTHENTICATION_FAILED")
        executor.shutdown()

        assertThat(jdbc.queryForMap("SELECT state, credential_version FROM identity_customer_account WHERE login_id = 'parallel.user'"))
            .containsEntry("state", "LOCKED")
            .containsEntry("credential_version", 1L)
        assertThat(
            jdbc.queryForObject(
                "SELECT failure_count FROM identity_login_attempt WHERE scope_type = 'LOGIN_ID'",
                Int::class.java,
            ),
        ).isEqualTo(5)
    }

    @Test
    fun `customer and merchant attempt locks share deterministic ordering without lost updates`() {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        val futures =
            LoginAttemptActorType.entries.flatMap { actorType ->
                val loginHmac = hmac.loginId(actorType, "shared.user")
                val ipHmac = hmac.ip(actorType, "198.51.100.44")
                List(2) {
                    executor.submit {
                        start.await(10, TimeUnit.SECONDS)
                        transactions.executeWithoutResult {
                            val lock = attempts.beginFailure(actorType, loginHmac, ipHmac, DEFAULT_NOW)
                            attempts.applyFailure(lock, DEFAULT_NOW)
                        }
                    }
                }
            }
        try {
            start.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val rows =
            jdbc.queryForList(
                "SELECT actor_type, scope_type, failure_count FROM identity_login_attempt ORDER BY actor_type, scope_type",
            )
        assertThat(rows).hasSize(4)
        assertThat(rows).allSatisfy { row -> assertThat(row["failure_count"]).isEqualTo(2) }
        assertThat(rows.map { it["actor_type"] }.toSet()).containsExactlyInAnyOrder("CUSTOMER", "MERCHANT")
    }

    @Test
    fun `credential change while password verification waits returns 401 without attempt or session`() {
        service.register(CustomerRegistration("snapshot.user", VALID_PASSWORD, "Snapshot Customer"))
        val accountId = accountId("snapshot.user")
        val replacementHash = passwordSecurity.encode("replacement customer password 789")
        val executor = Executors.newSingleThreadExecutor()

        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("SELECT id FROM identity_customer_account WHERE id = ? FOR UPDATE").use { statement ->
                    statement.setObject(1, accountId)
                    statement.executeQuery().use { result -> assertThat(result.next()).isTrue() }
                }
                val outcome =
                    executor.submit<String> {
                        try {
                            service.login("snapshot.user", VALID_PASSWORD, "198.51.100.55", null)
                            "UNEXPECTED_SUCCESS"
                        } catch (failure: DomainFailure) {
                            failure.code.name
                        }
                    }
                assertThat(waitForAccountLockWait()).isTrue()
                connection
                    .prepareStatement(
                        """
                        UPDATE identity_customer_account
                           SET password_hash = ?, credential_version = credential_version + 1, updated_at = ?
                         WHERE id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, replacementHash)
                        statement.setTimestamp(2, Timestamp.from(DEFAULT_NOW))
                        statement.setObject(3, accountId)
                        assertThat(statement.executeUpdate()).isOne()
                    }
                connection.commit()

                assertThat(outcome.get(20, TimeUnit.SECONDS)).isEqualTo("AUTHENTICATION_FAILED")
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            } finally {
                executor.shutdownNow()
            }
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_login_attempt", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session", Long::class.java)).isZero()
    }

    @Test
    fun `concurrent fifth and sixth customer login keeps exactly five sessions`() {
        service.register(CustomerRegistration("session.cap", VALID_PASSWORD, "Session Cap Customer"))
        val accountId = accountId("session.cap")
        val initial =
            (1..4).map { index ->
                service.login("session.cap", VALID_PASSWORD, "198.51.100.$index", null).session.sessionId
            }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val futures =
            (5..6).map { index ->
                executor.submit<String> {
                    start.await(10, TimeUnit.SECONDS)
                    service.login("session.cap", VALID_PASSWORD, "198.51.100.$index", null).session.sessionId
                }
            }
        val concurrent =
            try {
                start.countDown()
                futures.map { it.get(20, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

        val stored =
            jdbc
                .queryForList(
                    "SELECT session_id FROM spring_session WHERE principal_name = ? ORDER BY session_id",
                    String::class.java,
                    principalName(BrowserActorType.CUSTOMER, accountId.toString()),
                ).filterNotNull()
        assertThat((initial + concurrent).toSet()).hasSize(6)
        assertThat(stored).hasSize(5).allMatch { it in initial + concurrent }
    }

    @Test
    fun `session insert failure rolls back rotation and expired lock activation`() {
        service.register(CustomerRegistration("session.rollback", VALID_PASSWORD, "Session Rollback Customer"))
        val accountId = accountId("session.rollback")
        val existing =
            service.login("session.rollback", VALID_PASSWORD, "198.51.100.71", null).session.sessionId
        jdbc.update(
            """
            UPDATE identity_customer_account
               SET state = 'LOCKED', locked_until = ?, credential_version = credential_version + 1, updated_at = ?
             WHERE id = ?
            """.trimIndent(),
            Timestamp.from(DEFAULT_NOW.minusSeconds(1)),
            Timestamp.from(DEFAULT_NOW),
            accountId,
        )
        createSessionInsertFailureTrigger()

        assertThatThrownBy {
            service.login("session.rollback", VALID_PASSWORD, "198.51.100.71", existing)
        }.isInstanceOfSatisfying(DomainFailure::class.java) { failure ->
            assertThat(failure.code).isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
        }
        dropSessionFailureTrigger()

        assertThat(jdbc.queryForMap("SELECT state, locked_until FROM identity_customer_account WHERE id = ?", accountId))
            .containsEntry("state", "LOCKED")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE session_id = ?", Long::class.java, existing))
            .isOne()
    }

    @Test
    fun `session identity ignores forged customer ID and preserves order ownership`() {
        val csrf = issueCsrf()
        service.register(CustomerRegistration("owner.user", VALID_PASSWORD, "Order Owner"))
        service.register(CustomerRegistration("other.user", VALID_PASSWORD, "Other Customer"))
        val ownerId = accountId("owner.user")
        val otherId = accountId("other.user")
        val ownerSession =
            requireNotNull(login(csrf, "owner.user", VALID_PASSWORD).andReturn().response.getCookie("BEANFLOW_CUSTOMER_SESSION"))
        val otherSession =
            requireNotNull(login(csrf, "other.user", VALID_PASSWORD).andReturn().response.getCookie("BEANFLOW_CUSTOMER_SESSION"))
        val fixture = OrderCreationFixture(customerId = ownerId)
        OrderCreationDatabaseFixture.insertBase(jdbc, fixture)

        mockMvc
            .perform(
                post("/api/v1/orders")
                    .cookie(ownerSession, csrf)
                    .header(CSRF_HEADER, csrf.value)
                    .header("Idempotency-Key", "customer-session-owner-001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(orderRequestBody(fixture, otherId)),
            ).andExpect(status().isCreated)
        val order = jdbc.queryForMap("SELECT id, customer_id FROM ordering_order")
        assertThat(order["customer_id"]).isEqualTo(ownerId)

        mockMvc
            .perform(get("/api/v1/orders/{orderId}", order["id"]).cookie(otherSession))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
    }

    private fun issueCsrf(): Cookie {
        val result = mockMvc.perform(get("/api/v1/auth/customer/csrf")).andExpect(status().isNoContent).andReturn()
        return requireNotNull(result.response.getCookie("BEANFLOW_CUSTOMER_XSRF"))
    }

    private fun register(
        csrf: Cookie,
        loginId: String,
        password: String,
        displayName: String,
    ) = mockMvc.perform(
        post("/api/v1/auth/customer/registrations")
            .cookie(csrf)
            .header(CSRF_HEADER, csrf.value)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"loginId":"$loginId","password":"$password","displayName":"$displayName"}"""),
    )

    private fun login(
        csrf: Cookie,
        loginId: String,
        password: String,
    ) = mockMvc.perform(
        post("/api/v1/auth/customer/sessions")
            .cookie(csrf)
            .header(CSRF_HEADER, csrf.value)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"loginId":"$loginId","password":"$password"}"""),
    )

    private fun dropPointFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_point_provision ON loyalty_point_account")
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_point_provision()")
    }

    private fun dropPointConflictTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_precreate_point_account ON identity_customer_account")
        jdbc.execute("DROP FUNCTION IF EXISTS test_precreate_point_account()")
    }

    private fun createSessionInsertFailureTrigger() {
        jdbc.execute(
            """
            CREATE FUNCTION test_fail_customer_session_insert() RETURNS trigger AS ${'$'}${'$'}
            BEGIN RAISE EXCEPTION 'forced customer session insert failure'; END;
            ${'$'}${'$'} LANGUAGE plpgsql
            """.trimIndent(),
        )
        jdbc.execute(
            "CREATE TRIGGER test_fail_customer_session_insert BEFORE INSERT ON spring_session " +
                "FOR EACH ROW EXECUTE FUNCTION test_fail_customer_session_insert()",
        )
    }

    private fun dropSessionFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_customer_session_insert ON spring_session")
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_customer_session_insert()")
    }

    private fun accountId(loginId: String): UUID =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT id FROM identity_customer_account WHERE login_id = ?",
                UUID::class.java,
                loginId,
            ),
        )

    private fun <T> captureIdentityLogs(block: () -> T): Pair<T, String> {
        val logger = LoggerFactory.getLogger("io.github.kdh949.beanflow.identity") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            val result = block()
            result to appender.list.joinToString("\n", transform = ILoggingEvent::getFormattedMessage)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private fun waitForAccountLockWait(): Boolean {
        repeat(400) {
            val waiting =
                jdbc.queryForObject(
                    """
                    SELECT count(*)
                      FROM pg_stat_activity
                     WHERE datname = current_database()
                       AND pid <> pg_backend_pid()
                       AND wait_event_type = 'Lock'
                       AND query LIKE '%identity_customer_account%'
                    """.trimIndent(),
                    Long::class.java,
                ) ?: 0
            if (waiting > 0) return true
            Thread.sleep(25)
        }
        return false
    }

    private fun assertAuthenticationFailure(block: () -> Unit) {
        assertThatThrownBy(block)
            .isInstanceOfSatisfying(DomainFailure::class.java) { failure ->
                assertThat(failure.code).isEqualTo(FailureCode.AUTHENTICATION_FAILED)
            }
    }

    private fun orderRequestBody(
        fixture: OrderCreationFixture,
        forgedCustomerId: UUID,
    ): String =
        """
        {
          "customerId": "$forgedCustomerId",
          "storeId": "${fixture.storeId}",
          "pickupSlotId": "${fixture.pickupSlotId}",
          "lines": [
            {
              "menuId": "${fixture.menuId}",
              "optionIds": [],
              "quantity": 1
            }
          ],
          "pointsToUseKrw": 0
        }
        """.trimIndent()

    private fun recordFailure(
        loginHmac: String,
        ipHmac: String,
        now: Instant,
    ) {
        transactions.executeWithoutResult {
            val locked = attempts.beginFailure(LoginAttemptActorType.CUSTOMER, loginHmac, ipHmac, now)
            attempts.applyFailure(locked, now)
        }
    }

    private companion object {
        const val CSRF_HEADER = "X-BEANFLOW-CSRF"
        const val VALID_PASSWORD = "valid customer password 123"
        const val WRONG_PASSWORD = "wrong customer password 456"
        val DEFAULT_NOW: Instant = Instant.parse("2026-08-13T00:00:00Z")
    }
}

@TestConfiguration(proxyBeanMethods = false)
internal class CustomerAuthenticationTestClockConfiguration {
    @Bean
    @Primary
    fun customerAuthenticationTestClock(): MutableCustomerAuthenticationClock =
        MutableCustomerAuthenticationClock(Instant.parse("2026-08-13T00:00:00Z"))
}

internal class MutableCustomerAuthenticationClock(
    initial: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    private val current = AtomicReference(initial)

    fun set(now: Instant) {
        current.set(now)
    }

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableCustomerAuthenticationClock(current.get(), zone)

    override fun instant(): Instant = current.get()
}

package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.shared.api.BrowserActorLoader
import io.github.kdh949.beanflow.shared.api.BrowserActorType
import io.github.kdh949.beanflow.shared.api.BrowserSessionLifecycle
import io.github.kdh949.beanflow.shared.api.CreateLoginSession
import io.github.kdh949.beanflow.shared.api.CurrentActor
import io.github.kdh949.beanflow.shared.api.CustomerActor
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.LoginSessionCoordinator
import io.github.kdh949.beanflow.shared.api.MerchantAccountState
import io.github.kdh949.beanflow.shared.api.MerchantActor
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class, TestBrowserActorLoaderConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies startup, DDL, or committed state across a transaction boundary")
@SpringBootTest(properties = ["spring.main.allow-bean-definition-overriding=true"])
class BrowserSessionPostgresIntegrationTest(
    @Autowired private val coordinator: LoginSessionCoordinator,
    @Autowired private val lifecycle: BrowserSessionLifecycle,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired transactionManager: PlatformTransactionManager,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val clock: Clock,
) {
    private val transactions = TransactionTemplate(transactionManager)

    @BeforeEach
    fun clearSessions() {
        jdbcTemplate.update("DELETE FROM spring_session")
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS test_browser_account_lock (id uuid PRIMARY KEY)")
    }

    @Test
    fun `explicit save rotates id and logout makes the same cookie unauthorized`() {
        val actorId = UUID.randomUUID()
        val first = create(BrowserActorType.CUSTOMER, actorId, clock.millis() - 1_000, 1)
        val rotated = create(BrowserActorType.CUSTOMER, actorId, clock.millis(), 1, first.sessionId)

        assertThat(rotated.sessionId).isNotEqualTo(first.sessionId)
        assertThat(countSession(first.sessionId)).isZero()
        assertThat(countSession(rotated.sessionId)).isOne()
        assertThat(attributeNames(rotated.sessionId)).containsExactlyInAnyOrder(
            "org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME",
            ACTOR_ID_ATTRIBUTE,
            AUTHENTICATED_AT_ATTRIBUTE,
            CREDENTIAL_VERSION_ATTRIBUTE,
        )

        mockMvc
            .perform(
                get("/api/v1/point-accounts/${UUID.randomUUID()}")
                    .cookie(sessionCookie("BEANFLOW_CUSTOMER_SESSION", rotated.sessionId)),
            ).andExpect(status().isNotFound)

        transactions.executeWithoutResult { lifecycle.logout(rotated.sessionId) }
        mockMvc
            .perform(
                get("/api/v1/point-accounts/${UUID.randomUUID()}")
                    .cookie(sessionCookie("BEANFLOW_CUSTOMER_SESSION", rotated.sessionId)),
            ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `caller account row lock keeps concurrent merchant logins within cap`() {
        val actorId = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO test_browser_account_lock(id) VALUES (?)", actorId)
        val oldest = create(BrowserActorType.MERCHANT, actorId, 100, 1)
        create(BrowserActorType.MERCHANT, actorId, 101, 1)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures =
                (102L..103L).map { authenticatedAt ->
                    pool.submit {
                        start.await(10, TimeUnit.SECONDS)
                        transactions.executeWithoutResult {
                            jdbcTemplate.queryForObject(
                                "SELECT id FROM test_browser_account_lock WHERE id = ? FOR UPDATE",
                                UUID::class.java,
                                actorId,
                            )
                            coordinator.create(
                                CreateLoginSession(BrowserActorType.MERCHANT, actorId, authenticatedAt, 1),
                            )
                        }
                    }
                }
            start.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertThat(countPrincipalSessions(BrowserActorType.MERCHANT, actorId)).isEqualTo(3)
        assertThat(countSession(oldest.sessionId)).isZero()
    }

    @Test
    fun `new session insert failure rolls back deletion of rotated session`() {
        val actorId = UUID.randomUUID()
        val existing = create(BrowserActorType.CUSTOMER, actorId, 100, 1)
        jdbcTemplate.execute(
            """
            CREATE OR REPLACE FUNCTION test_reject_browser_session_insert()
            RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'injected session insert failure'; END;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            "CREATE TRIGGER test_browser_session_insert_failure BEFORE INSERT ON spring_session " +
                "FOR EACH ROW EXECUTE FUNCTION test_reject_browser_session_insert()",
        )
        try {
            assertThatThrownBy {
                transactions.executeWithoutResult {
                    coordinator.create(
                        CreateLoginSession(BrowserActorType.CUSTOMER, actorId, 200, 1, existing.sessionId),
                    )
                }
            }.isInstanceOf(DomainFailure::class.java)
        } finally {
            jdbcTemplate.execute("DROP TRIGGER test_browser_session_insert_failure ON spring_session")
            jdbcTemplate.execute("DROP FUNCTION test_reject_browser_session_insert()")
        }

        assertThat(countSession(existing.sessionId)).isOne()
        assertThat(countPrincipalSessions(BrowserActorType.CUSTOMER, actorId)).isOne()
    }

    @Test
    fun `session delete failure prevents rotation without exceeding the cap`() {
        val actorId = UUID.randomUUID()
        val existing = create(BrowserActorType.MERCHANT, actorId, 100, 1)
        jdbcTemplate.execute(
            """
            CREATE OR REPLACE FUNCTION test_reject_browser_session_delete()
            RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'injected session delete failure'; END;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            "CREATE TRIGGER test_browser_session_delete_failure BEFORE DELETE ON spring_session " +
                "FOR EACH ROW EXECUTE FUNCTION test_reject_browser_session_delete()",
        )
        try {
            assertThatThrownBy {
                transactions.executeWithoutResult {
                    coordinator.create(
                        CreateLoginSession(BrowserActorType.MERCHANT, actorId, 200, 1, existing.sessionId),
                    )
                }
            }.isInstanceOf(DomainFailure::class.java)
        } finally {
            jdbcTemplate.execute("DROP TRIGGER test_browser_session_delete_failure ON spring_session")
            jdbcTemplate.execute("DROP FUNCTION test_reject_browser_session_delete()")
        }

        assertThat(countSession(existing.sessionId)).isOne()
        assertThat(countPrincipalSessions(BrowserActorType.MERCHANT, actorId)).isOne()
    }

    private fun create(
        actorType: BrowserActorType,
        actorId: UUID,
        authenticatedAt: Long,
        credentialVersion: Long,
        currentSessionId: String? = null,
    ) = requireNotNull(
        transactions.execute {
            coordinator.create(
                CreateLoginSession(actorType, actorId, authenticatedAt, credentialVersion, currentSessionId),
            )
        },
    )

    private fun countSession(sessionId: String): Long =
        requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM spring_session WHERE session_id = ?",
                Long::class.java,
                sessionId,
            ),
        )

    private fun sessionCookie(
        name: String,
        sessionId: String,
    ): Cookie = Cookie(name, Base64.getEncoder().encodeToString(sessionId.toByteArray(Charsets.UTF_8)))

    private fun countPrincipalSessions(
        actorType: BrowserActorType,
        actorId: UUID,
    ): Long =
        requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM spring_session WHERE principal_name = ?",
                Long::class.java,
                principalName(actorType, actorId.toString()),
            ),
        )

    private fun attributeNames(sessionId: String): List<String> =
        jdbcTemplate
            .queryForList(
                """
                SELECT a.attribute_name
                FROM spring_session_attributes a
                JOIN spring_session s ON s.primary_id = a.session_primary_id
                WHERE s.session_id = ?
                """.trimIndent(),
                String::class.java,
                sessionId,
            ).filterNotNull()
}

@TestConfiguration(proxyBeanMethods = false)
internal class TestBrowserActorLoaderConfiguration {
    @Bean
    fun customerBrowserActorLoader(): BrowserActorLoader = testLoader(BrowserActorType.CUSTOMER) { actorId -> CustomerActor(actorId) }

    @Bean
    fun merchantBrowserActorLoader(): BrowserActorLoader =
        testLoader(BrowserActorType.MERCHANT) { actorId -> MerchantActor(actorId, MerchantAccountState.ACTIVE) }

    private fun testLoader(
        type: BrowserActorType,
        actor: (UUID) -> CurrentActor,
    ): BrowserActorLoader =
        object : BrowserActorLoader {
            override val actorType = type

            override fun load(
                actorId: UUID,
                credentialVersion: Long,
            ): CurrentActor = actor(actorId)
        }
}

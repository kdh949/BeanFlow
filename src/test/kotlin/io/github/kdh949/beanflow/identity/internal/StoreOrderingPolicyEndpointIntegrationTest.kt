package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.merchant.api.StoreDiscoveryQueryOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies versioned Store ordering policy authoring and replay")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class StoreOrderingPolicyEndpointIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val passwords: CustomerPasswordSecurity,
    @Autowired private val applicationService: StoreOrderingPolicyApplicationService,
    @Autowired private val discoveryQueries: StoreDiscoveryQueryOperations,
    @Autowired transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    @BeforeEach
    fun cleanDatabase() {
        jdbc.execute(
            """
            TRUNCATE TABLE spring_session_attributes, spring_session, identity_login_attempt,
                merchant_store_ordering_policy_command, identity_store_membership, identity_merchant_account,
                operations_audit_record, merchant_store CASCADE
            """.trimIndent(),
        )
    }

    @Test
    fun `OWNER and STAFF read and replace policy while a normalized no-op preserves version and Audit`() {
        listOf("OWNER", "STAFF").forEachIndexed { index, role ->
            val storeId = seedStore()
            val session = signIn("policy.$index", storeId, role)

            getPolicy(session, storeId)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.storeId").value(storeId.toString()))
                .andExpect(jsonPath("$.acceptingOrders").value(true))
                .andExpect(jsonPath("$.pickupEnabled").value(true))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.updatedAt").value("1970-01-01T00:00:00Z"))

            putPolicy(session, storeId, "policy-key-$index-0001", expectedVersion = 0, acceptingOrders = false)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.acceptingOrders").value(false))
                .andExpect(jsonPath("$.pickupEnabled").value(true))
                .andExpect(jsonPath("$.version").value(1))

            putPolicy(session, storeId, "policy-key-$index-0002", expectedVersion = 1, acceptingOrders = false)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.version").value(1))

            assertThat(policyVersion(storeId)).isEqualTo(1)
            assertThat(auditActorTypes(storeId)).containsExactly(if (role == "OWNER") "STORE_OWNER" else "STORE_STAFF")
        }
    }

    @Test
    fun `same key replays the first response and changed payload is rejected`() {
        val storeId = seedStore()
        val session = signIn("policy.replay", storeId, "OWNER")
        val key = "policy-replay-key-0001"

        val first =
            putPolicy(session, storeId, key, expectedVersion = 0, acceptingOrders = false)
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val replay =
            putPolicy(session, storeId, key, expectedVersion = 0, acceptingOrders = false)
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        assertThat(replay).isEqualTo(first)

        putPolicy(session, storeId, key, expectedVersion = 0, acceptingOrders = true)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))

        assertThat(policyVersion(storeId)).isEqualTo(1)
        assertThat(commandCount()).isEqualTo(1)
        assertThat(auditActorTypes(storeId)).containsExactly("STORE_OWNER")
    }

    @Test
    fun `same actor and key across Stores deterministically returns replay conflict instead of dependency failure`() {
        val firstStoreId = seedStore()
        val secondStoreId = seedStore()
        val actor = signIn("policy.cross-store-key", firstStoreId, "OWNER")
        seedMembership(actor.actorId, secondStoreId, "OWNER")
        val key = "policy-cross-store-key-001"
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        jdbc.execute(
            """
            CREATE OR REPLACE FUNCTION test_delay_policy_command_insert() RETURNS trigger
            LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
                PERFORM pg_sleep(0.25);
                RETURN NEW;
            END
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            """
            CREATE TRIGGER test_delay_policy_command_insert
            BEFORE INSERT ON merchant_store_ordering_policy_command
            FOR EACH ROW EXECUTE FUNCTION test_delay_policy_command_insert()
            """.trimIndent(),
        )

        try {
            val first =
                executor.submit(
                    Callable {
                        ready.countDown()
                        check(start.await(5, TimeUnit.SECONDS))
                        runCatching {
                            applicationService.replace(
                                StoreOrderingPolicyCommandContext(actor.actorId, key),
                                firstStoreId,
                                acceptingOrders = false,
                                pickupEnabled = true,
                                expectedVersion = 0,
                            )
                        }
                    },
                )
            val second =
                executor.submit(
                    Callable {
                        ready.countDown()
                        check(start.await(5, TimeUnit.SECONDS))
                        runCatching {
                            applicationService.replace(
                                StoreOrderingPolicyCommandContext(actor.actorId, key),
                                secondStoreId,
                                acceptingOrders = false,
                                pickupEnabled = true,
                                expectedVersion = 0,
                            )
                        }
                    },
                )
            check(ready.await(5, TimeUnit.SECONDS))
            start.countDown()

            val results = listOf(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
            assertThat(results.count { it.isSuccess }).isOne()
            val failure = results.single { it.isFailure }.exceptionOrNull() as DomainFailure
            assertThat(failure.code).isEqualTo(FailureCode.IDEMPOTENCY_KEY_REUSED)
            assertThat(listOf(policyVersion(firstStoreId), policyVersion(secondStoreId)))
                .containsExactlyInAnyOrder(0, 1)
            assertThat(commandCount()).isOne()
            assertThat(auditActorTypes(firstStoreId) + auditActorTypes(secondStoreId)).hasSize(1)
        } finally {
            start.countDown()
            executor.shutdownNow()
            jdbc.execute("DROP TRIGGER IF EXISTS test_delay_policy_command_insert ON merchant_store_ordering_policy_command")
            jdbc.execute("DROP FUNCTION IF EXISTS test_delay_policy_command_insert()")
        }
    }

    @Test
    fun `stale revoked and cross-store requests do not change policy`() {
        val storeId = seedStore()
        val otherStoreId = seedStore()
        val session = signIn("policy.scope", storeId, "OWNER")

        putPolicy(session, storeId, "policy-stale-key-0001", expectedVersion = 7, acceptingOrders = false)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("MERCHANT_CONTENT_STALE"))
        getPolicy(session, otherStoreId)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        putPolicy(session, otherStoreId, "policy-cross-key-0001", expectedVersion = 0, acceptingOrders = false)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        jdbc.update("UPDATE identity_store_membership SET status = 'REVOKED' WHERE store_id = ?", storeId)
        getPolicy(session, storeId)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
        putPolicy(session, storeId, "policy-revoked-key-0001", expectedVersion = 0, acceptingOrders = false)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

        assertThat(policyVersion(storeId)).isZero()
        assertThat(commandCount()).isZero()
        assertThat(auditActorTypes(storeId)).isEmpty()
    }

    @Test
    fun `anonymous access is unauthorized and an authenticated mutation requires CSRF`() {
        val storeId = seedStore()

        mockMvc
            .perform(get("/api/v1/stores/$storeId/ordering-policy"))
            .andExpect(status().isUnauthorized)

        val session = signIn("policy.csrf", storeId, "OWNER")
        mockMvc
            .perform(
                put("/api/v1/stores/$storeId/ordering-policy")
                    .cookie(session.session)
                    .header("Idempotency-Key", "policy-csrf-key-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(policyJson(expectedVersion = 0, acceptingOrders = false)),
            ).andExpect(status().isForbidden)

        assertThat(policyVersion(storeId)).isZero()
        assertThat(commandCount()).isZero()
    }

    @Test
    fun `committed ordering policy is immediately reflected in the customer Store projection`() {
        val storeId = seedStore()
        seedDiscoveryProfile(storeId)
        val session = signIn("policy.projection", storeId, "OWNER")

        assertThat(discoveryQueries.findVisibleStores(listOf(storeId)).single().orderingAvailable).isTrue()

        putPolicy(session, storeId, "policy-projection-key-001", expectedVersion = 0, acceptingOrders = false)
            .andExpect(status().isOk)

        assertThat(discoveryQueries.findVisibleStores(listOf(storeId)).single().orderingAvailable).isFalse()
    }

    @Test
    fun `policy owner row command ledger and Audit roll back together`() {
        val storeId = seedStore()
        val actor = signIn("policy.rollback", storeId, "OWNER")

        assertThatThrownBy {
            transactions.executeWithoutResult {
                applicationService.replace(
                    StoreOrderingPolicyCommandContext(actor.actorId, "policy-rollback-key-001"),
                    storeId,
                    acceptingOrders = false,
                    pickupEnabled = true,
                    expectedVersion = 0,
                )
                error("force Store policy transaction rollback")
            }
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(policyVersion(storeId)).isZero()
        assertThat(commandCount()).isZero()
        assertThat(auditActorTypes(storeId)).isEmpty()
    }

    @Test
    fun `authoring shared membership lock lets the first command commit before revoke`() {
        val storeId = seedStore()
        val actor = signIn("policy.author-first", storeId, "OWNER")
        val authoringPrepared = CountDownLatch(1)
        val allowAuthoringCommit = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val authoring =
                executor.submit(
                    Callable {
                        transactions.execute {
                            val policy =
                                applicationService.replace(
                                    StoreOrderingPolicyCommandContext(actor.actorId, "policy-author-first-001"),
                                    storeId,
                                    acceptingOrders = false,
                                    pickupEnabled = true,
                                    expectedVersion = 0,
                                )
                            authoringPrepared.countDown()
                            check(allowAuthoringCommit.await(5, TimeUnit.SECONDS))
                            policy
                        }
                    },
                )
            check(authoringPrepared.await(10, TimeUnit.SECONDS))

            val revoke =
                executor.submit {
                    transactions.executeWithoutResult {
                        jdbc.update(
                            "UPDATE identity_store_membership SET status = 'REVOKED' WHERE actor_id = ? AND store_id = ?",
                            actor.actorId,
                            storeId,
                        )
                    }
                }
            assertThatThrownBy { revoke.get(300, TimeUnit.MILLISECONDS) }
                .isInstanceOf(TimeoutException::class.java)

            allowAuthoringCommit.countDown()
            assertThat(authoring.get(10, TimeUnit.SECONDS)?.version).isEqualTo(1)
            revoke.get(5, TimeUnit.SECONDS)

            assertThat(policyVersion(storeId)).isOne()
            assertThat(membershipStatus(actor.actorId, storeId)).isEqualTo("REVOKED")
        } finally {
            allowAuthoringCommit.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `revoke committed first makes the waiting authoring command forbidden`() {
        val storeId = seedStore()
        val actor = signIn("policy.revoke-first", storeId, "STAFF")
        val revokeUpdated = CountDownLatch(1)
        val allowRevokeCommit = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val revoke =
                executor.submit {
                    transactions.executeWithoutResult {
                        jdbc.update(
                            "UPDATE identity_store_membership SET status = 'REVOKED' WHERE actor_id = ? AND store_id = ?",
                            actor.actorId,
                            storeId,
                        )
                        revokeUpdated.countDown()
                        check(allowRevokeCommit.await(5, TimeUnit.SECONDS))
                    }
                }
            check(revokeUpdated.await(5, TimeUnit.SECONDS))

            val authoring =
                executor.submit(
                    Callable {
                        applicationService.replace(
                            StoreOrderingPolicyCommandContext(actor.actorId, "policy-revoke-first-001"),
                            storeId,
                            acceptingOrders = false,
                            pickupEnabled = true,
                            expectedVersion = 0,
                        )
                    },
                )
            assertThatThrownBy { authoring.get(300, TimeUnit.MILLISECONDS) }
                .isInstanceOf(TimeoutException::class.java)

            allowRevokeCommit.countDown()
            revoke.get(5, TimeUnit.SECONDS)
            val failure =
                try {
                    authoring.get(10, TimeUnit.SECONDS)
                    error("Authoring should fail after membership revoke")
                } catch (exception: ExecutionException) {
                    exception.cause as DomainFailure
                }

            assertThat(failure.code).isEqualTo(FailureCode.ACCESS_DENIED)
            assertThat(policyVersion(storeId)).isZero()
            assertThat(commandCount()).isZero()
        } finally {
            allowRevokeCommit.countDown()
            executor.shutdownNow()
        }
    }

    private fun getPolicy(
        session: MerchantSession,
        storeId: UUID,
    ) = mockMvc.perform(get("/api/v1/stores/$storeId/ordering-policy").cookie(session.session))

    private fun putPolicy(
        session: MerchantSession,
        storeId: UUID,
        key: String,
        expectedVersion: Long,
        acceptingOrders: Boolean,
        pickupEnabled: Boolean = true,
    ) = mockMvc.perform(
        put("/api/v1/stores/$storeId/ordering-policy")
            .cookie(session.session, session.csrf)
            .header(CSRF_HEADER, session.csrf.value)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(policyJson(expectedVersion, acceptingOrders, pickupEnabled)),
    )

    private fun policyJson(
        expectedVersion: Long,
        acceptingOrders: Boolean,
        pickupEnabled: Boolean = true,
    ) = """
        {
          "acceptingOrders": $acceptingOrders,
          "pickupEnabled": $pickupEnabled,
          "expectedVersion": $expectedVersion
        }
        """.trimIndent()

    private fun seedStore(): UUID =
        UUID.randomUUID().also { storeId ->
            jdbc.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                storeId,
            )
        }

    private fun seedDiscoveryProfile(storeId: UUID) {
        jdbc.update(
            """
            INSERT INTO merchant_store_discovery_profile (store_id, name, location, region_code)
            VALUES (?, 'Policy Store', ST_SetSRID(ST_MakePoint(127.0276, 37.4979), 4326)::geography, '1168010100')
            """.trimIndent(),
            storeId,
        )
    }

    private fun signIn(
        loginId: String,
        storeId: UUID,
        role: String,
    ): MerchantSession {
        val accountId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO identity_merchant_account
                (id, login_id, password_hash, credential_version, display_name, state,
                 temporary_password_expires_at, password_changed_at, locked_until,
                 created_at, updated_at, version)
            VALUES (?, ?, ?, 0, 'Policy Merchant', 'ACTIVE', NULL, ?, NULL, ?, ?, 0)
            """.trimIndent(),
            accountId,
            loginId,
            passwords.encode(PASSWORD),
            Timestamp.from(NOW),
            Timestamp.from(NOW.minusSeconds(1)),
            Timestamp.from(NOW),
        )
        jdbc.update(
            """
            INSERT INTO identity_store_membership
                (id, actor_id, store_id, membership_role, status, created_at, updated_at, version)
            VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, 0)
            """.trimIndent(),
            UUID.randomUUID(),
            accountId,
            storeId,
            role,
            Timestamp.from(NOW),
            Timestamp.from(NOW),
        )
        val csrf =
            requireNotNull(
                mockMvc
                    .perform(get("/api/v1/auth/merchant/csrf"))
                    .andReturn()
                    .response
                    .getCookie(CSRF_COOKIE),
            )
        val session =
            requireNotNull(
                mockMvc
                    .perform(
                        post("/api/v1/auth/merchant/sessions")
                            .cookie(csrf)
                            .header(CSRF_HEADER, csrf.value)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"loginId":"$loginId","password":"$PASSWORD"}"""),
                    ).andExpect(status().isOk)
                    .andReturn()
                    .response
                    .getCookie(SESSION_COOKIE),
            )
        return MerchantSession(accountId, session, csrf)
    }

    private fun seedMembership(
        actorId: UUID,
        storeId: UUID,
        role: String,
    ) {
        jdbc.update(
            """
            INSERT INTO identity_store_membership
                (id, actor_id, store_id, membership_role, status, created_at, updated_at, version)
            VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, 0)
            """.trimIndent(),
            UUID.randomUUID(),
            actorId,
            storeId,
            role,
            Timestamp.from(NOW),
            Timestamp.from(NOW),
        )
    }

    private fun policyVersion(storeId: UUID): Long =
        requireNotNull(
            jdbc.queryForObject("SELECT ordering_policy_version FROM merchant_store WHERE id = ?", Long::class.java, storeId),
        )

    private fun commandCount(): Long =
        requireNotNull(jdbc.queryForObject("SELECT count(*) FROM merchant_store_ordering_policy_command", Long::class.java))

    private fun membershipStatus(
        actorId: UUID,
        storeId: UUID,
    ): String =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT status FROM identity_store_membership WHERE actor_id = ? AND store_id = ?",
                String::class.java,
                actorId,
                storeId,
            ),
        )

    private fun auditActorTypes(storeId: UUID): List<String> =
        jdbc
            .queryForList(
                "SELECT actor_type FROM operations_audit_record WHERE target_id = ? ORDER BY occurred_at, id",
                String::class.java,
                storeId,
            ).filterNotNull()

    private data class MerchantSession(
        val actorId: UUID,
        val session: Cookie,
        val csrf: Cookie,
    )

    private companion object {
        const val CSRF_COOKIE = "BEANFLOW_MERCHANT_XSRF"
        const val SESSION_COOKIE = "BEANFLOW_MERCHANT_SESSION"
        const val CSRF_HEADER = "X-BEANFLOW-CSRF"
        const val PASSWORD = "merchant-current-password-2026"
        val NOW: Instant = Instant.parse("2026-08-27T00:00:00Z")
    }
}

package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.MerchantAccountDatabaseFixture
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.identity.api.StoreAccessOperations
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
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
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies committed membership lifecycle, HTTP grants, Audit rollback and authoring lock races")
@SpringBootTest
internal class StoreMembershipManagementIntegrationTest(
    @Autowired private val service: StoreMembershipManagementService,
    @Autowired private val access: StoreAccessOperations,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val mvc: MockMvc,
    @Autowired private val mapper: ObjectMapper,
    @Autowired transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)

    @BeforeEach fun clean() {
        jdbc.execute(
            "TRUNCATE identity_store_membership, merchant_store, identity_merchant_account, " +
                "operations_operator_permission_grant, operations_audit_record CASCADE",
        )
    }

    @Test fun `HTTP creates reads and changes membership without changing credentials`() {
        val c = command()
        val result =
            mvc
                .perform(
                    post(path(c))
                        .with(jwt(c.operatorId))
                        .header(
                            "Idempotency-Key",
                            c.key,
                        ).contentType(MediaType.APPLICATION_JSON)
                        .content(
                            mapper.writeValueAsString(
                                AddStoreMembershipRequest(
                                    c.accountId,
                                    c.role,
                                    c.reason,
                                ),
                            ),
                        ),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn()
                .response.contentAsString
        val first =
            mapper.readValue(
                result,
                ManagedStoreMembership::class.java,
            )
        mvc
            .perform(get(path(c)).with(jwt(c.operatorId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].accountDisplayName").value("Test merchant actor"))
            .andExpect(jsonPath("$.items[0].accountLoginId").isString)
        mvc
            .perform(get("${path(c)}/${c.accountId}").with(jwt(c.operatorId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.membershipId").value(first.membershipId.toString()))
            .andExpect(jsonPath("$.accountDisplayName").value("Test merchant actor"))
        mvc
            .perform(
                put("${path(c)}/${c.accountId}")
                    .with(jwt(c.operatorId))
                    .header(
                        "Idempotency-Key",
                        "membership-http-role",
                    ).contentType(MediaType.APPLICATION_JSON)
                    .content(
                        mapper.writeValueAsString(
                            ReplaceStoreMembershipRequest(
                                StoreActorRole.STAFF,
                                StoreMembershipStatus.ACTIVE,
                                first.version,
                                "역할 확인",
                            ),
                        ),
                    ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.role").value("STAFF"))
        assertThat(
            jdbc.queryForObject(
                "SELECT credential_version FROM identity_merchant_account WHERE id = ?",
                Long::class.java,
                c.accountId,
            ),
        ).isZero()
        mvc.perform(get(path(c))).andExpect(status().isUnauthorized)
        mvc.perform(get(path(c)).with(jwt(UUID.randomUUID()))).andExpect(status().isForbidden)
        mvc
            .perform(
                post(path(c))
                    .with(jwt(c.operatorId))
                    .header(
                        "Idempotency-Key",
                        "membership-http-invalid",
                    ).contentType(
                        MediaType.APPLICATION_JSON,
                    ).content("""{"accountId":"${c.accountId}","role":"OWNER","reason":"확인","status":"ACTIVE"}"""),
            ).andExpect(status().isBadRequest)
    }

    @Test fun `role revocation and reactivation affect authoring while replay keeps its first response`() {
        val c = command()
        val original = service.change(c)
        assertThat(
            access
                .requireCatalogAccess(
                    c.accountId,
                    c.storeId,
                    setOf(StoreActorRole.OWNER),
                ).role,
        ).isEqualTo(StoreActorRole.OWNER)
        val role =
            service.change(
                c.copy(
                    key = "membership-role-key",
                    role = StoreActorRole.STAFF,
                    expectedVersion = 0,
                ),
            )
        failure(FailureCode.ACCESS_DENIED) {
            access.requireCatalogAccess(
                c.accountId,
                c.storeId,
                setOf(StoreActorRole.OWNER),
            )
        }
        service.change(
            c.copy(
                key = "membership-revoke-key",
                role = StoreActorRole.STAFF,
                status = StoreMembershipStatus.REVOKED,
                expectedVersion = role.version,
            ),
        )
        failure(FailureCode.ACCESS_DENIED) {
            access.requireStoreAuthoringAccess(
                c.accountId,
                c.storeId,
                setOf(StoreActorRole.STAFF),
            )
        }
        failure(FailureCode.RESOURCE_STATE_CONFLICT) {
            service.change(c.copy(key = "membership-duplicate-key"))
        }
        val restored =
            service.change(
                c.copy(
                    key = "membership-restore-key",
                    expectedVersion = 2,
                ),
            )
        assertThat(restored.version).isEqualTo(3)
        assertThat(
            access
                .requireStoreAuthoringAccess(
                    c.accountId,
                    c.storeId,
                    setOf(StoreActorRole.OWNER),
                ).role,
        ).isEqualTo(StoreActorRole.OWNER)
        assertThat(service.change(c)).isEqualTo(original)
        failure(FailureCode.IDEMPOTENCY_KEY_REUSED) {
            service.change(c.copy(role = StoreActorRole.STAFF))
        }
        failure(FailureCode.RESOURCE_STATE_CONFLICT) {
            service.change(
                c.copy(
                    key = "membership-stale-key",
                    expectedVersion = 0,
                ),
            )
        }
        failure(FailureCode.RESOURCE_STATE_CONFLICT) {
            service.change(
                c.copy(
                    key = "membership-noop-key",
                    expectedVersion = 3,
                ),
            )
        }
        assertThat(count("identity_store_membership")).isOne()
    }

    @Test fun `audit failure and revoked operator grants cannot leave partial membership state`() {
        val c = command()
        jdbc.execute(
            "ALTER TABLE operations_audit_record ADD CONSTRAINT test_membership_audit " +
                "CHECK (action <> 'STORE_MEMBERSHIP_ADDED')",
        )
        try {
            assertThatThrownBy {
                service.change(c)
            }.isInstanceOf(RuntimeException::class.java)
        } finally {
            jdbc.execute("ALTER TABLE operations_audit_record DROP CONSTRAINT test_membership_audit")
        }
        assertThat(count("identity_store_membership")).isZero()
        assertThat(count("identity_membership_command")).isZero()
        service.change(c)
        jdbc.update(
            "UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now() WHERE actor_id = ?",
            c.operatorId,
        )
        failure(FailureCode.ACCESS_DENIED) {
            service.change(c)
        }
    }

    @Test fun `list cursors are actor and store bound and membership identity is immutable`() {
        val c = command()
        service.change(c)
        val other = command()
        service.change(other.copy(storeId = c.storeId))
        val page =
            service.list(
                c.operatorId,
                c.storeId,
                null,
                1,
            )
        assertThat(
            service
                .list(
                    c.operatorId,
                    c.storeId,
                    page.nextCursor,
                    1,
                ).items,
        ).hasSize(1)
        assertThatThrownBy {
            service.list(
                other.operatorId,
                c.storeId,
                page.nextCursor,
                1,
            )
        }.isInstanceOf(DomainFailure::class.java)
        assertThatThrownBy {
            service.list(
                c.operatorId,
                other.storeId,
                page.nextCursor,
                1,
            )
        }.isInstanceOf(DomainFailure::class.java)
        failure(FailureCode.RESOURCE_NOT_FOUND) {
            service.get(
                c.operatorId,
                other.storeId,
                c.accountId,
            )
        }
        failure(FailureCode.RESOURCE_NOT_FOUND) {
            service.change(
                c.copy(
                    key = "missing-account-key",
                    accountId = UUID.randomUUID(),
                ),
            )
        }
        assertThatThrownBy {
            jdbc.update(
                "UPDATE identity_store_membership SET store_id = ? WHERE actor_id = ?",
                other.storeId,
                c.accountId,
            )
        }.isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("Membership identity is immutable")
    }

    @Test fun `granting membership does not activate an expired merchant credential`() {
        val c = command()
        jdbc.update(
            "UPDATE identity_merchant_account SET state = 'EXPIRED', password_changed_at = null, " +
                "temporary_password_expires_at = now() - interval '1 day', " +
                "created_at = now() - interval '2 days' WHERE id = ?",
            c.accountId,
        )
        service.change(c)
        assertThatThrownBy {
            access.requireCatalogAccess(
                c.accountId,
                c.storeId,
                setOf(StoreActorRole.OWNER),
            )
        }.isInstanceOf(DomainFailure::class.java)
        assertThat(
            jdbc.queryForObject(
                "SELECT state FROM identity_merchant_account WHERE id = ?",
                String::class.java,
                c.accountId,
            ),
        ).isEqualTo("EXPIRED")
    }

    @Test fun `concurrent creation is unique and revocation waits for an in flight authoring transaction`() {
        val c = command()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val barrier = CyclicBarrier(2)
            val futures =
                (0..1).map { index ->
                    executor.submit<Boolean> {
                        barrier.await(
                            5,
                            TimeUnit.SECONDS,
                        )
                        try {
                            service.change(c.copy(key = "membership-race-key-$index"))
                            true
                        } catch (e: DomainFailure) {
                            assertThat(e.code).isEqualTo(FailureCode.RESOURCE_STATE_CONFLICT)
                            false
                        }
                    }
                }
            assertThat(
                futures.map {
                    it.get(
                        20,
                        TimeUnit.SECONDS,
                    )
                },
            ).containsExactlyInAnyOrder(
                true,
                false,
            )
            val acquired = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder =
                executor.submit {
                    tx.execute {
                        access.requireStoreAuthoringAccess(
                            c.accountId,
                            c.storeId,
                            setOf(StoreActorRole.OWNER),
                        )
                        acquired.countDown()
                        check(
                            release.await(
                                10,
                                TimeUnit.SECONDS,
                            ),
                        )
                    }
                }
            assertThat(
                acquired.await(
                    5,
                    TimeUnit.SECONDS,
                ),
            ).isTrue()
            val revoke =
                executor.submit<ManagedStoreMembership> {
                    service.change(
                        c.copy(
                            key = "membership-lock-revoke",
                            status = StoreMembershipStatus.REVOKED,
                            expectedVersion = 0,
                        ),
                    )
                }
            try {
                assertThatThrownBy {
                    revoke.get(
                        200,
                        TimeUnit.MILLISECONDS,
                    )
                }.isInstanceOf(java.util.concurrent.TimeoutException::class.java)
            } finally {
                release.countDown()
            }
            holder.get(
                10,
                TimeUnit.SECONDS,
            )
            assertThat(
                revoke
                    .get(
                        10,
                        TimeUnit.SECONDS,
                    ).status,
            ).isEqualTo(StoreMembershipStatus.REVOKED)
            failure(FailureCode.ACCESS_DENIED) {
                access.requireStoreAuthoringAccess(
                    c.accountId,
                    c.storeId,
                    setOf(StoreActorRole.OWNER),
                )
            }
        } finally {
            executor.shutdownNow()
            assertThat(
                executor.awaitTermination(
                    30,
                    TimeUnit.SECONDS,
                ),
            ).isTrue()
        }
    }

    private fun command(): StoreMembershipCommand {
        val operator = UUID.randomUUID()
        val account = UUID.randomUUID()
        val store = UUID.randomUUID()
        MerchantAccountDatabaseFixture.insertActive(
            jdbc,
            account,
        )
        jdbc.update(
            "INSERT INTO merchant_store(id, accepting_orders, pickup_enabled, version) VALUES (?, false, false, 0)",
            store,
        )
        listOf(
            "STORE_MEMBERSHIP_READ",
            "STORE_MEMBERSHIP_WRITE",
        ).forEach { permission ->
            jdbc.update(
                "INSERT INTO operations_operator_permission_grant(actor_id, permission, state, " +
                    "granted_at, version, audit_source_reference) VALUES (?, ?, 'ACTIVE', ?, 1, ?)",
                operator,
                permission,
                Timestamp.from(Instant.now().minusSeconds(1)),
                "membership:$operator:$permission",
            )
        }
        return StoreMembershipCommand(
            operator,
            store,
            account,
            "membership-create-key",
            StoreActorRole.OWNER,
            StoreMembershipStatus.ACTIVE,
            null,
            "소속 확인",
            Instant.now(),
        )
    }

    private fun path(c: StoreMembershipCommand) = "/api/v1/operations/stores/${c.storeId}/memberships"

    private fun count(table: String) =
        jdbc.queryForObject(
            "SELECT count(*) FROM $table",
            Long::class.java,
        )

    private fun jwt(actor: UUID) =
        jwt()
            .jwt {
                it.subject(actor.toString())
            }.authorities(SimpleGrantedAuthority("ROLE_PLATFORM_OPERATOR"))

    private fun failure(
        code: FailureCode,
        action: () -> Unit,
    ) {
        assertThatThrownBy(action).isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(code)
        }
    }
}

package io.github.kdh949.beanflow.demo.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.identity.api.DemoIdentityOperations
import io.github.kdh949.beanflow.merchant.api.DemoStoreProvisioning
import io.github.kdh949.beanflow.shared.api.BrowserActorType
import io.github.kdh949.beanflow.shared.api.CreateLoginSession
import io.github.kdh949.beanflow.shared.api.LoginSessionCoordinator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class, DemoTestClockConfiguration::class)
@BeanflowIsolatedSpringContext("verifies cleanup after visitor demo issuance is disabled")
@SpringBootTest(
    properties = [
        "beanflow.demo.enabled=false",
        "beanflow.demo.expiry-initial-delay-ms=3600000",
    ],
)
internal class DemoDisabledCleanupIntegrationTest
    @Autowired
    constructor(
        private val applicationContext: ApplicationContext,
        private val identities: DemoIdentityOperations,
        private val stores: DemoStoreProvisioning,
        private val sessions: LoginSessionCoordinator,
        private val repository: DemoWorkspaceRepository,
        private val expiryWorker: DemoExpiryWorker,
        private val jdbc: JdbcTemplate,
        private val clock: DemoMutableClock,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)

        @Test
        fun `disabled issuance still cleans a workspace created before restart`() {
            assertThat(applicationContext.getBeansOfType(DemoWorkspaceService::class.java)).isEmpty()
            val createdAt = Instant.parse("2026-09-15T03:00:00Z")
            clock.set(createdAt)
            val workspace =
                transactions.execute {
                    val workspaceId = UUID.randomUUID()
                    val customerId = UUID.randomUUID()
                    val merchantId = UUID.randomUUID()
                    val store = stores.create(workspaceId, createdAt)
                    val expiresAt = createdAt.plusSeconds(1800)
                    identities.provision(customerId, merchantId, store.storeId, createdAt, expiresAt)
                    val customerSession =
                        sessions.create(CreateLoginSession(BrowserActorType.CUSTOMER, customerId, createdAt.toEpochMilli(), 0))
                    val merchantSession =
                        sessions.create(CreateLoginSession(BrowserActorType.MERCHANT, merchantId, createdAt.toEpochMilli(), 0))
                    DemoWorkspace(
                        id = workspaceId,
                        browserHash = "a".repeat(64),
                        startKey = "disabled-restart-cleanup",
                        mode = "GUIDED",
                        customerId = customerId,
                        merchantId = merchantId,
                        storeId = store.storeId,
                        menuId = store.sampleMenuId,
                        customerSessionId = customerSession.sessionId,
                        merchantSessionId = merchantSession.sessionId,
                        orderReference = null,
                        createdAt = createdAt,
                        expiresAt = expiresAt,
                        endedAt = null,
                    ).also(repository::insert)
                }

            clock.set(workspace.expiresAt)
            expiryWorker.run()

            assertThat(
                jdbc.queryForObject(
                    "SELECT ended_at IS NOT NULL FROM demo_workspace WHERE id = ?",
                    Boolean::class.java,
                    workspace.id,
                ),
            ).isTrue()
            assertThat(
                jdbc.queryForMap(
                    "SELECT accepting_orders, pickup_enabled FROM merchant_store WHERE id = ?",
                    workspace.storeId,
                ),
            ).containsEntry("accepting_orders", false).containsEntry("pickup_enabled", false)
            assertThat(
                jdbc.queryForObject(
                    "SELECT credential_version FROM identity_customer_account WHERE id = ?",
                    Long::class.java,
                    workspace.customerId,
                ),
            ).isEqualTo(1L)
            assertThat(
                jdbc.queryForObject(
                    "SELECT credential_version FROM identity_merchant_account WHERE id = ?",
                    Long::class.java,
                    workspace.merchantId,
                ),
            ).isEqualTo(1L)
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM spring_session WHERE session_id IN (?, ?)",
                    Int::class.java,
                    workspace.customerSessionId,
                    workspace.merchantSessionId,
                ),
            ).isZero()
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM operations_audit_record WHERE action = 'DEMO_WORKSPACE_ENDED' AND target_id = ?",
                    Int::class.java,
                    workspace.id,
                ),
            ).isOne()
        }
    }

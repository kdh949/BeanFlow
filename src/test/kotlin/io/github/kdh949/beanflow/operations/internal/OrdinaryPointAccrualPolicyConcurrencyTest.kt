package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualExpiryRule
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyOperations
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyScopeType
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicySelectionSource
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicySnapshot
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyState
import io.github.kdh949.beanflow.operations.api.PointAccrualIssuerType
import io.github.kdh949.beanflow.operations.api.PointAccrualRoundingMode
import io.github.kdh949.beanflow.operations.api.SelectedOrdinaryPointAccrualPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class OrdinaryPointAccrualPolicyConcurrencyTest
    @Autowired
    constructor(
        private val service: OrdinaryPointAccrualPolicyService,
        private val selector: OrdinaryPointAccrualPolicyOperations,
        private val jdbcTemplate: JdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)
        private val actorId = UUID.fromString("50000000-0000-0000-0000-000000000001")

        @BeforeEach
        fun resetMutableState() {
            jdbcTemplate.update("DELETE FROM operations_point_accrual_policy_head WHERE scope_type = 'STORE'")
            jdbcTemplate.update("DELETE FROM operations_operator_permission_grant")
            jdbcTemplate.update("DELETE FROM operations_audit_record")
            jdbcTemplate.update(
                """
                INSERT INTO operations_operator_permission_grant (
                    actor_id, permission, state, granted_at, version, audit_source_reference
                ) VALUES (?, 'POINT_ACCRUAL_POLICY_WRITE', 'ACTIVE', now(), 1, ?)
                """.trimIndent(),
                actorId,
                "concurrency-grant:${UUID.randomUUID()}",
            )
        }

        @Test
        fun `first Store override and no-head selection observe one complete commit order`() {
            val storeId = insertStore()
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val write =
                    executor.submit<OrdinaryPointAccrualPolicyVersionResult> {
                        barrier.await()
                        service.change(overrideCommand(storeId, "first-store-race-0001"))
                    }
                val read =
                    executor.submit<SelectedOrdinaryPointAccrualPolicy> {
                        barrier.await()
                        transactions.execute { selector.selectForOrder(storeId) }!!
                    }

                val written = write.get(10, TimeUnit.SECONDS)
                val selected = read.get(10, TimeUnit.SECONDS)
                assertThat(selected.selectionSource)
                    .isIn(
                        OrdinaryPointAccrualPolicySelectionSource.GLOBAL_NO_OVERRIDE,
                        OrdinaryPointAccrualPolicySelectionSource.STORE_OVERRIDE,
                    )
                if (selected.selectionSource == OrdinaryPointAccrualPolicySelectionSource.STORE_OVERRIDE) {
                    assertThat(selected.policy.policyVersionId).isEqualTo(written.policyVersionId)
                }
                assertThat(transactions.execute { selector.selectForOrder(storeId) }!!.policy.policyVersionId)
                    .isEqualTo(written.policyVersionId)
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `inherited Store selection and GLOBAL update return either complete adjacent version`() {
            val storeId = insertStore()
            val inherited = service.change(inheritCommand(storeId, "inherit-race-setup-0001"))
            assertThat(inherited.state).isEqualTo(OrdinaryPointAccrualPolicyState.INHERIT_GLOBAL)
            val oldGlobal = currentGlobalVersion()
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val write =
                    executor.submit<OrdinaryPointAccrualPolicyVersionResult> {
                        barrier.await()
                        service.change(globalCommand(oldGlobal, "global-race-0001"))
                    }
                val read =
                    executor.submit<SelectedOrdinaryPointAccrualPolicy> {
                        barrier.await()
                        transactions.execute { selector.selectForOrder(storeId) }!!
                    }

                val written = write.get(10, TimeUnit.SECONDS)
                val selected = read.get(10, TimeUnit.SECONDS)
                assertThat(selected.selectionSource)
                    .isEqualTo(OrdinaryPointAccrualPolicySelectionSource.GLOBAL_INHERITED)
                assertThat(selected.policy.policyVersionId).isIn(oldGlobal, written.policyVersionId)
                assertThat(transactions.execute { selector.selectForOrder(storeId) }!!.policy.policyVersionId)
                    .isEqualTo(written.policyVersionId)
            } finally {
                executor.shutdownNow()
            }
        }

        private fun overrideCommand(
            storeId: UUID,
            key: String,
        ) = command(
            scopeType = OrdinaryPointAccrualPolicyScopeType.STORE,
            scopeReference = storeId,
            state = OrdinaryPointAccrualPolicyState.OVERRIDE,
            expected = null,
            key = key,
        )

        private fun inheritCommand(
            storeId: UUID,
            key: String,
        ) = command(
            scopeType = OrdinaryPointAccrualPolicyScopeType.STORE,
            scopeReference = storeId,
            state = OrdinaryPointAccrualPolicyState.INHERIT_GLOBAL,
            expected = null,
            key = key,
        )

        private fun globalCommand(
            expected: Long,
            key: String,
        ) = command(
            scopeType = OrdinaryPointAccrualPolicyScopeType.GLOBAL,
            scopeReference = OrdinaryPointAccrualPolicySnapshot.GLOBAL_SCOPE_REFERENCE,
            state = OrdinaryPointAccrualPolicyState.OVERRIDE,
            expected = expected,
            key = key,
        )

        private fun command(
            scopeType: OrdinaryPointAccrualPolicyScopeType,
            scopeReference: UUID,
            state: OrdinaryPointAccrualPolicyState,
            expected: Long?,
            key: String,
        ) = ChangeOrdinaryPointAccrualPolicyCommand(
            scopeType = scopeType,
            scopeReference = scopeReference,
            state = state,
            accrualRateBps = 400.takeIf { state == OrdinaryPointAccrualPolicyState.OVERRIDE },
            roundingMode = PointAccrualRoundingMode.FLOOR.takeIf { state == OrdinaryPointAccrualPolicyState.OVERRIDE },
            issuerType = PointAccrualIssuerType.PLATFORM.takeIf { state == OrdinaryPointAccrualPolicyState.OVERRIDE },
            issuerReference = "platform:race".takeIf { state == OrdinaryPointAccrualPolicyState.OVERRIDE },
            expiryRule =
                OrdinaryPointAccrualExpiryRule.EXACT_DURATION_FROM_COMPLETION
                    .takeIf { state == OrdinaryPointAccrualPolicyState.OVERRIDE },
            validityDays = 365.takeIf { state == OrdinaryPointAccrualPolicyState.OVERRIDE },
            expectedPolicyVersionId = expected,
            actorId = actorId,
            idempotencyKey = key,
            reason = "Concurrency linearization proof",
            now = Instant.parse("2026-08-01T04:00:00Z"),
        )

        private fun insertStore(): UUID =
            UUID.randomUUID().also {
                jdbcTemplate.update(
                    "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled) VALUES (?, true, true)",
                    it,
                )
            }

        private fun currentGlobalVersion(): Long =
            jdbcTemplate.queryForObject(
                "SELECT policy_version_id FROM operations_point_accrual_policy_head WHERE scope_type = 'GLOBAL'",
                Long::class.java,
            )!!
    }

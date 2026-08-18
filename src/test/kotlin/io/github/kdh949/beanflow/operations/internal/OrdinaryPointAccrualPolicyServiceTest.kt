package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualExpiryRule
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyOperations
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyScopeType
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicySelectionSource
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicySnapshot
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyState
import io.github.kdh949.beanflow.operations.api.PointAccrualIssuerType
import io.github.kdh949.beanflow.operations.api.PointAccrualRoundingMode
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest
internal class OrdinaryPointAccrualPolicyServiceTest
    @Autowired
    constructor(
        private val service: OrdinaryPointAccrualPolicyService,
        private val selector: OrdinaryPointAccrualPolicyOperations,
        private val jdbcTemplate: JdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)
        private val actorId = UUID.fromString("40000000-0000-0000-0000-000000000001")

        @BeforeEach
        fun resetMutableState() {
            dropAuditFailureTrigger()
            jdbcTemplate.update("DELETE FROM operations_point_accrual_policy_head WHERE scope_type = 'STORE'")
            jdbcTemplate.update("DELETE FROM operations_operator_permission_grant")
            jdbcTemplate.update("DELETE FROM operations_audit_record")
            grantWrite()
        }

        @AfterEach
        fun cleanupFailureTrigger() = dropAuditFailureTrigger()

        @Test
        fun `Store override and inherit versions change only future selections`() {
            val storeId = insertStore()
            val initial = select(storeId)

            val override = service.change(overrideCommand(storeId = storeId, expected = null, key = "store-override-0001"))
            val selectedOverride = select(storeId)
            val inherit =
                service.change(
                    inheritCommand(storeId, expected = override.policyVersionId, key = "store-inherit-0001"),
                )
            val selectedInherited = select(storeId)

            assertThat(initial.selectionSource).isEqualTo(OrdinaryPointAccrualPolicySelectionSource.GLOBAL_NO_OVERRIDE)
            assertThat(selectedOverride.selectionSource).isEqualTo(OrdinaryPointAccrualPolicySelectionSource.STORE_OVERRIDE)
            assertThat(selectedOverride.policy.policyVersionId).isEqualTo(override.policyVersionId)
            assertThat(selectedOverride.policy.accrualRateBps).isEqualTo(500)
            assertThat(inherit.state).isEqualTo(OrdinaryPointAccrualPolicyState.INHERIT_GLOBAL)
            assertThat(selectedInherited.selectionSource).isEqualTo(OrdinaryPointAccrualPolicySelectionSource.GLOBAL_INHERITED)
            assertThat(selectedInherited.policy).isEqualTo(initial.policy)
            assertThat(selectedOverride.policy.accrualRateBps).isEqualTo(500)
        }

        @Test
        fun `GLOBAL change replays same key and rejects different payload and stale expected version`() {
            val current = currentGlobalVersion()
            val command = globalCommand(current, "global-change-0001", reason = "New global ordinary accrual policy")

            val first = service.change(command)
            val replay = service.change(command)

            assertThat(replay).isEqualTo(first)
            assertFailure(FailureCode.IDEMPOTENCY_KEY_REUSED) {
                service.change(command.copy(reason = "Different reason for the same key"))
            }
            assertFailure(FailureCode.ORDER_STATE_CONFLICT) {
                service.change(globalCommand(current, "global-stale-0001", reason = "Stale expected version"))
            }
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM operations_audit_record WHERE action = 'POINT_ACCRUAL_POLICY_CHANGED'",
                    Long::class.java,
                ),
            ).isOne()
        }

        @Test
        fun `missing grant Store and Audit failure all fail without a policy mutation`() {
            val storeId = insertStore()
            val versionsBefore = versionCount()
            jdbcTemplate.update("DELETE FROM operations_operator_permission_grant")
            assertFailure(FailureCode.ACCESS_DENIED) {
                service.change(overrideCommand(storeId, null, "missing-grant-0001"))
            }

            grantWrite()
            assertFailure(FailureCode.RESOURCE_NOT_FOUND) {
                service.change(overrideCommand(UUID.randomUUID(), null, "missing-store-0001"))
            }

            installAuditFailureTrigger()
            assertFailure(FailureCode.DEPENDENCY_UNAVAILABLE) {
                service.change(overrideCommand(storeId, null, "audit-failure-0001"))
            }
            assertThat(versionCount()).isEqualTo(versionsBefore)
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM operations_point_accrual_policy_head WHERE scope_type = 'STORE'",
                    Long::class.java,
                ),
            ).isZero()
        }

        private fun select(storeId: UUID) = transactions.execute { selector.selectForOrder(storeId) }!!

        private fun globalCommand(
            expected: Long,
            key: String,
            reason: String,
        ) = ChangeOrdinaryPointAccrualPolicyCommand(
            scopeType = OrdinaryPointAccrualPolicyScopeType.GLOBAL,
            scopeReference = OrdinaryPointAccrualPolicySnapshot.GLOBAL_SCOPE_REFERENCE,
            state = OrdinaryPointAccrualPolicyState.OVERRIDE,
            accrualRateBps = 300,
            roundingMode = PointAccrualRoundingMode.HALF_UP,
            issuerType = PointAccrualIssuerType.PLATFORM,
            issuerReference = "platform:global",
            expiryRule = OrdinaryPointAccrualExpiryRule.SEOUL_CALENDAR_DAYS_FROM_COMPLETION,
            validityDays = 180,
            expectedPolicyVersionId = expected,
            actorId = actorId,
            idempotencyKey = key,
            reason = reason,
            now = Instant.parse("2026-08-01T01:00:00Z"),
        )

        private fun overrideCommand(
            storeId: UUID,
            expected: Long?,
            key: String,
        ) = ChangeOrdinaryPointAccrualPolicyCommand(
            scopeType = OrdinaryPointAccrualPolicyScopeType.STORE,
            scopeReference = storeId,
            state = OrdinaryPointAccrualPolicyState.OVERRIDE,
            accrualRateBps = 500,
            roundingMode = PointAccrualRoundingMode.FLOOR,
            issuerType = PointAccrualIssuerType.STORE,
            issuerReference = "store:$storeId",
            expiryRule = OrdinaryPointAccrualExpiryRule.EXACT_DURATION_FROM_COMPLETION,
            validityDays = 90,
            expectedPolicyVersionId = expected,
            actorId = actorId,
            idempotencyKey = key,
            reason = "Store-specific ordinary accrual policy",
            now = Instant.parse("2026-08-01T02:00:00Z"),
        )

        private fun inheritCommand(
            storeId: UUID,
            expected: Long,
            key: String,
        ) = ChangeOrdinaryPointAccrualPolicyCommand(
            scopeType = OrdinaryPointAccrualPolicyScopeType.STORE,
            scopeReference = storeId,
            state = OrdinaryPointAccrualPolicyState.INHERIT_GLOBAL,
            accrualRateBps = null,
            roundingMode = null,
            issuerType = null,
            issuerReference = null,
            expiryRule = null,
            validityDays = null,
            expectedPolicyVersionId = expected,
            actorId = actorId,
            idempotencyKey = key,
            reason = "Return Store to current global policy",
            now = Instant.parse("2026-08-01T03:00:00Z"),
        )

        private fun insertStore(): UUID =
            UUID.randomUUID().also {
                jdbcTemplate.update(
                    "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled) VALUES (?, true, true)",
                    it,
                )
            }

        private fun grantWrite() {
            jdbcTemplate.update(
                """
                INSERT INTO operations_operator_permission_grant (
                    actor_id, permission, state, granted_at, version, audit_source_reference
                ) VALUES (?, 'POINT_ACCRUAL_POLICY_WRITE', 'ACTIVE', now(), 1, ?)
                ON CONFLICT (actor_id, permission) DO NOTHING
                """.trimIndent(),
                actorId,
                "test-grant:${UUID.randomUUID()}",
            )
        }

        private fun currentGlobalVersion(): Long =
            jdbcTemplate.queryForObject(
                "SELECT policy_version_id FROM operations_point_accrual_policy_head WHERE scope_type = 'GLOBAL'",
                Long::class.java,
            )!!

        private fun versionCount(): Long =
            jdbcTemplate.queryForObject("SELECT count(*) FROM operations_point_accrual_policy_version", Long::class.java)!!

        private fun assertFailure(
            code: FailureCode,
            block: () -> Unit,
        ) {
            assertThatThrownBy(block)
                .isInstanceOfSatisfying(DomainFailure::class.java) { assertThat(it.code).isEqualTo(code) }
        }

        private fun installAuditFailureTrigger() {
            jdbcTemplate.execute(
                """
                CREATE OR REPLACE FUNCTION fail_point_accrual_change_audit() RETURNS trigger AS ${'$'}${'$'}
                BEGIN
                    IF NEW.action = 'POINT_ACCRUAL_POLICY_CHANGED' THEN
                        RAISE EXCEPTION 'forced point accrual change audit failure';
                    END IF;
                    RETURN NEW;
                END;
                ${'$'}${'$'} LANGUAGE plpgsql;
                CREATE TRIGGER fail_point_accrual_change_audit
                    BEFORE INSERT ON operations_audit_record
                    FOR EACH ROW EXECUTE FUNCTION fail_point_accrual_change_audit();
                """.trimIndent(),
            )
        }

        private fun dropAuditFailureTrigger() {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_point_accrual_change_audit ON operations_audit_record")
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_point_accrual_change_audit()")
        }
    }

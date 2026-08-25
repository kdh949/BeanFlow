package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.operations.api.ListOrdinaryPointAccrualPolicyVersionsCommand
import io.github.kdh949.beanflow.operations.api.ListStorePointAccrualPolicyHeadsCommand
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualExpiryRule
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyQueryOperations
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyScopeType
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicySnapshot
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyState
import io.github.kdh949.beanflow.operations.api.PointAccrualIssuerType
import io.github.kdh949.beanflow.operations.api.PointAccrualRoundingMode
import io.github.kdh949.beanflow.operations.api.ReadOrdinaryPointAccrualPolicyCommand
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
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("uses a database failure trigger that aborts the current transaction")
@SpringBootTest
internal class OrdinaryPointAccrualPolicyQueryTest
    @Autowired
    constructor(
        private val query: OrdinaryPointAccrualPolicyQueryOperations,
        private val writeService: OrdinaryPointAccrualPolicyService,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        private val actorId = UUID.fromString("60000000-0000-0000-0000-000000000001")
        private val now = Instant.parse("2026-08-01T05:00:00Z")

        @BeforeEach
        fun resetMutableState() {
            dropAuditFailureTrigger()
            jdbcTemplate.update("DELETE FROM operations_point_accrual_policy_head WHERE scope_type = 'STORE'")
            jdbcTemplate.update("DELETE FROM operations_operator_permission_grant")
            jdbcTemplate.update("DELETE FROM operations_audit_record")
            grant("POINT_ACCRUAL_POLICY_READ")
            grant("POINT_ACCRUAL_POLICY_WRITE")
        }

        @AfterEach
        fun cleanupFailureTrigger() = dropAuditFailureTrigger()

        @Test
        fun `all five read shapes share authorization reason and audited projections`() {
            val firstStore = insertStore()
            val secondStore = insertStore()
            val firstHead = writeService.change(override(firstStore, null, "query-store-one-0001"))
            val secondHead = writeService.change(inherit(secondStore, null, "query-store-two-0001"))

            val global = query.currentGlobal(read())
            val globalHistory = query.globalHistory(history(limit = 1))
            val storeHeads =
                query.storeHeads(
                    ListStorePointAccrualPolicyHeadsCommand(actorId, "Policy list review", null, null, 20, now),
                )
            val effectiveStore = query.currentStore(firstStore, read())
            val storeHistory = query.storeHistory(firstStore, history(limit = 20))

            assertThat(global.scopeType).isEqualTo(OrdinaryPointAccrualPolicyScopeType.GLOBAL)
            assertThat(globalHistory.items).hasSize(1)
            assertThat(storeHeads.items.map { it.policyVersionId }).contains(firstHead.policyVersionId, secondHead.policyVersionId)
            assertThat(effectiveStore.explicitHead!!.policyVersionId).isEqualTo(firstHead.policyVersionId)
            assertThat(effectiveStore.effectivePolicy.policyVersionId).isEqualTo(firstHead.policyVersionId)
            assertThat(storeHistory.items.map { it.policyVersionId }).containsExactly(firstHead.policyVersionId)
            assertThat(auditCount()).isEqualTo(5)
        }

        @Test
        fun `history cursor paginates without duplication and is rejected across scope`() {
            val current = currentGlobalVersion()
            writeService.change(global(current, "query-global-one-0001"))
            val next = currentGlobalVersion()
            writeService.change(global(next, "query-global-two-0001"))

            val first = query.globalHistory(history(limit = 1))
            val second = query.globalHistory(history(limit = 1, cursor = first.nextCursor))

            assertThat(first.items.single().policyVersionId).isNotEqualTo(second.items.single().policyVersionId)
            assertThatThrownBy { query.storeHistory(insertStore(), history(limit = 1, cursor = first.nextCursor)) }
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.INVALID_REQUEST)
                }
        }

        @Test
        fun `missing grant invalid reason and Audit failure return no unaudited body`() {
            jdbcTemplate.update("DELETE FROM operations_operator_permission_grant WHERE permission = 'POINT_ACCRUAL_POLICY_READ'")
            assertFailure(FailureCode.ACCESS_DENIED) { query.currentGlobal(read()) }

            grant("POINT_ACCRUAL_POLICY_READ")
            assertFailure(FailureCode.INVALID_REQUEST) { query.currentGlobal(read(reason = "   ")) }

            installAuditFailureTrigger()
            assertFailure(FailureCode.DEPENDENCY_UNAVAILABLE) { query.currentGlobal(read()) }
            assertThat(auditCount()).isZero()
        }

        private fun read(reason: String = "Policy support review") = ReadOrdinaryPointAccrualPolicyCommand(actorId, reason, now)

        private fun history(
            limit: Int?,
            cursor: String? = null,
        ) = ListOrdinaryPointAccrualPolicyVersionsCommand(actorId, "Policy history review", cursor, limit, now)

        private fun override(
            storeId: UUID,
            expected: Long?,
            key: String,
        ) = change(OrdinaryPointAccrualPolicyScopeType.STORE, storeId, OrdinaryPointAccrualPolicyState.OVERRIDE, expected, key)

        private fun inherit(
            storeId: UUID,
            expected: Long?,
            key: String,
        ) = change(OrdinaryPointAccrualPolicyScopeType.STORE, storeId, OrdinaryPointAccrualPolicyState.INHERIT_GLOBAL, expected, key)

        private fun global(
            expected: Long,
            key: String,
        ) = change(
            OrdinaryPointAccrualPolicyScopeType.GLOBAL,
            OrdinaryPointAccrualPolicySnapshot.GLOBAL_SCOPE_REFERENCE,
            OrdinaryPointAccrualPolicyState.OVERRIDE,
            expected,
            key,
        )

        private fun change(
            scope: OrdinaryPointAccrualPolicyScopeType,
            reference: UUID,
            state: OrdinaryPointAccrualPolicyState,
            expected: Long?,
            key: String,
        ) = ChangeOrdinaryPointAccrualPolicyCommand(
            scope,
            reference,
            state,
            200.takeIf { state == OrdinaryPointAccrualPolicyState.OVERRIDE },
            PointAccrualRoundingMode.FLOOR.takeIf { state == OrdinaryPointAccrualPolicyState.OVERRIDE },
            PointAccrualIssuerType.PLATFORM.takeIf { state == OrdinaryPointAccrualPolicyState.OVERRIDE },
            "platform:query".takeIf { state == OrdinaryPointAccrualPolicyState.OVERRIDE },
            OrdinaryPointAccrualExpiryRule.EXACT_DURATION_FROM_COMPLETION.takeIf {
                state == OrdinaryPointAccrualPolicyState.OVERRIDE
            },
            365.takeIf { state == OrdinaryPointAccrualPolicyState.OVERRIDE },
            expected,
            actorId,
            key,
            "Query test policy setup",
            now,
        )

        private fun insertStore(): UUID =
            UUID.randomUUID().also {
                jdbcTemplate.update("INSERT INTO merchant_store (id, accepting_orders, pickup_enabled) VALUES (?, true, true)", it)
            }

        private fun grant(permission: String) {
            jdbcTemplate.update(
                """
                INSERT INTO operations_operator_permission_grant (
                    actor_id, permission, state, granted_at, version, audit_source_reference
                ) VALUES (?, ?, 'ACTIVE', now(), 1, ?)
                ON CONFLICT (actor_id, permission) DO NOTHING
                """.trimIndent(),
                actorId,
                permission,
                "query-grant:$permission:${UUID.randomUUID()}",
            )
        }

        private fun currentGlobalVersion(): Long =
            jdbcTemplate.queryForObject(
                "SELECT policy_version_id FROM operations_point_accrual_policy_head WHERE scope_type = 'GLOBAL'",
                Long::class.java,
            )!!

        private fun auditCount(): Long =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM operations_audit_record WHERE action = 'POINT_ACCRUAL_POLICY_READ'",
                Long::class.java,
            )!!

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
                CREATE OR REPLACE FUNCTION fail_point_accrual_read_audit() RETURNS trigger AS ${'$'}${'$'}
                BEGIN
                    IF NEW.action = 'POINT_ACCRUAL_POLICY_READ' THEN
                        RAISE EXCEPTION 'forced point accrual read audit failure';
                    END IF;
                    RETURN NEW;
                END;
                ${'$'}${'$'} LANGUAGE plpgsql;
                CREATE TRIGGER fail_point_accrual_read_audit
                    BEFORE INSERT ON operations_audit_record
                    FOR EACH ROW EXECUTE FUNCTION fail_point_accrual_read_audit();
                """.trimIndent(),
            )
        }

        private fun dropAuditFailureTrigger() {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_point_accrual_read_audit ON operations_audit_record")
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_point_accrual_read_audit()")
        }
    }

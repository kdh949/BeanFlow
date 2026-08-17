package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

internal class OrdinaryPointAccrualPolicyMigrationTest : IsolatedPostgresSupport() {
    companion object {
        const val GLOBAL_SCOPE = "00000000-0000-0000-0000-000000000000"
        const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }

    private val dataSource by lazy {
        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
    }
    private val jdbcTemplate by lazy { JdbcTemplate(dataSource) }
    private val transactionTemplate by lazy { TransactionTemplate(DataSourceTransactionManager(dataSource)) }

    @BeforeEach
    fun resetToV15() {
        flyway(cleanDisabled = false).clean()
        flyway(target = "15").migrate()
    }

    @Test
    fun `V16 marks every existing Order legacy without seeding policy or grants`() {
        val orderId = insertOrderAndLine()

        migrateCurrent()

        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT source_state FROM ordering_order_point_accrual_source WHERE order_id = ?",
                String::class.java,
                orderId,
            ),
        ).isEqualTo("LEGACY_NOT_APPLICABLE")
        assertThat(count("operations_point_accrual_policy_version")).isZero()
        assertThat(count("operations_point_accrual_policy_head")).isZero()
        assertThat(count("ordering_order_point_accrual_snapshot")).isZero()
        assertThat(count("ordering_order_point_accrual_unit")).isZero()
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM operations_operator_permission_grant
                 WHERE permission IN ('POINT_ACCRUAL_POLICY_READ', 'POINT_ACCRUAL_POLICY_WRITE')
                """.trimIndent(),
                Long::class.java,
            ),
        ).isZero()
    }

    @Test
    fun `policy version is conditional immutable and head cannot cross scope`() {
        migrateCurrent()
        val globalVersion = insertGlobalPolicyVersion()

        jdbcTemplate.update(
            """
            INSERT INTO operations_point_accrual_policy_head (
                scope_type, scope_reference, policy_version_id
            ) VALUES ('GLOBAL', ?::uuid, ?)
            """.trimIndent(),
            GLOBAL_SCOPE,
            globalVersion,
        )

        assertThatThrownBy {
            jdbcTemplate.update(
                "UPDATE operations_point_accrual_policy_version SET accrual_rate_bps = 200 WHERE policy_version_id = ?",
                globalVersion,
            )
        }.hasStackTraceContaining("immutable")
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO operations_point_accrual_policy_version (
                    scope_type, scope_reference, state, effective_at, actor_type, actor_reference,
                    reason, payload_hash, source_reference
                ) VALUES (
                    'GLOBAL', ?::uuid, 'INHERIT_GLOBAL', now(), 'SYSTEM', 'bootstrap:test',
                    'invalid global inheritance', ?, ?
                )
                """.trimIndent(),
                GLOBAL_SCOPE,
                HASH_B,
                "invalid:${UUID.randomUUID()}",
            )
        }.hasStackTraceContaining("chk_point_accrual_policy_scope")

        val storeVersion = insertStorePolicyVersion(UUID.randomUUID())
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                UPDATE operations_point_accrual_policy_head
                   SET policy_version_id = ?
                 WHERE scope_type = 'GLOBAL' AND scope_reference = ?::uuid
                """.trimIndent(),
                storeVersion,
                GLOBAL_SCOPE,
            )
        }.hasStackTraceContaining("fk_point_accrual_policy_head_version_scope")
    }

    @Test
    fun `new Order requires a complete snapshotted source whose units tie out`() {
        migrateCurrent()
        val policyVersion = insertGlobalPolicyVersion()
        val validOrderId = UUID.randomUUID()

        transactionTemplate.executeWithoutResult {
            insertOrderAndLine(validOrderId)
            insertSnapshot(validOrderId, policyVersion, secondUnitAccrualKrw = 10)
        }
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT gross_accrual_amount_krw FROM ordering_order_point_accrual_snapshot WHERE order_id = ?",
                Long::class.java,
                validOrderId,
            ),
        ).isEqualTo(20)

        val invalidOrderId = UUID.randomUUID()
        assertThatThrownBy {
            transactionTemplate.executeWithoutResult {
                insertOrderAndLine(invalidOrderId)
                insertSnapshot(invalidOrderId, policyVersion, secondUnitAccrualKrw = 9)
            }
        }.hasStackTraceContaining("snapshot unit allocation does not tie out")
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ordering_order WHERE id = ?",
                Long::class.java,
                invalidOrderId,
            ),
        ).isZero()

        assertThatThrownBy {
            transactionTemplate.executeWithoutResult {
                val orderId = insertOrderAndLine(UUID.randomUUID())
                jdbcTemplate.update(
                    """
                    INSERT INTO ordering_order_point_accrual_source (order_id, source_state, created_at)
                    VALUES (?, 'LEGACY_NOT_APPLICABLE', now())
                    """.trimIndent(),
                    orderId,
                )
            }
        }.hasStackTraceContaining("legacy point accrual source cannot be created after V16")
    }

    @Test
    fun `permission check accepts only the expanded closed vocabulary`() {
        migrateCurrent()

        jdbcTemplate.update(
            """
            INSERT INTO operations_operator_permission_grant (
                actor_id, permission, state, granted_at, version, audit_source_reference
            ) VALUES (?, 'POINT_ACCRUAL_POLICY_READ', 'ACTIVE', now(), 1, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            "migration-test:${UUID.randomUUID()}",
        )
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO operations_operator_permission_grant (
                    actor_id, permission, state, granted_at, version, audit_source_reference
                ) VALUES (?, 'POINT_ACCRUAL_POLICY_ADMIN', 'ACTIVE', now(), 1, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                "migration-test:${UUID.randomUUID()}",
            )
        }.hasStackTraceContaining("chk_operator_permission_vocabulary")
    }

    private fun insertGlobalPolicyVersion(): Long =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO operations_point_accrual_policy_version (
                scope_type, scope_reference, state, accrual_rate_bps, rounding_mode,
                issuer_type, issuer_reference, expiry_rule, validity_days,
                effective_at, actor_type, actor_reference, reason, payload_hash, source_reference
            ) VALUES (
                'GLOBAL', ?::uuid, 'OVERRIDE', 1000, 'FLOOR',
                'PLATFORM', 'beanflow-platform', 'EXACT_DURATION_FROM_COMPLETION', 365,
                now(), 'SYSTEM', 'bootstrap:test', 'verified initial policy', ?, ?
            ) RETURNING policy_version_id
            """.trimIndent(),
            Long::class.java,
            GLOBAL_SCOPE,
            HASH_A,
            "bootstrap:${UUID.randomUUID()}",
        )!!

    private fun insertStorePolicyVersion(storeId: UUID): Long =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO operations_point_accrual_policy_version (
                scope_type, scope_reference, state, accrual_rate_bps, rounding_mode,
                issuer_type, issuer_reference, expiry_rule, validity_days,
                effective_at, actor_type, actor_reference, reason,
                idempotency_actor_id, idempotency_key, payload_hash, source_reference
            ) VALUES (
                'STORE', ?, 'OVERRIDE', 500, 'HALF_UP',
                'STORE', 'store-policy', 'SEOUL_CALENDAR_DAYS_FROM_COMPLETION', 30,
                now(), 'PLATFORM_OPERATOR', ?, 'store override', ?, 'migration-key-0001', ?, ?
            ) RETURNING policy_version_id
            """.trimIndent(),
            Long::class.java,
            storeId,
            storeId.toString(),
            storeId,
            HASH_B,
            "operator:${UUID.randomUUID()}",
        )!!

    private fun insertSnapshot(
        orderId: UUID,
        policyVersion: Long,
        secondUnitAccrualKrw: Long,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO ordering_order_point_accrual_source (order_id, source_state, created_at)
            VALUES (?, 'SNAPSHOTTED', now())
            """.trimIndent(),
            orderId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO ordering_order_point_accrual_snapshot (
                order_id, source_state, policy_version_id, selected_scope_type, selected_scope_reference,
                selection_source, accrual_rate_bps, rounding_mode, issuer_type, issuer_reference,
                expiry_rule, validity_days, canonical_policy_hash, order_payable_krw,
                gross_accrual_amount_krw, snapshot_schema_version, created_at
            ) VALUES (
                ?, 'SNAPSHOTTED', ?, 'GLOBAL', ?::uuid,
                'GLOBAL_NO_OVERRIDE', 1000, 'FLOOR', 'PLATFORM', 'beanflow-platform',
                'EXACT_DURATION_FROM_COMPLETION', 365, ?, 200, 20, 1, now()
            )
            """.trimIndent(),
            orderId,
            policyVersion,
            GLOBAL_SCOPE,
            HASH_A,
        )
        val orderLineId =
            jdbcTemplate.queryForObject(
                "SELECT id FROM ordering_order_line WHERE order_id = ?",
                UUID::class.java,
                orderId,
            )!!
        listOf(10L, secondUnitAccrualKrw).forEachIndexed { unitPosition, accrued ->
            jdbcTemplate.update(
                """
                INSERT INTO ordering_order_point_accrual_unit (
                    order_id, order_line_id, line_sequence, unit_position,
                    cash_payable_krw, accrued_amount_krw, created_at
                ) VALUES (?, ?, 0, ?, 100, ?, now())
                """.trimIndent(),
                orderId,
                orderLineId,
                unitPosition,
                accrued,
            )
        }
    }

    private fun insertOrderAndLine(orderId: UUID = UUID.randomUUID()): UUID {
        jdbcTemplate.update(
            """
            INSERT INTO ordering_order (
                id, customer_id, store_id, pickup_slot_id, state,
                subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw,
                currency, reservation_expires_at, created_at, updated_at
            ) VALUES (
                ?, ?, ?, ?, 'PENDING_PAYMENT',
                200, 0, 0, 200, 'KRW', now() + interval '10 minutes', now(), now()
            )
            """.trimIndent(),
            orderId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO ordering_order_line (
                id, order_id, line_sequence, menu_id, menu_name, option_names_json,
                sellable_requirements_json, unit_price_krw, quantity, gross_krw,
                coupon_discount_krw, points_applied_krw, cash_payable_krw
            ) VALUES (?, ?, 0, ?, 'Migration test menu', '[]', '[]', 100, 2, 200, 0, 0, 200)
            """.trimIndent(),
            UUID.randomUUID(),
            orderId,
            UUID.randomUUID(),
        )
        return orderId
    }

    private fun count(table: String): Long = jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!

    private fun migrateCurrent() {
        flyway(target = "16").migrate()
    }

    private fun flyway(
        target: String? = null,
        cleanDisabled: Boolean = true,
    ): Flyway {
        val configuration =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .cleanDisabled(cleanDisabled)
        target?.let(configuration::target)
        return configuration.load()
    }
}

package io.github.kdh949.beanflow.loyalty.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.UUID

@Testcontainers
internal class PointRecoveryMigrationTest {
    companion object {
        const val GLOBAL_SCOPE = "00000000-0000-0000-0000-000000000000"
        const val SNAPSHOT_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:17.6"))
    }

    private val dataSource by lazy {
        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
    }
    private val jdbcTemplate by lazy { JdbcTemplate(dataSource) }
    private val transactionTemplate by lazy { TransactionTemplate(DataSourceTransactionManager(dataSource)) }

    @BeforeEach
    fun resetToV16() {
        flyway(cleanDisabled = false).clean()
        flyway(target = "16").migrate()
    }

    @Test
    fun `V17 creates Payment eligibility and Loyalty recovery constraints`() {
        migrateCurrent()

        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'public' AND table_name IN (
                    'payment_order_point_accrual_outcome',
                    'payment_refund_point_recovery_work',
                    'loyalty_point_recovery_pending',
                    'loyalty_point_recovery_result',
                    'loyalty_point_accrual_result'
                 )
                """.trimIndent(),
                Long::class.java,
            ),
        ).isEqualTo(5)
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM information_schema.columns
                 WHERE (table_name = 'loyalty_point_account' AND column_name = 'recovery_pending_krw')
                    OR (table_name = 'loyalty_point_transaction' AND column_name = 'point_recovery_pending_id')
                    OR (table_name = 'loyalty_point_lot' AND column_name = 'accrual_snapshot_hash')
                """.trimIndent(),
                Long::class.java,
            ),
        ).isEqualTo(3)

        val accountId = UUID.randomUUID()
        val lotId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO loyalty_point_account (
                id, customer_id, available_points_krw, reserved_points_krw, recovery_pending_krw
            ) VALUES (?, ?, 100, 0, 0)
            """.trimIndent(),
            accountId,
            UUID.randomUUID(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO loyalty_point_lot (
                id, point_account_id, available_amount_krw, reserved_amount_krw,
                expires_at, issuer_type, issuer_reference
            ) VALUES (?, ?, 100, 0, now() + interval '30 days', 'PLATFORM', 'migration-test')
            """.trimIndent(),
            lotId,
            accountId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO loyalty_point_transaction (
                id, point_account_id, point_lot_id, amount_krw, type, source_reference, occurred_at
            ) VALUES (?, ?, ?, 10, 'ACCRUAL', ?, now())
            """.trimIndent(),
            UUID.randomUUID(),
            accountId,
            lotId,
            "migration:accrual:${UUID.randomUUID()}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO loyalty_point_transaction (
                id, point_account_id, point_lot_id, amount_krw, type, source_reference, occurred_at
            ) VALUES (?, ?, ?, 10, 'RECOVERY', ?, now())
            """.trimIndent(),
            UUID.randomUUID(),
            accountId,
            lotId,
            "migration:recovery:${UUID.randomUUID()}",
        )
    }

    @Test
    fun `pending summary and monotonic state must tie out at commit`() {
        migrateCurrent()
        val accountId = UUID.randomUUID()
        insertAccount(accountId)

        transactionTemplate.executeWithoutResult {
            jdbcTemplate.update(
                """
                INSERT INTO loyalty_point_recovery_pending (
                    id, point_account_id, refund_source_reference, initial_amount_krw,
                    remaining_amount_krw, state, created_at
                ) VALUES (?, ?, ?, 40, 40, 'PENDING', now())
                """.trimIndent(),
                UUID.randomUUID(),
                accountId,
                "refund:${UUID.randomUUID()}:recovery",
            )
            jdbcTemplate.update(
                "UPDATE loyalty_point_account SET recovery_pending_krw = 40 WHERE id = ?",
                accountId,
            )
        }

        assertThatThrownBy {
            transactionTemplate.executeWithoutResult {
                jdbcTemplate.update(
                    """
                    INSERT INTO loyalty_point_recovery_pending (
                        id, point_account_id, refund_source_reference, initial_amount_krw,
                        remaining_amount_krw, state, created_at
                    ) VALUES (?, ?, ?, 10, 10, 'PENDING', now())
                    """.trimIndent(),
                    UUID.randomUUID(),
                    accountId,
                    "refund:${UUID.randomUUID()}:recovery",
                )
            }
        }.hasStackTraceContaining("recovery pending summary does not tie out")

        val pendingId =
            jdbcTemplate.queryForObject(
                "SELECT id FROM loyalty_point_recovery_pending WHERE point_account_id = ?",
                UUID::class.java,
                accountId,
            )!!
        transactionTemplate.executeWithoutResult {
            jdbcTemplate.update(
                """
                UPDATE loyalty_point_recovery_pending
                   SET remaining_amount_krw = 0, state = 'SETTLED', settled_at = now(), version = version + 1
                 WHERE id = ?
                """.trimIndent(),
                pendingId,
            )
            jdbcTemplate.update(
                "UPDATE loyalty_point_account SET recovery_pending_krw = 0 WHERE id = ?",
                accountId,
            )
        }
        assertThatThrownBy {
            jdbcTemplate.update(
                "UPDATE loyalty_point_recovery_pending SET remaining_amount_krw = 1 WHERE id = ?",
                pendingId,
            )
        }.hasStackTraceContaining("immutable")
    }

    @Test
    fun `existing snapshotted completion blocks clean V17 activation`() {
        insertCompletedSnapshottedOrder()

        assertThatThrownBy { migrateCurrent() }
            .hasStackTraceContaining("existing snapshotted completion/refund")
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name = 'payment_order_point_accrual_outcome'
                """.trimIndent(),
                Long::class.java,
            ),
        ).isZero()
    }

    private fun insertAccount(accountId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO loyalty_point_account (
                id, customer_id, available_points_krw, reserved_points_krw
            ) VALUES (?, ?, 0, 0)
            """.trimIndent(),
            accountId,
            UUID.randomUUID(),
        )
    }

    private fun insertCompletedSnapshottedOrder() {
        val orderId = UUID.randomUUID()
        val lineId = UUID.randomUUID()
        val policyVersion = insertPolicy()
        transactionTemplate.executeWithoutResult {
            jdbcTemplate.update(
                """
                INSERT INTO ordering_order (
                    id, customer_id, store_id, pickup_slot_id, state,
                    subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw,
                    currency, paid_at, acceptance_warning_at, acceptance_deadline_at,
                    accepted_at, preparing_at, ready_at, completed_at,
                    created_at, updated_at, version
                ) VALUES (
                    ?, ?, ?, ?, 'COMPLETED', 100, 0, 0, 100, 'KRW',
                    now() - interval '4 minutes', now() - interval '2 minutes', now() - interval '1 minute',
                    now() - interval '3 minutes', now() - interval '2 minutes', now() - interval '1 minute', now(),
                    now() - interval '5 minutes', now(), 5
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
                ) VALUES (?, ?, 0, ?, 'Migration test menu', '[]', '[]', 100, 1, 100, 0, 0, 100)
                """.trimIndent(),
                lineId,
                orderId,
                UUID.randomUUID(),
            )
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
                    ?, 'SNAPSHOTTED', ?, 'GLOBAL', ?::uuid, 'GLOBAL_NO_OVERRIDE', 1000, 'FLOOR',
                    'PLATFORM', 'beanflow-platform', 'EXACT_DURATION_FROM_COMPLETION', 365,
                    ?, 100, 10, 1, now()
                )
                """.trimIndent(),
                orderId,
                policyVersion,
                GLOBAL_SCOPE,
                SNAPSHOT_HASH,
            )
            jdbcTemplate.update(
                """
                INSERT INTO ordering_order_point_accrual_unit (
                    order_id, order_line_id, line_sequence, unit_position,
                    cash_payable_krw, accrued_amount_krw, created_at
                ) VALUES (?, ?, 0, 0, 100, 10, now())
                """.trimIndent(),
                orderId,
                lineId,
            )
        }
    }

    private fun insertPolicy(): Long =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO operations_point_accrual_policy_version (
                scope_type, scope_reference, state, accrual_rate_bps, rounding_mode,
                issuer_type, issuer_reference, expiry_rule, validity_days,
                effective_at, actor_type, actor_reference, reason, payload_hash, source_reference
            ) VALUES (
                'GLOBAL', ?::uuid, 'OVERRIDE', 1000, 'FLOOR', 'PLATFORM', 'beanflow-platform',
                'EXACT_DURATION_FROM_COMPLETION', 365, now(), 'SYSTEM', 'migration-test',
                'migration test policy', ?, ?
            ) RETURNING policy_version_id
            """.trimIndent(),
            Long::class.java,
            GLOBAL_SCOPE,
            SNAPSHOT_HASH,
            "migration:${UUID.randomUUID()}",
        )!!

    private fun migrateCurrent() {
        flyway(target = "17").migrate()
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

package io.github.kdh949.beanflow.demo

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.OrderSettlementInputSnapshotOperations
import io.github.kdh949.beanflow.ordering.internal.OrderSettlementInputSnapshotService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * The `local-demo` seed against PostgreSQL, running the CLI's own Spring configuration.
 *
 * Three properties matter and none of them can be checked by reading the code: re-running must not
 * duplicate anything, a failure part way through must leave nothing behind, and the seed must
 * refuse to run when the required GLOBAL accrual policy is absent instead of inventing one.
 */
@Import(TestcontainersConfiguration::class, OrderSettlementInputSnapshotService::class)
@BeanflowIsolatedSpringContext("verifies startup, DDL, or committed state across a transaction boundary")
@SpringBootTest(
    classes = [LocalDemoSeedApplication::class],
    properties = [
        "spring.profiles.active=local,local-demo",
        "spring.autoconfigure.exclude=" +
            "org.springframework.modulith.runtime.autoconfigure.SpringModulithRuntimeAutoConfiguration," +
            "org.springframework.modulith.observability.autoconfigure.ModuleObservabilityAutoConfiguration," +
            "org.springframework.modulith.actuator.autoconfigure.ApplicationModulesEndpointConfiguration",
    ],
)
internal class LocalDemoSeedIntegrationTest
    @Autowired
    constructor(
        private val seeder: LocalDemoSeeder,
        private val jdbcTemplate: JdbcTemplate,
        private val settlementInputSnapshots: OrderSettlementInputSnapshotOperations,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)

        @BeforeEach
        fun cleanFixtureTables() {
            dropCouponFailureTrigger()
            jdbcTemplate.execute("TRUNCATE TABLE ${SEEDED_TABLES.joinToString(", ")} CASCADE")
        }

        @Test
        fun `re-running the seed produces the same fixture and inserts nothing the second time`() {
            val first = transactions.execute { seeder.seed() }.orEmpty()
            val countsAfterFirst = counts()

            val second = transactions.execute { seeder.seed() }.orEmpty()

            assertThat(first).isNotEmpty()
            assertThat(second).isEmpty()
            assertThat(counts()).isEqualTo(countsAfterFirst)
            // Fixed identifiers, so "no duplicates" is exactly "no second row per identifier".
            assertThat(countsAfterFirst.values.sum()).isEqualTo(first.size.toLong())
        }

        @Test
        fun `a failure part way through rolls the whole fixture back`() {
            // Coupon issuance is seeded last, so the stores and menus written before it are the
            // rows a partial seed would leave behind.
            installCouponFailureTrigger()

            assertThatThrownBy { transactions.execute { seeder.seed() } }
                .hasMessageContaining("injected coupon issuance failure")

            dropCouponFailureTrigger()
            assertThat(counts().values.sum()).isZero()
        }

        @Test
        fun `the seed refuses to run when the required GLOBAL accrual policy is absent`() {
            val headRow =
                jdbcTemplate.queryForMap(
                    """
                    SELECT scope_type, scope_reference, policy_version_id, version
                      FROM operations_point_accrual_policy_head
                     WHERE scope_type = 'GLOBAL'
                    """.trimIndent(),
                )
            jdbcTemplate.update("DELETE FROM operations_point_accrual_policy_head WHERE scope_type = 'GLOBAL'")

            try {
                assertThatThrownBy { transactions.execute { seeder.seed() } }
                    .hasMessageContaining("GLOBAL ordinary point accrual policy is missing")
                // No silent default policy, and no partially seeded fixture either.
                assertThat(counts().values.sum()).isZero()
                assertThat(
                    jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM operations_point_accrual_policy_head",
                        Long::class.java,
                    ),
                ).isZero()
            } finally {
                jdbcTemplate.update(
                    """
                    INSERT INTO operations_point_accrual_policy_head
                        (scope_type, scope_reference, policy_version_id, version)
                    VALUES (?, ?, ?, ?)
                    """.trimIndent(),
                    headRow["scope_type"],
                    headRow["scope_reference"],
                    headRow["policy_version_id"],
                    headRow["version"],
                )
            }
        }

        @Test
        fun `the seed starts the point account at zero without an unaudited lot or transaction`() {
            transactions.execute { seeder.seed() }

            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT available_points_krw FROM loyalty_point_account WHERE id = ?",
                    Long::class.java,
                    LocalDemoFixture.POINT_ACCOUNT_ID,
                ),
            ).isZero()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM loyalty_point_lot WHERE point_account_id = ?",
                    Long::class.java,
                    LocalDemoFixture.POINT_ACCOUNT_ID,
                ),
            ).isZero()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM loyalty_point_transaction WHERE point_account_id = ?",
                    Long::class.java,
                    LocalDemoFixture.POINT_ACCOUNT_ID,
                ),
            ).isZero()
        }

        @Test
        fun `the seed provides a confirmed financial tail with a partial-refund adjustment ready for an owner dispute`() {
            transactions.execute { seeder.seed() }

            val settlementInput = settlementInputSnapshots.read(LocalDemoFixture.HISTORICAL_ORDER_ID)
            assertThat(settlementInput.orderId).isEqualTo(LocalDemoFixture.HISTORICAL_ORDER_ID)
            assertThat(settlementInput.storeId).isEqualTo(LocalDemoFixture.STORE_ID)
            assertThat(settlementInput.grossPaidKrw).isEqualTo(LocalDemoFixture.HISTORICAL_ORDER_GROSS_KRW)
            assertThat(settlementInput.feeKrw).isEqualTo(300)
            assertThat(settlementInput.netSettlementKrw).isEqualTo(9_700)
            assertThat(settlementInput.canonicalSnapshotHash).matches("^[0-9a-f]{64}$")
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT canonical_snapshot_hash FROM ordering_order_settlement_input_snapshot WHERE order_id = ?",
                    String::class.java,
                    LocalDemoFixture.HISTORICAL_ORDER_ID,
                ),
            ).isEqualTo(settlementInput.canonicalSnapshotHash)

            val row =
                jdbcTemplate.queryForMap(
                    """
                    SELECT batch.state, batch.adjustment_krw, item.net_settlement_krw, adjustment.reason_code
                      FROM settlement_batch batch
                      JOIN settlement_item item ON item.settlement_batch_id = batch.id
                      JOIN settlement_adjustment adjustment ON adjustment.id = batch.adjustment_cursor_id
                     WHERE batch.id = ?
                    """.trimIndent(),
                    LocalDemoFixture.ADJUSTED_SETTLEMENT_BATCH_ID,
                )

            assertThat(row["state"]).isEqualTo("CONFIRMED")
            assertThat(row["adjustment_krw"]).isEqualTo(-LocalDemoFixture.HISTORICAL_REFUND_KRW)
            assertThat(row["net_settlement_krw"]).isEqualTo(9_700L)
            assertThat(row["reason_code"]).isEqualTo("REFUND_SUCCEEDED")
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT succeeded_amount_krw FROM payment_refund WHERE id = ?",
                    Long::class.java,
                    LocalDemoFixture.HISTORICAL_REFUND_ID,
                ),
            ).isEqualTo(LocalDemoFixture.HISTORICAL_REFUND_KRW)
        }

        @Test
        fun `the seed links merchant accounts to memberships with an expiring initial credential`() {
            transactions.execute { seeder.seed() }

            assertThat(
                jdbcTemplate.queryForMap(
                    "SELECT login_id, state, temporary_password_expires_at, password_changed_at " +
                        "FROM identity_merchant_account WHERE id = ?",
                    LocalDemoFixture.STORE_OWNER_ID,
                ),
            ).containsEntry("login_id", LocalDemoFixture.MERCHANT_LOGIN_ID)
                .containsEntry("state", "INITIAL_PASSWORD")
                .containsEntry("password_changed_at", null)
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM identity_store_membership WHERE actor_id = ? AND store_id = ? AND status = 'ACTIVE'",
                    Long::class.java,
                    LocalDemoFixture.STORE_OWNER_ID,
                    LocalDemoFixture.STORE_ID,
                ),
            ).isEqualTo(1)
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT state FROM identity_merchant_account WHERE id = ?",
                    String::class.java,
                    LocalDemoFixture.OTHER_STORE_OWNER_ID,
                ),
            ).isEqualTo("ACTIVE")
        }

        private fun counts(): Map<String, Long> =
            SEEDED_TABLES
                .associateWith { table ->
                    jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Long::class.java) ?: 0
                }.filterValues { it > 0 }

        private fun installCouponFailureTrigger() {
            jdbcTemplate.execute(
                """
                CREATE FUNCTION test_reject_coupon_issuance()
                RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN RAISE EXCEPTION 'injected coupon issuance failure'; END;
                ${'$'}${'$'}
                """.trimIndent(),
            )
            jdbcTemplate.execute(
                """
                CREATE TRIGGER test_reject_coupon_issuance
                BEFORE INSERT ON promotion_coupon_issuance
                FOR EACH ROW EXECUTE FUNCTION test_reject_coupon_issuance()
                """.trimIndent(),
            )
        }

        private fun dropCouponFailureTrigger() {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_reject_coupon_issuance ON promotion_coupon_issuance")
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_reject_coupon_issuance()")
        }

        private companion object {
            /** Every table the seed writes. The counts below are only meaningful if this is complete. */
            val SEEDED_TABLES =
                listOf(
                    "merchant_store_discovery_profile",
                    "identity_customer_account",
                    "identity_merchant_account",
                    "identity_store_membership",
                    "merchant_menu_configuration_requirement",
                    "merchant_menu_configuration",
                    "merchant_menu_option",
                    "merchant_menu",
                    "inventory_sellable_stock",
                    "fulfillment_pickup_slot",
                    "loyalty_point_account",
                    "merchant_store_settlement_terms",
                    "payment_method",
                    "promotion_campaign_eligible_menu",
                    "promotion_coupon_issuance",
                    "promotion_campaign",
                    "settlement_adjustment",
                    "settlement_item",
                    "settlement_batch",
                    "payment_refund",
                    "payment_payment",
                    "ordering_order_settlement_input_snapshot",
                    "ordering_order_point_accrual_unit",
                    "ordering_order_point_accrual_snapshot",
                    "ordering_order_point_accrual_source",
                    "ordering_order_line",
                    "ordering_order",
                    "ordering_public_reference_registry",
                    "merchant_store",
                )
        }
    }

package io.github.kdh949.beanflow.demo

import io.github.kdh949.beanflow.TestcontainersConfiguration
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
@Import(TestcontainersConfiguration::class)
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
                    "identity_store_membership",
                    "merchant_menu_configuration_requirement",
                    "merchant_menu_configuration",
                    "merchant_menu_option",
                    "merchant_menu",
                    "inventory_sellable_stock",
                    "fulfillment_pickup_slot",
                    "loyalty_point_lot",
                    "loyalty_point_account",
                    "merchant_store_settlement_terms",
                    "payment_method",
                    "promotion_campaign_eligible_menu",
                    "promotion_coupon_issuance",
                    "promotion_campaign",
                    "merchant_store",
                )
        }
    }

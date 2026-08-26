package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.api.OrderPointAccrualSnapshotOperations
import io.github.kdh949.beanflow.ordering.api.OrderPointAccrualSourceState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("invokes REQUIRES_NEW order idempotency registration")
@SpringBootTest
internal class OrderCreationPointAccrualSnapshotIntegrationTest
    @Autowired
    constructor(
        private val createOrderUseCase: CreateOrderUseCase,
        private val orderQuoteUseCase: io.github.kdh949.beanflow.ordering.api.OrderQuoteUseCase,
        private val snapshotOperations: OrderPointAccrualSnapshotOperations,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        private var originalGlobalPolicyVersionId: Long = 0

        @BeforeEach
        fun prepare() {
            removeSnapshotFailure()
            OrderCreationDatabaseFixture.clean(jdbcTemplate)
            originalGlobalPolicyVersionId = currentGlobalPolicyVersionId()
        }

        @AfterEach
        fun cleanup() {
            removeSnapshotFailure()
            jdbcTemplate.update(
                """
                UPDATE operations_point_accrual_policy_head
                   SET policy_version_id = ?
                 WHERE scope_type = 'GLOBAL'
                   AND scope_reference = '00000000-0000-0000-0000-000000000000'::uuid
                """.trimIndent(),
                originalGlobalPolicyVersionId,
            )
        }

        @Test
        fun `external payable orders freeze the policy selected at creation`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, priceKrw = 1_000)

            val firstResponse =
                createOrderUseCase.create(
                    "accrual-snapshot-0001",
                    orderQuoteUseCase.attachCurrentQuote(fixture.command()),
                )
            val firstOrderId = orderId(firstResponse.body)
            val beforePolicyChange = snapshotOperations.read(firstOrderId)

            val nextVersionId = installNextGlobalPolicy(accrualRateBps = 777)
            val secondResponse =
                createOrderUseCase.create(
                    "accrual-snapshot-0002",
                    orderQuoteUseCase.attachCurrentQuote(fixture.command()),
                )
            val secondOrderId = orderId(secondResponse.body)

            assertThat(firstResponse.status).isEqualTo(201)
            assertThat(secondResponse.status).isEqualTo(201)
            assertThat(snapshotOperations.read(firstOrderId)).isEqualTo(beforePolicyChange)
            assertThat(beforePolicyChange.sourceState).isEqualTo(OrderPointAccrualSourceState.SNAPSHOTTED)
            assertThat(beforePolicyChange.snapshot!!.policy.policyVersionId)
                .isEqualTo(originalGlobalPolicyVersionId)
            assertThat(beforePolicyChange.snapshot!!.orderPayableKrw).isEqualTo(1_000)
            assertThat(
                beforePolicyChange.snapshot!!
                    .units
                    .single()
                    .cashPayableKrw,
            ).isEqualTo(1_000)

            val afterPolicyChange = snapshotOperations.read(secondOrderId)
            assertThat(afterPolicyChange.snapshot!!.policy.policyVersionId).isEqualTo(nextVersionId)
            assertThat(afterPolicyChange.snapshot!!.policy.accrualRateBps).isEqualTo(777)
            assertThat(afterPolicyChange.snapshot!!.grossAccrualAmountKrw).isEqualTo(77)
            assertThat(
                afterPolicyChange.snapshot!!
                    .units
                    .single()
                    .accruedAmountKrw,
            ).isEqualTo(77)
        }

        @Test
        fun `benefit only orders persist a complete zero cash and zero accrual snapshot`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, priceKrw = 1_000)
            OrderCreationDatabaseFixture.insertPoints(jdbcTemplate, fixture.customerId, 1_000)

            val response =
                createOrderUseCase.create(
                    "benefit-accrual-0001",
                    orderQuoteUseCase.attachCurrentQuote(fixture.command(pointsToUseKrw = 1_000)),
                )
            val source = snapshotOperations.read(orderId(response.body))

            assertThat(response.status).isEqualTo(201)
            assertThat(source.sourceState).isEqualTo(OrderPointAccrualSourceState.SNAPSHOTTED)
            assertThat(source.snapshot!!.orderPayableKrw).isZero()
            assertThat(source.snapshot!!.grossAccrualAmountKrw).isZero()
            assertThat(
                source.snapshot!!
                    .units
                    .single()
                    .cashPayableKrw,
            ).isZero()
            assertThat(
                source.snapshot!!
                    .units
                    .single()
                    .accruedAmountKrw,
            ).isZero()
        }

        @Test
        fun `snapshot persistence failure rolls back the order and every owner reservation`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            installSnapshotFailure()

            val response =
                createOrderUseCase.create(
                    "accrual-failure-0001",
                    orderQuoteUseCase.attachCurrentQuote(fixture.command()),
                )

            assertThat(response.status).isEqualTo(503)
            assertThat(response.body).contains("\"code\":\"DEPENDENCY_UNAVAILABLE\"")
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isZero()
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "fulfillment_pickup_reservation")).isZero()
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "inventory_stock_reservation")).isZero()
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order_point_accrual_source")).isZero()
        }

        private fun currentGlobalPolicyVersionId(): Long =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    """
                    SELECT policy_version_id
                      FROM operations_point_accrual_policy_head
                     WHERE scope_type = 'GLOBAL'
                       AND scope_reference = '00000000-0000-0000-0000-000000000000'::uuid
                    """.trimIndent(),
                    Long::class.java,
                ),
            )

        private fun installNextGlobalPolicy(accrualRateBps: Int): Long {
            val sourceReference = "test:future-global-policy:${UUID.randomUUID()}"
            val hash = "d".repeat(64)
            val versionId =
                requireNotNull(
                    jdbcTemplate.queryForObject(
                        """
                        INSERT INTO operations_point_accrual_policy_version (
                            scope_type, scope_reference, state, accrual_rate_bps, rounding_mode,
                            issuer_type, issuer_reference, expiry_rule, validity_days, effective_at,
                            actor_type, actor_reference, reason, payload_hash, source_reference
                        ) VALUES (
                            'GLOBAL', '00000000-0000-0000-0000-000000000000'::uuid, 'OVERRIDE', ?, 'FLOOR',
                            'PLATFORM', 'test:beanflow-platform', 'EXACT_DURATION_FROM_COMPLETION', 365, ?,
                            'SYSTEM', 'order-snapshot-integration-test', 'Future-order policy test', ?, ?
                        )
                        RETURNING policy_version_id
                        """.trimIndent(),
                        Long::class.java,
                        accrualRateBps,
                        Timestamp.from(Instant.parse("2026-08-02T00:00:00Z")),
                        hash,
                        sourceReference,
                    ),
                )
            jdbcTemplate.update(
                """
                UPDATE operations_point_accrual_policy_head
                   SET policy_version_id = ?
                 WHERE scope_type = 'GLOBAL'
                   AND scope_reference = '00000000-0000-0000-0000-000000000000'::uuid
                """.trimIndent(),
                versionId,
            )
            return versionId
        }

        private fun orderId(body: String): UUID =
            UUID.fromString(requireNotNull(Regex("\\\"orderId\\\":\\\"([^\\\"]+)\\\"").find(body)).groupValues[1])

        private fun installSnapshotFailure() {
            jdbcTemplate.execute(
                """
                CREATE OR REPLACE FUNCTION fail_order_point_accrual_snapshot() RETURNS trigger AS ${'$'}${'$'}
                BEGIN
                    RAISE EXCEPTION 'forced order point accrual snapshot failure';
                END;
                ${'$'}${'$'} LANGUAGE plpgsql;
                CREATE TRIGGER fail_order_point_accrual_snapshot
                    BEFORE INSERT ON ordering_order_point_accrual_snapshot
                    FOR EACH ROW EXECUTE FUNCTION fail_order_point_accrual_snapshot();
                """.trimIndent(),
            )
        }

        private fun removeSnapshotFailure() {
            jdbcTemplate.execute(
                "DROP TRIGGER IF EXISTS fail_order_point_accrual_snapshot ON ordering_order_point_accrual_snapshot",
            )
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_order_point_accrual_snapshot()")
        }
    }

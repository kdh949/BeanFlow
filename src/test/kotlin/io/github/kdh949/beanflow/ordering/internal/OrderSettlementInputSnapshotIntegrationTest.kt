package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.api.OrderSettlementInputSnapshotOperations
import io.github.kdh949.beanflow.ordering.api.StoredHttpResponse
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
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest
internal class OrderSettlementInputSnapshotIntegrationTest
    @Autowired
    constructor(
        private val createOrderUseCase: CreateOrderUseCase,
        private val orderQuoteUseCase: io.github.kdh949.beanflow.ordering.api.OrderQuoteUseCase,
        private val snapshotOperations: OrderSettlementInputSnapshotOperations,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        @BeforeEach
        fun prepare() {
            removeSnapshotFailure()
            OrderCreationDatabaseFixture.clean(jdbcTemplate)
        }

        @AfterEach
        fun cleanup() {
            removeSnapshotFailure()
        }

        @Test
        fun `order creation stores one immutable settlement input with exact fee and net amounts`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(
                jdbcTemplate,
                fixture,
                priceKrw = 1_000,
                settlementFeeRateBps = 333,
            )

            val response =
                createOrderUseCase.create(
                    "settlement-input-0001",
                    orderQuoteUseCase.attachCurrentQuote(fixture.command()),
                )
            val snapshot = snapshotOperations.read(orderId(response.body))

            assertThat(response.status).isEqualTo(201)
            assertThat(snapshot.storeId).isEqualTo(fixture.storeId)
            assertThat(snapshot.grossPaidKrw).isEqualTo(1_000)
            assertThat(snapshot.feeBaseKrw).isEqualTo(1_000)
            assertThat(snapshot.feeRateBps).isEqualTo(333)
            assertThat(snapshot.feeKrw).isEqualTo(33)
            assertThat(snapshot.benefitCostKrw).isZero()
            assertThat(snapshot.netSettlementKrw).isEqualTo(967)
            assertThat(snapshot.canonicalSnapshotHash).matches("[0-9a-f]{64}")
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order_settlement_input_snapshot"))
                .isOne()
        }

        @Test
        fun `coupon and mixed point issuers freeze only store-owned benefit costs`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, priceKrw = 1_000)
            val couponIssuanceId = OrderCreationDatabaseFixture.insertFixedCoupon(jdbcTemplate, fixture, 200)
            insertMixedPointLots(fixture, storeIssuerReference = fixture.storeId.toString())

            val response =
                createOrderUseCase.create(
                    "settlement-input-mixed-0001",
                    orderQuoteUseCase.attachCurrentQuote(
                        fixture.command(pointsToUseKrw = 300, couponIssuanceId = couponIssuanceId),
                    ),
                )
            val snapshot = snapshotOperations.read(orderId(response.body))

            assertThat(response.status).isEqualTo(201)
            assertThat(snapshot.couponDiscountKrw).isEqualTo(200)
            assertThat(snapshot.platformCouponCostKrw).isZero()
            assertThat(snapshot.couponCostKrw).isEqualTo(200)
            assertThat(snapshot.pointsAppliedKrw).isEqualTo(300)
            assertThat(snapshot.pointCostKrw).isEqualTo(150)
            assertThat(snapshot.feeBaseKrw).isEqualTo(500)
            assertThat(snapshot.feeKrw).isEqualTo(25)
            assertThat(snapshot.benefitCostKrw).isEqualTo(350)
            assertThat(snapshot.netSettlementKrw).isEqualTo(625)
            assertThat(snapshot.pointAllocationHash).matches("[0-9a-f]{64}")
        }

        @Test
        fun `store point issuer mismatch fails closed and rolls back every created resource`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            insertMixedPointLots(fixture, storeIssuerReference = UUID.randomUUID().toString())

            val response =
                createOrderUseCase.create(
                    "settlement-input-point-mismatch-0001",
                    orderQuoteUseCase.attachCurrentQuote(fixture.command(pointsToUseKrw = 300)),
                )

            assertThat(response.status).isEqualTo(503)
            assertThat(response.body).contains("\"code\":\"SETTLEMENT_INPUT_UNAVAILABLE\"")
            assertNoOrderOrReservation()
        }

        @Test
        fun `missing store terms fails before creating owner reservations`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(
                jdbcTemplate,
                fixture,
                includeSettlementTerms = false,
            )

            val response =
                createOrderUseCase.create(
                    "settlement-input-no-terms-0001",
                    fixture.command(expectedQuoteFingerprint = "0".repeat(64)),
                )

            assertThat(response.status).isEqualTo(503)
            assertThat(response.body).contains("\"code\":\"SETTLEMENT_INPUT_UNAVAILABLE\"")
            assertNoOrderOrReservation()
        }

        @Test
        fun `snapshot persistence failure rolls back order coupon point stock and pickup`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val couponIssuanceId = OrderCreationDatabaseFixture.insertFixedCoupon(jdbcTemplate, fixture, 100)
            OrderCreationDatabaseFixture.insertPoints(jdbcTemplate, fixture.customerId, 100)
            installSnapshotFailure()

            val response =
                createOrderUseCase.create(
                    "settlement-input-persistence-failure-0001",
                    orderQuoteUseCase.attachCurrentQuote(
                        fixture.command(pointsToUseKrw = 100, couponIssuanceId = couponIssuanceId),
                    ),
                )

            assertThat(response.status).isEqualTo(503)
            assertThat(response.body).contains("\"code\":\"SETTLEMENT_INPUT_UNAVAILABLE\"")
            assertNoOrderOrReservation()
        }

        @Test
        fun `idempotent replay preserves one snapshot and the same canonical hash`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)

            val command = orderQuoteUseCase.attachCurrentQuote(fixture.command())
            val first = createOrderUseCase.create("settlement-input-replay-0001", command)
            val before = snapshotOperations.read(orderId(first.body))
            val replay = createOrderUseCase.create("settlement-input-replay-0001", command)
            val after = snapshotOperations.read(orderId(replay.body))

            assertThat(replay.replay).isTrue()
            assertThat(after).isEqualTo(before)
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order_settlement_input_snapshot"))
                .isOne()
        }

        @Test
        fun `new future store terms do not change an existing snapshot`() {
            val fixture = OrderCreationFixture()
            val nextEffectiveAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS)
            OrderCreationDatabaseFixture.insertBase(
                jdbcTemplate,
                fixture,
                settlementFeeRateBps = 500,
                settlementTermsEffectiveTo = nextEffectiveAt,
            )
            val response =
                createOrderUseCase.create(
                    "settlement-input-terms-change-0001",
                    orderQuoteUseCase.attachCurrentQuote(fixture.command()),
                )
            val before = snapshotOperations.read(orderId(response.body))

            jdbcTemplate.update(
                """
                INSERT INTO merchant_store_settlement_terms (
                    terms_version_id, store_id, source_reference, fee_rate_bps,
                    effective_from, effective_to, created_at
                ) VALUES (?, ?, ?, 900, ?, NULL, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                fixture.storeId,
                "test:future-store-settlement-terms:${fixture.storeId}",
                Timestamp.from(nextEffectiveAt),
                Timestamp.from(Instant.now()),
            )

            assertThat(snapshotOperations.read(orderId(response.body))).isEqualTo(before)
            assertThat(before.feeRateBps).isEqualTo(500)
        }

        @Test
        fun `concurrent future terms publication cannot change the terms selected for order creation`() {
            val fixture = OrderCreationFixture()
            val nextEffectiveAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS)
            OrderCreationDatabaseFixture.insertBase(
                jdbcTemplate,
                fixture,
                settlementFeeRateBps = 500,
                settlementTermsEffectiveTo = nextEffectiveAt,
            )
            val start = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            val command = orderQuoteUseCase.attachCurrentQuote(fixture.command())

            try {
                val orderFuture =
                    executor.submit(
                        Callable<StoredHttpResponse> {
                            start.await()
                            createOrderUseCase.create("settlement-input-concurrent-terms-0001", command)
                        },
                    )
                val termsFuture =
                    executor.submit(
                        Callable<Int> {
                            start.await()
                            jdbcTemplate.update(
                                """
                                INSERT INTO merchant_store_settlement_terms (
                                    terms_version_id, store_id, source_reference, fee_rate_bps,
                                    effective_from, effective_to, created_at
                                ) VALUES (?, ?, ?, 900, ?, NULL, ?)
                                """.trimIndent(),
                                UUID.randomUUID(),
                                fixture.storeId,
                                "test:concurrent-future-store-settlement-terms:${fixture.storeId}",
                                Timestamp.from(nextEffectiveAt),
                                Timestamp.from(Instant.now()),
                            )
                        },
                    )

                val response = orderFuture.get(20, TimeUnit.SECONDS)
                assertThat(termsFuture.get(20, TimeUnit.SECONDS)).isEqualTo(1)
                assertThat(response.status).isEqualTo(201)
                assertThat(snapshotOperations.read(orderId(response.body)).feeRateBps).isEqualTo(500)
            } finally {
                executor.shutdownNow()
            }
        }

        private fun insertMixedPointLots(
            fixture: OrderCreationFixture,
            storeIssuerReference: String,
        ) {
            val accountId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO loyalty_point_account (
                    id, customer_id, available_points_krw, reserved_points_krw
                ) VALUES (?, ?, 300, 0)
                """.trimIndent(),
                accountId,
                fixture.customerId,
            )
            listOf(
                Triple("PLATFORM", "platform:test", 100L),
                Triple("BRAND", "brand:test", 50L),
                Triple("STORE", storeIssuerReference, 150L),
            ).forEachIndexed { index, (issuerType, issuerReference, amountKrw) ->
                jdbcTemplate.update(
                    """
                    INSERT INTO loyalty_point_lot (
                        id, point_account_id, available_amount_krw, reserved_amount_krw,
                        expires_at, issuer_type, issuer_reference
                    ) VALUES (?, ?, ?, 0, ?, ?, ?)
                    """.trimIndent(),
                    UUID.randomUUID(),
                    accountId,
                    amountKrw,
                    Timestamp.from(Instant.parse("2030-01-0${index + 1}T00:00:00Z")),
                    issuerType,
                    issuerReference,
                )
            }
        }

        private fun assertNoOrderOrReservation() {
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isZero()
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order_settlement_input_snapshot"))
                .isZero()
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "fulfillment_pickup_reservation")).isZero()
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "inventory_stock_reservation")).isZero()
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "promotion_coupon_reservation")).isZero()
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "loyalty_point_reservation")).isZero()
        }

        private fun orderId(body: String): UUID =
            UUID.fromString(requireNotNull(Regex("\\\"orderId\\\":\\\"([^\\\"]+)\\\"").find(body)).groupValues[1])

        private fun installSnapshotFailure() {
            jdbcTemplate.execute(
                """
                CREATE OR REPLACE FUNCTION fail_order_settlement_input_snapshot() RETURNS trigger AS ${'$'}${'$'}
                BEGIN
                    RAISE EXCEPTION 'forced settlement input snapshot failure';
                END;
                ${'$'}${'$'} LANGUAGE plpgsql;
                CREATE TRIGGER fail_order_settlement_input_snapshot
                    BEFORE INSERT ON ordering_order_settlement_input_snapshot
                    FOR EACH ROW EXECUTE FUNCTION fail_order_settlement_input_snapshot();
                """.trimIndent(),
            )
        }

        private fun removeSnapshotFailure() {
            jdbcTemplate.execute(
                "DROP TRIGGER IF EXISTS fail_order_settlement_input_snapshot ON ordering_order_settlement_input_snapshot",
            )
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_order_settlement_input_snapshot()")
        }
    }

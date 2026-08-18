package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.api.StoredHttpResponse
import io.github.kdh949.beanflow.payment.internal.BenefitOnlyPaymentService
import io.github.kdh949.beanflow.payment.internal.PaymentJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest(
    properties = [
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
    ],
)
internal class BenefitOnlyOrderCreationTest
    @Autowired
    constructor(
        private val createOrderUseCase: CreateOrderUseCase,
        private val expiryWorker: ReservationExpiryWorker,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        @BeforeEach
        fun cleanDatabase() = OrderCreationDatabaseFixture.clean(jdbcTemplate)

        @Test
        fun `zero payable order atomically approves payment and confirms all resources`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, priceKrw = 1_000)
            val couponIssuanceId =
                OrderCreationDatabaseFixture.insertFixedCoupon(jdbcTemplate, fixture, 100)
            val (accountId, lotId) =
                OrderCreationDatabaseFixture.insertPoints(jdbcTemplate, fixture.customerId, 900)

            val response =
                createOrderUseCase.create(
                    "benefit-only-001",
                    fixture.command(pointsToUseKrw = 900, couponIssuanceId = couponIssuanceId),
                )

            assertThat(response.status).isEqualTo(201)
            assertThat(response.body)
                .contains("\"state\":\"PAID\"")
                .contains("\"type\":\"BENEFIT_ONLY\"")
                .contains("\"approvalState\":\"APPROVED\"")
                .contains("\"approvedAmountKrw\":0")
                .doesNotContain("reservationExpiresAt")
            assertThat(value<String>("SELECT state FROM ordering_order")).isEqualTo("PAID")
            assertThat(value<Long>("SELECT payable_krw FROM ordering_order")).isZero()
            assertThat(jdbcTemplate.queryForObject("SELECT reservation_expires_at FROM ordering_order", Any::class.java))
                .isNull()
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "payment_payment")).isEqualTo(1)
            assertThat(value<String>("SELECT type FROM payment_payment")).isEqualTo("BENEFIT_ONLY")
            assertThat(value<String>("SELECT approval_state FROM payment_payment")).isEqualTo("APPROVED")
            assertThat(value<Long>("SELECT approved_amount_krw FROM payment_payment")).isZero()

            assertThat(value<String>("SELECT state FROM fulfillment_pickup_reservation")).isEqualTo("CONFIRMED")
            assertThat(value<Long>("SELECT reserved_count FROM fulfillment_pickup_slot")).isZero()
            assertThat(value<Long>("SELECT confirmed_count FROM fulfillment_pickup_slot")).isEqualTo(1)
            assertThat(value<String>("SELECT state FROM inventory_stock_reservation")).isEqualTo("CONFIRMED")
            assertThat(value<Long>("SELECT reserved_quantity FROM inventory_sellable_stock")).isZero()
            assertThat(value<Long>("SELECT confirmed_quantity FROM inventory_sellable_stock")).isEqualTo(1)
            assertThat(value<String>("SELECT state FROM loyalty_point_reservation")).isEqualTo("USED")
            assertThat(value<String>("SELECT state FROM promotion_coupon_reservation")).isEqualTo("USED")
            assertThat(
                value<String>(
                    "SELECT state FROM promotion_coupon_issuance WHERE id = ?",
                    couponIssuanceId,
                ),
            ).isEqualTo("USED")
            assertThat(
                value<Long>(
                    "SELECT available_points_krw FROM loyalty_point_account WHERE id = ?",
                    accountId,
                ),
            ).isZero()
            assertThat(
                value<Long>(
                    "SELECT reserved_points_krw FROM loyalty_point_account WHERE id = ?",
                    accountId,
                ),
            ).isZero()
            assertThat(
                value<Long>(
                    "SELECT reserved_amount_krw FROM loyalty_point_lot WHERE id = ?",
                    lotId,
                ),
            ).isZero()
            assertThat(value<String>("SELECT type FROM loyalty_point_transaction")).isEqualTo("USE")
            assertThat(expiryWorker.runOnce()).isZero()

            assertThat(
                jdbcTemplate.queryForList(
                    "SELECT action FROM operations_audit_record ORDER BY action",
                    String::class.java,
                ),
            ).contains(
                "BENEFIT_ONLY_PAYMENT_APPROVED",
                "ORDER_CREATED",
                "PICKUP_CONFIRMED",
                "STOCK_CONFIRMED",
                "COUPON_CONFIRMED",
                "POINTS_CONFIRMED",
            )
        }

        @Test
        fun `positive payable never enters benefit only payment path`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, priceKrw = 1_000)
            OrderCreationDatabaseFixture.insertPoints(jdbcTemplate, fixture.customerId, 999)

            val response =
                createOrderUseCase.create(
                    "benefit-only-002",
                    fixture.command(pointsToUseKrw = 999),
                )

            assertThat(response.status).isEqualTo(201)
            assertThat(response.body)
                .contains("\"state\":\"PENDING_PAYMENT\"")
                .contains("\"payableKrw\":1")
                .doesNotContain("\"payment\"")
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "payment_payment")).isZero()
            assertThat(value<String>("SELECT state FROM fulfillment_pickup_reservation")).isEqualTo("RESERVED")
            assertThat(value<String>("SELECT state FROM inventory_stock_reservation")).isEqualTo("RESERVED")
            assertThat(value<String>("SELECT state FROM loyalty_point_reservation")).isEqualTo("RESERVED")
        }

        @Test
        fun `same key concurrency creates one payment and one confirmation set`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, priceKrw = 1_000)
            OrderCreationDatabaseFixture.insertPoints(jdbcTemplate, fixture.customerId, 1_000)
            val command = fixture.command(pointsToUseKrw = 1_000)
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)

            val futures: List<Future<StoredHttpResponse>> =
                (1..2).map {
                    executor.submit<StoredHttpResponse> {
                        barrier.await()
                        createOrderUseCase.create("benefit-concurrent-01", command)
                    }
                }
            val responses = futures.map { it.get(15, TimeUnit.SECONDS) }
            executor.shutdown()

            assertThat(responses.map(StoredHttpResponse::status)).allMatch { it == 201 || it == 409 }
            assertThat(responses).anyMatch { it.status == 201 }
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isEqualTo(1)
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "payment_payment")).isEqualTo(1)
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "loyalty_point_transaction")).isEqualTo(1)
            assertThat(value<Long>("SELECT confirmed_count FROM fulfillment_pickup_slot")).isEqualTo(1)
            assertThat(value<Long>("SELECT confirmed_quantity FROM inventory_sellable_stock")).isEqualTo(1)
        }

        @Test
        fun `confirmation dependency failure rolls back payment order and every reservation`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, priceKrw = 1_000)
            OrderCreationDatabaseFixture.insertPoints(jdbcTemplate, fixture.customerId, 1_000)
            installStockConfirmationFault()

            try {
                val response =
                    createOrderUseCase.create(
                        "benefit-fault-001",
                        fixture.command(pointsToUseKrw = 1_000),
                    )

                assertThat(response.status).isEqualTo(503)
                assertThat(response.body).contains("\"code\":\"DEPENDENCY_UNAVAILABLE\"")
                assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isZero()
                assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "payment_payment")).isZero()
                assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "fulfillment_pickup_reservation")).isZero()
                assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "inventory_stock_reservation")).isZero()
                assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "loyalty_point_reservation")).isZero()
                assertThat(value<Long>("SELECT reserved_count FROM fulfillment_pickup_slot")).isZero()
                assertThat(value<Long>("SELECT reserved_quantity FROM inventory_sellable_stock")).isZero()
            } finally {
                removeStockConfirmationFault()
            }
        }

        @Test
        fun `benefit payment service has no external provider collaborator`() {
            assertThat(BenefitOnlyPaymentService::class.java.declaredFields.map { it.type })
                .contains(PaymentJpaRepository::class.java)
                .allMatch { it == PaymentJpaRepository::class.java }
        }

        private fun installStockConfirmationFault() {
            jdbcTemplate.execute(
                """
                CREATE OR REPLACE FUNCTION test_remove_stock_reservation()
                RETURNS trigger AS ${'$'}body${'$'}
                BEGIN
                    DELETE FROM inventory_stock_reservation WHERE order_id = NEW.order_id;
                    RETURN NEW;
                END;
                ${'$'}body${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            )
            jdbcTemplate.execute(
                """
                CREATE TRIGGER test_remove_stock_reservation_after_payment
                AFTER INSERT ON payment_payment
                FOR EACH ROW EXECUTE FUNCTION test_remove_stock_reservation()
                """.trimIndent(),
            )
        }

        private fun removeStockConfirmationFault() {
            jdbcTemplate.execute(
                "DROP TRIGGER IF EXISTS test_remove_stock_reservation_after_payment ON payment_payment",
            )
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_remove_stock_reservation()")
        }

        private inline fun <reified T : Any> value(
            sql: String,
            vararg args: Any,
        ): T = requireNotNull(jdbcTemplate.queryForObject(sql, T::class.java, *args))
    }

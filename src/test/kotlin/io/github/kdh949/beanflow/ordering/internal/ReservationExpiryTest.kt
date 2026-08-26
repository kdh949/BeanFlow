package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.api.OrderPaymentLeaseGuard
import io.github.kdh949.beanflow.ordering.api.ReservationExpiryOutcome
import io.github.kdh949.beanflow.ordering.api.ReservationExpiryUseCase
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest(
    properties = [
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
    ],
)
internal class ReservationExpiryTest
    @Autowired
    constructor(
        private val createOrderUseCase: CreateOrderUseCase,
        private val orderQuoteUseCase: io.github.kdh949.beanflow.ordering.api.OrderQuoteUseCase,
        private val expiryUseCase: ReservationExpiryUseCase,
        private val paymentLeaseGuard: OrderPaymentLeaseGuard,
        private val worker: ReservationExpiryWorker,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        @BeforeEach
        fun cleanDatabase() = OrderCreationDatabaseFixture.clean(jdbcTemplate)

        @Test
        fun `reservation is valid one nanosecond before deadline and expires exactly at deadline`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val orderId = createOrder(fixture, "expiry-boundary-01")
            val deadline = orderDeadline(orderId)

            val before = expiryUseCase.expireIfDue(orderId, deadline.minusNanos(1))
            val boundary = expiryUseCase.expireIfDue(orderId, deadline)

            assertThat(before.outcome).isEqualTo(ReservationExpiryOutcome.NOT_ELIGIBLE)
            assertThat(boundary.outcome).isEqualTo(ReservationExpiryOutcome.EXPIRED)
            assertExpiredState(orderId, fixture)
            assertThat(
                countWhere(
                    "operations_audit_record",
                    "source_reference = ?",
                    "order:$orderId:expiry",
                ),
            ).isEqualTo(3)
            assertThat(
                value<String>(
                    "SELECT actor_type FROM operations_audit_record WHERE action = 'ORDER_EXPIRED' AND target_id = ?",
                    orderId,
                ),
            ).isEqualTo("SYSTEM")
        }

        @Test
        fun `reservation is expired one nanosecond after deadline`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val orderId = createOrder(fixture, "expiry-after-boundary-01")
            val deadline = orderDeadline(orderId)

            assertThat(expiryUseCase.expireIfDue(orderId, deadline.plusNanos(1)).outcome)
                .isEqualTo(ReservationExpiryOutcome.EXPIRED)
            assertExpiredState(orderId, fixture)
        }

        @Test
        fun `payment guard accepts before deadline and rejects exact deadline after materializing expiry`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val orderId = createOrder(fixture, "payment-boundary-01")
            val deadline = orderDeadline(orderId)

            paymentLeaseGuard.requireEligible(fixture.customerId, orderId, deadline.minusNanos(1))
            assertThatThrownBy {
                paymentLeaseGuard.requireEligible(fixture.customerId, orderId, deadline)
            }.isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.ORDER_STATE_CONFLICT)
            }
            assertExpiredState(orderId, fixture)
        }

        @Test
        fun `owner release failure rolls back order and earlier releases`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val orderId = createOrder(fixture, "expiry-rollback-01")
            val deadline = orderDeadline(orderId)
            jdbcTemplate.update("DELETE FROM inventory_stock_reservation WHERE order_id = ?", orderId)

            assertThatThrownBy { expiryUseCase.expireIfDue(orderId, deadline) }
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
                }

            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId))
                .isEqualTo("PENDING_PAYMENT")
            assertThat(value<String>("SELECT state FROM fulfillment_pickup_reservation WHERE order_id = ?", orderId))
                .isEqualTo("RESERVED")
            assertThat(value<Long>("SELECT reserved_count FROM fulfillment_pickup_slot WHERE id = ?", fixture.pickupSlotId))
                .isEqualTo(1)
            assertThat(
                countWhere(
                    "operations_audit_record",
                    "source_reference = ?",
                    "order:$orderId:expiry",
                ),
            ).isZero()
        }

        @Test
        fun `concurrent expiry restores each resource once`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val orderId = createOrder(fixture, "expiry-concurrent-01")
            val deadline = orderDeadline(orderId)
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)

            val futures =
                (1..2).map {
                    executor.submit<ReservationExpiryOutcome> {
                        barrier.await()
                        expiryUseCase.expireIfDue(orderId, deadline).outcome
                    }
                }
            val outcomes = futures.map { it.get(15, TimeUnit.SECONDS) }
            executor.shutdown()

            assertThat(outcomes).containsExactlyInAnyOrder(
                ReservationExpiryOutcome.EXPIRED,
                ReservationExpiryOutcome.NOT_ELIGIBLE,
            )
            assertExpiredState(orderId, fixture)
            assertThat(
                countWhere(
                    "operations_audit_record",
                    "source_reference = ?",
                    "order:$orderId:expiry",
                ),
            ).isEqualTo(3)
        }

        @Test
        fun `worker restart does not restore an expired order twice`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val orderId = createOrder(fixture, "expiry-worker-01")
            val dueAt = Instant.now().minusSeconds(1)
            jdbcTemplate.update(
                "UPDATE ordering_order SET reservation_expires_at = ? WHERE id = ?",
                Timestamp.from(dueAt),
                orderId,
            )
            jdbcTemplate.update(
                "UPDATE fulfillment_pickup_reservation SET expires_at = ? WHERE order_id = ?",
                Timestamp.from(dueAt),
                orderId,
            )
            jdbcTemplate.update(
                "UPDATE inventory_stock_reservation SET expires_at = ? WHERE order_id = ?",
                Timestamp.from(dueAt),
                orderId,
            )

            assertThat(worker.runOnce()).isEqualTo(1)
            assertThat(worker.runOnce()).isZero()
            assertExpiredState(orderId, fixture)
        }

        private fun createOrder(
            fixture: OrderCreationFixture,
            key: String,
        ): UUID {
            val response = createOrderUseCase.create(key, orderQuoteUseCase.attachCurrentQuote(fixture.command()))
            assertThat(response.status).isEqualTo(201)
            return requireNotNull(
                jdbcTemplate.queryForObject("SELECT id FROM ordering_order", UUID::class.java),
            )
        }

        private fun orderDeadline(orderId: UUID): Instant =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT reservation_expires_at FROM ordering_order WHERE id = ?",
                    Timestamp::class.java,
                    orderId,
                ),
            ).toInstant()

        private fun assertExpiredState(
            orderId: UUID,
            fixture: OrderCreationFixture,
        ) {
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("EXPIRED")
            assertThat(value<String>("SELECT state FROM fulfillment_pickup_reservation WHERE order_id = ?", orderId))
                .isEqualTo("EXPIRED")
            assertThat(value<String>("SELECT state FROM inventory_stock_reservation WHERE order_id = ?", orderId))
                .isEqualTo("EXPIRED")
            assertThat(value<Long>("SELECT reserved_count FROM fulfillment_pickup_slot WHERE id = ?", fixture.pickupSlotId))
                .isZero()
            assertThat(
                value<Long>(
                    "SELECT available_quantity FROM inventory_sellable_stock WHERE id = ?",
                    fixture.sellableUnitId,
                ),
            ).isEqualTo(10)
            assertThat(
                value<Long>(
                    "SELECT reserved_quantity FROM inventory_sellable_stock WHERE id = ?",
                    fixture.sellableUnitId,
                ),
            ).isZero()
        }

        private inline fun <reified T : Any> value(
            sql: String,
            vararg args: Any,
        ): T = requireNotNull(jdbcTemplate.queryForObject(sql, T::class.java, *args))

        private fun countWhere(
            table: String,
            predicate: String,
            vararg args: Any,
        ): Long =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM $table WHERE $predicate",
                    Long::class.java,
                    *args,
                ),
            )
    }

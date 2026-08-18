package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.eventing.api.BenefitRestorationPolicySnapshotV1
import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.OrderCancelledV1
import io.github.kdh949.beanflow.fulfillment.api.PickupReservationOperations
import io.github.kdh949.beanflow.fulfillment.api.ReservePickupCommand
import io.github.kdh949.beanflow.fulfillment.internal.PickupReservationJpaRepository
import io.github.kdh949.beanflow.fulfillment.internal.PickupReservationState
import io.github.kdh949.beanflow.fulfillment.internal.PickupSlotEntity
import io.github.kdh949.beanflow.fulfillment.internal.PickupSlotJpaRepository
import io.github.kdh949.beanflow.inventory.api.ReserveStockCommand
import io.github.kdh949.beanflow.inventory.api.StockRequirement
import io.github.kdh949.beanflow.inventory.api.StockReservationOperations
import io.github.kdh949.beanflow.inventory.internal.SellableStockEntity
import io.github.kdh949.beanflow.inventory.internal.SellableStockJpaRepository
import io.github.kdh949.beanflow.inventory.internal.StockReservationJpaRepository
import io.github.kdh949.beanflow.inventory.internal.StockReservationState
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicyOperations
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationTrigger
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitType
import io.github.kdh949.beanflow.operations.api.OpenOrderCompensationCaseCommand
import io.github.kdh949.beanflow.operations.api.OrderCompensationOperations
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepState
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepType
import io.github.kdh949.beanflow.operations.api.OrderCompensationTrigger
import io.github.kdh949.beanflow.shared.api.OrderTerminationTrigger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest(
    properties = [
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.payment.refund.initial-delay-ms=3600000",
        "beanflow.notification.initial-delay-ms=3600000",
    ],
)
internal class OrderTerminationResourceListenerIntegrationTest
    @Autowired
    constructor(
        private val pickupOperations: PickupReservationOperations,
        private val stockOperations: StockReservationOperations,
        private val compensationOperations: OrderCompensationOperations,
        private val policies: ExpiredBenefitRestorationPolicyOperations,
        private val pickupSlotRepository: PickupSlotJpaRepository,
        private val pickupReservationRepository: PickupReservationJpaRepository,
        private val stockRepository: SellableStockJpaRepository,
        private val stockReservationRepository: StockReservationJpaRepository,
        private val eventPublisher: ApplicationEventPublisher,
        private val jdbcTemplate: JdbcTemplate,
        private val clock: Clock,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)

        @BeforeEach
        fun cleanDatabase() {
            jdbcTemplate.execute(
                "TRUNCATE TABLE event_publication, operations_order_compensation_case, " +
                    "fulfillment_pickup_reservation, fulfillment_pickup_slot, " +
                    "inventory_stock_reservation, inventory_sellable_stock CASCADE",
            )
        }

        @Test
        fun `OrderCancelledV1 releases pickup and stock with terminal version sources`() {
            val orderId = UUID.randomUUID()
            val storeId = UUID.randomUUID()
            val slotId = UUID.randomUUID()
            val stockId = UUID.randomUUID()
            // The slot must still be reservable when reserve() runs (BR-05, ADR-076), so it is
            // placed ahead of the injected Clock. NOW stays the fixed lease and termination time.
            val slotStartsAt = clock.instant().plus(Duration.ofHours(1))
            transactions.executeWithoutResult {
                pickupSlotRepository.save(
                    PickupSlotEntity(slotId, storeId, slotStartsAt, slotStartsAt.plusSeconds(600), 2),
                )
                stockRepository.save(SellableStockEntity(stockId, storeId, 5))
                pickupOperations.reserve(
                    ReservePickupCommand(
                        orderId,
                        storeId,
                        slotId,
                        clock.instant().plus(Duration.ofMinutes(10)),
                        "pickup:$orderId",
                    ),
                )
                stockOperations.reserve(
                    ReserveStockCommand(
                        orderId,
                        storeId,
                        listOf(StockRequirement(stockId, 2)),
                        NOW.plusSeconds(600),
                        "stock:$orderId",
                    ),
                )
                pickupOperations.confirm(orderId, clock.instant(), "pickup:$orderId")
                stockOperations.confirm(orderId, "stock:$orderId")
            }
            val couponPolicy =
                policies.current(ExpiredBenefitRestorationTrigger.CUSTOMER_CANCELLATION, ExpiredBenefitType.COUPON)
            val pointsPolicy =
                policies.current(ExpiredBenefitRestorationTrigger.CUSTOMER_CANCELLATION, ExpiredBenefitType.POINTS)
            val event =
                OrderCancelledV1(
                    envelope =
                        EventEnvelope(
                            UUID.randomUUID(),
                            "OrderCancelledV1",
                            orderId,
                            7,
                            NOW,
                            1,
                            "cancel-$orderId",
                            "customer-cancellation-command:${UUID.randomUUID()}",
                        ),
                    orderId = orderId,
                    cancelledAt = NOW,
                    couponRequired = false,
                    pointsRequired = false,
                    couponPolicy = couponPolicy.toEventPolicy(),
                    pointsPolicy = pointsPolicy.toEventPolicy(),
                )
            compensationOperations.open(
                OpenOrderCompensationCaseCommand(
                    UUID.randomUUID(),
                    event.envelope.eventId,
                    orderId,
                    event.envelope.aggregateVersion,
                    UUID.randomUUID(),
                    storeId,
                    OrderCompensationTrigger.CUSTOMER_CANCELLATION,
                    "order:$orderId:customer-cancellation:${event.envelope.aggregateVersion}",
                    couponPolicy,
                    pointsPolicy,
                    false,
                    false,
                    false,
                    event.envelope.correlationId,
                    NOW,
                ),
            )

            transactions.executeWithoutResult { eventPublisher.publishEvent(event) }

            // The owner reservation state and the compensation step state are written by different
            // steps of the listener, so waiting only on the reservation lets the step still be
            // PROCESSING. Both must be settled before the assertions below run.
            await("cancellation resource listeners") {
                pickupReservationRepository.findByOrderId(orderId)?.state ==
                    PickupReservationState.RELEASED_AFTER_TERMINATION &&
                    stockReservationRepository.findByOrderIdOrderBySellableUnitId(orderId).singleOrNull()?.state ==
                    StockReservationState.RELEASED_AFTER_TERMINATION &&
                    compensationOperations
                        .findByOrderId(orderId)
                        ?.steps
                        ?.filter { it.type == OrderCompensationStepType.PICKUP || it.type == OrderCompensationStepType.STOCK }
                        ?.let { steps ->
                            steps.size == 2 && steps.all { it.state == OrderCompensationStepState.SUCCEEDED }
                        } == true
            }
            val pickup = requireNotNull(pickupReservationRepository.findByOrderId(orderId))
            val stock = stockReservationRepository.findByOrderIdOrderBySellableUnitId(orderId).single()
            assertThat(pickup.restorationSourceReference)
                .isEqualTo("order:$orderId:customer-cancellation:7:pickup")
            assertThat(stock.restorationSourceReference)
                .isEqualTo("order:$orderId:customer-cancellation:7:stock")
            assertThat(pickup.restorationTrigger).isEqualTo(OrderTerminationTrigger.CUSTOMER_CANCELLATION)
            assertThat(stock.restorationTrigger).isEqualTo(OrderTerminationTrigger.CUSTOMER_CANCELLATION)
            val steps = requireNotNull(compensationOperations.findByOrderId(orderId)).steps.associateBy { it.type }
            assertThat(steps.getValue(OrderCompensationStepType.PICKUP).state)
                .isEqualTo(OrderCompensationStepState.SUCCEEDED)
            assertThat(steps.getValue(OrderCompensationStepType.STOCK).state)
                .isEqualTo(OrderCompensationStepState.SUCCEEDED)
            val publicationTargets =
                jdbcTemplate.queryForList(
                    "SELECT listener_id FROM event_publication WHERE event_type = ? AND serialized_event LIKE ?",
                    String::class.java,
                    OrderCancelledV1::class.java.name,
                    "%${event.envelope.eventId}%",
                )
            assertThat(publicationTargets).containsExactlyInAnyOrder(
                "beanflow.order-compensation.order-cancelled.pickup.v1",
                "beanflow.order-compensation.order-cancelled.stock.v1",
                "beanflow.order-compensation.order-cancelled.coupon.v1",
                "beanflow.order-compensation.order-cancelled.points.v1",
            )
        }

        private fun io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicySnapshot.toEventPolicy() =
            BenefitRestorationPolicySnapshotV1(policyVersion, mode.name, compensationValidityDays)

        private fun await(
            description: String,
            assertion: () -> Boolean,
        ) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                if (runCatching(assertion).getOrDefault(false)) return
                Thread.sleep(20)
            }
            check(assertion()) { "Timed out waiting for $description" }
        }

        private companion object {
            val NOW: Instant = Instant.parse("2026-08-03T10:00:00Z")
        }
    }

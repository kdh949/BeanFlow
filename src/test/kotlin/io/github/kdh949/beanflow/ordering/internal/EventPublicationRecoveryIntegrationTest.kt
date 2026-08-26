package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.eventing.api.BenefitRestorationPolicySnapshotV1
import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.OrderCancelledV1
import io.github.kdh949.beanflow.eventing.api.OrderReadyV1
import io.github.kdh949.beanflow.inventory.api.ReserveStockCommand
import io.github.kdh949.beanflow.inventory.api.StockRequirement
import io.github.kdh949.beanflow.inventory.api.StockReservationOperations
import io.github.kdh949.beanflow.inventory.internal.SellableStockEntity
import io.github.kdh949.beanflow.inventory.internal.SellableStockJpaRepository
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicyOperations
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationTrigger
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitType
import io.github.kdh949.beanflow.operations.api.OpenOrderCompensationCaseCommand
import io.github.kdh949.beanflow.operations.api.OrderCompensationOperations
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepState
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepType
import io.github.kdh949.beanflow.operations.api.OrderCompensationTrigger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.modulith.events.IncompleteEventPublications
import org.springframework.modulith.events.ResubmissionOptions
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Import(TestcontainersConfiguration::class, PublicationFailureTestConfiguration::class)
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest(
    properties = [
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.refund.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
    ],
)
internal class EventPublicationRecoveryIntegrationTest
    @Autowired
    constructor(
        private val eventPublisher: ApplicationEventPublisher,
        private val publications: IncompleteEventPublications,
        private val recoveryWorker: EventPublicationRecoveryWorker,
        private val failingListener: FailingReadyPublicationListener,
        private val stockOperations: StockReservationOperations,
        private val stockRepository: SellableStockJpaRepository,
        private val compensationOperations: OrderCompensationOperations,
        private val policies: ExpiredBenefitRestorationPolicyOperations,
        private val jdbcTemplate: JdbcTemplate,
        private val clock: PublicationRecoveryTestClock,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)

        @BeforeEach
        fun cleanDatabase() {
            await("previous publications to complete") { incompletePublicationCount() == 0L }
            jdbcTemplate.execute(
                "TRUNCATE TABLE notification_customer_preference, notification_inbox_item, notification_delivery, " +
                    "operations_reprocessing_case, " +
                    "operations_order_compensation_case, inventory_stock_reservation, " +
                    "inventory_sellable_stock, event_publication CASCADE",
            )
            failingListener.reset()
            clock.reset()
        }

        @AfterEach
        fun completeScriptedPublication() {
            jdbcTemplate.update(
                "DELETE FROM event_publication WHERE event_type = ?",
                OrderCancelledV1::class.java.name,
            )
            failingListener.allowSuccess()
            if (incompletePublicationCount() > 0) {
                publications.resubmitIncompletePublications(
                    ResubmissionOptions
                        .defaults()
                        .withBatchSize(100)
                        .withMaxInFlight(100),
                )
                await("scripted publications to complete during cleanup") {
                    incompletePublicationCount() == 0L
                }
            }
        }

        @Test
        fun `failed persistent publication is retried and completed without another source event`() {
            val event =
                OrderReadyV1(
                    envelope =
                        EventEnvelope(
                            eventId = UUID.randomUUID(),
                            eventType = "OrderReadyV1",
                            aggregateId = UUID.randomUUID(),
                            aggregateVersion = 1,
                            occurredAt = Instant.now(),
                            payloadVersion = 1,
                            correlationId = "publication-recovery-correlation",
                            causationId = "publication-recovery-test",
                        ),
                    orderId = UUID.randomUUID(),
                    customerId = UUID.randomUUID(),
                    storeId = UUID.randomUUID(),
                    readyAt = Instant.now(),
                )

            transactions.executeWithoutResult {
                eventPublisher.publishEvent(event)
            }

            await("one listener publication to remain incomplete") {
                failingListener.callCount() == 1 &&
                    incompletePublicationCount() == 1L &&
                    incompletePublicationAttemptCount() == 1 &&
                    incompletePublicationStatus() == "FAILED" &&
                    notificationCount(event.envelope.eventId) == 1L &&
                    inboxCount(event.customerId) == 1L
            }
            failingListener.allowSuccess()
            clock.advance(Duration.ofSeconds(12))

            recoveryWorker.runOnce()

            await("failed publication to complete after resubmission") {
                failingListener.callCount() == 2 && incompletePublicationCount() == 0L
            }
            assertThat(notificationCount(event.envelope.eventId)).isEqualTo(1)
            assertThat(inboxCount(event.customerId)).isEqualTo(1)
        }

        @Test
        fun `five failed resubmissions open one event publication manual review case`() {
            val event =
                OrderReadyV1(
                    envelope =
                        EventEnvelope(
                            eventId = UUID.randomUUID(),
                            eventType = "OrderReadyV1",
                            aggregateId = UUID.randomUUID(),
                            aggregateVersion = 1,
                            occurredAt = Instant.now(),
                            payloadVersion = 1,
                            correlationId = "publication-exhaustion-correlation",
                            causationId = "publication-exhaustion-test",
                        ),
                    orderId = UUID.randomUUID(),
                    customerId = UUID.randomUUID(),
                    storeId = UUID.randomUUID(),
                    readyAt = Instant.now(),
                )

            transactions.executeWithoutResult {
                eventPublisher.publishEvent(event)
            }

            await("initial publication failure") {
                failingListener.callCount() == 1 &&
                    incompletePublicationAttemptCount() == 1 &&
                    incompletePublicationStatus() == "FAILED" &&
                    notificationCount(event.envelope.eventId) == 1L &&
                    inboxCount(event.customerId) == 1L
            }
            listOf(12L, 31L, 121L, 301L, 901L).forEachIndexed { index, seconds ->
                clock.advance(Duration.ofSeconds(seconds))
                recoveryWorker.runOnce()
                await("publication resubmission failure ${index + 1}") {
                    failingListener.callCount() == index + 2 &&
                        incompletePublicationAttemptCount() == index + 2 &&
                        incompletePublicationStatus() == "FAILED"
                }
            }

            recoveryWorker.runOnce()
            recoveryWorker.runOnce()

            assertThat(incompletePublicationAttemptCount()).isEqualTo(6)
            assertThat(eventPublicationManualReviewCount(event.envelope.correlationId)).isEqualTo(1)
            assertThat(notificationCount(event.envelope.eventId)).isEqualTo(1)
            assertThat(inboxCount(event.customerId)).isEqualTo(1)
        }

        @Test
        fun `reserved analytics target remains durable without consuming retry attempts`() {
            val event =
                OrderReadyV1(
                    envelope =
                        EventEnvelope(
                            eventId = UUID.randomUUID(),
                            eventType = "OrderReadyV1",
                            aggregateId = UUID.randomUUID(),
                            aggregateVersion = 1,
                            occurredAt = Instant.now(),
                            payloadVersion = 1,
                            correlationId = "reserved-analytics-correlation",
                            causationId = "reserved-analytics-test",
                        ),
                    orderId = UUID.randomUUID(),
                    customerId = UUID.randomUUID(),
                    storeId = UUID.randomUUID(),
                    readyAt = Instant.now(),
                )
            transactions.executeWithoutResult { eventPublisher.publishEvent(event) }
            await("initial listener failure before target reservation") {
                failingListener.callCount() == 1 && incompletePublicationAttemptCount() == 1
            }
            jdbcTemplate.update(
                "UPDATE event_publication SET listener_id = 'beanflow.analytics.reserved-test' " +
                    "WHERE completion_date IS NULL",
            )
            try {
                clock.advance(Duration.ofMinutes(20))

                recoveryWorker.runOnce()

                assertThat(failingListener.callCount()).isEqualTo(1)
                assertThat(incompletePublicationAttemptCount()).isEqualTo(1)
                assertThat(eventPublicationManualReviewCount(event.envelope.correlationId)).isZero()
            } finally {
                jdbcTemplate.update(
                    "DELETE FROM event_publication WHERE listener_id = 'beanflow.analytics.reserved-test'",
                )
            }
        }

        @Test
        fun `mapped compensation publication exhaustion changes only its step without a business attempt`() {
            val fixture = cancellationWithMissingPickup()
            await("only pickup publication to remain incomplete") {
                incompletePublicationCount() == 1L &&
                    incompletePublicationListenerId() ==
                    "beanflow.order-compensation.order-cancelled.pickup.v1"
            }
            jdbcTemplate.update(
                "UPDATE event_publication SET completion_attempts = 6 WHERE completion_date IS NULL",
            )

            recoveryWorker.runOnce()
            recoveryWorker.runOnce()

            val steps =
                requireNotNull(compensationOperations.findByOrderId(fixture.orderId))
                    .steps
                    .associateBy { it.type }
            assertThat(steps.getValue(OrderCompensationStepType.PICKUP).state)
                .isEqualTo(OrderCompensationStepState.MANUAL_REVIEW)
            assertThat(steps.getValue(OrderCompensationStepType.PICKUP).attemptCount).isZero()
            assertThat(steps.getValue(OrderCompensationStepType.STOCK).state)
                .isEqualTo(OrderCompensationStepState.SUCCEEDED)
            assertThat(steps.getValue(OrderCompensationStepType.COUPON).state)
                .isEqualTo(OrderCompensationStepState.NOT_REQUIRED)
            assertThat(steps.getValue(OrderCompensationStepType.POINTS).state)
                .isEqualTo(OrderCompensationStepState.NOT_REQUIRED)
            assertThat(reprocessingReasonCount("EVENT_PUBLICATION_RETRY_EXHAUSTED")).isEqualTo(1)
            assertThat(completedCancellationPublicationCount()).isEqualTo(3)
        }

        @Test
        fun `unknown compensation publication target opens unmapped case without mutating steps`() {
            val fixture = cancellationWithMissingPickup()
            await("pickup publication failure before unknown target mutation") {
                incompletePublicationCount() == 1L
            }
            val before = requireNotNull(compensationOperations.findByOrderId(fixture.orderId)).steps
            jdbcTemplate.update(
                "UPDATE event_publication SET listener_id = 'legacy.default-listener', completion_attempts = 6 " +
                    "WHERE completion_date IS NULL",
            )

            recoveryWorker.runOnce()

            val after = requireNotNull(compensationOperations.findByOrderId(fixture.orderId)).steps
            assertThat(after).isEqualTo(before)
            assertThat(reprocessingReasonCount("PUBLICATION_TARGET_UNMAPPED")).isEqualTo(1)
            assertThat(reprocessingReasonCount("EVENT_PUBLICATION_RETRY_EXHAUSTED")).isZero()
            assertThat(completedCancellationPublicationCount()).isEqualTo(3)
        }

        private fun cancellationWithMissingPickup(): CancellationFixture {
            val orderId = UUID.randomUUID()
            val storeId = UUID.randomUUID()
            val stockId = UUID.randomUUID()
            transactions.executeWithoutResult {
                stockRepository.save(SellableStockEntity(stockId, storeId, 2))
                stockOperations.reserve(
                    ReserveStockCommand(
                        orderId,
                        storeId,
                        listOf(StockRequirement(stockId, 1)),
                        clock.instant().plusSeconds(300),
                        "stock:$orderId",
                    ),
                )
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
                            eventId = UUID.randomUUID(),
                            eventType = "OrderCancelledV1",
                            aggregateId = orderId,
                            aggregateVersion = 8,
                            occurredAt = clock.instant(),
                            payloadVersion = 1,
                            correlationId = "cancel-publication-$orderId",
                            causationId = "customer-cancellation-command:${UUID.randomUUID()}",
                        ),
                    orderId = orderId,
                    cancelledAt = clock.instant(),
                    couponRequired = false,
                    pointsRequired = false,
                    couponPolicy = couponPolicy.toEventPolicy(),
                    pointsPolicy = pointsPolicy.toEventPolicy(),
                )
            compensationOperations.open(
                OpenOrderCompensationCaseCommand(
                    caseId = UUID.randomUUID(),
                    eventId = event.envelope.eventId,
                    orderId = orderId,
                    terminalOrderVersion = event.envelope.aggregateVersion,
                    customerId = UUID.randomUUID(),
                    storeId = storeId,
                    trigger = OrderCompensationTrigger.CUSTOMER_CANCELLATION,
                    sourceReference = "order:$orderId:customer-cancellation:8",
                    couponPolicy = couponPolicy,
                    pointsPolicy = pointsPolicy,
                    paymentRequired = false,
                    couponRequired = false,
                    pointsRequired = false,
                    correlationId = event.envelope.correlationId,
                    now = clock.instant(),
                ),
            )
            transactions.executeWithoutResult { eventPublisher.publishEvent(event) }
            return CancellationFixture(orderId)
        }

        private fun io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicySnapshot.toEventPolicy() =
            BenefitRestorationPolicySnapshotV1(policyVersion, mode.name, compensationValidityDays)

        private fun incompletePublicationListenerId(): String =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT listener_id FROM event_publication WHERE completion_date IS NULL",
                    String::class.java,
                ),
            )

        private fun completedCancellationPublicationCount(): Long =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM event_publication WHERE event_type = ? AND completion_date IS NOT NULL",
                    Long::class.java,
                    OrderCancelledV1::class.java.name,
                ),
            )

        private fun reprocessingReasonCount(reason: String): Long =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM operations_reprocessing_case WHERE reason = ?",
                    Long::class.java,
                    reason,
                ),
            )

        private fun incompletePublicationCount(): Long =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM event_publication WHERE completion_date IS NULL",
                    Long::class.java,
                ),
            )

        private fun incompletePublicationAttemptCount(): Int =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT completion_attempts FROM event_publication WHERE completion_date IS NULL",
                    Int::class.java,
                ),
            )

        private fun incompletePublicationStatus(): String =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT status FROM event_publication WHERE completion_date IS NULL",
                    String::class.java,
                ),
            )

        private fun notificationCount(eventId: UUID): Long =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM notification_delivery WHERE event_id = ?",
                    Long::class.java,
                    eventId,
                ),
            )

        private fun inboxCount(customerId: UUID): Long =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM notification_inbox_item WHERE customer_id = ?",
                    Long::class.java,
                    customerId,
                ),
            )

        private fun eventPublicationManualReviewCount(correlationId: String): Long =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    """
                    SELECT count(*)
                      FROM operations_reprocessing_case
                     WHERE case_type = 'EVENT_PUBLICATION'
                       AND status = 'MANUAL_REVIEW'
                       AND reason = 'EVENT_PUBLICATION_RETRY_EXHAUSTED'
                       AND correlation_id = ?
                    """.trimIndent(),
                    Long::class.java,
                    correlationId,
                ),
            )

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

        private data class CancellationFixture(
            val orderId: UUID,
        )
    }

@TestConfiguration(proxyBeanMethods = false)
internal class PublicationFailureTestConfiguration {
    @Bean
    @Primary
    fun publicationRecoveryTestClock(): PublicationRecoveryTestClock = PublicationRecoveryTestClock()

    @Bean
    fun failingReadyPublicationListener(): FailingReadyPublicationListener = FailingReadyPublicationListener()
}

/**
 * A clock the test moves by hand. Like [PickupSlotPaymentDeadlineTestClock] it reads at microsecond
 * precision, because PostgreSQL rounds a `timestamptz` to microseconds and this test claims due work
 * without advancing the clock first; a finer instant could be stored as later than the clock reports
 * and leave that work permanently not due.
 */
internal class PublicationRecoveryTestClock(
    private val source: () -> Instant = Instant::now,
) : Clock() {
    private val current = AtomicReference(source().truncatedTo(ChronoUnit.MICROS))

    fun reset() {
        current.set(source().truncatedTo(ChronoUnit.MICROS))
    }

    fun advance(duration: Duration) {
        current.updateAndGet { it.plus(duration) }
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current.get()
}

internal open class FailingReadyPublicationListener {
    private val fail = AtomicBoolean(true)
    private val calls = AtomicInteger()

    open fun reset() {
        fail.set(true)
        calls.set(0)
    }

    open fun allowSuccess() {
        fail.set(false)
    }

    open fun callCount(): Int = calls.get()

    @ApplicationModuleListener
    open fun on(event: OrderReadyV1) {
        calls.incrementAndGet()
        if (fail.get()) {
            error("SCRIPTED_PUBLICATION_FAILURE:${event.orderId}")
        }
    }
}

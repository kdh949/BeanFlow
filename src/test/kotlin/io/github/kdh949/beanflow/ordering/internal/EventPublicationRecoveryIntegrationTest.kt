package io.github.kdh949.beanflow.ordering.internal

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
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
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
        private val queries: EventPublicationRecoveryQueries,
        private val manualReview: EventPublicationManualReviewService,
        private val scope: AutomaticPublicationRecoveryScope,
        private val meters: MeterRegistry,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)
        private val log = LoggerFactory.getLogger(EventPublicationRecoveryWorker::class.java) as Logger
        private val captured = ListAppender<ILoggingEvent>()
        private val seededPublicationIds = mutableListOf<UUID>()

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
            captured.start()
            log.addAppender(captured)
        }

        @AfterEach
        fun completeScriptedPublication() {
            seededPublicationIds.forEach { jdbcTemplate.update("DELETE FROM event_publication WHERE id = ?", it) }
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

        @AfterEach
        fun detachLogCapture() {
            log.detachAppender(captured)
            captured.stop()
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
            val exhaustionBefore = exhaustionCount()
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
            repeat(180) { recoveryWorker.runOnce() }
            assertThat(exhaustionCount() - exhaustionBefore).isEqualTo(1.0)
            assertThat(manualReviewLogs()).hasSize(1)
            assertThat(manualReviewLogs().single()).contains("eventId=${event.envelope.eventId}", "publicationId=", "listenerId=")
            assertThat(gauge("beanflow.event.publication.manual.review.pending.count")).isEqualTo(1.0)
            assertThat(gauge("beanflow.event.publication.retry.pending.count")).isZero()
            assertThat(gauge("beanflow.event.publication.pending.count")).isEqualTo(1.0)
            assertThat(queries.findExhaustedIds(100)).isEmpty()

            // A new worker has no memory of previous ticks and must still respect the durable handoff.
            val freshMeters = SimpleMeterRegistry()
            try {
                clock.advance(Duration.ofMinutes(30))
                EventPublicationRecoveryWorker(publications, queries, manualReview, scope, clock, freshMeters, 100).runOnce()
                assertThat(freshMeters.find("beanflow.event.publication.exhaustion.count").counter()).isNull()
                assertThat(freshMeters.get("beanflow.event.publication.manual.review.oldest.age.seconds").gauge().value())
                    .isEqualTo(1800.0)
            } finally {
                freshMeters.close()
            }
            assertThat(manualReviewLogs()).hasSize(1)
            assertThat(failingListener.callCount()).isEqualTo(6)
        }

        @Test
        fun `blocked publications are excluded before the automatic recovery batch limit`() {
            val event = publishFailingReady()
            val publicationId = incompletePublicationId()
            seedBlockedPublications(publicationId, "manual", 125)
            seedBlockedPublications(publicationId, "reserved", 125)
            seedBlockedPublications(publicationId, "not-due", 125)
            clock.advance(Duration.ofSeconds(12))
            failingListener.allowSuccess()

            recoveryWorker.runOnce()

            await("due publication behind blocked rows to complete") {
                jdbcTemplate.queryForObject(
                    "SELECT completion_date IS NOT NULL FROM event_publication WHERE id = ?",
                    Boolean::class.java,
                    publicationId,
                ) == true
            }
            assertThat(failingListener.callCount()).isEqualTo(2)
            assertThat(incompletePublicationCount()).isEqualTo(375)
            assertThat(notificationCount(event.envelope.eventId)).isEqualTo(1)
            assertThat(queries.findExhaustedIds(100)).isEmpty()
            assertThat(manualReviewLogs()).isEmpty()
            assertThat(gauge("beanflow.event.publication.manual.review.pending.count")).isEqualTo(125.0)
            assertThat(gauge("beanflow.event.publication.retry.pending.count")).isEqualTo(125.0)
        }

        @Test
        fun `concurrent workers hand off a publication once`() {
            val event = publishFailingReady()
            jdbcTemplate.update("UPDATE event_publication SET completion_attempts = 6 WHERE completion_date IS NULL")
            val before = exhaustionCount()
            val start = CountDownLatch(1)
            Executors.newFixedThreadPool(2).use { executor ->
                val runs =
                    (1..2).map {
                        executor.submit {
                            start.await()
                            recoveryWorker.runOnce()
                        }
                    }
                start.countDown()
                runs.forEach { it.get(10, TimeUnit.SECONDS) }
            }
            assertThat(eventPublicationManualReviewCount(event.envelope.correlationId)).isEqualTo(1)
            assertThat(exhaustionCount() - before).isEqualTo(1.0)
            assertThat(manualReviewLogs()).hasSize(1)
            assertThat(failingListener.callCount()).isEqualTo(1)
            assertThat(incompletePublicationAttemptCount()).isEqualTo(6)
        }

        @Test
        fun `failed compensation handoff rolls back its case and can be retried`() {
            val fixture = cancellationWithMissingPickup()
            await("pickup failure") { incompletePublicationCount() == 1L }
            jdbcTemplate.update("UPDATE event_publication SET completion_attempts = 6 WHERE completion_date IS NULL")
            val before = requireNotNull(compensationOperations.findByOrderId(fixture.orderId)).steps
            jdbcTemplate.execute(
                """
                CREATE FUNCTION test_reject_publication_handoff() RETURNS trigger LANGUAGE plpgsql AS '
                BEGIN RAISE EXCEPTION ''SCRIPTED_HANDOFF_FAILURE''; END'
                """.trimIndent(),
            )
            try {
                jdbcTemplate.execute(
                    """
                    CREATE TRIGGER test_reject_publication_handoff BEFORE UPDATE ON operations_order_compensation_step
                    FOR EACH ROW WHEN (NEW.state = 'MANUAL_REVIEW') EXECUTE FUNCTION test_reject_publication_handoff()
                    """.trimIndent(),
                )
                assertThatThrownBy { recoveryWorker.runOnce() }.hasStackTraceContaining("SCRIPTED_HANDOFF_FAILURE")
                assertThat(reprocessingReasonCount("EVENT_PUBLICATION_RETRY_EXHAUSTED")).isZero()
                assertThat(requireNotNull(compensationOperations.findByOrderId(fixture.orderId)).steps).isEqualTo(before)
                assertThat(manualReviewLogs()).isEmpty()
                assertThat(queries.findExhaustedIds(100)).hasSize(1)
            } finally {
                jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_reject_publication_handoff ON operations_order_compensation_step")
                jdbcTemplate.execute("DROP FUNCTION test_reject_publication_handoff()")
            }
            recoveryWorker.runOnce()
            assertThat(reprocessingReasonCount("EVENT_PUBLICATION_RETRY_EXHAUSTED")).isEqualTo(1)
            assertThat(manualReviewLogs()).hasSize(1)
            assertThat(
                requireNotNull(compensationOperations.findByOrderId(fixture.orderId))
                    .steps
                    .single { it.type == OrderCompensationStepType.PICKUP }
                    .attemptCount,
            ).isZero()
        }

        @Test
        fun `automatic query scope is cleared on failure and preserves explicit replay`() {
            publishFailingReady()
            assertThatThrownBy { scope.run(clock.instant()) { error("SCRIPTED_SCOPE_FAILURE") } }
                .hasMessage("SCRIPTED_SCOPE_FAILURE")
            assertThat(scope.currentTime()).isNull()
            jdbcTemplate.update("UPDATE event_publication SET completion_attempts = 6 WHERE completion_date IS NULL")
            recoveryWorker.runOnce()
            val id = incompletePublicationId()
            failingListener.allowSuccess()
            publications.resubmitIncompletePublications { it.identifier == id }
            await("explicit exact-publication replay to complete") { incompletePublicationCount() == 0L }
            assertThat(failingListener.callCount()).isEqualTo(2)
        }

        private fun publishFailingReady(): OrderReadyV1 {
            val event =
                OrderReadyV1(
                    envelope =
                        EventEnvelope(
                            UUID.randomUUID(),
                            "OrderReadyV1",
                            UUID.randomUUID(),
                            1,
                            clock.instant(),
                            1,
                            "recovery-test:${UUID.randomUUID()}",
                            "recovery-test",
                        ),
                    orderId = UUID.randomUUID(),
                    customerId = UUID.randomUUID(),
                    storeId = UUID.randomUUID(),
                    readyAt = clock.instant(),
                )
            transactions.executeWithoutResult { eventPublisher.publishEvent(event) }
            await("scripted ready failure") { incompletePublicationCount() == 1L && incompletePublicationStatus() == "FAILED" }
            return event
        }

        private fun seedBlockedPublications(
            template: UUID,
            kind: String,
            count: Int,
        ) {
            repeat(count) {
                val id = UUID.randomUUID()
                seededPublicationIds.add(id)
                jdbcTemplate.update(
                    """
                    INSERT INTO event_publication
                        (id, listener_id, event_type, serialized_event, publication_date, status, completion_attempts, last_resubmission_date)
                    SELECT ?, CASE WHEN ? = 'reserved' THEN 'beanflow.analytics.reserved-test' ELSE listener_id END,
                           event_type, serialized_event, ?, 'FAILED', ?, ? FROM event_publication WHERE id = ?
                    """.trimIndent(),
                    id,
                    kind,
                    Timestamp.from(clock.instant().minusSeconds(7200)),
                    if (kind == "manual") 6 else 2,
                    Timestamp.from(clock.instant()),
                    template,
                )
                if (kind == "manual") {
                    jdbcTemplate.update(
                        """
                        INSERT INTO operations_reprocessing_case
                            (id, case_type, owner_reference, status, reason, correlation_id, created_at, updated_at, version)
                        VALUES (?, 'EVENT_PUBLICATION', ?, 'MANUAL_REVIEW', 'EVENT_PUBLICATION_RETRY_EXHAUSTED',
                                'blocked-fixture', ?, ?, 0)
                        """.trimIndent(),
                        UUID.randomUUID(),
                        "event-publication:$id",
                        Timestamp.from(clock.instant()),
                        Timestamp.from(clock.instant()),
                    )
                }
            }
        }

        private fun incompletePublicationId(): UUID =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT id FROM event_publication WHERE completion_date IS NULL",
                    UUID::class.java,
                ),
            )

        private fun exhaustionCount(): Double =
            meters
                .find("beanflow.event.publication.exhaustion.count")
                .tag("event_type", "orderreadyv1")
                .tag("outcome", "manual_review")
                .counter()
                ?.count() ?: 0.0

        private fun gauge(name: String): Double = meters.get(name).gauge().value()

        private fun manualReviewLogs(): List<String> =
            captured.list
                .map { it.formattedMessage }
                .filter { it.contains("outcome=MANUAL_REVIEW") }

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

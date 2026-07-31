package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.OrderReadyV1
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Import(TestcontainersConfiguration::class, PublicationFailureTestConfiguration::class)
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
        private val jdbcTemplate: JdbcTemplate,
        private val clock: PublicationRecoveryTestClock,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)

        @BeforeEach
        fun cleanDatabase() {
            await("previous publications to complete") { incompletePublicationCount() == 0L }
            jdbcTemplate.execute(
                "TRUNCATE TABLE notification_delivery, operations_reprocessing_case, event_publication CASCADE",
            )
            failingListener.reset()
            clock.reset()
        }

        @AfterEach
        fun completeScriptedPublication() {
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
                    notificationCount(event.envelope.eventId) == 1L
            }
            failingListener.allowSuccess()
            clock.advance(Duration.ofSeconds(12))

            recoveryWorker.runOnce()

            await("failed publication to complete after resubmission") {
                failingListener.callCount() == 2 && incompletePublicationCount() == 0L
            }
            assertThat(notificationCount(event.envelope.eventId)).isEqualTo(1)
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
                    notificationCount(event.envelope.eventId) == 1L
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
        }

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
    }

@TestConfiguration(proxyBeanMethods = false)
internal class PublicationFailureTestConfiguration {
    @Bean
    @Primary
    fun publicationRecoveryTestClock(): PublicationRecoveryTestClock = PublicationRecoveryTestClock()

    @Bean
    fun failingReadyPublicationListener(): FailingReadyPublicationListener = FailingReadyPublicationListener()
}

internal class PublicationRecoveryTestClock : Clock() {
    private val current = AtomicReference(Instant.now())

    fun reset() {
        current.set(Instant.now())
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

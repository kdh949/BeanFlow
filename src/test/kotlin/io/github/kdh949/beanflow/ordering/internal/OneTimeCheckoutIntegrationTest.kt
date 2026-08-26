package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import io.github.kdh949.beanflow.payment.internal.ScriptedTestPaymentGateway
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
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class, PickupSlotPaymentDeadlineTestConfiguration::class)
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest(
    properties = [
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.checkout.frontend-base-url=https://checkout.beanflow.test",
    ],
)
internal class OneTimeCheckoutIntegrationTest
    @Autowired
    constructor(
        private val createOrderUseCase: CreateOrderUseCase,
        private val orderQuoteUseCase: io.github.kdh949.beanflow.ordering.api.OrderQuoteUseCase,
        private val checkoutService: OneTimeCheckoutService,
        private val reconciliationWorker: PaymentReconciliationWorker,
        private val gateway: ScriptedTestPaymentGateway,
        private val testClock: PickupSlotPaymentDeadlineTestClock,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        @BeforeEach
        fun setUp() {
            OrderCreationDatabaseFixture.clean(jdbcTemplate)
            gateway.reset()
            testClock.reset()
        }

        @Test
        fun `prepare stores server authoritative values without a PaymentMethod lookup`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "one-time-prepare-order")

            val prepared = checkoutService.prepare(fixture.customerId, orderId, "one-time-prepare-key")

            assertThat(prepared.state).isEqualTo("READY")
            assertThat(prepared.amount.value).isEqualTo(1_000)
            assertThat(prepared.amount.currency).isEqualTo("KRW")
            assertThat(prepared.providerOrderId).matches("bf_[a-f0-9]{32}")
            assertThat(prepared.customerKey).matches("bf_[A-Za-z0-9_-]{43}")
            assertThat(prepared.successUrl).contains("/app/payments/${prepared.paymentId}/success")
            assertThat(prepared.failUrl).contains("/app/payments/${prepared.paymentId}/fail")
            assertThat(value<UUID>("SELECT id FROM payment_payment WHERE order_id = ?", orderId))
                .isEqualTo(prepared.paymentId)
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT payment_method_id FROM payment_payment WHERE id = ?",
                    UUID::class.java,
                    prepared.paymentId,
                ),
            ).isNull()
            assertThat(value<Long>("SELECT count(*) FROM payment_method")).isZero()

            val replay = checkoutService.prepare(fixture.customerId, orderId, "one-time-prepare-key")
            assertThat(replay).isEqualTo(prepared)
        }

        @Test
        fun `expired order without an attempt materializes reservations and returns reservation expired`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "one-time-expired-without-attempt")
            testClock.set(value<Timestamp>("SELECT reservation_expires_at FROM ordering_order WHERE id = ?", orderId).toInstant())

            assertThatThrownBy {
                checkoutService.prepare(fixture.customerId, orderId, "one-time-expired-without-attempt-key")
            }.isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.RESERVATION_EXPIRED)
            }

            assertExpiredOrderAndReservations(orderId)
            assertThat(value<Long>("SELECT count(*) FROM payment_payment")).isZero()
            assertNoProviderCalls()
        }

        @Test
        fun `expired order with a ready attempt materializes reservations instead of replaying it`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "one-time-expired-with-ready-attempt")
            val prepared =
                checkoutService.prepare(
                    fixture.customerId,
                    orderId,
                    "one-time-expired-with-ready-attempt-key",
                )
            testClock.set(value<Timestamp>("SELECT reservation_expires_at FROM ordering_order WHERE id = ?", orderId).toInstant())

            assertThatThrownBy {
                checkoutService.prepare(
                    fixture.customerId,
                    orderId,
                    "one-time-expired-with-ready-attempt-key",
                )
            }.isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.RESERVATION_EXPIRED)
            }

            assertExpiredOrderAndReservations(orderId)
            assertThat(value<String>("SELECT state FROM payment_one_time_attempt WHERE payment_id = ?", prepared.paymentId))
                .isEqualTo("READY")
            assertNoProviderCalls()
        }

        @Test
        fun `success callback confirms once and exact replay does not call Provider again`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "one-time-confirm-order")
            val prepared = checkoutService.prepare(fixture.customerId, orderId, "one-time-confirm-prepare")
            gateway.enqueueOneTimeConfirmation(
                ProviderPaymentResult.Approved("payment-key-approved", 1_000, "KRW"),
            )
            val request =
                OneTimePaymentConfirmationRequest(
                    paymentKey = "payment-key-approved",
                    orderId = prepared.providerOrderId,
                    amount = 1_000,
                )

            val first = checkoutService.confirm(fixture.customerId, prepared.paymentId, "callback-key-1", request)
            val replay = checkoutService.confirm(fixture.customerId, prepared.paymentId, "callback-key-1", request)
            val orderReference = value<String>("SELECT public_reference FROM ordering_order WHERE id = ?", orderId)

            assertThat(first.status).isEqualTo(200)
            assertThat(replay.status).isEqualTo(200)
            assertThat(replay.replay).isTrue()
            assertPublicPaymentBody(first.body, orderReference)
            assertPublicPaymentBody(replay.body, orderReference)
            assertPublicPaymentBody(checkoutService.current(fixture.customerId, prepared.paymentId).body, orderReference)
            assertThat(gateway.oneTimeConfirmationCalls.get()).isOne()
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("PAID")
            assertThat(value<String>("SELECT state FROM payment_one_time_attempt WHERE payment_id = ?", prepared.paymentId))
                .isEqualTo("APPROVED")
        }

        @Test
        fun `tampered amount and order binding fail before Provider confirmation`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "one-time-tamper-order")
            val prepared = checkoutService.prepare(fixture.customerId, orderId, "one-time-tamper-prepare")

            assertThatThrownBy {
                checkoutService.confirm(
                    fixture.customerId,
                    prepared.paymentId,
                    "callback-tamper-key",
                    OneTimePaymentConfirmationRequest("payment-key-tampered", prepared.providerOrderId, 999),
                )
            }.isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.PAYMENT_CALLBACK_MISMATCH)
            }
            assertThat(gateway.oneTimeConfirmationCalls.get()).isZero()
            assertThat(value<String>("SELECT state FROM payment_one_time_attempt WHERE payment_id = ?", prepared.paymentId))
                .isEqualTo("READY")
        }

        @Test
        fun `concurrent exact callbacks make one Provider call while the other observes processing`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "one-time-concurrent-order")
            val prepared = checkoutService.prepare(fixture.customerId, orderId, "one-time-concurrent-prepare")
            val block = gateway.blockNextApproval()
            gateway.enqueueOneTimeConfirmation(
                ProviderPaymentResult.Approved("payment-key-concurrent", 1_000, "KRW"),
            )
            val request =
                OneTimePaymentConfirmationRequest(
                    paymentKey = "payment-key-concurrent",
                    orderId = prepared.providerOrderId,
                    amount = 1_000,
                )
            val executor = Executors.newFixedThreadPool(2)
            try {
                val first =
                    executor.submit<Int> {
                        checkoutService.confirm(fixture.customerId, prepared.paymentId, "callback-concurrent-1", request).status
                    }
                assertThat(block.awaitStarted()).isTrue()

                val second =
                    checkoutService.confirm(
                        fixture.customerId,
                        prepared.paymentId,
                        "callback-concurrent-2",
                        request,
                    )

                assertThat(second.status).isEqualTo(202)
                assertThat(second.replay).isTrue()
                assertThat(gateway.oneTimeConfirmationCalls.get()).isOne()
                block.release()
                assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(200)
            } finally {
                block.release()
                executor.shutdownNow()
            }
        }

        @Test
        fun `unknown confirmation is recovered by paymentKey lookup without a second confirm`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "one-time-unknown-order")
            val prepared = checkoutService.prepare(fixture.customerId, orderId, "one-time-unknown-prepare")
            gateway.enqueueOneTimeConfirmation(ProviderPaymentResult.Unknown("TIMEOUT"))
            val request =
                OneTimePaymentConfirmationRequest(
                    paymentKey = "payment-key-eventual",
                    orderId = prepared.providerOrderId,
                    amount = 1_000,
                )

            val response = checkoutService.confirm(fixture.customerId, prepared.paymentId, "callback-unknown-1", request)
            val orderReference = value<String>("SELECT public_reference FROM ordering_order WHERE id = ?", orderId)

            assertThat(response.status).isEqualTo(202)
            assertPublicPaymentBody(response.body, orderReference)
            val current = checkoutService.current(fixture.customerId, prepared.paymentId)
            assertThat(current.status).isEqualTo(202)
            assertPublicPaymentBody(current.body, orderReference)
            assertPublicPaymentBody(
                value("SELECT response_body FROM payment_idempotency_record WHERE payment_id = ?", prepared.paymentId),
                orderReference,
            )
            assertThat(value<String>("SELECT state FROM payment_one_time_attempt WHERE payment_id = ?", prepared.paymentId))
                .isEqualTo("UNKNOWN")
            jdbcTemplate.update(
                "UPDATE payment_reconciliation SET next_attempt_at = TIMESTAMPTZ '2000-01-01 00:00:00Z' WHERE payment_id = ?",
                prepared.paymentId,
            )
            gateway.enqueueLookup(
                ProviderPaymentResult.Approved("payment-key-eventual", 1_000, "KRW"),
            )

            assertThat(reconciliationWorker.runOnce()).isEqualTo(1)
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("PAID")
            assertThat(value<String>("SELECT state FROM payment_one_time_attempt WHERE payment_id = ?", prepared.paymentId))
                .isEqualTo("APPROVED")
            assertThat(gateway.oneTimeConfirmationCalls.get()).isOne()
            assertThat(gateway.lookupCalls.get()).isOne()
        }

        private fun pendingOrder(
            fixture: OrderCreationFixture,
            key: String,
        ): UUID {
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            assertThat(createOrderUseCase.create(key, orderQuoteUseCase.attachCurrentQuote(fixture.command())).status)
                .isEqualTo(201)
            return value("SELECT id FROM ordering_order")
        }

        private fun assertExpiredOrderAndReservations(orderId: UUID) {
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("EXPIRED")
            assertThat(value<String>("SELECT state FROM fulfillment_pickup_reservation WHERE order_id = ?", orderId))
                .isEqualTo("EXPIRED")
            assertThat(value<String>("SELECT state FROM inventory_stock_reservation WHERE order_id = ?", orderId))
                .isEqualTo("EXPIRED")
        }

        private fun assertNoProviderCalls() {
            assertThat(gateway.approvalCalls.get()).isZero()
            assertThat(gateway.oneTimeConfirmationCalls.get()).isZero()
            assertThat(gateway.lookupCalls.get()).isZero()
            assertThat(gateway.voidCalls.get()).isZero()
            assertThat(gateway.refundCalls.get()).isZero()
            assertThat(gateway.rejectionRefundCalls.get()).isZero()
            assertThat(gateway.rejectionRefundLookupCalls.get()).isZero()
        }

        private fun assertPublicPaymentBody(
            body: String,
            orderReference: String,
        ) {
            assertThat(body)
                .contains("\"orderReference\":\"$orderReference\"")
                .doesNotContain("\"orderId\"")
        }

        private inline fun <reified T : Any> value(
            sql: String,
            vararg args: Any,
        ): T = jdbcTemplate.queryForObject(sql, T::class.java, *args) as T
    }

package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import io.github.kdh949.beanflow.payment.internal.GatewayRecoveryResult
import io.github.kdh949.beanflow.payment.internal.ScriptedTestPaymentGateway
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, PickupSlotPaymentDeadlineTestConfiguration::class)
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest(
    properties = [
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.checkout.frontend-base-url=https://checkout.beanflow.test",
    ],
)
internal class OneTimeCheckoutIntegrationTest
    @Autowired
    constructor(
        private val createOrderUseCase: CreateOrderUseCase,
        private val orderQuoteUseCase: io.github.kdh949.beanflow.ordering.api.OrderQuoteUseCase,
        private val checkoutService: OneTimeCheckoutService,
        private val publicCheckout: PublicCheckoutService,
        private val mockMvc: MockMvc,
        private val reconciliationWorker: PaymentReconciliationWorker,
        private val acceptanceDeadlineWorker: StoreAcceptanceDeadlineWorker,
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
        fun `public checkout resolves owned order and resumes the same ready attempt without another payment`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "public-checkout-order")
            val reference = value<String>("SELECT public_reference FROM ordering_order WHERE id = ?", orderId)
            assertThat(publicCheckout.get(fixture.customerId, reference).canPay).isTrue()
            assertThat(value<Long>("SELECT count(*) FROM payment_payment")).isZero()
            val actor = jwt().jwt { it.subject(fixture.customerId.toString()) }.authorities(SimpleGrantedAuthority("ROLE_CUSTOMER"))
            val body =
                mockMvc
                    .perform(
                        post(
                            "/api/v1/me/orders/$reference/payment-attempts",
                        ).with(actor).with(csrf()).header("Idempotency-Key", "public-checkout-key"),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.orderReference").value(reference))
                    .andExpect(jsonPath("$.orderId").doesNotExist())
                    .andReturn()
                    .response.contentAsString
            assertThat(body).doesNotContain(orderId.toString())
            val first = publicCheckout.get(fixture.customerId, reference)
            val second = publicCheckout.get(fixture.customerId, reference)
            assertThat(first.readyAttempt).isNotNull()
            assertThat(second.readyAttempt).isEqualTo(first.readyAttempt)
            assertThat(value<Long>("SELECT count(*) FROM payment_payment")).isOne()
            mockMvc
                .perform(get("/api/v1/me/orders/$reference/checkout").with(actor))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.canPay").value(true))
                .andExpect(jsonPath("$.order.orderReference").value(reference))
                .andExpect(jsonPath("$.order.orderId").doesNotExist())
                .andExpect(jsonPath("$.readyAttempt.orderId").doesNotExist())
            assertNoProviderCalls()
        }

        @ParameterizedTest
        @CsvSource(
            "APPROVING,CONFIRMING",
            "UNKNOWN,UNKNOWN",
            "RECONCILING,RECONCILING",
            "MANUAL_REVIEW,MANUAL_REVIEW",
            "APPROVED,APPROVED",
            "FAILED,FAILED",
            "UNKNOWN,READY",
            "READY,UNKNOWN",
        )
        fun `public prepare only replays a currently ready payment`(
            paymentState: String,
            attemptState: String,
        ) {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "public-state-order")
            val reference = value<String>("SELECT public_reference FROM ordering_order WHERE id = ?", orderId)
            val prepared = publicCheckout.prepare(fixture.customerId, reference, "public-state-key")
            jdbcTemplate.update(
                "UPDATE payment_payment SET approval_state = ?, approved_amount_krw = ? WHERE id = ?",
                paymentState,
                if (paymentState == "APPROVED") 1000L else null,
                prepared.paymentId,
            )
            if (attemptState != "READY") {
                jdbcTemplate.update(
                    """
                    UPDATE payment_one_time_attempt SET state = ?, payment_key = ?, callback_payload_hash = ?,
                        claim_token = ?, claimed_at = ? WHERE payment_id = ?
                    """.trimIndent(),
                    attemptState,
                    "test-payment-key",
                    "a".repeat(64),
                    if (attemptState == "CONFIRMING") UUID.randomUUID() else null,
                    if (attemptState == "CONFIRMING") Timestamp.from(testClock.instant()) else null,
                    prepared.paymentId,
                )
            }
            val actor = jwt().jwt { it.subject(fixture.customerId.toString()) }.authorities(SimpleGrantedAuthority("ROLE_CUSTOMER"))
            mockMvc
                .perform(
                    post("/api/v1/me/orders/$reference/payment-attempts")
                        .with(actor)
                        .with(csrf())
                        .header("Idempotency-Key", "public-state-key"),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"))
                .andExpect(jsonPath("$.providerOrderId").doesNotExist())
            assertThat(publicCheckout.get(fixture.customerId, reference).readyAttempt).isNull()
            assertThat(value<Long>("SELECT count(*) FROM payment_payment")).isOne()
            assertNoProviderCalls()
        }

        @Test
        fun `public prepare refuses the same key at and after reservation expiry`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "public-expiry-order")
            val reference = value<String>("SELECT public_reference FROM ordering_order WHERE id = ?", orderId)
            val prepared = publicCheckout.prepare(fixture.customerId, reference, "public-expiry-key")
            testClock.set(prepared.expiresAt)
            val actor = jwt().jwt { it.subject(fixture.customerId.toString()) }.authorities(SimpleGrantedAuthority("ROLE_CUSTOMER"))
            repeat(2) {
                mockMvc
                    .perform(
                        post("/api/v1/me/orders/$reference/payment-attempts")
                            .with(actor)
                            .with(csrf())
                            .header("Idempotency-Key", "public-expiry-key"),
                    ).andExpect(status().isConflict)
                    .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"))
                    .andExpect(jsonPath("$.providerOrderId").doesNotExist())
            }
            assertThat(publicCheckout.get(fixture.customerId, reference).canPay).isFalse()
            assertThat(value<Long>("SELECT count(*) FROM payment_payment")).isOne()
            assertNoProviderCalls()
        }

        @Test
        fun `public checkout never exposes an attempt outside customer ownership`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "public-checkout-ownership")
            val reference = value<String>("SELECT public_reference FROM ordering_order WHERE id = ?", orderId)
            assertThatThrownBy { publicCheckout.get(UUID.randomUUID(), reference) }
                .isInstanceOfSatisfying(DomainFailure::class.java) { assertThat(it.code).isEqualTo(FailureCode.ACCESS_DENIED) }
            assertThatThrownBy { publicCheckout.prepare(UUID.randomUUID(), reference, "public-forbidden-key") }
                .isInstanceOfSatisfying(DomainFailure::class.java) { assertThat(it.code).isEqualTo(FailureCode.ACCESS_DENIED) }
            assertThat(value<Long>("SELECT count(*) FROM payment_payment")).isZero()
        }

        @Test
        fun `public checkout omits uncertain payment attempts and expires an open checkout`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "public-checkout-unknown")
            val reference = value<String>("SELECT public_reference FROM ordering_order WHERE id = ?", orderId)
            val attempt = checkoutService.prepare(fixture.customerId, orderId, "public-unknown-prepare")
            gateway.enqueueOneTimeConfirmation(ProviderPaymentResult.Unknown("TIMEOUT"))
            checkoutService.confirm(
                fixture.customerId,
                attempt.paymentId,
                "public-unknown-confirm",
                OneTimePaymentConfirmationRequest("unknown-key", attempt.providerOrderId, 1000),
            )
            val current = publicCheckout.get(fixture.customerId, reference)
            assertThat(current.paymentState).isEqualTo("UNKNOWN")
            assertThat(current.canPay).isFalse()
            assertThat(current.readyAttempt).isNull()
            assertThat(gateway.oneTimeConfirmationCalls.get()).isOne()
            testClock.set(value<Timestamp>("SELECT reservation_expires_at FROM ordering_order WHERE id = ?", orderId).toInstant())
            val expired = publicCheckout.get(fixture.customerId, reference)
            assertThat(expired.order.status).isEqualTo("EXPIRED")
            assertThat(expired.canPay).isFalse()
            assertThat(expired.readyAttempt).isNull()
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
        fun `immediate checkout keeps resources unreserved and remains payable beyond five minutes`() {
            testClock.set(Instant.parse("2026-08-12T03:00:00Z"))
            val fixture = OrderCreationFixture()
            val orderId = pendingImmediateOrder(fixture, "immediate-six-minute-order")

            assertThat(value<String>("SELECT checkout_mode FROM ordering_order WHERE id = ?", orderId)).isEqualTo("IMMEDIATE")
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT reservation_expires_at FROM ordering_order WHERE id = ?",
                    Timestamp::class.java,
                    orderId,
                ),
            ).isNull()
            assertThat(value<Long>("SELECT count(*) FROM fulfillment_pickup_reservation WHERE order_id = ?", orderId)).isZero()
            assertThat(value<Long>("SELECT count(*) FROM promotion_coupon_reservation WHERE order_id = ?", orderId)).isZero()
            assertThat(value<Long>("SELECT count(*) FROM loyalty_point_reservation WHERE order_id = ?", orderId)).isZero()

            val prepared = checkoutService.prepare(fixture.customerId, orderId, "immediate-six-minute-prepare")
            assertThat(prepared.expiresAt).isEqualTo(Instant.parse("2026-08-12T09:00:00Z"))
            testClock.set(Instant.parse("2026-08-12T03:06:00Z"))
            gateway.enqueueOneTimeConfirmation(ProviderPaymentResult.Approved("immediate-six-minute", 1_000, "KRW"))

            val response =
                checkoutService.confirm(
                    fixture.customerId,
                    prepared.paymentId,
                    "immediate-six-minute-confirm",
                    OneTimePaymentConfirmationRequest("immediate-six-minute", prepared.providerOrderId, 1_000),
                )

            assertThat(response.status).isEqualTo(200)
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("PAID")
            assertThat(value<Long>("SELECT count(*) FROM ordering_order_settlement_input_snapshot WHERE order_id = ?", orderId)).isOne()
        }

        @Test
        fun `first immediate callback at store close is rejected before Provider confirmation`() {
            testClock.set(Instant.parse("2026-08-12T03:00:00Z"))
            val fixture = OrderCreationFixture()
            val orderId = pendingImmediateOrder(fixture, "immediate-closed-callback-order")
            val prepared = checkoutService.prepare(fixture.customerId, orderId, "immediate-closed-callback-prepare")
            val request =
                OneTimePaymentConfirmationRequest(
                    paymentKey = "immediate-closed-callback",
                    orderId = prepared.providerOrderId,
                    amount = 1_000,
                )
            testClock.set(prepared.expiresAt)
            gateway.enqueueOneTimeConfirmation(ProviderPaymentResult.Approved(request.paymentKey, request.amount, "KRW"))

            assertThatThrownBy {
                checkoutService.confirm(
                    fixture.customerId,
                    prepared.paymentId,
                    "immediate-closed-callback-confirm",
                    request,
                )
            }.isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.STORE_CLOSED)
            }

            assertNoProviderCalls()
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("PENDING_PAYMENT")
            assertThat(value<String>("SELECT state FROM payment_one_time_attempt WHERE payment_id = ?", prepared.paymentId))
                .isEqualTo("READY")
        }

        @Test
        fun `approved immediate callback replays after store close without another Provider confirmation`() {
            testClock.set(Instant.parse("2026-08-12T03:00:00Z"))
            val fixture = OrderCreationFixture()
            val orderId = pendingImmediateOrder(fixture, "immediate-approved-replay-order")
            val prepared = checkoutService.prepare(fixture.customerId, orderId, "immediate-approved-replay-prepare")
            val request =
                OneTimePaymentConfirmationRequest(
                    paymentKey = "immediate-approved-replay",
                    orderId = prepared.providerOrderId,
                    amount = 1_000,
                )
            gateway.enqueueOneTimeConfirmation(ProviderPaymentResult.Approved(request.paymentKey, request.amount, "KRW"))
            assertThat(
                checkoutService
                    .confirm(
                        fixture.customerId,
                        prepared.paymentId,
                        "immediate-approved-replay-confirm",
                        request,
                    ).status,
            ).isEqualTo(200)

            testClock.set(prepared.expiresAt)
            val replay =
                checkoutService.confirm(
                    fixture.customerId,
                    prepared.paymentId,
                    "immediate-approved-replay-confirm",
                    request,
                )

            assertThat(replay.status).isEqualTo(200)
            assertThat(replay.replay).isTrue()
            assertThat(gateway.oneTimeConfirmationCalls.get()).isOne()
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("PAID")
        }

        @Test
        fun `manual ordering off blocks a new immediate confirmation without cancelling an existing paid order`() {
            testClock.set(Instant.parse("2026-08-12T03:00:00Z"))
            val fixture = OrderCreationFixture()
            val paidOrderId = pendingImmediateOrder(fixture, "immediate-before-manual-off")
            val paidAttempt = checkoutService.prepare(fixture.customerId, paidOrderId, "immediate-before-manual-off-prepare")
            gateway.enqueueOneTimeConfirmation(ProviderPaymentResult.Approved("before-manual-off", 1_000, "KRW"))
            assertThat(
                checkoutService
                    .confirm(
                        fixture.customerId,
                        paidAttempt.paymentId,
                        "immediate-before-manual-off-confirm",
                        OneTimePaymentConfirmationRequest("before-manual-off", paidAttempt.providerOrderId, 1_000),
                    ).status,
            ).isEqualTo(200)

            val command = fixture.command().copy(pickupSlotId = null)
            assertThat(createOrderUseCase.create("immediate-after-manual-off", orderQuoteUseCase.attachCurrentQuote(command)).status)
                .isEqualTo(201)
            val pendingOrderId =
                requireNotNull(
                    jdbcTemplate.queryForList("SELECT id FROM ordering_order WHERE id <> ?", UUID::class.java, paidOrderId).single(),
                )
            val pendingAttempt =
                checkoutService.prepare(fixture.customerId, pendingOrderId, "immediate-after-manual-off-prepare")
            jdbcTemplate.update("UPDATE merchant_store SET accepting_orders = false WHERE id = ?", fixture.storeId)
            gateway.enqueueOneTimeConfirmation(ProviderPaymentResult.Approved("after-manual-off", 1_000, "KRW"))

            assertThatThrownBy {
                checkoutService.confirm(
                    fixture.customerId,
                    pendingAttempt.paymentId,
                    "immediate-after-manual-off-confirm",
                    OneTimePaymentConfirmationRequest("after-manual-off", pendingAttempt.providerOrderId, 1_000),
                )
            }.isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.STORE_NOT_ACCEPTING_ORDERS)
            }

            assertThat(gateway.oneTimeConfirmationCalls.get()).isOne()
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", paidOrderId)).isEqualTo("PAID")
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", pendingOrderId)).isEqualTo("PENDING_PAYMENT")
            assertThat(value<String>("SELECT state FROM payment_one_time_attempt WHERE payment_id = ?", pendingAttempt.paymentId))
                .isEqualTo("READY")
        }

        @Test
        fun `immediate unpaid draft expires at the immutable store close cutoff`() {
            testClock.set(Instant.parse("2026-08-12T03:00:00Z"))
            val fixture = OrderCreationFixture()
            val orderId = pendingImmediateOrder(fixture, "immediate-store-close-draft")
            val reference = value<String>("SELECT public_reference FROM ordering_order WHERE id = ?", orderId)

            testClock.set(Instant.parse("2026-08-12T09:00:00Z"))
            acceptanceDeadlineWorker.runOnce()

            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("EXPIRED")
            assertThat(value<Long>("SELECT count(*) FROM ordering_order_settlement_input_snapshot WHERE order_id = ?", orderId)).isZero()
            assertThat(
                value<Long>(
                    "SELECT count(*) FROM operations_audit_record WHERE target_id = ? AND action = 'ORDER_EXPIRED_AT_STORE_CLOSE'",
                    orderId,
                ),
            ).isOne()
            assertThat(publicCheckout.get(fixture.customerId, reference).canPay).isFalse()
        }

        @Test
        fun `concurrent startup overdue scans expire one immediate draft exactly once`() {
            testClock.set(Instant.parse("2026-08-12T03:00:00Z"))
            val fixture = OrderCreationFixture()
            val orderId = pendingImmediateOrder(fixture, "immediate-startup-overdue")
            testClock.set(Instant.parse("2026-08-12T09:00:00Z"))
            val barrier = java.util.concurrent.CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val scans =
                    List(2) {
                        executor.submit {
                            barrier.await()
                            acceptanceDeadlineWorker.runStartupOverdueScan()
                        }
                    }
                scans.forEach { it.get(10, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("EXPIRED")
            assertThat(
                value<Long>(
                    "SELECT count(*) FROM operations_audit_record WHERE target_id = ? AND action = 'ORDER_EXPIRED_AT_STORE_CLOSE'",
                    orderId,
                ),
            ).isOne()
        }

        @Test
        fun `two approved immediate payments competing for one coupon leave one paid and recover the loser`() {
            testClock.set(Instant.parse("2026-08-12T03:00:00Z"))
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, priceKrw = 2_000)
            OrderCreationDatabaseFixture.insertOperatingHours(jdbcTemplate, fixture.storeId)
            val couponId = OrderCreationDatabaseFixture.insertFixedCoupon(jdbcTemplate, fixture, 1_000)
            val command = fixture.command(couponIssuanceId = couponId).copy(pickupSlotId = null)

            assertThat(createOrderUseCase.create("immediate-coupon-first", orderQuoteUseCase.attachCurrentQuote(command)).status)
                .isEqualTo(201)
            val firstOrderId = value<UUID>("SELECT id FROM ordering_order")
            assertThat(createOrderUseCase.create("immediate-coupon-second", orderQuoteUseCase.attachCurrentQuote(command)).status)
                .isEqualTo(201)
            val secondOrderId =
                requireNotNull(
                    jdbcTemplate.queryForList("SELECT id FROM ordering_order WHERE id <> ?", UUID::class.java, firstOrderId).single(),
                )

            assertThat(value<Long>("SELECT count(*) FROM promotion_coupon_reservation")).isZero()
            val first = checkoutService.prepare(fixture.customerId, firstOrderId, "immediate-coupon-first-prepare")
            val second = checkoutService.prepare(fixture.customerId, secondOrderId, "immediate-coupon-second-prepare")
            gateway.enqueueOneTimeConfirmation(
                ProviderPaymentResult.Approved("coupon-winner", 1_000, "KRW"),
                ProviderPaymentResult.Approved("coupon-loser", 1_000, "KRW"),
            )

            val executor = Executors.newFixedThreadPool(2)
            val results =
                try {
                    listOf(
                        executor.submit<Pair<UUID, Int>> {
                            firstOrderId to
                                checkoutService
                                    .confirm(
                                        fixture.customerId,
                                        first.paymentId,
                                        "immediate-coupon-first-confirm",
                                        OneTimePaymentConfirmationRequest("coupon-winner", first.providerOrderId, 1_000),
                                    ).status
                        },
                        executor.submit<Pair<UUID, Int>> {
                            secondOrderId to
                                checkoutService
                                    .confirm(
                                        fixture.customerId,
                                        second.paymentId,
                                        "immediate-coupon-second-confirm",
                                        OneTimePaymentConfirmationRequest("coupon-loser", second.providerOrderId, 1_000),
                                    ).status
                        },
                    ).map { it.get(10, TimeUnit.SECONDS) }
                } finally {
                    executor.shutdownNow()
                }
            assertThat(results.map(Pair<UUID, Int>::second)).containsExactlyInAnyOrder(200, 202)
            val winnerOrderId = results.single { it.second == 200 }.first
            val loserOrderId = results.single { it.second == 202 }.first
            val loserPaymentId = if (loserOrderId == firstOrderId) first.paymentId else second.paymentId

            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", winnerOrderId)).isEqualTo("PAID")
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", loserOrderId)).isEqualTo("CANCELLED")
            assertThat(value<String>("SELECT cancellation_cause FROM ordering_order WHERE id = ?", loserOrderId))
                .isEqualTo("PAYMENT_COMMITMENT_FAILED")
            assertThat(value<String>("SELECT payment_commitment_failure_code FROM ordering_order WHERE id = ?", loserOrderId))
                .isEqualTo("COUPON_NOT_AVAILABLE")
            assertThat(value<Long>("SELECT count(*) FROM promotion_coupon_reservation WHERE state = 'USED'")).isOne()
            assertThat(value<Long>("SELECT count(*) FROM fulfillment_pickup_reservation")).isZero()
            assertThatThrownBy {
                jdbcTemplate.update("UPDATE promotion_coupon_reservation SET state = 'RELEASED' WHERE state = 'USED'")
            }.isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)

            gateway.enqueueVoid(GatewayRecoveryResult.Succeeded)
            assertThat(reconciliationWorker.runOnce()).isEqualTo(1)
            assertThat(gateway.voidCalls.get()).isOne()
            assertThat(
                value<String>("SELECT status FROM payment_reconciliation WHERE payment_id = ? AND kind = 'LATE_VOID'", loserPaymentId),
            ).isEqualTo("SUCCEEDED")
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", winnerOrderId)).isEqualTo("PAID")
        }

        @Test
        fun `immediate approval consumes point lots and uses final issuer allocation for settlement`() {
            testClock.set(Instant.parse("2026-08-12T03:00:00Z"))
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, priceKrw = 2_000)
            OrderCreationDatabaseFixture.insertOperatingHours(jdbcTemplate, fixture.storeId)
            val (accountId, lotId) =
                OrderCreationDatabaseFixture.insertPoints(
                    jdbcTemplate,
                    fixture.customerId,
                    500,
                    issuerType = "STORE",
                    issuerReference = fixture.storeId.toString(),
                )
            val command = fixture.command(pointsToUseKrw = 500).copy(pickupSlotId = null)
            val creation = createOrderUseCase.create("immediate-point-approval", orderQuoteUseCase.attachCurrentQuote(command))
            assertThat(creation.status).withFailMessage(creation.body).isEqualTo(201)
            val orderId = value<UUID>("SELECT id FROM ordering_order")
            assertThat(value<Long>("SELECT count(*) FROM loyalty_point_reservation WHERE order_id = ?", orderId)).isZero()

            val prepared = checkoutService.prepare(fixture.customerId, orderId, "immediate-point-prepare")
            gateway.enqueueOneTimeConfirmation(ProviderPaymentResult.Approved("immediate-point-payment", 1_500, "KRW"))
            val response =
                checkoutService.confirm(
                    fixture.customerId,
                    prepared.paymentId,
                    "immediate-point-confirm",
                    OneTimePaymentConfirmationRequest("immediate-point-payment", prepared.providerOrderId, 1_500),
                )

            assertThat(response.status).isEqualTo(200)
            assertThat(value<Long>("SELECT available_points_krw FROM loyalty_point_account WHERE id = ?", accountId)).isZero()
            assertThat(value<Long>("SELECT available_amount_krw FROM loyalty_point_lot WHERE id = ?", lotId)).isZero()
            assertThat(value<String>("SELECT state FROM loyalty_point_reservation WHERE order_id = ?", orderId)).isEqualTo("USED")
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT reservation_expires_at FROM loyalty_point_reservation WHERE order_id = ?",
                    Timestamp::class.java,
                    orderId,
                ),
            ).isNull()
            assertThat(
                value<Long>("SELECT point_cost_krw FROM ordering_order_settlement_input_snapshot WHERE order_id = ?", orderId),
            ).isEqualTo(500)
            assertThat(value<Long>("SELECT count(*) FROM loyalty_point_transaction WHERE type = 'USE'")).isOne()
            assertThatThrownBy {
                jdbcTemplate.update("UPDATE loyalty_point_reservation SET state = 'RELEASED' WHERE order_id = ?", orderId)
            }.isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
        }

        @Test
        fun `approval replaces an expired quoted point lot and settles from the actually used issuer`() {
            testClock.set(Instant.parse("2026-08-12T03:00:00Z"))
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, priceKrw = 2_000)
            OrderCreationDatabaseFixture.insertOperatingHours(jdbcTemplate, fixture.storeId)
            val (accountId, quotedLotId) =
                OrderCreationDatabaseFixture.insertPoints(
                    jdbcTemplate,
                    fixture.customerId,
                    500,
                    issuerType = "PLATFORM",
                    issuerReference = "platform:quoted-lot",
                )
            val usedLotId = UUID.randomUUID()
            jdbcTemplate.update(
                "UPDATE loyalty_point_lot SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2026-08-12T03:01:00Z")),
                quotedLotId,
            )
            jdbcTemplate.update(
                """
                WITH changed_account AS (
                    UPDATE loyalty_point_account
                    SET available_points_krw = available_points_krw + 500
                    WHERE id = ?
                    RETURNING id
                )
                INSERT INTO loyalty_point_lot (
                    id, point_account_id, available_amount_krw, reserved_amount_krw, expires_at,
                    issuer_type, issuer_reference
                )
                SELECT ?, id, 500, 0, ?, 'STORE', ? FROM changed_account
                """.trimIndent(),
                accountId,
                usedLotId,
                Timestamp.from(Instant.parse("2035-01-01T00:00:00Z")),
                fixture.storeId.toString(),
            )
            val command = fixture.command(pointsToUseKrw = 500).copy(pickupSlotId = null)
            val creation = createOrderUseCase.create("immediate-expired-point-lot", orderQuoteUseCase.attachCurrentQuote(command))
            assertThat(creation.status).withFailMessage(creation.body).isEqualTo(201)
            val orderId = value<UUID>("SELECT id FROM ordering_order")

            val prepared = checkoutService.prepare(fixture.customerId, orderId, "immediate-expired-point-lot-prepare")
            testClock.set(Instant.parse("2026-08-12T03:02:00Z"))
            gateway.enqueueOneTimeConfirmation(ProviderPaymentResult.Approved("expired-point-lot-payment", 1_500, "KRW"))
            val response =
                checkoutService.confirm(
                    fixture.customerId,
                    prepared.paymentId,
                    "immediate-expired-point-lot-confirm",
                    OneTimePaymentConfirmationRequest("expired-point-lot-payment", prepared.providerOrderId, 1_500),
                )

            assertThat(response.status).isEqualTo(200)
            assertThat(value<Long>("SELECT available_amount_krw FROM loyalty_point_lot WHERE id = ?", quotedLotId)).isEqualTo(500)
            assertThat(value<Long>("SELECT available_amount_krw FROM loyalty_point_lot WHERE id = ?", usedLotId)).isZero()
            assertThat(
                value<Long>("SELECT point_cost_krw FROM ordering_order_settlement_input_snapshot WHERE order_id = ?", orderId),
            ).isEqualTo(500)
            assertThat(
                value<String>(
                    """
                    SELECT l.issuer_reference
                    FROM loyalty_point_reservation_allocation a
                    JOIN loyalty_point_reservation r ON r.id = a.point_reservation_id
                    JOIN loyalty_point_lot l ON l.id = a.point_lot_id
                    WHERE r.order_id = ?
                    """.trimIndent(),
                    orderId,
                ),
            ).isEqualTo(fixture.storeId.toString())
        }

        @Test
        fun `two approved immediate payments competing for one point balance leave one paid and recover the loser`() {
            testClock.set(Instant.parse("2026-08-12T03:00:00Z"))
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            OrderCreationDatabaseFixture.insertOperatingHours(jdbcTemplate, fixture.storeId)
            val (accountId) = OrderCreationDatabaseFixture.insertPoints(jdbcTemplate, fixture.customerId, 500)
            val command = fixture.command(pointsToUseKrw = 500).copy(pickupSlotId = null)

            assertThat(createOrderUseCase.create("immediate-points-first", orderQuoteUseCase.attachCurrentQuote(command)).status)
                .isEqualTo(201)
            val firstOrderId = value<UUID>("SELECT id FROM ordering_order")
            assertThat(createOrderUseCase.create("immediate-points-second", orderQuoteUseCase.attachCurrentQuote(command)).status)
                .isEqualTo(201)
            val secondOrderId =
                requireNotNull(
                    jdbcTemplate.queryForList("SELECT id FROM ordering_order WHERE id <> ?", UUID::class.java, firstOrderId).single(),
                )

            assertThat(value<Long>("SELECT count(*) FROM loyalty_point_reservation")).isZero()
            val first = checkoutService.prepare(fixture.customerId, firstOrderId, "immediate-points-first-prepare")
            val second = checkoutService.prepare(fixture.customerId, secondOrderId, "immediate-points-second-prepare")
            gateway.enqueueOneTimeConfirmation(
                ProviderPaymentResult.Approved("points-winner", 500, "KRW"),
                ProviderPaymentResult.Approved("points-loser", 500, "KRW"),
            )

            val executor = Executors.newFixedThreadPool(2)
            val results =
                try {
                    listOf(
                        executor.submit<Pair<UUID, Int>> {
                            firstOrderId to
                                checkoutService
                                    .confirm(
                                        fixture.customerId,
                                        first.paymentId,
                                        "immediate-points-first-confirm",
                                        OneTimePaymentConfirmationRequest("points-winner", first.providerOrderId, 500),
                                    ).status
                        },
                        executor.submit<Pair<UUID, Int>> {
                            secondOrderId to
                                checkoutService
                                    .confirm(
                                        fixture.customerId,
                                        second.paymentId,
                                        "immediate-points-second-confirm",
                                        OneTimePaymentConfirmationRequest("points-loser", second.providerOrderId, 500),
                                    ).status
                        },
                    ).map { it.get(10, TimeUnit.SECONDS) }
                } finally {
                    executor.shutdownNow()
                }
            assertThat(results.map(Pair<UUID, Int>::second)).containsExactlyInAnyOrder(200, 202)
            val winnerOrderId = results.single { it.second == 200 }.first
            val loserOrderId = results.single { it.second == 202 }.first
            val loserPaymentId = if (loserOrderId == firstOrderId) first.paymentId else second.paymentId

            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", winnerOrderId)).isEqualTo("PAID")
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", loserOrderId)).isEqualTo("CANCELLED")
            assertThat(value<String>("SELECT payment_commitment_failure_code FROM ordering_order WHERE id = ?", loserOrderId))
                .isEqualTo("POINT_BALANCE_INSUFFICIENT")
            assertThat(value<Long>("SELECT available_points_krw FROM loyalty_point_account WHERE id = ?", accountId)).isZero()
            assertThat(value<Long>("SELECT count(*) FROM loyalty_point_reservation WHERE state = 'USED'")).isOne()
            assertThat(value<Long>("SELECT count(*) FROM loyalty_point_transaction WHERE type = 'USE'")).isOne()

            gateway.enqueueVoid(GatewayRecoveryResult.Succeeded)
            assertThat(reconciliationWorker.runOnce()).isEqualTo(1)
            assertThat(gateway.voidCalls.get()).isOne()
            assertThat(
                value<String>("SELECT status FROM payment_reconciliation WHERE payment_id = ? AND kind = 'LATE_VOID'", loserPaymentId),
            ).isEqualTo("SUCCEEDED")
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", winnerOrderId)).isEqualTo("PAID")
        }

        @Test
        fun `zero payable immediate order commits benefits and paid state atomically without Provider`() {
            testClock.set(Instant.parse("2026-08-12T03:00:00Z"))
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            OrderCreationDatabaseFixture.insertOperatingHours(jdbcTemplate, fixture.storeId)
            OrderCreationDatabaseFixture.insertPoints(jdbcTemplate, fixture.customerId, 1_000)
            val command = fixture.command(pointsToUseKrw = 1_000).copy(pickupSlotId = null)

            val creation =
                createOrderUseCase.create(
                    "immediate-benefit-only",
                    orderQuoteUseCase.attachCurrentQuote(command),
                )
            assertThat(creation.status).withFailMessage(creation.body).isEqualTo(201)
            assertThat(value<Long>("SELECT count(*) FROM ordering_order")).isOne()
            val orderId = value<UUID>("SELECT id FROM ordering_order")
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("PAID")
            assertThat(value<String>("SELECT checkout_mode FROM ordering_order WHERE id = ?", orderId)).isEqualTo("IMMEDIATE")
            assertThat(value<String>("SELECT state FROM loyalty_point_reservation WHERE order_id = ?", orderId)).isEqualTo("USED")
            assertThat(value<Long>("SELECT count(*) FROM ordering_order_settlement_input_snapshot WHERE order_id = ?", orderId)).isOne()
            assertThat(value<Long>("SELECT count(*) FROM fulfillment_pickup_reservation WHERE order_id = ?", orderId)).isZero()
            assertThat(value<Long>("SELECT count(*) FROM payment_payment WHERE order_id = ?", orderId)).isOne()
            assertThat(value<String>("SELECT approval_state FROM payment_payment WHERE order_id = ?", orderId)).isEqualTo("APPROVED")
            assertNoProviderCalls()
        }

        @Test
        fun `settlement snapshot failure rolls back point use and schedules approved payment recovery`() {
            testClock.set(Instant.parse("2026-08-12T03:00:00Z"))
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, priceKrw = 2_000)
            OrderCreationDatabaseFixture.insertOperatingHours(jdbcTemplate, fixture.storeId)
            val (accountId) = OrderCreationDatabaseFixture.insertPoints(jdbcTemplate, fixture.customerId, 500)
            val command = fixture.command(pointsToUseKrw = 500).copy(pickupSlotId = null)
            val creation = createOrderUseCase.create("immediate-snapshot-failure", orderQuoteUseCase.attachCurrentQuote(command))
            assertThat(creation.status).withFailMessage(creation.body).isEqualTo(201)
            val orderId = value<UUID>("SELECT id FROM ordering_order")
            val prepared = checkoutService.prepare(fixture.customerId, orderId, "immediate-snapshot-failure-prepare")
            gateway.enqueueOneTimeConfirmation(ProviderPaymentResult.Approved("snapshot-failure-payment", 1_500, "KRW"))
            jdbcTemplate.execute(
                "ALTER TABLE ordering_order_settlement_input_snapshot " +
                    "ADD CONSTRAINT test_reject_immediate_settlement_snapshot CHECK (order_id <> '$orderId'::uuid)",
            )
            val response =
                try {
                    checkoutService.confirm(
                        fixture.customerId,
                        prepared.paymentId,
                        "immediate-snapshot-failure-confirm",
                        OneTimePaymentConfirmationRequest("snapshot-failure-payment", prepared.providerOrderId, 1_500),
                    )
                } finally {
                    jdbcTemplate.execute(
                        "ALTER TABLE ordering_order_settlement_input_snapshot " +
                            "DROP CONSTRAINT test_reject_immediate_settlement_snapshot",
                    )
                }

            assertThat(response.status).isEqualTo(202)
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("CANCELLED")
            assertThat(value<String>("SELECT payment_commitment_failure_code FROM ordering_order WHERE id = ?", orderId))
                .isEqualTo("SETTLEMENT_INPUT_UNAVAILABLE")
            assertThat(value<Long>("SELECT available_points_krw FROM loyalty_point_account WHERE id = ?", accountId)).isEqualTo(500)
            assertThat(value<Long>("SELECT count(*) FROM loyalty_point_reservation WHERE order_id = ?", orderId)).isZero()
            assertThat(value<Long>("SELECT count(*) FROM ordering_order_settlement_input_snapshot WHERE order_id = ?", orderId)).isZero()
            assertThat(
                value<String>("SELECT status FROM payment_reconciliation WHERE payment_id = ? AND kind = 'LATE_VOID'", prepared.paymentId),
            ).isEqualTo("SCHEDULED")
        }

        @Test
        fun `point shortage after immediate coupon use rolls every benefit back and schedules approved payment recovery`() {
            testClock.set(Instant.parse("2026-08-12T03:00:00Z"))
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, priceKrw = 2_000)
            OrderCreationDatabaseFixture.insertOperatingHours(jdbcTemplate, fixture.storeId)
            val couponId = OrderCreationDatabaseFixture.insertFixedCoupon(jdbcTemplate, fixture, 500)
            val (accountId, lotId) = OrderCreationDatabaseFixture.insertPoints(jdbcTemplate, fixture.customerId, 500)
            val command = fixture.command(couponIssuanceId = couponId, pointsToUseKrw = 500).copy(pickupSlotId = null)
            val creation = createOrderUseCase.create("immediate-partial-benefit-failure", orderQuoteUseCase.attachCurrentQuote(command))
            assertThat(creation.status).withFailMessage(creation.body).isEqualTo(201)
            val orderId = value<UUID>("SELECT id FROM ordering_order")
            val prepared = checkoutService.prepare(fixture.customerId, orderId, "immediate-partial-benefit-prepare")

            jdbcTemplate.update(
                """
                WITH changed_account AS (
                    UPDATE loyalty_point_account SET available_points_krw = 0 WHERE id = ? RETURNING id
                )
                UPDATE loyalty_point_lot SET available_amount_krw = 0
                WHERE id = ? AND point_account_id = (SELECT id FROM changed_account)
                """.trimIndent(),
                accountId,
                lotId,
            )
            gateway.enqueueOneTimeConfirmation(ProviderPaymentResult.Approved("partial-benefit-failure", 1_000, "KRW"))

            val response =
                checkoutService.confirm(
                    fixture.customerId,
                    prepared.paymentId,
                    "immediate-partial-benefit-confirm",
                    OneTimePaymentConfirmationRequest("partial-benefit-failure", prepared.providerOrderId, 1_000),
                )

            assertThat(response.status).isEqualTo(202)
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("CANCELLED")
            assertThat(value<String>("SELECT payment_commitment_failure_code FROM ordering_order WHERE id = ?", orderId))
                .isEqualTo("POINT_BALANCE_INSUFFICIENT")
            assertThat(value<String>("SELECT state FROM promotion_coupon_issuance WHERE id = ?", couponId)).isEqualTo("AVAILABLE")
            assertThat(value<Long>("SELECT count(*) FROM promotion_coupon_reservation WHERE order_id = ?", orderId)).isZero()
            assertThat(value<Long>("SELECT count(*) FROM loyalty_point_reservation WHERE order_id = ?", orderId)).isZero()
            assertThat(value<Long>("SELECT count(*) FROM ordering_order_settlement_input_snapshot WHERE order_id = ?", orderId)).isZero()
            assertThat(
                value<String>("SELECT status FROM payment_reconciliation WHERE payment_id = ? AND kind = 'LATE_VOID'", prepared.paymentId),
            ).isEqualTo("SCHEDULED")
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

        private fun pendingImmediateOrder(
            fixture: OrderCreationFixture,
            key: String,
        ): UUID {
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            OrderCreationDatabaseFixture.insertOperatingHours(jdbcTemplate, fixture.storeId)
            val command = fixture.command().copy(pickupSlotId = null)
            val response = createOrderUseCase.create(key, orderQuoteUseCase.attachCurrentQuote(command))
            assertThat(response.status).withFailMessage(response.body).isEqualTo(201)
            return value("SELECT id FROM ordering_order")
        }

        private fun assertExpiredOrderAndReservations(orderId: UUID) {
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("EXPIRED")
            assertThat(value<String>("SELECT state FROM fulfillment_pickup_reservation WHERE order_id = ?", orderId))
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

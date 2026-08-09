package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.api.ReservationExpiryUseCase
import io.github.kdh949.beanflow.payment.api.PaymentReconciliationOperations
import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import io.github.kdh949.beanflow.payment.internal.ScriptedTestPaymentGateway
import io.github.kdh949.beanflow.payment.internal.PaymentProviderRequestLoader
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Import(TestcontainersConfiguration::class, PickupSlotPaymentDeadlineTestConfiguration::class)
@SpringBootTest(
    properties = [
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
    ],
)
internal class PaymentConfirmationIntegrationTest
    @Autowired
    constructor(
        private val createOrderUseCase: CreateOrderUseCase,
        private val expiryUseCase: ReservationExpiryUseCase,
        private val confirmationService: PaymentConfirmationService,
        private val reconciliationWorker: PaymentReconciliationWorker,
        private val reconciliationOperations: PaymentReconciliationOperations,
        private val gateway: ScriptedTestPaymentGateway,
        private val providerRequestLoader: PaymentProviderRequestLoader,
        private val jdbcTemplate: JdbcTemplate,
        private val testClock: PickupSlotPaymentDeadlineTestClock,
    ) {
        @BeforeEach
        fun setUp() {
            OrderCreationDatabaseFixture.clean(jdbcTemplate)
            gateway.reset()
            testClock.reset()
        }

        @Test
        fun `approval confirms all required owners and order in one transaction`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "payment-approved-order")
            val paymentMethodId = insertPaymentMethod(fixture.customerId)
            gateway.enqueueApproval(
                ProviderPaymentResult.Approved("provider-approved-1", 1_000, "KRW"),
            )

            val response =
                confirmationService.confirm(
                    fixture.customerId,
                    orderId,
                    paymentMethodId,
                    "payment-approved-key",
                )

            assertThat(response.status).isEqualTo(200)
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("PAID")
            assertThat(
                value<String>("SELECT state FROM fulfillment_pickup_reservation WHERE order_id = ?", orderId),
            ).isEqualTo("CONFIRMED")
            assertThat(
                value<String>("SELECT state FROM inventory_stock_reservation WHERE order_id = ?", orderId),
            ).isEqualTo("CONFIRMED")
            assertThat(
                value<String>("SELECT approval_state FROM payment_payment WHERE order_id = ?", orderId),
            ).isEqualTo("APPROVED")
            assertThat(gateway.approvalCalls.get()).isEqualTo(1)
            val paymentId = value<UUID>("SELECT id FROM payment_payment WHERE order_id = ?", orderId)
            assertThat(
                value<Long>("SELECT count(*) FROM payment_provider_request_snapshot WHERE payment_id = ?", paymentId),
            ).isOne()
            val snapshotToken =
                value<String>(
                    "SELECT token_reference FROM payment_provider_request_snapshot WHERE payment_id = ?",
                    paymentId,
                )
            jdbcTemplate.update(
                "UPDATE payment_method SET status = 'DEACTIVATED', token_reference = ? WHERE id = ?",
                "changed-after-payment",
                paymentMethodId,
            )
            assertThat(providerRequestLoader.loadLookup(paymentId).tokenReference).isEqualTo(snapshotToken)

            val replay =
                confirmationService.confirm(
                    fixture.customerId,
                    orderId,
                    paymentMethodId,
                    "payment-approved-key",
                )
            assertThat(replay.status).isEqualTo(200)
            assertThat(replay.replay).isTrue()
            assertThat(gateway.approvalCalls.get()).isEqualTo(1)
        }

        @Test
        fun `provider request loader fails closed when immutable snapshot is missing`() {
            val customerId = UUID.randomUUID()
            val methodId = insertPaymentMethod(customerId)
            val paymentId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO payment_payment (
                    id, order_id, customer_id, payment_method_id, type, approval_state,
                    requested_amount_krw, currency, source_reference, correlation_id,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'EXTERNAL', 'APPROVING', 1000, 'KRW', ?, 'missing-snapshot', now(), now())
                """.trimIndent(),
                paymentId,
                UUID.randomUUID(),
                customerId,
                methodId,
                "missing-snapshot:$paymentId",
            )

            assertThatThrownBy { providerRequestLoader.load(paymentId) }
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
                }
        }

        @Test
        fun `explicit decline cancels order and releases reservations`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "payment-declined-order")
            val paymentMethodId = insertPaymentMethod(fixture.customerId)
            gateway.enqueueApproval(ProviderPaymentResult.Declined("DO_NOT_HONOR"))

            val response =
                confirmationService.confirm(
                    fixture.customerId,
                    orderId,
                    paymentMethodId,
                    "payment-declined-key",
                )

            assertThat(response.status).isEqualTo(422)
            assertThat(response.body).contains(FailureCode.PAYMENT_DECLINED.name)
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("CANCELLED")
            assertThat(
                value<String>("SELECT cancellation_cause FROM ordering_order WHERE id = ?", orderId),
            ).isEqualTo("PAYMENT_DECLINED")
            assertThat(
                value<Instant>("SELECT cancelled_at FROM ordering_order WHERE id = ?", orderId),
            ).isNotNull()
            assertThat(
                value<String>("SELECT state FROM fulfillment_pickup_reservation WHERE order_id = ?", orderId),
            ).isEqualTo("RELEASED")
            assertThat(
                value<String>("SELECT state FROM inventory_stock_reservation WHERE order_id = ?", orderId),
            ).isEqualTo("RELEASED")
            assertThat(
                value<String>("SELECT approval_state FROM payment_payment WHERE order_id = ?", orderId),
            ).isEqualTo("FAILED")
        }

        @Test
        fun `unknown response is replayed without a second approval and lookup can complete it`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "payment-unknown-order")
            val paymentMethodId = insertPaymentMethod(fixture.customerId)
            gateway.enqueueApproval(ProviderPaymentResult.Unknown("TIMEOUT"))

            val first =
                confirmationService.confirm(
                    fixture.customerId,
                    orderId,
                    paymentMethodId,
                    "payment-unknown-key",
                )
            val replay =
                confirmationService.confirm(
                    fixture.customerId,
                    orderId,
                    paymentMethodId,
                    "payment-unknown-key",
                )

            assertThat(first.status).isEqualTo(202)
            assertThat(replay.status).isEqualTo(202)
            assertThat(replay.replay).isTrue()
            assertThat(gateway.approvalCalls.get()).isEqualTo(1)

            jdbcTemplate.update(
                "UPDATE payment_reconciliation SET next_attempt_at = ? WHERE payment_id = " +
                    "(SELECT id FROM payment_payment WHERE order_id = ?)",
                Timestamp.from(Instant.now().minusSeconds(1)),
                orderId,
            )
            gateway.enqueueLookup(
                ProviderPaymentResult.Approved("provider-approved-after-lookup", 1_000, "KRW"),
            )

            assertThat(reconciliationWorker.runOnce()).isEqualTo(1)
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("PAID")
            assertThat(gateway.lookupCalls.get()).isEqualTo(1)
        }

        @Test
        fun `unknown replay at the lease boundary materializes expiry without a new approval`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "payment-unknown-expiry-order")
            val paymentMethodId = insertPaymentMethod(fixture.customerId)
            gateway.enqueueApproval(ProviderPaymentResult.Unknown("TIMEOUT"))
            confirmationService.confirm(
                fixture.customerId,
                orderId,
                paymentMethodId,
                "payment-unknown-expiry-key",
            )
            makeOrderAndApprovalLookupDue(orderId)

            val replay =
                confirmationService.confirm(
                    fixture.customerId,
                    orderId,
                    paymentMethodId,
                    "payment-unknown-expiry-key",
                )

            assertThat(replay.status).isEqualTo(202)
            assertThat(replay.replay).isTrue()
            assertThat(gateway.approvalCalls.get()).isEqualTo(1)
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("EXPIRED")
        }

        @Test
        fun `same key with another payment method is rejected before another Provider call`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "payment-key-conflict-order")
            val firstMethodId = insertPaymentMethod(fixture.customerId)
            val otherMethodId = insertPaymentMethod(fixture.customerId)
            gateway.enqueueApproval(ProviderPaymentResult.Unknown("TIMEOUT"))
            confirmationService.confirm(fixture.customerId, orderId, firstMethodId, "payment-conflict-key")

            assertThatThrownBy {
                confirmationService.confirm(
                    fixture.customerId,
                    orderId,
                    otherMethodId,
                    "payment-conflict-key",
                )
            }.isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.IDEMPOTENCY_KEY_REUSED)
            }
            assertThat(gateway.approvalCalls.get()).isEqualTo(1)
        }

        @Test
        fun `late approval never revives an expired order and is voided once`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "payment-late-order")
            val paymentMethodId = insertPaymentMethod(fixture.customerId)
            gateway.enqueueApproval(ProviderPaymentResult.Unknown("RESPONSE_LOST"))
            assertThat(
                confirmationService
                    .confirm(
                        fixture.customerId,
                        orderId,
                        paymentMethodId,
                        "payment-late-key",
                    ).status,
            ).isEqualTo(202)
            makeOrderAndApprovalLookupDue(orderId)
            gateway.enqueueLookup(
                ProviderPaymentResult.Approved("provider-late-approval", 1_000, "KRW"),
            )

            assertThat(reconciliationWorker.runOnce()).isEqualTo(1)
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("EXPIRED")
            assertThat(
                value<String>("SELECT approval_state FROM payment_payment WHERE order_id = ?", orderId),
            ).isEqualTo("RECONCILING")

            gateway.enqueueVoid(io.github.kdh949.beanflow.payment.internal.GatewayRecoveryResult.Succeeded)
            assertThat(reconciliationWorker.runOnce()).isEqualTo(1)
            assertThat(gateway.voidCalls.get()).isEqualTo(1)
            assertThat(
                value<String>(
                    "SELECT status FROM payment_reconciliation WHERE kind = 'LATE_VOID' " +
                        "AND payment_id = (SELECT id FROM payment_payment WHERE order_id = ?)",
                    orderId,
                ),
            ).isEqualTo("SUCCEEDED")
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("EXPIRED")
        }

        @Test
        fun `Provider approval crossing pickup start expires the order instead of confirming the slot`() {
            val fixture = OrderCreationFixture()
            val pickupStartsAt = Instant.parse("2030-01-01T00:00:00Z")
            testClock.set(pickupStartsAt.minusNanos(1))
            val orderId = pendingOrderForPickupStart(fixture, "payment-pickup-start-approval", pickupStartsAt)
            val paymentMethodId = insertPaymentMethod(fixture.customerId)
            val block = gateway.blockNextApproval()
            gateway.enqueueApproval(ProviderPaymentResult.Approved("provider-after-pickup-start", 1_000, "KRW"))
            val executor = Executors.newSingleThreadExecutor()

            try {
                val confirmation =
                    executor.submit<io.github.kdh949.beanflow.ordering.api.StoredHttpResponse> {
                        confirmationService.confirm(
                            fixture.customerId,
                            orderId,
                            paymentMethodId,
                            "payment-pickup-start-key",
                        )
                    }
                assertThat(block.awaitStarted()).isTrue()
                testClock.set(pickupStartsAt)
                block.release()

                assertThat(confirmation.get(10, TimeUnit.SECONDS).status).isEqualTo(202)
            } finally {
                block.release()
                executor.shutdownNow()
            }

            assertThat(
                value<Instant>("SELECT reservation_expires_at FROM ordering_order WHERE id = ?", orderId),
            ).isEqualTo(pickupStartsAt)
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("EXPIRED")
            assertThat(
                value<String>("SELECT state FROM fulfillment_pickup_reservation WHERE order_id = ?", orderId),
            ).isEqualTo("EXPIRED")
            assertThat(
                value<String>("SELECT approval_state FROM payment_payment WHERE order_id = ?", orderId),
            ).isEqualTo("RECONCILING")
        }

        @Test
        fun `UNKNOWN lookup approval crossing pickup start stays expired and enters late reconciliation`() {
            val fixture = OrderCreationFixture()
            val pickupStartsAt = Instant.parse("2030-01-01T00:00:00Z")
            testClock.set(pickupStartsAt.minusNanos(1))
            val orderId = pendingOrderForPickupStart(fixture, "payment-pickup-start-unknown", pickupStartsAt)
            val paymentMethodId = insertPaymentMethod(fixture.customerId)
            gateway.enqueueApproval(ProviderPaymentResult.Unknown("RESPONSE_LOST"))

            assertThat(
                confirmationService
                    .confirm(
                        fixture.customerId,
                        orderId,
                        paymentMethodId,
                        "payment-pickup-start-unknown-key",
                    ).status,
            ).isEqualTo(202)
            testClock.set(pickupStartsAt)
            jdbcTemplate.update(
                "UPDATE payment_reconciliation SET next_attempt_at = ? WHERE kind = 'APPROVAL_LOOKUP' " +
                    "AND payment_id = (SELECT id FROM payment_payment WHERE order_id = ?)",
                Timestamp.from(pickupStartsAt.minusSeconds(1)),
                orderId,
            )
            gateway.enqueueLookup(ProviderPaymentResult.Approved("provider-late-lookup", 1_000, "KRW"))

            assertThat(reconciliationWorker.runOnce()).isEqualTo(1)
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("EXPIRED")
            assertThat(
                value<String>("SELECT state FROM fulfillment_pickup_reservation WHERE order_id = ?", orderId),
            ).isEqualTo("EXPIRED")
            assertThat(
                value<String>("SELECT approval_state FROM payment_payment WHERE order_id = ?", orderId),
            ).isEqualTo("RECONCILING")
            assertThat(
                value<Long>(
                    "SELECT count(*) FROM payment_reconciliation WHERE kind = 'LATE_VOID' " +
                        "AND payment_id = (SELECT id FROM payment_payment WHERE order_id = ?)",
                    orderId,
                ),
            ).isEqualTo(1)
        }

        @Test
        fun `five unknown lookups create one manual review case and stop automatic calls`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "payment-manual-order")
            val paymentMethodId = insertPaymentMethod(fixture.customerId)
            gateway.enqueueApproval(ProviderPaymentResult.Unknown("TIMEOUT"))
            confirmationService.confirm(fixture.customerId, orderId, paymentMethodId, "payment-manual-key")

            repeat(5) { attempt ->
                makeApprovalLookupDue(orderId)
                gateway.enqueueLookup(ProviderPaymentResult.Unknown("LOOKUP_UNKNOWN_${attempt + 1}"))
                assertThat(reconciliationWorker.runOnce()).isEqualTo(1)
            }

            assertThat(gateway.lookupCalls.get()).isEqualTo(5)
            assertThat(
                value<String>("SELECT approval_state FROM payment_payment WHERE order_id = ?", orderId),
            ).isEqualTo("MANUAL_REVIEW")
            assertThat(
                value<Long>(
                    "SELECT count(*) FROM operations_reprocessing_case WHERE owner_reference = " +
                        "(SELECT source_reference FROM payment_reconciliation WHERE kind = 'APPROVAL_LOOKUP' " +
                        "AND payment_id = (SELECT id FROM payment_payment WHERE order_id = ?))",
                    orderId,
                ),
            ).isEqualTo(1)
            assertThat(reconciliationWorker.runOnce()).isZero()
            assertThat(gateway.lookupCalls.get()).isEqualTo(5)
        }

        @Test
        fun `explicitly unavailable late void falls forward to one full refund`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "payment-late-refund-order")
            val paymentMethodId = insertPaymentMethod(fixture.customerId)
            gateway.enqueueApproval(ProviderPaymentResult.Unknown("RESPONSE_LOST"))
            confirmationService.confirm(
                fixture.customerId,
                orderId,
                paymentMethodId,
                "payment-late-refund-key",
            )
            makeOrderAndApprovalLookupDue(orderId)
            gateway.enqueueLookup(
                ProviderPaymentResult.Approved("provider-late-refund", 1_000, "KRW"),
            )
            assertThat(reconciliationWorker.runOnce()).isEqualTo(1)

            gateway.enqueueVoid(io.github.kdh949.beanflow.payment.internal.GatewayRecoveryResult.Unavailable)
            assertThat(reconciliationWorker.runOnce()).isEqualTo(1)
            gateway.enqueueRefund(io.github.kdh949.beanflow.payment.internal.GatewayRecoveryResult.Succeeded)
            assertThat(reconciliationWorker.runOnce()).isEqualTo(1)

            assertThat(gateway.voidCalls.get()).isEqualTo(1)
            assertThat(gateway.refundCalls.get()).isEqualTo(1)
            assertThat(
                value<String>(
                    "SELECT status FROM payment_reconciliation WHERE kind = 'LATE_REFUND' " +
                        "AND payment_id = (SELECT id FROM payment_payment WHERE order_id = ?)",
                    orderId,
                ),
            ).isEqualTo("SUCCEEDED")
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("EXPIRED")
        }

        @Test
        fun `owner failure rolls back approval state order and earlier confirmations`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "payment-owner-fault-order")
            val paymentMethodId = insertPaymentMethod(fixture.customerId)
            jdbcTemplate.update("DELETE FROM inventory_stock_reservation WHERE order_id = ?", orderId)
            gateway.enqueueApproval(
                ProviderPaymentResult.Approved("provider-owner-fault", 1_000, "KRW"),
            )

            assertThatThrownBy {
                confirmationService.confirm(
                    fixture.customerId,
                    orderId,
                    paymentMethodId,
                    "payment-owner-fault-key",
                )
            }.isInstanceOf(DomainFailure::class.java)

            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId))
                .isEqualTo("PENDING_PAYMENT")
            assertThat(
                value<String>("SELECT state FROM fulfillment_pickup_reservation WHERE order_id = ?", orderId),
            ).isEqualTo("RESERVED")
            assertThat(
                value<String>("SELECT approval_state FROM payment_payment WHERE order_id = ?", orderId),
            ).isEqualTo("APPROVING")
        }

        @Test
        fun `payment method ownership is enforced before Provider call`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "payment-method-owner-order")
            val paymentMethodId = insertPaymentMethod(UUID.randomUUID())

            assertThatThrownBy {
                confirmationService.confirm(
                    fixture.customerId,
                    orderId,
                    paymentMethodId,
                    "payment-method-owner-key",
                )
            }.isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.ACCESS_DENIED)
            }
            assertThat(gateway.approvalCalls.get()).isZero()
            assertThat(value<Long>("SELECT count(*) FROM payment_payment")).isZero()
        }

        @Test
        fun `deactivated payment method is rejected before Provider call`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "payment-method-revoked-order")
            val paymentMethodId = insertPaymentMethod(fixture.customerId, "DEACTIVATED")

            assertThatThrownBy {
                confirmationService.confirm(
                    fixture.customerId,
                    orderId,
                    paymentMethodId,
                    "payment-method-revoked-key",
                )
            }.isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.PAYMENT_METHOD_STATE_CONFLICT)
            }
            assertThat(gateway.approvalCalls.get()).isZero()
        }

        @Test
        fun `Provider transport failure becomes UNKNOWN and keeps lookup recovery`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "payment-transport-order")
            val paymentMethodId = insertPaymentMethod(fixture.customerId)
            gateway.enqueueApprovalFailure("connection reset")

            val response =
                confirmationService.confirm(
                    fixture.customerId,
                    orderId,
                    paymentMethodId,
                    "payment-transport-key",
                )

            assertThat(response.status).isEqualTo(202)
            assertThat(response.body).contains("\"approvalState\":\"UNKNOWN\"")
            assertThat(
                value<String>(
                    "SELECT status FROM payment_reconciliation WHERE payment_id = " +
                        "(SELECT id FROM payment_payment WHERE order_id = ?)",
                    orderId,
                ),
            ).isEqualTo("SCHEDULED")
        }

        @Test
        fun `concurrent requests with the same key execute one Provider approval`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "payment-concurrent-order")
            val paymentMethodId = insertPaymentMethod(fixture.customerId)
            val block = gateway.blockNextApproval()
            gateway.enqueueApproval(
                ProviderPaymentResult.Approved("provider-concurrent-approval", 1_000, "KRW"),
            )
            val executor = Executors.newSingleThreadExecutor()
            val first =
                executor.submit<io.github.kdh949.beanflow.ordering.api.StoredHttpResponse> {
                    confirmationService.confirm(
                        fixture.customerId,
                        orderId,
                        paymentMethodId,
                        "payment-concurrent-key",
                    )
                }

            try {
                assertThat(block.awaitStarted()).isTrue()
                val concurrent =
                    confirmationService.confirm(
                        fixture.customerId,
                        orderId,
                        paymentMethodId,
                        "payment-concurrent-key",
                    )
                assertThat(concurrent.status).isEqualTo(409)
                assertThat(concurrent.body).contains(FailureCode.IDEMPOTENCY_REQUEST_IN_PROGRESS.name)
            } finally {
                block.release()
                executor.shutdown()
            }

            assertThat(first.get(5, TimeUnit.SECONDS).status).isEqualTo(200)
            assertThat(gateway.approvalCalls.get()).isEqualTo(1)
        }

        @Test
        fun `amount mismatch never pays the order and consumes the bounded lookup schedule`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "payment-mismatch-order")
            val paymentMethodId = insertPaymentMethod(fixture.customerId)
            gateway.enqueueApproval(
                ProviderPaymentResult.Approved("provider-mismatch", 999, "KRW"),
            )

            val response =
                confirmationService.confirm(
                    fixture.customerId,
                    orderId,
                    paymentMethodId,
                    "payment-mismatch-key",
                )
            assertThat(response.status).isEqualTo(202)
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId))
                .isEqualTo("PENDING_PAYMENT")

            repeat(5) {
                makeApprovalLookupDue(orderId)
                gateway.enqueueLookup(
                    ProviderPaymentResult.Approved("provider-mismatch", 999, "KRW"),
                )
                assertThat(reconciliationWorker.runOnce()).isEqualTo(1)
            }

            assertThat(
                value<String>("SELECT approval_state FROM payment_payment WHERE order_id = ?", orderId),
            ).isEqualTo("MANUAL_REVIEW")
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId))
                .isEqualTo("PENDING_PAYMENT")
        }

        @Test
        fun `expired claim lease is reclaimed after a worker stops`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "payment-claim-restart-order")
            val paymentMethodId = insertPaymentMethod(fixture.customerId)
            gateway.enqueueApproval(ProviderPaymentResult.Unknown("TIMEOUT"))
            confirmationService.confirm(
                fixture.customerId,
                orderId,
                paymentMethodId,
                "payment-claim-restart-key",
            )
            makeApprovalLookupDue(orderId)

            val abandoned = reconciliationOperations.claimDue(Instant.now(), 1)
            assertThat(abandoned).hasSize(1)
            assertThat(gateway.lookupCalls.get()).isZero()
            jdbcTemplate.update(
                "UPDATE payment_reconciliation SET claim_until = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)),
                abandoned.single().workId,
            )
            gateway.enqueueLookup(
                ProviderPaymentResult.Approved("provider-reclaimed", 1_000, "KRW"),
            )

            assertThat(reconciliationWorker.runOnce()).isEqualTo(1)
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("PAID")
            assertThat(gateway.lookupCalls.get()).isEqualTo(1)
        }

        @Test
        fun `approval lookup racing the exact lease boundary cannot revive the order`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "payment-expiry-race-order")
            val paymentMethodId = insertPaymentMethod(fixture.customerId)
            gateway.enqueueApproval(ProviderPaymentResult.Unknown("TIMEOUT"))
            confirmationService.confirm(
                fixture.customerId,
                orderId,
                paymentMethodId,
                "payment-expiry-race-key",
            )
            makeOrderAndApprovalLookupDue(orderId)
            gateway.enqueueLookup(
                ProviderPaymentResult.Approved("provider-expiry-race", 1_000, "KRW"),
            )
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            val now = Instant.now()
            val expiry =
                executor.submit {
                    barrier.await()
                    expiryUseCase.expireIfDue(orderId, now)
                }
            val payment =
                executor.submit<Int> {
                    barrier.await()
                    reconciliationWorker.runOnce()
                }

            try {
                expiry.get(10, TimeUnit.SECONDS)
                assertThat(payment.get(10, TimeUnit.SECONDS)).isEqualTo(1)
            } finally {
                executor.shutdownNow()
            }

            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("EXPIRED")
            assertThat(
                value<String>("SELECT approval_state FROM payment_payment WHERE order_id = ?", orderId),
            ).isEqualTo("RECONCILING")
            assertThat(
                value<Long>(
                    "SELECT count(*) FROM payment_reconciliation WHERE kind = 'LATE_VOID' " +
                        "AND payment_id = (SELECT id FROM payment_payment WHERE order_id = ?)",
                    orderId,
                ),
            ).isEqualTo(1)
        }

        private fun pendingOrder(
            fixture: OrderCreationFixture,
            key: String,
        ): UUID {
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            assertThat(createOrderUseCase.create(key, fixture.command()).status).isEqualTo(201)
            return value("SELECT id FROM ordering_order")
        }

        private fun pendingOrderForPickupStart(
            fixture: OrderCreationFixture,
            key: String,
            pickupStartsAt: Instant,
        ): UUID {
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            jdbcTemplate.update(
                "UPDATE fulfillment_pickup_slot SET starts_at = ?, ends_at = ? WHERE id = ?",
                Timestamp.from(pickupStartsAt),
                Timestamp.from(pickupStartsAt.plusSeconds(600)),
                fixture.pickupSlotId,
            )
            assertThat(createOrderUseCase.create(key, fixture.command()).status).isEqualTo(201)
            return value("SELECT id FROM ordering_order")
        }

        private fun insertPaymentMethod(
            customerId: UUID,
            status: String = "ACTIVE",
        ): UUID {
            val id = UUID.randomUUID()
            val now = Timestamp.from(Instant.now())
            jdbcTemplate.update(
                """
                INSERT INTO payment_method (
                    id, customer_id, provider, token_reference, display_alias, card_brand,
                    last_four, status, created_at, updated_at, version
                )
                VALUES (?, ?, 'SCRIPTED', ?, 'Test method', 'TEST', '4242', ?, ?, ?, 0)
                """.trimIndent(),
                id,
                customerId,
                "test-token:$id",
                status,
                now,
                now,
            )
            return id
        }

        private fun makeOrderAndApprovalLookupDue(orderId: UUID) {
            val dueAt = Timestamp.from(Instant.now().minusSeconds(1))
            jdbcTemplate.update("UPDATE ordering_order SET reservation_expires_at = ? WHERE id = ?", dueAt, orderId)
            jdbcTemplate.update(
                "UPDATE fulfillment_pickup_reservation SET expires_at = ? WHERE order_id = ?",
                dueAt,
                orderId,
            )
            jdbcTemplate.update(
                "UPDATE inventory_stock_reservation SET expires_at = ? WHERE order_id = ?",
                dueAt,
                orderId,
            )
            makeApprovalLookupDue(orderId)
        }

        private fun makeApprovalLookupDue(orderId: UUID) {
            jdbcTemplate.update(
                "UPDATE payment_reconciliation SET next_attempt_at = ? WHERE kind = 'APPROVAL_LOOKUP' " +
                    "AND payment_id = (SELECT id FROM payment_payment WHERE order_id = ?)",
                Timestamp.from(Instant.now().minusSeconds(1)),
                orderId,
            )
        }

        private inline fun <reified T : Any> value(
            sql: String,
            vararg args: Any,
        ): T = requireNotNull(jdbcTemplate.queryForObject(sql, T::class.java, *args))
    }

@TestConfiguration(proxyBeanMethods = false)
internal class PickupSlotPaymentDeadlineTestConfiguration {
    @Bean
    @Primary
    fun pickupSlotPaymentDeadlineTestClock(): PickupSlotPaymentDeadlineTestClock = PickupSlotPaymentDeadlineTestClock()
}

/**
 * A clock that does not move, so a test controls every instant the application sees.
 *
 * It reads at microsecond precision on purpose. Work is scheduled at `now` and claimed with
 * `nextAttemptAt <= now`, and PostgreSQL rounds a `timestamptz` to microseconds on the way in. A
 * finer instant can therefore come back *later* than the clock still reports, leaving work that was
 * scheduled for right now permanently not due. A moving clock hides this; a fixed one cannot.
 * `Instant.now()` is microsecond-aligned on macOS and nanosecond-precise on Linux, so without this
 * the outcome would depend on the host.
 */
internal class PickupSlotPaymentDeadlineTestClock(
    private val source: () -> Instant = Instant::now,
) : Clock() {
    private val current = AtomicReference(storable(source()))

    fun reset() {
        current.set(storable(source()))
    }

    fun set(now: Instant) {
        current.set(storable(now))
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current.get()

    private companion object {
        fun storable(instant: Instant): Instant = instant.truncatedTo(ChronoUnit.MICROS)
    }
}

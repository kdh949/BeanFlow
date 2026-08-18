package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.internal.OrderCreationDatabaseFixture
import io.github.kdh949.beanflow.payment.api.PostAcceptanceResolutionOrderState
import io.github.kdh949.beanflow.payment.api.PostAcceptanceResolutionPaymentOperations
import io.github.kdh949.beanflow.payment.api.PostAcceptanceResolutionRefundState
import io.github.kdh949.beanflow.payment.api.RequestPostAcceptanceResolutionRefundCommand
import io.github.kdh949.beanflow.payment.internal.domain.PaymentApprovalState
import io.github.kdh949.beanflow.payment.internal.domain.PaymentType
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

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest(
    properties = [
        "beanflow.payment.refund.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
    ],
)
internal class PostAcceptanceResolutionPaymentIntegrationTest
    @Autowired
    constructor(
        private val operations: PostAcceptanceResolutionPaymentOperations,
        private val payments: PaymentJpaRepository,
        private val methods: PaymentMethodJpaRepository,
        private val refunds: RefundJpaRepository,
        private val gateway: ScriptedTestPaymentGateway,
        private val jdbc: JdbcTemplate,
    ) {
        @BeforeEach
        fun cleanDatabase() {
            jdbc.execute(
                """
                TRUNCATE TABLE
                    event_publication,
                    payment_refund,
                    payment_provider_request_snapshot,
                    payment_payment,
                    payment_method,
                    support_post_acceptance_resolution_step,
                    support_post_acceptance_resolution,
                    operations_audit_record,
                    ordering_order,
                    merchant_store
                CASCADE
                """.trimIndent(),
            )
            gateway.reset()
        }

        @Test
        fun `exact source replays while a changed payload is rejected`() {
            val fixture = fixture()
            val command = command(fixture)

            val created = operations.request(command)
            val replayed = operations.request(command)

            assertThat(created.replayed).isFalse()
            assertThat(replayed.refundId).isEqualTo(created.refundId)
            assertThat(replayed.replayed).isTrue()
            assertThatThrownBy { operations.request(command.copy(amountKrw = 6_000)) }
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.IDEMPOTENCY_KEY_REUSED)
                }
        }

        @Test
        fun `provider unknown remains visible and a later lookup can succeed`() {
            val fixture = fixture()
            val refund = operations.request(command(fixture))
            gateway.enqueueRejectionRefund(GatewayRefundResult.Unknown("PROVIDER_TIMEOUT"))

            val unknown = operations.execute(refund.refundId, NOW)

            assertThat(unknown.state).isEqualTo(PostAcceptanceResolutionRefundState.UNKNOWN)
            assertThat(payments.findByOrderId(fixture.orderId)!!.succeededRefundAmountKrw).isZero()

            gateway.enqueueRejectionRefundLookup(GatewayRefundResult.Succeeded("provider-resolution-refund"))
            val succeeded = operations.execute(refund.refundId, NOW.plusSeconds(10))

            assertThat(succeeded.state).isEqualTo(PostAcceptanceResolutionRefundState.SUCCEEDED)
            assertThat(payments.findByOrderId(fixture.orderId)!!.succeededRefundAmountKrw).isEqualTo(7_000)
            assertThat(gateway.rejectionRefundCalls.get()).isEqualTo(1)
            assertThat(gateway.rejectionRefundLookupCalls.get()).isEqualTo(1)
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM event_publication WHERE serialized_event LIKE '%PRE_ACCEPTANCE_CANCELLATION%'",
                    Long::class.java,
                ),
            ).isZero()
        }

        @Test
        fun `sub microsecond request time remains immediately claimable after persistence`() {
            val fixture = fixture()
            val requestAt = NOW.plusNanos(789)
            val refund = operations.request(command(fixture).copy(now = requestAt))
            gateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-sub-microsecond-refund"))

            val succeeded = operations.execute(refund.refundId, requestAt)

            assertThat(succeeded.state).isEqualTo(PostAcceptanceResolutionRefundState.SUCCEEDED)
            assertThat(gateway.rejectionRefundCalls.get()).isOne()
        }

        @Test
        fun `an unresolved provider result blocks another resolution refund`() {
            val fixture = fixture()
            val first = operations.request(command(fixture))
            operations.execute(first.refundId, NOW)
            val secondResolution = UUID.randomUUID()
            insertResolution(secondResolution, fixture.orderId)

            assertThatThrownBy {
                operations.request(
                    command(fixture).copy(
                        resolutionId = secondResolution,
                        sourceReference = "support-resolution:$secondResolution:payment-refund",
                    ),
                )
            }.isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.PAYMENT_REFUND_UNRESOLVED)
            }
        }

        @Test
        fun `cumulative successful refunds cannot exceed the approved cash`() {
            val fixture = fixture()
            val payment = payments.findByOrderId(fixture.orderId)!!
            payment.succeededRefundAmountKrw = 6_000
            payments.saveAndFlush(payment)

            assertThatThrownBy { operations.request(command(fixture).copy(amountKrw = 2_000)) }
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.ORDER_STATE_CONFLICT)
                }
            assertThat(refunds.count()).isZero()
        }

        private fun fixture(): Fixture {
            val orderId = UUID.randomUUID()
            val customerId = UUID.randomUUID()
            val storeId = UUID.randomUUID()
            val paymentId = UUID.randomUUID()
            val methodId = UUID.randomUUID()
            val resolutionId = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                storeId,
            )
            val publicReference = OrderCreationDatabaseFixture.registerPublicReference(jdbc, orderId, NOW)
            jdbc.execute("ALTER TABLE ordering_order DISABLE TRIGGER USER")
            try {
                jdbc.update(
                    """
                    INSERT INTO ordering_order (
                        id, customer_id, store_id, pickup_slot_id,
                        public_reference, pickup_business_date, pickup_sequence,
                        store_name_snapshot, pickup_window_start_snapshot, pickup_window_end_snapshot,
                        state, subtotal_krw,
                        coupon_discount_krw, points_applied_krw, payable_krw, currency,
                        reservation_expires_at, paid_at, accepted_at, preparing_at,
                        created_at, updated_at, version
                    ) VALUES (?, ?, ?, ?, ?, DATE '2026-08-12', ?,
                              'Test Store', ?, ?,
                              'PREPARING', 7000, 0, 0, 7000, 'KRW', NULL, ?, ?, ?, ?, ?, 4)
                    """.trimIndent(),
                    orderId,
                    customerId,
                    storeId,
                    UUID.randomUUID(),
                    publicReference,
                    OrderCreationDatabaseFixture.pickupSequence(orderId),
                    Timestamp.from(NOW.minusSeconds(180)),
                    Timestamp.from(NOW.minusSeconds(120)),
                    Timestamp.from(NOW.minusSeconds(120)),
                    Timestamp.from(NOW.minusSeconds(90)),
                    Timestamp.from(NOW.minusSeconds(60)),
                    Timestamp.from(NOW.minusSeconds(180)),
                    Timestamp.from(NOW),
                )
            } finally {
                jdbc.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
            }
            methods.saveAndFlush(
                PaymentMethodEntity(
                    id = methodId,
                    customerId = customerId,
                    provider = "SCRIPTED",
                    tokenReference = "token",
                    displayAlias = "test",
                    cardBrand = "TEST",
                    lastFour = "1234",
                    status = PaymentMethodStatus.ACTIVE,
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )
            payments.saveAndFlush(
                PaymentEntity(
                    id = paymentId,
                    orderId = orderId,
                    customerId = customerId,
                    paymentMethodId = methodId,
                    type = PaymentType.EXTERNAL,
                    approvalState = PaymentApprovalState.APPROVED,
                    requestedAmountKrw = 7_000,
                    approvedAmountKrw = 7_000,
                    currency = "KRW",
                    sourceReference = "payment:$paymentId",
                    providerTransactionReference = "provider-payment-$paymentId",
                    correlationId = "correlation-$orderId",
                    approvedAt = NOW,
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )
            jdbc.update(
                """
                INSERT INTO payment_provider_request_snapshot (
                    payment_id, payment_method_id, provider, token_reference,
                    provider_customer_reference, created_at
                ) VALUES (?, ?, 'SCRIPTED', 'token', NULL, ?)
                """.trimIndent(),
                paymentId,
                methodId,
                Timestamp.from(NOW),
            )
            insertResolution(resolutionId, orderId)
            return Fixture(orderId, resolutionId, UUID.randomUUID())
        }

        private fun insertResolution(
            resolutionId: UUID,
            orderId: UUID,
        ) {
            jdbc.execute("ALTER TABLE support_post_acceptance_resolution DISABLE TRIGGER ALL")
            try {
                jdbc.update(
                    """
                    INSERT INTO support_post_acceptance_resolution (
                        id, support_case_id, request_id, revision_id, revision_number, action,
                        action_payload_digest, order_id, trigger_order_state, trigger_order_version,
                        requester_actor_id, command_actor_id, executor_actor_id, outcome, responsibility, cash_refund_krw,
                        restore_points, restore_coupon, settlement_adjustment_krw, evidence_digest,
                        idempotency_key, payload_hash, state, created_at, updated_at,
                        retention_expires_at, version
                    ) VALUES (?, ?, ?, ?, 1, 'POST_ACCEPTANCE_RESOLUTION', ?, ?, 'PREPARING', 4,
                              ?, ?, ?, 'FULL_REFUND', 'PLATFORM', 7000, false, false, NULL, ?,
                              ?, ?, 'PLANNED', ?, ?, ?, 0)
                    """.trimIndent(),
                    resolutionId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    DIGEST,
                    orderId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    DIGEST,
                    "resolution-$resolutionId",
                    DIGEST,
                    Timestamp.from(NOW),
                    Timestamp.from(NOW),
                    Timestamp.from(NOW.plusSeconds(90L * 24 * 60 * 60)),
                )
            } finally {
                jdbc.execute("ALTER TABLE support_post_acceptance_resolution ENABLE TRIGGER ALL")
            }
        }

        private fun command(fixture: Fixture) =
            RequestPostAcceptanceResolutionRefundCommand(
                resolutionId = fixture.resolutionId,
                actorId = fixture.actorId,
                orderId = fixture.orderId,
                amountKrw = 7_000,
                orderState = PostAcceptanceResolutionOrderState.PREPARING,
                orderCompletedAt = null,
                orderVersion = 4,
                sourceReference = "support-resolution:${fixture.resolutionId}:payment-refund",
                payloadHash = DIGEST,
                correlationId = "support-resolution-${fixture.resolutionId}",
                now = NOW,
            )

        private data class Fixture(
            val orderId: UUID,
            val resolutionId: UUID,
            val actorId: UUID,
        )

        private companion object {
            val NOW: Instant = Instant.parse("2026-08-12T02:00:00Z")
            const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        }
    }

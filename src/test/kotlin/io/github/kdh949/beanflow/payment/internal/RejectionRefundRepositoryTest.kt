package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.eventing.api.BenefitRestorationPolicySnapshotV1
import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import io.github.kdh949.beanflow.eventing.api.OrderRejectionActorType
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationMode
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicySnapshot
import io.github.kdh949.beanflow.operations.api.OpenOrderCompensationCaseCommand
import io.github.kdh949.beanflow.operations.api.OrderCompensationOperations
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepState
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepType
import io.github.kdh949.beanflow.operations.api.OrderCompensationTrigger
import io.github.kdh949.beanflow.payment.api.CustomerCancellationPaymentOperations
import io.github.kdh949.beanflow.payment.api.PrepareCustomerCancellationPaymentCommand
import io.github.kdh949.beanflow.payment.internal.domain.PaymentApprovalState
import io.github.kdh949.beanflow.payment.internal.domain.PaymentType
import io.github.kdh949.beanflow.payment.internal.domain.RefundClaimMode
import io.github.kdh949.beanflow.payment.internal.domain.RefundState
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "beanflow.payment.refund.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
    ],
)
internal class RejectionRefundRepositoryTest
    @Autowired
    constructor(
        private val refundService: RejectionRefundService,
        private val cancellationPayments: CustomerCancellationPaymentOperations,
        private val refundRepository: RefundJpaRepository,
        private val paymentRepository: PaymentJpaRepository,
        private val paymentMethodRepository: PaymentMethodJpaRepository,
        private val compensationOperations: OrderCompensationOperations,
        private val gateway: ScriptedTestPaymentGateway,
        private val jdbcTemplate: JdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)

        @BeforeEach
        fun cleanDatabase() {
            jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    event_publication,
                    payment_cancellation_recovery_snapshot,
                    payment_refund,
                    payment_reconciliation,
                    payment_idempotency_record,
                    payment_provider_request_snapshot,
                    payment_payment,
                    payment_method,
                    operations_order_compensation_step,
                    operations_order_compensation_case,
                    operations_reprocessing_case,
                    operations_audit_record,
                    ordering_order,
                    merchant_store
                CASCADE
                """.trimIndent(),
            )
            gateway.reset()
        }

        @Test
        fun `customer cancellation success records general and dedicated terminal publications`() {
            val fixture = fixture()
            val snapshot =
                transactions.execute {
                    cancellationPayments.prepare(
                        PrepareCustomerCancellationPaymentCommand(
                            orderId = fixture.orderId,
                            cancellationOrderVersion = 9,
                            customerReasonCode = "CHANGED_MIND",
                            correlationId = fixture.envelope.correlationId,
                            now = NOW,
                        ),
                    )
                }!!
            markCustomerCancelled(fixture.orderId, 9)
            gateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-customer-refund"))

            val claim = refundService.claimDue(NOW, 10).single()
            refundService.recordResult(claim, refundService.callProvider(claim), NOW)

            assertThat(refundRepository.findById(snapshot.refundId!!).orElseThrow().state)
                .isEqualTo(RefundState.SUCCEEDED)
            assertThat(
                jdbcTemplate.queryForObject(
                    "select count(*) from event_publication where listener_id = " +
                        "'beanflow.notification.customer-cancellation-refund-succeeded-v1'",
                    Long::class.java,
                ),
            ).isEqualTo(1)
            val payload =
                jdbcTemplate.queryForObject(
                    "select serialized_event from event_publication where listener_id = " +
                        "'beanflow.notification.customer-cancellation-refund-succeeded-v1'",
                    String::class.java,
                )!!
            assertThat(payload).contains("\"orderAggregateVersion\":9", "\"refundAmountKrw\":7000")
            assertThat(payload).doesNotContain("refundId", "paymentId", "provider", "customerReasonCode")
        }

        @Test
        fun `customer cancellation terminal explicit failure records one delayed publication`() {
            val fixture = fixture()
            transactions.execute {
                cancellationPayments.prepare(
                    PrepareCustomerCancellationPaymentCommand(
                        orderId = fixture.orderId,
                        cancellationOrderVersion = 10,
                        customerReasonCode = "WAIT_TOO_LONG",
                        correlationId = fixture.envelope.correlationId,
                        now = NOW,
                    ),
                )
            }
            markCustomerCancelled(fixture.orderId, 10)
            gateway.enqueueRejectionRefund(GatewayRefundResult.Failed("DECLINED"))

            val claim = refundService.claimDue(NOW, 10).single()
            refundService.recordResult(claim, refundService.callProvider(claim), NOW)

            assertThat(refundRepository.findAll().single().state).isEqualTo(RefundState.FAILED)
            assertThat(
                jdbcTemplate.queryForObject(
                    "select count(*) from event_publication where listener_id = " +
                        "'beanflow.notification.customer-cancellation-refund-delayed-v1'",
                    Long::class.java,
                ),
            ).isEqualTo(1)
            assertThat(
                jdbcTemplate.queryForObject(
                    "select count(*) from event_publication where listener_id = " +
                        "'beanflow.notification.customer-cancellation-refund-succeeded-v1'",
                    Long::class.java,
                ),
            ).isZero()
        }

        @Test
        fun `customer cancellation result detects missing setup and rolls back local success`() {
            val fixture = fixture()
            val snapshot =
                transactions.execute {
                    cancellationPayments.prepare(
                        PrepareCustomerCancellationPaymentCommand(
                            orderId = fixture.orderId,
                            cancellationOrderVersion = 11,
                            customerReasonCode = "CHANGED_MIND",
                            correlationId = fixture.envelope.correlationId,
                            now = NOW,
                        ),
                    )
                }!!
            markCustomerCancelled(fixture.orderId, 11)
            jdbcTemplate.update("DELETE FROM payment_cancellation_recovery_snapshot WHERE order_id = ?", fixture.orderId)
            gateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-customer-refund-missing-setup"))

            val claim = refundService.claimDue(NOW, 10).single()
            val providerResult = refundService.callProvider(claim)

            assertThatThrownBy { refundService.recordResult(claim, providerResult, NOW) }
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
                }
            assertThat(refundRepository.findById(requireNotNull(snapshot.refundId)).orElseThrow().state)
                .isEqualTo(RefundState.PROCESSING)
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM operations_reprocessing_case " +
                        "WHERE case_type = 'PAYMENT_CANCELLATION_SETUP'",
                    Long::class.java,
                ),
            ).isEqualTo(1)
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM operations_audit_record " +
                        "WHERE action = 'PAYMENT_CANCELLATION_SETUP_INCOMPLETE_DETECTED'",
                    Long::class.java,
                ),
            ).isEqualTo(1)
            assertThat(gateway.rejectionRefundCalls.get()).isEqualTo(1)
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM event_publication WHERE listener_id LIKE " +
                        "'beanflow.notification.customer-cancellation-refund-%'",
                    Long::class.java,
                ),
            ).isZero()
        }

        @Test
        fun `customer cancellation result remains retryable when setup evidence cannot persist`() {
            val fixture = fixture()
            val snapshot =
                transactions.execute {
                    cancellationPayments.prepare(
                        PrepareCustomerCancellationPaymentCommand(
                            orderId = fixture.orderId,
                            cancellationOrderVersion = 12,
                            customerReasonCode = "CHANGED_MIND",
                            correlationId = fixture.envelope.correlationId,
                            now = NOW,
                        ),
                    )
                }!!
            markCustomerCancelled(fixture.orderId, 12)
            jdbcTemplate.update("DELETE FROM payment_cancellation_recovery_snapshot WHERE order_id = ?", fixture.orderId)
            jdbcTemplate.execute(
                "ALTER TABLE operations_audit_record ADD CONSTRAINT test_reject_payment_setup_detection " +
                    "CHECK (action <> 'PAYMENT_CANCELLATION_SETUP_INCOMPLETE_DETECTED')",
            )
            gateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-customer-refund-audit-failure"))

            val claim = refundService.claimDue(NOW, 10).single()
            val providerResult = refundService.callProvider(claim)
            try {
                assertThatThrownBy { refundService.recordResult(claim, providerResult, NOW) }
                    .isInstanceOfSatisfying(DomainFailure::class.java) {
                        assertThat(it.code).isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
                    }
            } finally {
                jdbcTemplate.execute(
                    "ALTER TABLE operations_audit_record DROP CONSTRAINT test_reject_payment_setup_detection",
                )
            }

            assertThat(refundRepository.findById(requireNotNull(snapshot.refundId)).orElseThrow().state)
                .isEqualTo(RefundState.PROCESSING)
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM operations_reprocessing_case " +
                        "WHERE case_type = 'PAYMENT_CANCELLATION_SETUP'",
                    Long::class.java,
                ),
            ).isZero()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM operations_audit_record " +
                        "WHERE action = 'PAYMENT_CANCELLATION_SETUP_INCOMPLETE_DETECTED'",
                    Long::class.java,
                ),
            ).isZero()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM event_publication WHERE listener_id LIKE " +
                        "'beanflow.notification.customer-cancellation-refund-%'",
                    Long::class.java,
                ),
            ).isZero()
        }

        @Test
        fun `PaymentRefunded pre-acceptance cancellation omits settlement effect`() {
            val event = fixture()
            refundService.request(event)
            refundService.request(event.copy(envelope = event.envelope.copy(eventId = UUID.randomUUID())))
            gateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-refund-1"))

            val claim = refundService.claimDue(NOW, 10).single()
            assertThat(claim.mode).isEqualTo(RefundClaimMode.REQUEST)
            refundService.recordResult(claim, refundService.callProvider(claim), NOW)

            val refund = refundRepository.findAll().single()
            val payment = paymentRepository.findByOrderId(event.orderId)!!
            val paymentStep =
                compensationOperations
                    .findByOrderId(event.orderId)!!
                    .steps
                    .single { it.type == OrderCompensationStepType.PAYMENT }
            assertThat(refund.state).isEqualTo(RefundState.SUCCEEDED)
            assertThat(refund.succeededAmountKrw).isEqualTo(7_000)
            assertThat(payment.succeededRefundAmountKrw).isEqualTo(7_000)
            assertThat(paymentStep.state).isEqualTo(OrderCompensationStepState.SUCCEEDED)
            assertThat(gateway.rejectionRefundCalls.get()).isEqualTo(1)
            assertThat(refundRepository.count()).isEqualTo(1)
            assertThat(
                jdbcTemplate.queryForObject("select count(*) from event_publication", Long::class.java),
            ).isEqualTo(2)
            val payload =
                requireNotNull(
                    jdbcTemplate.queryForObject(
                        """
                        select serialized_event from event_publication
                         where listener_id = 'beanflow.settlement.payment-refunded-v1'
                        """.trimIndent(),
                        String::class.java,
                    ),
                )
            assertThat(payload).contains("\"completionDisposition\":\"PRE_ACCEPTANCE_CANCELLATION\"")
            assertThat(payload).doesNotContain("settlementRefundEffect", "orderCompletedAt", "settlementItemSource")
        }

        @Test
        fun `unknown refund result is reconciled by lookup without another refund request`() {
            val event = fixture()
            refundService.request(event)
            gateway.enqueueRejectionRefund(GatewayRefundResult.Unknown("ACK_LOST"))

            val requestClaim = refundService.claimDue(NOW, 10).single()
            refundService.recordResult(requestClaim, refundService.callProvider(requestClaim), NOW)
            val unknown = refundRepository.findAll().single()

            assertThat(unknown.state).isEqualTo(RefundState.UNKNOWN)
            assertThat(unknown.nextAttemptAt).isEqualTo(NOW.plusSeconds(10))

            gateway.enqueueRejectionRefundLookup(GatewayRefundResult.Succeeded("provider-refund-2"))
            val lookupClaim = refundService.claimDue(NOW.plusSeconds(10), 10).single()
            assertThat(lookupClaim.mode).isEqualTo(RefundClaimMode.LOOKUP)
            refundService.recordResult(
                lookupClaim,
                refundService.callProvider(lookupClaim),
                NOW.plusSeconds(10),
            )

            assertThat(refundRepository.findAll().single().state).isEqualTo(RefundState.SUCCEEDED)
            assertThat(gateway.rejectionRefundCalls.get()).isEqualTo(1)
            assertThat(gateway.rejectionRefundLookupCalls.get()).isEqualTo(1)
        }

        @Test
        fun `explicit provider failure sends payment compensation to manual review`() {
            val event = fixture()
            refundService.request(event)
            gateway.enqueueRejectionRefund(GatewayRefundResult.Failed("REFUND_DECLINED"))

            val claim = refundService.claimDue(NOW, 10).single()
            refundService.recordResult(claim, refundService.callProvider(claim), NOW)

            val refund = refundRepository.findAll().single()
            val beanCase = compensationOperations.findByOrderId(event.orderId)!!
            val paymentStep = beanCase.steps.single { it.type == OrderCompensationStepType.PAYMENT }
            assertThat(refund.state).isEqualTo(RefundState.FAILED)
            assertThat(paymentStep.state).isEqualTo(OrderCompensationStepState.MANUAL_REVIEW)
        }

        private fun fixture(): OrderRejectedV1 {
            val paymentId = UUID.randomUUID()
            val orderId = UUID.randomUUID()
            val customerId = UUID.randomUUID()
            val storeId = UUID.randomUUID()
            val eventId = UUID.randomUUID()
            val methodId = UUID.randomUUID()
            jdbcTemplate.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                storeId,
            )
            jdbcTemplate.execute("ALTER TABLE ordering_order DISABLE TRIGGER USER")
            try {
                jdbcTemplate.update(
                    """
                    INSERT INTO ordering_order (
                        id, customer_id, store_id, pickup_slot_id, state,
                        subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw,
                        currency, reservation_expires_at, paid_at, acceptance_warning_at,
                        acceptance_deadline_at, created_at, updated_at, version
                    ) VALUES (?, ?, ?, ?, 'PAID', 7000, 0, 0, 7000, 'KRW', NULL,
                              ?, ?, ?, ?, ?, 1)
                    """.trimIndent(),
                    orderId,
                    customerId,
                    storeId,
                    UUID.randomUUID(),
                    Timestamp.from(NOW),
                    Timestamp.from(NOW.plusSeconds(120)),
                    Timestamp.from(NOW.plusSeconds(180)),
                    Timestamp.from(NOW.minusSeconds(60)),
                    Timestamp.from(NOW),
                )
            } finally {
                jdbcTemplate.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
            }
            paymentMethodRepository.save(
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
            paymentRepository.save(
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
            jdbcTemplate.update(
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
            val policy =
                ExpiredBenefitRestorationPolicySnapshot(
                    policyVersion = 1,
                    mode = ExpiredBenefitRestorationMode.COMPENSATE_WITH_NEW_ISSUANCE,
                    compensationValidityDays = 30,
                    effectiveAt = NOW,
                    updatedBy = UUID(0, 0),
                    reason = "INITIAL_DEFAULT",
                )
            compensationOperations.open(
                OpenOrderCompensationCaseCommand(
                    caseId = UUID.randomUUID(),
                    eventId = eventId,
                    orderId = orderId,
                    terminalOrderVersion = 1,
                    customerId = customerId,
                    storeId = storeId,
                    trigger = OrderCompensationTrigger.STORE_REJECTION,
                    sourceReference = "event:$eventId:rejection-case",
                    couponPolicy = policy,
                    pointsPolicy = policy,
                    paymentRequired = true,
                    couponRequired = false,
                    pointsRequired = false,
                    correlationId = "correlation-$orderId",
                    now = NOW,
                ),
            )
            return OrderRejectedV1(
                envelope =
                    EventEnvelope(
                        eventId = eventId,
                        eventType = "OrderRejectedV1",
                        aggregateId = orderId,
                        aggregateVersion = 1,
                        occurredAt = NOW,
                        payloadVersion = 1,
                        correlationId = "correlation-$orderId",
                        causationId = "store-command",
                    ),
                orderId = orderId,
                customerId = customerId,
                storeId = storeId,
                actorId = UUID.randomUUID().toString(),
                actorType = OrderRejectionActorType.STORE_STAFF,
                reason = "OUT_OF_STOCK",
                rejectedAt = NOW,
                couponPolicy = eventPolicy(),
                pointsPolicy = eventPolicy(),
                paymentRequired = true,
                couponRequired = false,
                pointsRequired = false,
            )
        }

        private fun eventPolicy() =
            BenefitRestorationPolicySnapshotV1(
                policyVersionId = 1,
                mode = ExpiredBenefitRestorationMode.COMPENSATE_WITH_NEW_ISSUANCE.name,
                compensationValidityDays = 30,
            )

        private fun markCustomerCancelled(
            orderId: UUID,
            version: Long,
        ) {
            jdbcTemplate.update(
                "UPDATE ordering_order SET state = 'CANCELLED', cancelled_at = ?, " +
                    "cancellation_cause = 'CUSTOMER_REQUEST', cancellation_reason_code = 'CHANGED_MIND', " +
                    "updated_at = ?, version = ? WHERE id = ?",
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                version,
                orderId,
            )
        }

        private companion object {
            val NOW: Instant = Instant.parse("2026-07-30T10:00:00Z")
        }
    }

package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import io.github.kdh949.beanflow.eventing.api.OrderRejectionActorType
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationMode
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicySnapshot
import io.github.kdh949.beanflow.operations.api.OpenRejectionCompensationCaseCommand
import io.github.kdh949.beanflow.operations.api.RejectionCompensationOperations
import io.github.kdh949.beanflow.operations.api.RejectionCompensationStepState
import io.github.kdh949.beanflow.operations.api.RejectionCompensationStepType
import io.github.kdh949.beanflow.payment.internal.domain.PaymentApprovalState
import io.github.kdh949.beanflow.payment.internal.domain.PaymentType
import io.github.kdh949.beanflow.payment.internal.domain.RefundClaimMode
import io.github.kdh949.beanflow.payment.internal.domain.RefundState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
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
        private val refundRepository: RefundJpaRepository,
        private val paymentRepository: PaymentJpaRepository,
        private val paymentMethodRepository: PaymentMethodJpaRepository,
        private val compensationOperations: RejectionCompensationOperations,
        private val gateway: ScriptedTestPaymentGateway,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        @BeforeEach
        fun cleanDatabase() {
            jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    payment_refund,
                    payment_reconciliation,
                    payment_idempotency_record,
                    payment_payment,
                    payment_method,
                    operations_rejection_compensation_step,
                    operations_rejection_compensation_case
                CASCADE
                """.trimIndent(),
            )
            gateway.reset()
        }

        @Test
        fun `rejection requests and completes exactly one full refund`() {
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
                    .single { it.type == RejectionCompensationStepType.PAYMENT }
            assertThat(refund.state).isEqualTo(RefundState.SUCCEEDED)
            assertThat(refund.succeededAmountKrw).isEqualTo(7_000)
            assertThat(payment.succeededRefundAmountKrw).isEqualTo(7_000)
            assertThat(paymentStep.state).isEqualTo(RejectionCompensationStepState.SUCCEEDED)
            assertThat(gateway.rejectionRefundCalls.get()).isEqualTo(1)
            assertThat(refundRepository.count()).isEqualTo(1)
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
            val paymentStep = beanCase.steps.single { it.type == RejectionCompensationStepType.PAYMENT }
            assertThat(refund.state).isEqualTo(RefundState.FAILED)
            assertThat(paymentStep.state).isEqualTo(RejectionCompensationStepState.MANUAL_REVIEW)
        }

        private fun fixture(): OrderRejectedV1 {
            val paymentId = UUID.randomUUID()
            val orderId = UUID.randomUUID()
            val customerId = UUID.randomUUID()
            val storeId = UUID.randomUUID()
            val eventId = UUID.randomUUID()
            val methodId = UUID.randomUUID()
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
                OpenRejectionCompensationCaseCommand(
                    caseId = UUID.randomUUID(),
                    eventId = eventId,
                    orderId = orderId,
                    customerId = customerId,
                    storeId = storeId,
                    sourceReference = "event:$eventId:rejection-case",
                    policy = policy,
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
                policyVersion = 1,
                policyMode = ExpiredBenefitRestorationMode.COMPENSATE_WITH_NEW_ISSUANCE.name,
                policyValidityDays = 30,
                paymentRequired = true,
                couponRequired = false,
                pointsRequired = false,
            )
        }

        private companion object {
            val NOW: Instant = Instant.parse("2026-07-30T10:00:00Z")
        }
    }

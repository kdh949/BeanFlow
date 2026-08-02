package io.github.kdh949.beanflow.eventing.internal

import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.PaymentRefundedV1
import io.github.kdh949.beanflow.eventing.api.RefundCompletionDisposition
import io.github.kdh949.beanflow.eventing.api.SettlementRefundEffect
import io.github.kdh949.beanflow.shared.api.DomainFailure
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal class PaymentRefundedEventContractTest {
    private val validator = FinancialEventValidator()
    private val objectMapper = ObjectMapper()

    @Test
    fun `completed refund serializes the exact V1 immutable payload`() {
        val event = completedEvent()
        val expected =
            requireNotNull(javaClass.getResourceAsStream("/contracts/payment-refunded-v1.json")).use {
                objectMapper.readTree(it)
            }

        validator.validate(event)

        assertThat(objectMapper.readTree(objectMapper.writeValueAsBytes(event))).isEqualTo(expected)
    }

    @Test
    fun `pre-completion refund keeps effect and omits fields that do not exist yet`() {
        val event =
            completedEvent().copy(
                completionDisposition = RefundCompletionDisposition.PRE_COMPLETION_ORDER,
                orderCompletedAt = null,
                settlementDate = null,
                settlementItemSource = null,
            )

        validator.validate(event)
        val json = objectMapper.readTree(objectMapper.writeValueAsBytes(event))

        assertThat(json.has("orderCompletedAt")).isFalse()
        assertThat(json.has("settlementDate")).isFalse()
        assertThat(json.has("settlementItemSource")).isFalse()
        assertThat(json.has("settlementRefundEffect")).isTrue()
    }

    @Test
    fun `pre-acceptance cancellation omits completion fields and settlement effect`() {
        val event =
            completedEvent().copy(
                completionDisposition = RefundCompletionDisposition.PRE_ACCEPTANCE_CANCELLATION,
                orderCompletedAt = null,
                settlementDate = null,
                settlementItemSource = null,
                settlementRefundEffect = null,
            )

        validator.validate(event)
        val json = objectMapper.readTree(objectMapper.writeValueAsBytes(event))

        assertThat(json.has("orderCompletedAt")).isFalse()
        assertThat(json.has("settlementDate")).isFalse()
        assertThat(json.has("settlementItemSource")).isFalse()
        assertThat(json.has("settlementRefundEffect")).isFalse()
    }

    @Test
    fun `branch mismatch and non-tied signed effect are rejected`() {
        assertThatThrownBy {
            validator.validate(
                completedEvent().copy(
                    completionDisposition = RefundCompletionDisposition.PRE_ACCEPTANCE_CANCELLATION,
                ),
            )
        }.isInstanceOf(DomainFailure::class.java)

        assertThatThrownBy {
            validator.validate(
                completedEvent().copy(
                    settlementRefundEffect =
                        SettlementRefundEffect(
                            grossPaidDeltaKrw = -1_000,
                            feeDeltaKrw = -50,
                            benefitCostDeltaKrw = -300,
                            netSettlementDeltaKrw = -651,
                        ),
                ),
            )
        }.isInstanceOf(DomainFailure::class.java)
    }

    private fun completedEvent(): PaymentRefundedV1 {
        val refundId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val orderId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val succeededAt = Instant.parse("2026-08-02T00:30:00Z")
        val completedAt = Instant.parse("2026-08-01T15:30:00Z")
        return PaymentRefundedV1(
            envelope =
                EventEnvelope(
                    eventId = UUID.fromString("44444444-4444-4444-4444-444444444444"),
                    eventType = "PaymentRefundedV1",
                    aggregateId = refundId,
                    aggregateVersion = 3,
                    occurredAt = succeededAt,
                    payloadVersion = 1,
                    correlationId = "correlation:refund-fixture",
                    causationId = "refund:$refundId:succeeded",
                ),
            refundId = refundId,
            refundSource = "partial-refund:fixture",
            orderId = orderId,
            customerId = UUID.fromString("33333333-3333-3333-3333-333333333333"),
            refundSucceededAt = succeededAt,
            currency = "KRW",
            cashRefundedKrw = 500,
            completionDisposition = RefundCompletionDisposition.COMPLETED_ORDER,
            orderCompletedAt = completedAt,
            settlementDate = LocalDate.parse("2026-08-02"),
            settlementItemSource = "order:$orderId:completed:7",
            settlementRefundEffect =
                SettlementRefundEffect(
                    grossPaidDeltaKrw = -1_000,
                    feeDeltaKrw = -50,
                    benefitCostDeltaKrw = -300,
                    netSettlementDeltaKrw = -650,
                ),
        )
    }
}

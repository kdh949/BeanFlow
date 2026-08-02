package io.github.kdh949.beanflow.eventing.internal

import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.PointsAccruedV1
import io.github.kdh949.beanflow.shared.api.DomainFailure
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

internal class PointsAccruedEventContractTest {
    private val validator = FinancialEventValidator()
    private val objectMapper = ObjectMapper()

    @Test
    fun `points accrued serializes the exact V1 immutable payload`() {
        val event = event()
        val expected =
            requireNotNull(javaClass.getResourceAsStream("/contracts/points-accrued-v1.json")).use {
                objectMapper.readTree(it)
            }

        validator.validate(event)

        assertThat(objectMapper.readTree(objectMapper.writeValueAsBytes(event))).isEqualTo(expected)
    }

    @Test
    fun `changed transaction source without matching logical causation is rejected`() {
        assertThatThrownBy {
            validator.validate(event().copy(pointTransactionSource = "point:changed"))
        }.isInstanceOf(DomainFailure::class.java)
    }

    private fun event(): PointsAccruedV1 {
        val source = "order:11111111-1111-1111-1111-111111111111:completion-accrual:transaction"
        val completedAt = Instant.parse("2026-08-01T15:30:00Z")
        return PointsAccruedV1(
            envelope =
                EventEnvelope(
                    eventId = UUID.fromString("55555555-5555-5555-5555-555555555555"),
                    eventType = "PointsAccruedV1",
                    aggregateId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
                    aggregateVersion = 8,
                    occurredAt = completedAt,
                    payloadVersion = 1,
                    correlationId = "correlation:accrual-fixture",
                    causationId = "point-transaction:$source",
                ),
            pointTransactionSource = source,
            orderCompletionSource = "order:11111111-1111-1111-1111-111111111111:completed:5",
            orderId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            orderCompletedAt = completedAt,
            amountKrw = 125,
            currency = "KRW",
        )
    }
}

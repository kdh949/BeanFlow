package io.github.kdh949.beanflow.eventing.internal

import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.PointRestorationDisposition
import io.github.kdh949.beanflow.eventing.api.PointsRestoredV1
import io.github.kdh949.beanflow.shared.api.DomainFailure
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

internal class PointsRestoredEventContractTest {
    private val validator = FinancialEventValidator()
    private val objectMapper = ObjectMapper()

    @Test
    fun `points restored serializes the exact V1 immutable payload including nullable completion`() {
        val event = event()
        val expected =
            requireNotNull(javaClass.getResourceAsStream("/contracts/points-restored-v1.json")).use {
                objectMapper.readTree(it)
            }

        validator.validate(event)

        assertThat(objectMapper.readTree(objectMapper.writeValueAsBytes(event))).isEqualTo(expected)
        assertThat(expected.has("orderCompletedAt")).isTrue()
        assertThat(expected["orderCompletedAt"].isNull).isTrue()
    }

    @Test
    fun `negative restored amount and changed source lineage are rejected`() {
        assertThatThrownBy {
            validator.validate(event().copy(amountKrw = -1))
        }.isInstanceOf(DomainFailure::class.java)
        assertThatThrownBy {
            validator.validate(event().copy(pointTransactionSource = "point:changed"))
        }.isInstanceOf(DomainFailure::class.java)
    }

    private fun event(): PointsRestoredV1 {
        val source = "partial-refund:fixture:line:1:allocation:2:transaction"
        val succeededAt = Instant.parse("2026-08-02T00:30:00Z")
        return PointsRestoredV1(
            envelope =
                EventEnvelope(
                    eventId = UUID.fromString("66666666-6666-6666-6666-666666666666"),
                    eventType = "PointsRestoredV1",
                    aggregateId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
                    aggregateVersion = 9,
                    occurredAt = succeededAt,
                    payloadVersion = 1,
                    correlationId = "correlation:restoration-fixture",
                    causationId = "point-transaction:$source",
                ),
            pointTransactionSource = source,
            refundSource = "partial-refund:fixture",
            orderId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            refundSucceededAt = succeededAt,
            orderCompletedAt = null,
            amountKrw = 300,
            currency = "KRW",
            restorationDisposition = PointRestorationDisposition.COMPENSATION,
        )
    }
}

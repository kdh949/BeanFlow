package io.github.kdh949.beanflow.eventing.internal

import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.PointsAdjustedV1
import io.github.kdh949.beanflow.shared.api.DomainFailure
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

internal class PointsAdjustedEventContractTest {
    private val validator = FinancialEventValidator()
    private val objectMapper = ObjectMapper()

    @Test
    fun `credit serializes the exact V1 analytics handoff without sensitive command fields`() {
        val event = creditEvent()
        val expected =
            requireNotNull(javaClass.getResourceAsStream("/contracts/points-adjusted-v1.json")).use {
                objectMapper.readTree(it)
            }

        validator.validate(event)
        val serialized = objectMapper.writeValueAsBytes(event)

        assertThat(objectMapper.readTree(serialized)).isEqualTo(expected)
        assertThat(String(serialized))
            .doesNotContain("actor", "evidence", "idempotency", "issuerReference")
    }

    @Test
    fun `debit omits issuer and keeps the signed amount`() {
        val event = creditEvent().copy(amountKrw = -125, issuerType = null)

        validator.validate(event)

        assertThat(objectMapper.writeValueAsString(event))
            .contains("\"amountKrw\":-125")
            .doesNotContain("issuerType")
    }

    @Test
    fun `mismatched source version and direction payloads are rejected`() {
        val event = creditEvent()
        listOf(
            event.copy(
                envelope = event.envelope.copy(causationId = "point-adjustment:changed"),
            ),
            event.copy(envelope = event.envelope.copy(payloadVersion = 2)),
            event.copy(amountKrw = -125),
            event.copy(issuerType = null),
        ).forEach { invalid ->
            assertThatThrownBy { validator.validate(invalid) }
                .isInstanceOf(DomainFailure::class.java)
        }
    }

    private fun creditEvent(): PointsAdjustedV1 {
        val source = "11111111-1111-1111-1111-111111111111"
        val accountId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        return PointsAdjustedV1(
            envelope =
                EventEnvelope(
                    eventId = UUID.fromString("55555555-5555-5555-5555-555555555555"),
                    eventType = "PointsAdjustedV1",
                    aggregateId = accountId,
                    aggregateVersion = 9,
                    occurredAt = Instant.parse("2026-08-04T00:00:00Z"),
                    payloadVersion = 1,
                    correlationId = "correlation:point-adjustment-fixture",
                    causationId = "point-adjustment:$source",
                ),
            adjustmentSource = source,
            accountId = accountId,
            amountKrw = 125,
            issuerType = "STORE",
        )
    }
}

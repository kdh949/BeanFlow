package io.github.kdh949.beanflow.eventing.internal

import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.SettlementItemCreatedV1
import io.github.kdh949.beanflow.shared.api.DomainFailure
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal class SettlementItemCreatedEventContractTest {
    private val validator = FinancialEventValidator()
    private val objectMapper = ObjectMapper()

    @Test
    fun `settlement item created serializes the exact V1 immutable payload`() {
        val event = event()
        val expected =
            requireNotNull(javaClass.getResourceAsStream("/contracts/settlement-item-created-v1.json")).use {
                objectMapper.readTree(it)
            }

        validator.validate(event)

        assertThat(objectMapper.readTree(objectMapper.writeValueAsBytes(event))).isEqualTo(expected)
    }

    @Test
    fun `changed source lineage and amount tie-out are rejected`() {
        assertThatThrownBy {
            validator.validate(event().copy(itemSource = "order:changed:completed:7"))
        }.isInstanceOf(DomainFailure::class.java)
        assertThatThrownBy {
            validator.validate(event().copy(netSettlementKrw = 801))
        }.isInstanceOf(DomainFailure::class.java)
    }

    private fun event(): SettlementItemCreatedV1 {
        val itemId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val itemSource = "order:44444444-4444-4444-4444-444444444444:completed:7"
        return SettlementItemCreatedV1(
            envelope =
                EventEnvelope(
                    eventId = UUID.fromString("55555555-5555-5555-5555-555555555555"),
                    eventType = "SettlementItemCreatedV1",
                    aggregateId = itemId,
                    aggregateVersion = 0,
                    occurredAt = Instant.parse("2026-08-03T01:03:00Z"),
                    payloadVersion = 1,
                    correlationId = "correlation:settlement-item-fixture",
                    causationId = "settlement-item:$itemSource",
                ),
            settlementItemId = itemId,
            settlementBatchId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
            itemSource = itemSource,
            orderId = UUID.fromString("44444444-4444-4444-4444-444444444444"),
            storeId = UUID.fromString("33333333-3333-3333-3333-333333333333"),
            completedAt = Instant.parse("2026-08-03T01:02:03Z"),
            settlementDate = LocalDate.parse("2026-08-03"),
            currency = "KRW",
            grossPaidKrw = 1_000,
            feeKrw = 50,
            benefitCostKrw = 150,
            netSettlementKrw = 800,
        )
    }
}

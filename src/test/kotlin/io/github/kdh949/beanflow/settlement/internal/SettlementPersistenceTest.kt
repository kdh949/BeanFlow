package io.github.kdh949.beanflow.settlement.internal

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal class SettlementPersistenceTest {
    @Test
    fun `Plan 20 batch accepts only matching store and date while open`() {
        val storeId = UUID.randomUUID()
        val date = LocalDate.of(2026, 8, 3)
        val batch =
            SettlementBatchEntity(
                id = UUID.randomUUID(),
                storeId = storeId,
                settlementDate = date,
                createdAt = Instant.parse("2026-08-03T00:00:00Z"),
            )

        assertThatCode { batch.requireAcceptingItems(storeId, date) }.doesNotThrowAnyException()
        assertThatThrownBy { batch.requireAcceptingItems(UUID.randomUUID(), date) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { batch.requireAcceptingItems(storeId, date.plusDays(1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            SettlementBatchEntity(
                id = UUID.randomUUID(),
                storeId = storeId,
                settlementDate = date,
                state = SettlementBatchState.CALCULATED,
                createdAt = Instant.parse("2026-08-03T00:00:00Z"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `item constructor rejects financial snapshots that do not tie out`() {
        assertThatCode { item() }.doesNotThrowAnyException()
        assertThatThrownBy { item(benefitCostKrw = 149) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { item(netSettlementKrw = 801) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { item(feeRateBps = 10_001) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { item(currency = "USD") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { item(settlementDate = LocalDate.of(2026, 8, 2)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun item(
        currency: String = "KRW",
        feeRateBps: Int = 500,
        benefitCostKrw: Long = 150,
        netSettlementKrw: Long = 800,
        settlementDate: LocalDate = LocalDate.of(2026, 8, 3),
    ): SettlementItemEntity =
        SettlementItemEntity(
            id = UUID.randomUUID(),
            settlementBatchId = UUID.randomUUID(),
            orderId = UUID.randomUUID(),
            storeId = UUID.randomUUID(),
            itemSource = "order:${UUID.randomUUID()}:completed:7",
            completedAt = Instant.parse("2026-08-03T00:00:00Z"),
            settlementDate = settlementDate,
            currency = currency,
            grossPaidKrw = 1_000,
            feeRateBps = feeRateBps,
            feeKrw = 50,
            couponCostKrw = 100,
            pointCostKrw = 50,
            benefitCostKrw = benefitCostKrw,
            netSettlementKrw = netSettlementKrw,
            createdAt = Instant.parse("2026-08-03T00:00:01Z"),
        )
}

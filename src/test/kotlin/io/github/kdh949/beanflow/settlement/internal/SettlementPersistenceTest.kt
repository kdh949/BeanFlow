package io.github.kdh949.beanflow.settlement.internal

import org.assertj.core.api.Assertions.assertThat
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
    fun `batch calculation and confirmation guard summary and state transitions`() {
        val batch = batch()
        val calculatedAt = Instant.parse("2026-08-04T00:00:00Z")
        val summary = calculation()

        batch.calculate(summary, calculatedAt)

        assertThatCode { batch.confirm(calculatedAt.plusSeconds(1)) }.doesNotThrowAnyException()
        assertThat(batch.state).isEqualTo(SettlementBatchState.CONFIRMED)
        assertThat(batch.netSettlementKrw()).isEqualTo(650)
        assertThat(batch.carryForwardOutKrw).isZero()
        assertThatThrownBy { batch.confirm(calculatedAt.plusSeconds(2)) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { batch.calculate(summary, calculatedAt.plusSeconds(2)) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `negative calculated total becomes the only carry-forward output`() {
        val sourceBatchId = UUID.randomUUID()
        val batch = batch()
        batch.calculate(
            calculation(
                adjustmentKrw = -900,
                carryForwardInKrw = -100,
                carryForwardSourceBatchId = sourceBatchId,
            ),
            Instant.parse("2026-08-04T00:00:00Z"),
        )

        assertThat(batch.netSettlementKrw()).isEqualTo(-200)
        assertThat(batch.carryForwardOutKrw).isEqualTo(-200)
        assertThat(batch.carryForwardSourceBatchId).isEqualTo(sourceBatchId)
    }

    @Test
    fun `calculation rejects untied item summary and carry source`() {
        assertThatThrownBy { calculation(itemNetSettlementKrw = 801) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { calculation(carryForwardInKrw = -1, carryForwardSourceBatchId = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { calculation(carryForwardInKrw = 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
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

    private fun batch(): SettlementBatchEntity =
        SettlementBatchEntity(
            id = UUID.randomUUID(),
            storeId = UUID.randomUUID(),
            settlementDate = LocalDate.of(2026, 8, 3),
            createdAt = Instant.parse("2026-08-03T00:00:00Z"),
        )

    private fun calculation(
        itemNetSettlementKrw: Long = 800,
        adjustmentKrw: Long = -50,
        carryForwardInKrw: Long = -100,
        carryForwardSourceBatchId: UUID? = UUID.randomUUID(),
    ): SettlementBatchCalculation =
        SettlementBatchCalculation(
            itemCount = 1,
            grossPaidKrw = 1_000,
            feeKrw = 50,
            benefitCostKrw = 150,
            itemNetSettlementKrw = itemNetSettlementKrw,
            adjustmentKrw = adjustmentKrw,
            carryForwardInKrw = carryForwardInKrw,
            carryForwardSourceBatchId = carryForwardSourceBatchId,
            adjustmentCursorEffectiveAt = Instant.parse("2026-08-04T01:00:00Z"),
            adjustmentCursorId = UUID.randomUUID(),
        )
}

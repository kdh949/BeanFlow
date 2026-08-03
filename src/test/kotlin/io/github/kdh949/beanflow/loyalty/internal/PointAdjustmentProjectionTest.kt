package io.github.kdh949.beanflow.loyalty.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

internal class PointAdjustmentProjectionTest {
    @Test
    fun `public transaction amount is signed from every persisted balance effect`() {
        val cases =
            listOf(
                Triple(PointTransactionType.ACCRUAL, PointBalanceEffect.CREDIT, 10L),
                Triple(PointTransactionType.RESTORE, PointBalanceEffect.CREDIT, 10L),
                Triple(PointTransactionType.COMPENSATION, PointBalanceEffect.CREDIT, 10L),
                Triple(PointTransactionType.USE, PointBalanceEffect.DEBIT, -10L),
                Triple(PointTransactionType.EXPIRATION, PointBalanceEffect.DEBIT, -10L),
                Triple(PointTransactionType.RECOVERY, PointBalanceEffect.DEBIT, -10L),
                Triple(PointTransactionType.RESTORE_SKIPPED_EXPIRED, PointBalanceEffect.NONE, 0L),
                Triple(PointTransactionType.ADJUSTMENT, PointBalanceEffect.CREDIT, 10L),
                Triple(PointTransactionType.ADJUSTMENT, PointBalanceEffect.DEBIT, -10L),
            )

        cases.forEach { (type, effect, expectedAmount) ->
            val transaction =
                PointTransactionEntity(
                    id = UUID.randomUUID(),
                    pointAccountId = UUID.randomUUID(),
                    pointLotId = UUID.randomUUID(),
                    amountKrw = 10,
                    type = type,
                    balanceEffect = effect,
                    sourceReference = "projection:${UUID.randomUUID()}",
                    occurredAt = Instant.parse("2026-08-04T00:00:00Z"),
                )

            val view = pointTransactionView(transaction)

            assertThat(view.type.name).isEqualTo(type.name)
            assertThat(view.amountKrw).isEqualTo(expectedAmount)
        }
    }
}

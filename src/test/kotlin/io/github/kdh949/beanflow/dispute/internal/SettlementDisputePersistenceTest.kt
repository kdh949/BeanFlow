package io.github.kdh949.beanflow.dispute.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

internal class SettlementDisputePersistenceTest {
    @Test
    fun `accepted dispute releases held only after adjustment is known`() {
        val dispute = dispute()
        val adjustmentId = UUID.randomUUID()

        dispute.startReview()
        dispute.accept(adjustmentId, FILED_AT.plusSeconds(1))

        assertThat(dispute.state).isEqualTo(SettlementDisputeState.ACCEPTED)
        assertThat(dispute.heldAmountKrw).isZero()
        assertThat(dispute.settlementAdjustmentId).isEqualTo(adjustmentId)
        assertThatThrownBy { dispute.reject(FILED_AT.plusSeconds(2)) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `dispute cannot skip review or close before filing`() {
        assertThatThrownBy { dispute().reject(FILED_AT.plusSeconds(1)) }
            .isInstanceOf(IllegalStateException::class.java)
        val reviewing = dispute().also { it.startReview() }
        assertThatThrownBy { reviewing.withdraw(FILED_AT.minusNanos(1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `filing evidence and refile reference are immutable constructor guards`() {
        assertThatThrownBy { dispute(evidenceReferences = emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { dispute(refileCount = 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { dispute(previousDisputeId = UUID.randomUUID()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun dispute(
        previousDisputeId: UUID? = null,
        refileCount: Int = 0,
        evidenceReferences: List<String> = listOf("evidence:one"),
    ): SettlementDisputeEntity =
        SettlementDisputeEntity(
            id = UUID.randomUUID(),
            settlementItemId = UUID.randomUUID(),
            storeId = UUID.randomUUID(),
            previousDisputeId = previousDisputeId,
            refileCount = refileCount,
            state = SettlementDisputeState.FILED,
            expectedAdjustmentKrw = -100,
            heldAmountKrw = -100,
            reason = "amount mismatch",
            evidenceReferences = evidenceReferences,
            actorId = UUID.randomUUID(),
            operation = "CREATE_SETTLEMENT_DISPUTE_V1",
            idempotencyKey = "idempotency-key",
            payloadHash = "a".repeat(64),
            responseStatus = 201,
            responseBody = "{}",
            correlationId = "correlation-id",
            filedAt = FILED_AT,
        )

    private companion object {
        val FILED_AT: Instant = Instant.parse("2026-08-05T00:00:00Z")
    }
}

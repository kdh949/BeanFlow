package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

internal class PointAccountQueryMapperTest {
    @Test
    fun `every supported ledger type maps its stored balance effect to the public signed amount`() {
        val mappings =
            listOf(
                "ACCRUAL" to "CREDIT" to 10L,
                "USE" to "DEBIT" to -10L,
                "EXPIRATION" to "DEBIT" to -10L,
                "RESTORE" to "CREDIT" to 10L,
                "COMPENSATION" to "CREDIT" to 10L,
                "RESTORE_SKIPPED_EXPIRED" to "NONE" to 0L,
                "RECOVERY" to "DEBIT" to -10L,
                "ADJUSTMENT" to "CREDIT" to 10L,
                "ADJUSTMENT" to "DEBIT" to -10L,
            )

        mappings.forEach { (typeAndEffect, expectedAmount) ->
            val (type, effect) = typeAndEffect
            assertThat(projection(type, effect).toPublicView().amountKrw).isEqualTo(expectedAmount)
        }
    }

    @Test
    fun `unknown or inconsistent storage facts do not become zero or positive public effects`() {
        listOf(
            projection("UNKNOWN", "CREDIT"),
            projection("ACCRUAL", "DEBIT"),
            projection("ADJUSTMENT", "NONE"),
        ).forEach { projection ->
            assertThatThrownBy { projection.toPublicView() }
                .isInstanceOfSatisfying(DomainFailure::class.java) { failure ->
                    assertThat(failure.code).isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
                }
        }
    }

    @Test
    fun `operator access reason requires normalized non-control content`() {
        assertThat(normalizePointAccountAccessReason("  Support inquiry  ")).isEqualTo("Support inquiry")
        listOf(null, "", "   ", "a\u0001b", "x".repeat(201)).forEach { reason ->
            assertThatThrownBy { normalizePointAccountAccessReason(reason) }
                .isInstanceOfSatisfying(DomainFailure::class.java) { failure ->
                    assertThat(failure.code).isEqualTo(FailureCode.INVALID_REQUEST)
                }
        }
    }

    private fun projection(
        type: String,
        balanceEffect: String,
    ) = PointTransactionProjection(
        transactionId = UUID.randomUUID(),
        type = type,
        balanceEffect = balanceEffect,
        amountKrw = 10,
        occurredAt = Instant.parse("2026-08-06T00:00:00Z"),
        sourceReference = "query-mapper:${UUID.randomUUID()}",
    )
}

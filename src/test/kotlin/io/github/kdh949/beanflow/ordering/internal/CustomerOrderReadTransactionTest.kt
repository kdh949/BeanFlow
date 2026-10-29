package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.payment.api.CustomerCancellationPaymentOperations
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

internal class CustomerOrderReadTransactionTest {
    @Test
    fun `unsupported stored checkout mode fails detail projection explicitly`() {
        val now = Instant.parse("2026-09-30T03:00:00Z")
        val orderId = UUID.randomUUID()
        val customerId = UUID.randomUUID()
        val repository = mock(CustomerOrderQueryRepository::class.java)
        val reads =
            CustomerOrderReadTransaction(
                repository,
                mock(SignedCursorCodec::class.java),
                mock(CustomerCancellationPaymentOperations::class.java),
                CorrelationIdSource { "query-test" },
                ObjectMapper(),
            )
        val candidate =
            CustomerOrderCandidateProjection(orderId, customerId, now, "PENDING_PAYMENT", null, "UNSUPPORTED", now.plusSeconds(60))
        val header =
            CustomerOrderHeaderProjection(
                orderId = orderId,
                storeId = UUID.randomUUID(),
                publicReference = "BF-2345-6789",
                pickupSequence = 1,
                storeName = "Test Store",
                state = "PENDING_PAYMENT",
                createdAt = now,
                pickupWindowStart = null,
                pickupWindowEnd = null,
                subtotalKrw = 1_000,
                couponDiscountKrw = 0,
                pointsAppliedKrw = 0,
                payableKrw = 1_000,
                currency = "KRW",
                reservationExpiresAt = null,
                checkoutMode = "UNSUPPORTED",
                orderingWindowClosesAt = now.plusSeconds(60),
                acceptanceDeadlineAt = null,
                cancellationCause = null,
                paidAt = null,
                acceptedAt = null,
                preparingAt = null,
                readyAt = null,
                completedAt = null,
                preparationMinutes = null,
                estimatedReadyAt = null,
                version = 0,
            )
        `when`(repository.findDetailHeader(orderId, customerId)).thenReturn(header)
        `when`(repository.findDetailLines(orderId)).thenReturn(listOf(CustomerOrderLineProjection(orderId, 0, "Americano", "[]", 1, 1_000)))

        assertThatThrownBy { reads.projectDetail(customerId, candidate, now) }
            .isInstanceOfSatisfying(DomainFailure::class.java) { failure ->
                assertThat(failure.code).isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
                assertThat(failure.message).isEqualTo("Customer order checkout mode is unsupported")
            }
    }
}

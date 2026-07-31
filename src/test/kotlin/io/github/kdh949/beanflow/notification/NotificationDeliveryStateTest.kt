package io.github.kdh949.beanflow.notification

import io.github.kdh949.beanflow.notification.internal.domain.NotificationDelivery
import io.github.kdh949.beanflow.notification.internal.domain.NotificationDeliveryState
import io.github.kdh949.beanflow.notification.internal.domain.NotificationLogicalChannel
import io.github.kdh949.beanflow.notification.internal.domain.NotificationRecipientType
import io.github.kdh949.beanflow.notification.internal.domain.NotificationTemplate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal class NotificationDeliveryStateTest {
    @Test
    fun `failed attempts follow one five and thirty minute retry schedule`() {
        val delivery = delivery()
        var now = NOW

        RETRY_DELAYS.forEachIndexed { index, delay ->
            delivery.claim(UUID.randomUUID(), now, LEASE, MAX_ATTEMPTS)
            delivery.recordFailure("provider_unavailable", now, RETRY_DELAYS, MAX_ATTEMPTS)

            assertThat(delivery.state).isEqualTo(NotificationDeliveryState.RETRY_SCHEDULED)
            assertThat(delivery.attemptCount).isEqualTo(index + 1)
            assertThat(delivery.nextAttemptAt).isEqualTo(now.plus(delay))
            now = requireNotNull(delivery.nextAttemptAt)
        }

        delivery.claim(UUID.randomUUID(), now, LEASE, MAX_ATTEMPTS)
        delivery.recordFailure("provider_unavailable", now, RETRY_DELAYS, MAX_ATTEMPTS)

        assertThat(delivery.state).isEqualTo(NotificationDeliveryState.MANUAL_REVIEW)
        assertThat(delivery.attemptCount).isEqualTo(4)
        assertThat(delivery.nextAttemptAt).isNull()
    }

    @Test
    fun `same delivery cannot be claimed again before lease boundary`() {
        val delivery = delivery()
        delivery.claim(UUID.randomUUID(), NOW, LEASE, MAX_ATTEMPTS)

        assertThatThrownBy {
            delivery.claim(UUID.randomUUID(), NOW.plusSeconds(59), LEASE, MAX_ATTEMPTS)
        }.isInstanceOf(IllegalStateException::class.java)

        delivery.claim(UUID.randomUUID(), NOW.plusSeconds(60), LEASE, MAX_ATTEMPTS)
        assertThat(delivery.attemptCount).isEqualTo(2)
    }

    @Test
    fun `expired fourth claim becomes manual review`() {
        val delivery = delivery()
        var now = NOW
        repeat(MAX_ATTEMPTS - 1) {
            delivery.claim(UUID.randomUUID(), now, LEASE, MAX_ATTEMPTS)
            delivery.recordFailure("ack_lost", now, RETRY_DELAYS, MAX_ATTEMPTS)
            now = requireNotNull(delivery.nextAttemptAt)
        }
        delivery.claim(UUID.randomUUID(), now, LEASE, MAX_ATTEMPTS)

        delivery.markManualReviewAfterExpiredClaim(now.plus(LEASE), MAX_ATTEMPTS)

        assertThat(delivery.state).isEqualTo(NotificationDeliveryState.MANUAL_REVIEW)
        assertThat(delivery.lastFailureCode).isEqualTo("CLAIM_LEASE_EXPIRED")
        assertThat(delivery.claimToken).isNull()
    }

    @Test
    fun `provider acknowledgement makes delivery terminal`() {
        val delivery = delivery()
        delivery.claim(UUID.randomUUID(), NOW, LEASE, MAX_ATTEMPTS)

        delivery.succeed("provider-delivery", NOW)

        assertThat(delivery.state).isEqualTo(NotificationDeliveryState.SUCCEEDED)
        assertThat(delivery.providerDeliveryReference).isEqualTo("provider-delivery")
        assertThatThrownBy {
            delivery.claim(UUID.randomUUID(), NOW.plus(LEASE), LEASE, MAX_ATTEMPTS)
        }.isInstanceOf(IllegalStateException::class.java)
    }

    private fun delivery(): NotificationDelivery =
        NotificationDelivery.pending(
            id = UUID.randomUUID(),
            eventId = UUID.randomUUID(),
            eventType = "OrderReadyV1",
            orderId = UUID.randomUUID(),
            recipientType = NotificationRecipientType.CUSTOMER,
            recipientId = UUID.randomUUID(),
            logicalChannel = NotificationLogicalChannel.CUSTOMER_APP,
            template = NotificationTemplate.ORDER_READY,
            payloadJson = "{}",
            providerIdempotencyKey = "notification-key",
            correlationId = "correlation",
            now = NOW,
        )

    private companion object {
        const val MAX_ATTEMPTS = 4
        val NOW: Instant = Instant.parse("2026-07-30T00:00:00Z")
        val LEASE: Duration = Duration.ofMinutes(1)
        val RETRY_DELAYS: List<Duration> =
            listOf(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30))
    }
}

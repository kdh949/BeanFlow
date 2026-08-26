package io.github.kdh949.beanflow.notification

import io.github.kdh949.beanflow.notification.internal.domain.NotificationClassification
import io.github.kdh949.beanflow.notification.internal.domain.NotificationInboxItem
import io.github.kdh949.beanflow.notification.internal.domain.NotificationRecipientType
import io.github.kdh949.beanflow.notification.internal.domain.NotificationTarget
import io.github.kdh949.beanflow.notification.internal.domain.NotificationTargetType
import io.github.kdh949.beanflow.notification.internal.domain.NotificationTemplate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

internal class NotificationInboxItemTest {
    @Test
    fun `customer order source is transactional and orderless source is marketing`() {
        assertThat(NotificationClassification.classify(NotificationRecipientType.CUSTOMER, UUID.randomUUID()))
            .isEqualTo(NotificationClassification.TRANSACTIONAL)
        assertThat(NotificationClassification.classify(NotificationRecipientType.CUSTOMER, null))
            .isEqualTo(NotificationClassification.MARKETING)
        assertThat(NotificationClassification.classify(NotificationRecipientType.STORE, null))
            .isEqualTo(NotificationClassification.TRANSACTIONAL)
        assertThat(NotificationClassification.classify(NotificationRecipientType.PROFILE_TARGET, null))
            .isEqualTo(NotificationClassification.TRANSACTIONAL)
    }

    @Test
    fun `inbox item fixes retention at ninety days and reading is idempotent`() {
        val item = transactionalItem()
        val readAt = NOW.plusSeconds(10)

        assertThat(item.retentionExpiresAt).isEqualTo(NOW.plus(90, ChronoUnit.DAYS))
        assertThat(item.read(readAt)).isTrue()
        assertThat(item.readAt).isEqualTo(readAt)
        assertThat(item.read(readAt.plusSeconds(20))).isFalse()
        assertThat(item.readAt).isEqualTo(readAt)
        assertThat(item.retentionExpiresAt).isEqualTo(NOW.plus(90, ChronoUnit.DAYS))
    }

    @Test
    fun `order target only accepts a public reference and NONE accepts no reference`() {
        assertThat(NotificationTarget.order("BF-2345-6789").type).isEqualTo(NotificationTargetType.ORDER)
        assertThat(NotificationTarget.none().reference).isNull()

        assertThatThrownBy { NotificationTarget.order(UUID.randomUUID().toString()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun transactionalItem(): NotificationInboxItem =
        NotificationInboxItem.create(
            id = UUID.randomUUID(),
            customerId = UUID.randomUUID(),
            logicalSource = "event:${UUID.randomUUID()}:customer",
            orderId = UUID.randomUUID(),
            classification = NotificationClassification.TRANSACTIONAL,
            template = NotificationTemplate.ORDER_READY,
            title = "주문이 준비되었습니다",
            body = "매장에서 주문 준비를 마쳤습니다.",
            target = NotificationTarget.none(),
            createdAt = NOW,
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-26T03:00:00Z")
    }
}

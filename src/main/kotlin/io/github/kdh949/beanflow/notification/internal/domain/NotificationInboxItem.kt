package io.github.kdh949.beanflow.notification.internal.domain

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

internal enum class NotificationClassification {
    TRANSACTIONAL,
    MARKETING,
    ;

    companion object {
        fun classify(
            recipientType: NotificationRecipientType,
            orderId: UUID?,
        ): NotificationClassification =
            when (recipientType) {
                NotificationRecipientType.CUSTOMER -> if (orderId == null) MARKETING else TRANSACTIONAL

                NotificationRecipientType.STORE,
                NotificationRecipientType.PROFILE_TARGET,
                -> TRANSACTIONAL
            }
    }
}

internal enum class NotificationTargetType {
    NONE,
    ORDER,
}

internal class NotificationTarget private constructor(
    val type: NotificationTargetType,
    val reference: String?,
) {
    companion object {
        private val PUBLIC_ORDER_REFERENCE = Regex("^BF-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$")

        fun none(): NotificationTarget = NotificationTarget(NotificationTargetType.NONE, null)

        fun order(publicReference: String): NotificationTarget {
            require(publicReference.matches(PUBLIC_ORDER_REFERENCE)) { "Order notification target must use a public reference" }
            return NotificationTarget(NotificationTargetType.ORDER, publicReference)
        }

        fun restore(
            type: NotificationTargetType,
            reference: String?,
        ): NotificationTarget =
            when (type) {
                NotificationTargetType.NONE -> {
                    require(reference == null) { "NONE notification target cannot have a reference" }
                    none()
                }

                NotificationTargetType.ORDER -> {
                    order(requireNotNull(reference) { "ORDER notification target requires a reference" })
                }
            }
    }
}

internal data class NotificationInboxCopy(
    val title: String,
    val body: String,
) {
    companion object {
        fun forTemplate(template: NotificationTemplate): NotificationInboxCopy =
            when (template) {
                NotificationTemplate.ORDER_REJECTED -> {
                    NotificationInboxCopy("주문을 수락하지 못했습니다", "매장에서 주문을 수락하지 못했습니다. 주문 내역을 확인해 주세요.")
                }

                NotificationTemplate.ORDER_READY -> {
                    NotificationInboxCopy("주문이 준비되었습니다", "매장에서 주문 준비를 마쳤습니다.")
                }

                NotificationTemplate.ORDER_CANCELLATION_ACCEPTED -> {
                    NotificationInboxCopy("주문 취소를 접수했습니다", "주문 취소가 접수되었습니다. 환불 진행 상태는 주문 내역에서 확인해 주세요.")
                }

                NotificationTemplate.CUSTOMER_CANCELLATION_REFUND_SUCCEEDED -> {
                    NotificationInboxCopy("환불이 완료되었습니다", "취소한 주문의 환불이 완료되었습니다.")
                }

                NotificationTemplate.CUSTOMER_CANCELLATION_REFUND_DELAYED -> {
                    NotificationInboxCopy("환불 처리가 지연되고 있습니다", "취소한 주문의 환불을 계속 확인하고 있습니다.")
                }

                NotificationTemplate.SUPPORT_PICKUP_RESCHEDULED -> {
                    NotificationInboxCopy("픽업 시간이 변경되었습니다", "요청하신 주문의 픽업 시간이 변경되었습니다.")
                }

                NotificationTemplate.SUPPORT_POST_ACCEPTANCE_RESOLUTION -> {
                    NotificationInboxCopy("주문 지원 처리가 완료되었습니다", "요청하신 주문 지원 처리 결과를 확인해 주세요.")
                }

                NotificationTemplate.SUPPORT_GOODWILL_COMPENSATION_ISSUED -> {
                    NotificationInboxCopy("BeanFlow 혜택이 지급되었습니다", "지급된 혜택은 혜택 내역에서 확인할 수 있습니다.")
                }

                NotificationTemplate.STORE_ACCEPTANCE_WARNING,
                NotificationTemplate.SUPPORT_PROFILE_CHANGED,
                -> {
                    throw IllegalArgumentException("Notification template is not customer-visible")
                }
            }
    }
}

internal class NotificationInboxItem private constructor(
    val id: UUID,
    val customerId: UUID,
    val logicalSource: String,
    val orderId: UUID?,
    val classification: NotificationClassification,
    val template: NotificationTemplate,
    val title: String,
    val body: String,
    val target: NotificationTarget,
    readAt: Instant?,
    val createdAt: Instant,
    val retentionExpiresAt: Instant,
) {
    var readAt: Instant? = readAt
        private set

    fun read(now: Instant): Boolean {
        if (readAt != null) return false
        require(!now.isBefore(createdAt)) { "Notification read time cannot precede creation" }
        readAt = now
        return true
    }

    companion object {
        private val INBOX_TEMPLATES =
            setOf(
                NotificationTemplate.ORDER_REJECTED,
                NotificationTemplate.ORDER_READY,
                NotificationTemplate.ORDER_CANCELLATION_ACCEPTED,
                NotificationTemplate.CUSTOMER_CANCELLATION_REFUND_SUCCEEDED,
                NotificationTemplate.CUSTOMER_CANCELLATION_REFUND_DELAYED,
                NotificationTemplate.SUPPORT_PICKUP_RESCHEDULED,
                NotificationTemplate.SUPPORT_POST_ACCEPTANCE_RESOLUTION,
                NotificationTemplate.SUPPORT_GOODWILL_COMPENSATION_ISSUED,
            )

        @Suppress("LongParameterList")
        fun create(
            id: UUID,
            customerId: UUID,
            logicalSource: String,
            orderId: UUID?,
            classification: NotificationClassification,
            template: NotificationTemplate,
            title: String,
            body: String,
            target: NotificationTarget,
            createdAt: Instant,
        ): NotificationInboxItem =
            validated(
                id,
                customerId,
                logicalSource,
                orderId,
                classification,
                template,
                title,
                body,
                target,
                null,
                createdAt,
                createdAt.plus(90, ChronoUnit.DAYS),
            )

        @Suppress("LongParameterList")
        fun restore(
            id: UUID,
            customerId: UUID,
            logicalSource: String,
            orderId: UUID?,
            classification: NotificationClassification,
            template: NotificationTemplate,
            title: String,
            body: String,
            target: NotificationTarget,
            readAt: Instant?,
            createdAt: Instant,
            retentionExpiresAt: Instant,
        ): NotificationInboxItem =
            validated(
                id,
                customerId,
                logicalSource,
                orderId,
                classification,
                template,
                title,
                body,
                target,
                readAt,
                createdAt,
                retentionExpiresAt,
            )

        @Suppress("LongParameterList")
        private fun validated(
            id: UUID,
            customerId: UUID,
            logicalSource: String,
            orderId: UUID?,
            classification: NotificationClassification,
            template: NotificationTemplate,
            title: String,
            body: String,
            target: NotificationTarget,
            readAt: Instant?,
            createdAt: Instant,
            retentionExpiresAt: Instant,
        ): NotificationInboxItem {
            requireValidText(logicalSource, 240, "Notification logical source")
            requireValidText(title, 120, "Notification title")
            requireValidText(body, 500, "Notification body")
            require(template in INBOX_TEMPLATES) { "Notification template is not customer-visible" }
            require(classification == NotificationClassification.classify(NotificationRecipientType.CUSTOMER, orderId)) {
                "Notification classification does not match its order binding"
            }
            require(retentionExpiresAt == createdAt.plus(90, ChronoUnit.DAYS)) {
                "Notification retention must be fixed at ninety days"
            }
            require(readAt == null || !readAt.isBefore(createdAt)) { "Notification read time cannot precede creation" }
            return NotificationInboxItem(
                id,
                customerId,
                logicalSource,
                orderId,
                classification,
                template,
                title,
                body,
                target,
                readAt,
                createdAt,
                retentionExpiresAt,
            )
        }

        private fun requireValidText(
            value: String,
            maxLength: Int,
            label: String,
        ) {
            require(value == value.trim() && value.length in 1..maxLength && value.none(Char::isISOControl)) {
                "$label is invalid"
            }
        }
    }
}

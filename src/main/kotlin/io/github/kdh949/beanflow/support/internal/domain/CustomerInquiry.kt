package io.github.kdh949.beanflow.support.internal.domain

import java.time.Instant
import java.util.UUID

internal enum class CustomerInquiryCategory {
    ORDER_STATUS,
    PICKUP_RESCHEDULE,
    ORDER_CANCELLATION,
    PAYMENT_OR_REFUND,
    COUPON_OR_POINT,
    CUSTOMER_PROFILE,
    DELIVERY_STATUS,
    ACCOUNT_RECOVERY,
    PRIVACY,
    SAFETY,
    OTHER,
}

internal enum class InquiryMessageAuthor { CUSTOMER, SUPPORT }

/** Intake owns customer identity and exactly one assignment to a state-owning SupportCase. */
internal class CustomerInquiry(
    val id: UUID,
    val customerId: UUID,
    val title: String,
    val category: CustomerInquiryCategory,
    val orderId: UUID?,
    val orderReference: String?,
    var caseId: UUID?,
    var version: Long,
    val createdAt: Instant,
    var updatedAt: Instant,
    val retentionPolicyVersionId: Long,
) {
    fun claim(
        targetCaseId: UUID,
        expectedVersion: Long,
        now: Instant,
    ) {
        check(caseId == null) { "Inquiry already claimed" }
        check(version == expectedVersion) { "Inquiry version changed" }
        advance(now)
        caseId = targetCaseId
    }

    fun append(
        expectedVersion: Long,
        caseState: SupportCaseState?,
        now: Instant,
    ) {
        check(version == expectedVersion) { "Inquiry version changed" }
        check(caseState != SupportCaseState.RESOLVED && caseState != SupportCaseState.CLOSED) { "Inquiry is terminal" }
        advance(now)
    }

    private fun advance(now: Instant) {
        require(now >= updatedAt) { "Inquiry time cannot move backward" }
        version += 1
        updatedAt = now
    }
}

internal object CustomerInquiryContent {
    fun title(value: String): String = SupportContentPolicy.reason(value).also { require(it.length <= 100) }

    fun message(value: String): String {
        val normalized = value.replace("\r\n", "\n").trim()
        SupportContentPolicy.note(normalized.replace('\n', ' '))
        return normalized
    }
}

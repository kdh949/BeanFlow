package io.github.kdh949.beanflow.eventing.api

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import java.time.ZoneId
import java.util.UUID

object OrderCompletedV2Contract {
    const val EVENT_TYPE = "OrderCompletedV2"
    const val PAYLOAD_VERSION = 2

    fun validate(event: OrderCompletedV2) {
        val envelope = event.envelope
        if (envelope.eventId == ZERO_UUID || event.orderId == ZERO_UUID || event.customerId == ZERO_UUID ||
            event.storeId == ZERO_UUID || envelope.eventType != EVENT_TYPE ||
            envelope.payloadVersion != PAYLOAD_VERSION || envelope.aggregateId != event.orderId ||
            envelope.occurredAt != event.completedAt || envelope.aggregateVersion < 0 ||
            envelope.correlationId.isBlank() || envelope.causationId.isBlank() ||
            event.settlementDate != event.completedAt.atZone(SEOUL).toLocalDate() ||
            event.currency != "KRW" || event.feeRateBps !in 0..10_000 ||
            event.completionSource != "order:${event.orderId}:completed:${envelope.aggregateVersion}" ||
            listOf(
                event.grossPaidKrw,
                event.feeKrw,
                event.couponCostKrw,
                event.pointCostKrw,
                event.benefitCostKrw,
                event.netSettlementKrw,
            ).any { it < 0 }
        ) {
            invalid("OrderCompletedV2 envelope or required fields are invalid")
        }
        val expectedBenefit = exactAdd(event.couponCostKrw, event.pointCostKrw)
        val expectedNet = exactSubtract(exactSubtract(event.grossPaidKrw, event.feeKrw), expectedBenefit)
        if (event.benefitCostKrw != expectedBenefit || event.netSettlementKrw != expectedNet || expectedNet < 0) {
            invalid("OrderCompletedV2 monetary fields do not tie out")
        }
    }

    private fun exactAdd(
        left: Long,
        right: Long,
    ): Long =
        try {
            Math.addExact(left, right)
        } catch (failure: ArithmeticException) {
            invalid("OrderCompletedV2 monetary fields overflowed", failure)
        }

    private fun exactSubtract(
        left: Long,
        right: Long,
    ): Long =
        try {
            Math.subtractExact(left, right)
        } catch (failure: ArithmeticException) {
            invalid("OrderCompletedV2 monetary fields overflowed", failure)
        }

    private fun invalid(
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw DomainFailure(FailureCode.SETTLEMENT_INPUT_UNAVAILABLE, message).also { cause?.let(it::initCause) }

    private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    private val ZERO_UUID = UUID(0, 0)
}

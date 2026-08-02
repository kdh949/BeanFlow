package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.OrderCompletedV2
import io.github.kdh949.beanflow.eventing.api.OrderCompletedV2Contract
import io.github.kdh949.beanflow.ordering.api.OrderSettlementInputSnapshot
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Component
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

internal data class CompletedOrderFact(
    val orderId: UUID,
    val customerId: UUID,
    val storeId: UUID,
    val completedAt: Instant,
    val aggregateVersion: Long,
)

internal data class ApprovedPaymentSettlementFact(
    val orderId: UUID,
    val approvedAmountKrw: Long,
    val currency: String,
    val approvedAt: Instant,
    val approvalSource: String,
)

internal data class OrderCompletedV2EnvelopeInput(
    val eventId: UUID,
    val correlationId: String,
    val causationId: String,
)

@Component
internal class OrderCompletedV2Factory {
    fun create(
        order: CompletedOrderFact,
        payment: ApprovedPaymentSettlementFact,
        snapshot: OrderSettlementInputSnapshot,
        envelopeInput: OrderCompletedV2EnvelopeInput,
    ): OrderCompletedV2 {
        validateInputs(order, payment, snapshot, envelopeInput)
        val completionSource = "order:${order.orderId}:completed:${order.aggregateVersion}"
        val event =
            OrderCompletedV2(
                envelope =
                    EventEnvelope(
                        eventId = envelopeInput.eventId,
                        eventType = OrderCompletedV2Contract.EVENT_TYPE,
                        aggregateId = order.orderId,
                        aggregateVersion = order.aggregateVersion,
                        occurredAt = order.completedAt,
                        payloadVersion = OrderCompletedV2Contract.PAYLOAD_VERSION,
                        correlationId = envelopeInput.correlationId,
                        causationId = envelopeInput.causationId,
                    ),
                orderId = order.orderId,
                customerId = order.customerId,
                storeId = order.storeId,
                completedAt = order.completedAt,
                settlementDate = order.completedAt.atZone(SEOUL).toLocalDate(),
                currency = snapshot.currency,
                grossPaidKrw = snapshot.grossPaidKrw,
                feeRateBps = snapshot.feeRateBps,
                feeKrw = snapshot.feeKrw,
                couponCostKrw = snapshot.couponCostKrw,
                pointCostKrw = snapshot.pointCostKrw,
                benefitCostKrw = snapshot.benefitCostKrw,
                netSettlementKrw = snapshot.netSettlementKrw,
                completionSource = completionSource,
            )
        OrderCompletedV2Contract.validate(event)
        return event
    }

    private fun validateInputs(
        order: CompletedOrderFact,
        payment: ApprovedPaymentSettlementFact,
        snapshot: OrderSettlementInputSnapshot,
        envelopeInput: OrderCompletedV2EnvelopeInput,
    ) {
        if (order.aggregateVersion < 0 ||
            order.orderId != snapshot.orderId ||
            order.storeId != snapshot.storeId ||
            payment.orderId != order.orderId
        ) {
            unavailable("Order, Payment and settlement snapshot sources do not match")
        }
        if (payment.approvedAmountKrw != snapshot.feeBaseKrw ||
            payment.currency != snapshot.currency ||
            payment.currency != "KRW" ||
            payment.approvalSource.isBlank() ||
            payment.approvedAt.isBefore(snapshot.createdAt) ||
            payment.approvedAt.isAfter(order.completedAt)
        ) {
            unavailable("Approved Payment fact does not match the immutable settlement fee basis")
        }
        if (snapshot.snapshotSchemaVersion != 1 ||
            !snapshot.canonicalSnapshotHash.matches(HASH_PATTERN) ||
            snapshot.storeSettlementTermsSourceReference.isBlank() ||
            snapshot.createdAt.isAfter(order.completedAt) ||
            snapshot.currency != "KRW" ||
            snapshot.feeRateBps !in 0..10_000 ||
            listOf(
                snapshot.grossPaidKrw,
                snapshot.feeBaseKrw,
                snapshot.feeKrw,
                snapshot.couponCostKrw,
                snapshot.pointCostKrw,
                snapshot.benefitCostKrw,
                snapshot.netSettlementKrw,
            ).any { it < 0 }
        ) {
            unavailable("Settlement input snapshot is invalid for OrderCompletedV2")
        }
        val expectedFee =
            BigInteger
                .valueOf(snapshot.feeBaseKrw)
                .multiply(BigInteger.valueOf(snapshot.feeRateBps.toLong()))
                .divide(TEN_THOUSAND)
                .longValueExact()
        val expectedBenefit = exactAdd(snapshot.couponCostKrw, snapshot.pointCostKrw)
        val expectedNet = exactSubtract(exactSubtract(snapshot.grossPaidKrw, expectedFee), expectedBenefit)
        if (snapshot.feeKrw != expectedFee ||
            snapshot.benefitCostKrw != expectedBenefit ||
            snapshot.netSettlementKrw != expectedNet ||
            expectedNet < 0
        ) {
            unavailable("Settlement input snapshot amounts do not tie out for OrderCompletedV2")
        }
        if (envelopeInput.correlationId.isBlank() || envelopeInput.causationId.isBlank()) {
            unavailable("OrderCompletedV2 envelope lineage is missing")
        }
    }

    private fun exactAdd(
        left: Long,
        right: Long,
    ): Long =
        try {
            Math.addExact(left, right)
        } catch (failure: ArithmeticException) {
            unavailable("OrderCompletedV2 amount overflowed", failure)
        }

    private fun exactSubtract(
        left: Long,
        right: Long,
    ): Long =
        try {
            Math.subtractExact(left, right)
        } catch (failure: ArithmeticException) {
            unavailable("OrderCompletedV2 amount overflowed", failure)
        }

    private fun unavailable(
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw DomainFailure(FailureCode.SETTLEMENT_INPUT_UNAVAILABLE, message).also { cause?.let(it::initCause) }

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val TEN_THOUSAND: BigInteger = BigInteger.valueOf(10_000)
        val HASH_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.FinancialEventPublicationOperations
import io.github.kdh949.beanflow.eventing.api.PaymentRefundedV1
import io.github.kdh949.beanflow.eventing.api.RefundCompletionDisposition
import io.github.kdh949.beanflow.eventing.api.SettlementRefundEffect
import io.github.kdh949.beanflow.payment.api.PartialRefundSettlementContext
import io.github.kdh949.beanflow.payment.internal.domain.RefundState
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId

@Service
internal class PaymentRefundEventProducer(
    private val publications: FinancialEventPublicationOperations,
    private val identifierSource: IdentifierSource,
    private val jdbcTemplate: JdbcTemplate,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun publishPartial(
        refund: RefundEntity,
        payment: PaymentEntity,
        context: PartialRefundSettlementContext,
        succeededAt: Instant,
    ) {
        validateContext(refund, payment, context)
        val effect = settlementEffect(refund, context)
        val completed = context.orderCompletedAt
        val disposition =
            if (completed == null) {
                RefundCompletionDisposition.PRE_COMPLETION_ORDER
            } else {
                RefundCompletionDisposition.COMPLETED_ORDER
            }
        publications.publish(
            PaymentRefundedV1(
                envelope = envelope(refund, payment, succeededAt),
                refundId = refund.id,
                refundSource = refund.sourceReference,
                orderId = refund.orderId,
                customerId = context.customerId,
                refundSucceededAt = succeededAt,
                currency = context.currency,
                cashRefundedKrw = refund.requestedAmountKrw,
                completionDisposition = disposition,
                orderCompletedAt = completed,
                settlementDate = completed?.atZone(SEOUL)?.toLocalDate(),
                settlementItemSource =
                    completed?.let { "order:${refund.orderId}:completed:${context.orderAggregateVersion}" },
                settlementRefundEffect = effect,
            ),
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun publishPreAcceptance(
        refund: RefundEntity,
        payment: PaymentEntity,
        succeededAt: Instant,
    ) {
        val customerId =
            payment.customerId
                ?: unavailable("Refunded Payment customer is missing")
        if (refund.state != RefundState.SUCCEEDED || refund.succeededAmountKrw != refund.requestedAmountKrw ||
            refund.orderId != payment.orderId || payment.currency != KRW || refund.sourceReference.isBlank()
        ) {
            unavailable("Refund and Payment sources do not match")
        }
        publications.publish(
            PaymentRefundedV1(
                envelope = envelope(refund, payment, succeededAt),
                refundId = refund.id,
                refundSource = refund.sourceReference,
                orderId = refund.orderId,
                customerId = customerId,
                refundSucceededAt = succeededAt,
                currency = payment.currency,
                cashRefundedKrw = refund.requestedAmountKrw,
                completionDisposition = RefundCompletionDisposition.PRE_ACCEPTANCE_CANCELLATION,
            ),
        )
    }

    private fun envelope(
        refund: RefundEntity,
        payment: PaymentEntity,
        succeededAt: Instant,
    ) = EventEnvelope(
        eventId = identifierSource.next(),
        eventType = EVENT_TYPE,
        aggregateId = refund.id,
        aggregateVersion = Math.addExact(refund.version, 1),
        occurredAt = succeededAt,
        payloadVersion = PAYLOAD_VERSION,
        correlationId = refund.correlationId ?: payment.correlationId,
        causationId = "refund:${refund.id}:succeeded",
    )

    private fun validateContext(
        refund: RefundEntity,
        payment: PaymentEntity,
        context: PartialRefundSettlementContext,
    ) {
        if (refund.state != RefundState.SUCCEEDED || refund.succeededAmountKrw != refund.requestedAmountKrw ||
            refund.orderId != payment.orderId || payment.customerId != context.customerId ||
            payment.currency != context.currency || context.currency != KRW || refund.sourceReference.isBlank() ||
            context.orderAggregateVersion < 0 || context.feeRateBps !in 0..10_000 ||
            context.couponStoreShareBps !in 0..10_000
        ) {
            unavailable("Refund, Payment, Order and settlement snapshot sources do not match")
        }
        if ((context.orderState == COMPLETED) != (context.orderCompletedAt != null)) {
            unavailable("Order completion state and immutable completion fact do not match")
        }
        val amounts =
            listOf(
                context.grossPaidKrw,
                context.feeBaseKrw,
                context.couponDiscountKrw,
                context.pointsAppliedKrw,
                context.pointCostKrw,
            )
        if (amounts.any { it < 0 } || context.pointCostKrw > context.pointsAppliedKrw ||
            exactSum(context.feeBaseKrw, context.couponDiscountKrw, context.pointsAppliedKrw) !=
            context.grossPaidKrw
        ) {
            unavailable("Settlement snapshot amounts do not tie out")
        }
    }

    private fun settlementEffect(
        refund: RefundEntity,
        context: PartialRefundSettlementContext,
    ): SettlementRefundEffect {
        val current = allocation(refund.id, null, context.storeId.toString())
        val after = allocation(null, refund.paymentId, context.storeId.toString())
        if (current.lineCount == 0L || current.grossKrw != exactSum(current.cashKrw, current.couponKrw, current.pointsKrw) ||
            current.cashKrw != refund.requestedAmountKrw || current.pointsKrw != refund.requestedPointsKrw ||
            current.storePointReferenceMismatchCount != 0L || after.storePointReferenceMismatchCount != 0L
        ) {
            unavailable("Refund allocation does not tie out")
        }
        if (after.grossKrw > context.grossPaidKrw || after.cashKrw > context.feeBaseKrw ||
            after.couponKrw > context.couponDiscountKrw || after.pointsKrw > context.pointsAppliedKrw ||
            after.matchingStorePointsKrw > context.pointCostKrw
        ) {
            unavailable("Cumulative Refund allocation exceeds the immutable settlement snapshot")
        }
        val before = after.minus(current)
        val grossDelta = -Math.subtractExact(after.grossKrw, before.grossKrw)
        val feeDelta =
            -Math.subtractExact(
                floorBasisPoints(after.cashKrw, context.feeRateBps),
                floorBasisPoints(before.cashKrw, context.feeRateBps),
            )
        val couponDelta =
            -Math.subtractExact(
                floorBasisPoints(after.couponKrw, context.couponStoreShareBps),
                floorBasisPoints(before.couponKrw, context.couponStoreShareBps),
            )
        val pointDelta = -Math.subtractExact(after.matchingStorePointsKrw, before.matchingStorePointsKrw)
        val benefitDelta = Math.addExact(couponDelta, pointDelta)
        return SettlementRefundEffect(
            grossPaidDeltaKrw = grossDelta,
            feeDeltaKrw = feeDelta,
            benefitCostDeltaKrw = benefitDelta,
            netSettlementDeltaKrw = Math.subtractExact(Math.subtractExact(grossDelta, feeDelta), benefitDelta),
        )
    }

    private fun allocation(
        refundId: java.util.UUID?,
        paymentId: java.util.UUID?,
        storeId: String,
    ): AllocationTotal {
        require((refundId == null) xor (paymentId == null))
        val predicate = if (refundId != null) "refund.id = ?" else "refund.payment_id = ?"
        val id = refundId ?: requireNotNull(paymentId)
        return jdbcTemplate
            .query(
                """
                SELECT COUNT(DISTINCT line.id),
                       COALESCE(SUM(line.gross_krw), 0),
                       COALESCE(SUM(line.cash_refunded_krw), 0),
                       COALESCE(SUM(line.coupon_attribution_krw), 0),
                       COALESCE((SELECT SUM(point.amount_krw)
                                   FROM payment_refund_point_allocation point
                                   JOIN payment_refund point_refund ON point_refund.id = point.refund_id
                                  WHERE ${if (refundId != null) "point_refund.id = ?" else "point_refund.payment_id = ?"}), 0),
                       COALESCE((SELECT SUM(CASE WHEN request.issuer_type = 'STORE'
                                                    AND request.issuer_reference = ?
                                                THEN point.amount_krw ELSE 0 END)
                                   FROM payment_refund_point_allocation point
                                   JOIN payment_refund point_refund ON point_refund.id = point.refund_id
                                   JOIN payment_refund_point_request request
                                     ON request.id = point.refund_point_request_id
                                  WHERE ${if (refundId != null) "point_refund.id = ?" else "point_refund.payment_id = ?"}), 0),
                       COALESCE((SELECT COUNT(*)
                                   FROM payment_refund_point_allocation point
                                   JOIN payment_refund point_refund ON point_refund.id = point.refund_id
                                   JOIN payment_refund_point_request request
                                     ON request.id = point.refund_point_request_id
                                  WHERE ${if (refundId != null) "point_refund.id = ?" else "point_refund.payment_id = ?"}
                                    AND request.issuer_type = 'STORE'
                                    AND request.issuer_reference <> ?), 0)
                  FROM payment_refund_line_allocation line
                  JOIN payment_refund refund ON refund.id = line.refund_id
                 WHERE $predicate
                """.trimIndent(),
                { rs, _ ->
                    AllocationTotal(
                        lineCount = rs.getLong(1),
                        grossKrw = rs.getLong(2),
                        cashKrw = rs.getLong(3),
                        couponKrw = rs.getLong(4),
                        pointsKrw = rs.getLong(5),
                        matchingStorePointsKrw = rs.getLong(6),
                        storePointReferenceMismatchCount = rs.getLong(7),
                    )
                },
                id,
                storeId,
                id,
                id,
                storeId,
                id,
            ).single()
    }

    private fun floorBasisPoints(
        amount: Long,
        rateBps: Int,
    ): Long =
        BigInteger
            .valueOf(amount)
            .multiply(BigInteger.valueOf(rateBps.toLong()))
            .divide(BASIS_POINTS)
            .longValueExact()

    private fun exactSum(vararg values: Long): Long = values.fold(0L, Math::addExact)

    private fun unavailable(message: String): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)

    private data class AllocationTotal(
        val lineCount: Long,
        val grossKrw: Long,
        val cashKrw: Long,
        val couponKrw: Long,
        val pointsKrw: Long,
        val matchingStorePointsKrw: Long,
        val storePointReferenceMismatchCount: Long,
    ) {
        fun minus(other: AllocationTotal) =
            AllocationTotal(
                lineCount = Math.subtractExact(lineCount, other.lineCount),
                grossKrw = Math.subtractExact(grossKrw, other.grossKrw),
                cashKrw = Math.subtractExact(cashKrw, other.cashKrw),
                couponKrw = Math.subtractExact(couponKrw, other.couponKrw),
                pointsKrw = Math.subtractExact(pointsKrw, other.pointsKrw),
                matchingStorePointsKrw = Math.subtractExact(matchingStorePointsKrw, other.matchingStorePointsKrw),
                storePointReferenceMismatchCount =
                    Math.subtractExact(storePointReferenceMismatchCount, other.storePointReferenceMismatchCount),
            )
    }

    private companion object {
        const val EVENT_TYPE = "PaymentRefundedV1"
        const val PAYLOAD_VERSION = 1
        const val KRW = "KRW"
        const val COMPLETED = "COMPLETED"
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val BASIS_POINTS: BigInteger = BigInteger.valueOf(10_000)
    }
}

package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.fulfillment.api.PickupReservationOperations
import io.github.kdh949.beanflow.inventory.api.StockReservationOperations
import io.github.kdh949.beanflow.loyalty.api.PointReservationOperations
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.ordering.api.ReservationExpiryOutcome
import io.github.kdh949.beanflow.ordering.api.ReservationExpiryResult
import io.github.kdh949.beanflow.ordering.api.ReservationExpiryUseCase
import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.promotion.api.CouponReservationOperations
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.ReservationTransitionReport
import io.github.kdh949.beanflow.shared.api.ReservationTransitionResult
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
internal class ReservationExpiryService(
    private val orderRepository: OrderJpaRepository,
    private val pickupOperations: PickupReservationOperations,
    private val stockOperations: StockReservationOperations,
    private val couponOperations: CouponReservationOperations,
    private val pointOperations: PointReservationOperations,
    private val auditRecordOperations: AuditRecordOperations,
    private val correlationIdSource: CorrelationIdSource,
    private val meterRegistry: MeterRegistry,
) : ReservationExpiryUseCase {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun expireIfDue(
        orderId: UUID,
        now: Instant,
    ): ReservationExpiryResult {
        try {
            val order =
                orderRepository.findLockedById(orderId)
                    ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Order was not found")
            if (order.state != OrderState.PENDING_PAYMENT) {
                meterRegistry.counter("beanflow.reservation.expiry", "outcome", "not_eligible").increment()
                return ReservationExpiryResult(orderId, ReservationExpiryOutcome.NOT_ELIGIBLE)
            }
            val deadline =
                order.reservationExpiresAt
                    ?: throw dependencyFailure("Pending-payment order has no reservation deadline")
            if (now.isBefore(deadline)) {
                meterRegistry.counter("beanflow.reservation.expiry", "outcome", "not_due").increment()
                return ReservationExpiryResult(orderId, ReservationExpiryOutcome.NOT_ELIGIBLE)
            }

            val pickup =
                requireApplied(
                    "PICKUP",
                    pickupOperations.expire(orderId, now, OrderCreationTransaction.pickupSource(orderId)),
                )
            val stock =
                requireApplied(
                    "STOCK",
                    stockOperations.expire(orderId, now, OrderCreationTransaction.stockSource(orderId)),
                )
            val coupon =
                if (order.couponDiscountKrw > 0) {
                    requireApplied(
                        "COUPON",
                        couponOperations.release(orderId, now, OrderCreationTransaction.couponSource(orderId)),
                    )
                } else {
                    null
                }
            val points =
                if (order.pointsAppliedKrw > 0) {
                    requireApplied(
                        "POINTS",
                        pointOperations.release(orderId, now, OrderCreationTransaction.pointsSource(orderId)),
                    )
                } else {
                    null
                }

            order.expire(now)
            val sourceReference = "order:$orderId:expiry"
            val correlationId = correlationIdSource.currentOrCreate()
            val audits =
                mutableListOf(
                    audit(
                        action = "ORDER_EXPIRED",
                        targetType = "ORDER",
                        targetId = orderId,
                        now = now,
                        sourceReference = sourceReference,
                        correlationId = correlationId,
                        before = mapOf("state" to "PENDING_PAYMENT"),
                        after = mapOf("state" to "EXPIRED"),
                    ),
                )
            pickup.targetIds.forEach {
                audits += audit("PICKUP_EXPIRED", "PICKUP_RESERVATION", it, now, sourceReference, correlationId)
            }
            stock.targetIds.forEach {
                audits += audit("STOCK_EXPIRED", "STOCK_RESERVATION", it, now, sourceReference, correlationId)
            }
            coupon?.targetIds?.forEach {
                audits += audit("COUPON_RELEASED", "COUPON_RESERVATION", it, now, sourceReference, correlationId)
            }
            points?.targetIds?.forEach {
                audits += audit("POINTS_RELEASED", "POINT_RESERVATION", it, now, sourceReference, correlationId)
            }
            auditRecordOperations.appendAll(audits)
            meterRegistry.counter("beanflow.reservation.expiry", "outcome", "expired").increment()
            meterRegistry
                .summary("beanflow.reservation.expiry.lag.seconds")
                .record(
                    java.time.Duration
                        .between(deadline, now)
                        .toMillis()
                        .coerceAtLeast(0) / 1000.0,
                )
            logger.info(
                "reservation_expiry orderId={} outcome=EXPIRED deadline={} correlationId={}",
                orderId,
                deadline,
                correlationId,
            )
            return ReservationExpiryResult(orderId, ReservationExpiryOutcome.EXPIRED)
        } catch (failure: RuntimeException) {
            meterRegistry.counter("beanflow.reservation.expiry", "outcome", "failed").increment()
            logger.error("reservation_expiry orderId={} outcome=FAILED", orderId, failure)
            throw failure
        }
    }

    private fun requireApplied(
        owner: String,
        report: ReservationTransitionReport,
    ): ReservationTransitionReport {
        if (report.result != ReservationTransitionResult.APPLIED || report.targetIds.isEmpty()) {
            throw dependencyFailure("$owner reservation was not eligible for atomic expiry")
        }
        return report
    }

    private fun dependencyFailure(message: String) = DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)

    private fun audit(
        action: String,
        targetType: String,
        targetId: UUID,
        now: Instant,
        sourceReference: String,
        correlationId: String,
        before: Map<String, String> = mapOf("state" to "RESERVED"),
        after: Map<String, String> = mapOf("state" to "EXPIRED"),
    ) = AppendAuditRecordCommand(
        actorId = "SYSTEM",
        actorType = AuditActorType.SYSTEM,
        category = AuditCategory.ORDER_AND_FULFILLMENT,
        action = action,
        targetType = targetType,
        targetId = targetId,
        occurredAt = now,
        reason = "LEASE_DEADLINE_REACHED",
        beforeSummary = before,
        afterSummary = after,
        correlationId = correlationId,
        sourceReference = sourceReference,
    )
}

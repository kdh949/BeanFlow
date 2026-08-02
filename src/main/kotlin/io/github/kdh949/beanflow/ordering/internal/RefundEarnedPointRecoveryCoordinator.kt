package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.eventing.api.OrderCompletedV1
import io.github.kdh949.beanflow.loyalty.api.AccrualUnitAmount
import io.github.kdh949.beanflow.loyalty.api.AccrualUnitKey
import io.github.kdh949.beanflow.loyalty.api.AccrueCompletedOrderPointsCommand
import io.github.kdh949.beanflow.loyalty.api.PointIssuerType
import io.github.kdh949.beanflow.loyalty.api.RecordLegacyCompletedOrderPointsCommand
import io.github.kdh949.beanflow.loyalty.api.RecoverRefundEarnedPointsCommand
import io.github.kdh949.beanflow.loyalty.api.RefundEarnedPointRecoveryOperations
import io.github.kdh949.beanflow.ordering.api.OrderPointAccrualSourceState
import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.payment.api.ClaimedRefundPointRecovery
import io.github.kdh949.beanflow.payment.api.PointAccrualNotApplicableReason
import io.github.kdh949.beanflow.payment.api.PreparePointAccrualCompletionCommand
import io.github.kdh949.beanflow.payment.api.PreparedRefundPointRecovery
import io.github.kdh949.beanflow.payment.api.RecordPointAccrualNotApplicableCommand
import io.github.kdh949.beanflow.payment.api.RefundPointAccrualSnapshotSource
import io.github.kdh949.beanflow.payment.api.RefundPointAccrualSourceState
import io.github.kdh949.beanflow.payment.api.RefundPointAccrualUnit
import io.github.kdh949.beanflow.payment.api.RefundPointRecoveryOperations
import io.github.kdh949.beanflow.payment.api.RefundPointRecoveryResult
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class RefundEarnedPointRecoveryCoordinator(
    private val orderRepository: OrderJpaRepository,
    private val snapshotOperations: io.github.kdh949.beanflow.ordering.api.OrderPointAccrualSnapshotOperations,
    private val paymentOperations: RefundPointRecoveryOperations,
    private val loyaltyOperations: RefundEarnedPointRecoveryOperations,
) {
    private val accrualCalculator = OrderPointAccrualCalculator()

    @Transactional
    fun completeAccrual(
        event: OrderCompletedV1,
        processedAt: Instant,
    ) {
        validateCompletionEvent(event)
        val order =
            orderRepository.findById(event.orderId).orElse(null)
                ?: dependency("Completed Order source is missing")
        if (order.state != OrderState.COMPLETED || order.customerId != event.customerId ||
            order.storeId != event.storeId || order.completedAt != event.completedAt ||
            order.version != event.envelope.aggregateVersion
        ) {
            dependency("Completed Order persisted source does not match its event")
        }
        val source = snapshotOperations.read(event.orderId)
        val completionSource = completionSource(event.orderId, event.envelope.aggregateVersion)
        if (source.sourceState == OrderPointAccrualSourceState.LEGACY_NOT_APPLICABLE) {
            paymentOperations.recordNotApplicable(
                RecordPointAccrualNotApplicableCommand(
                    event.orderId,
                    OrderState.COMPLETED.name,
                    event.completedAt,
                    completionSource,
                    event.envelope.aggregateVersion,
                    PointAccrualNotApplicableReason.LEGACY_COMPLETED_ORDER,
                ),
            )
            loyaltyOperations.recordLegacyNotApplicable(
                RecordLegacyCompletedOrderPointsCommand(
                    event.orderId,
                    event.completedAt,
                    completionSource,
                    event.envelope.aggregateVersion,
                    processedAt,
                ),
            )
            return
        }
        val snapshot = source.snapshot ?: dependency("Snapshotted Order accrual source is missing")
        val eligibility =
            paymentOperations.prepareCompletion(
                PreparePointAccrualCompletionCommand(
                    orderId = event.orderId,
                    completedAt = event.completedAt,
                    completionSourceReference = completionSource,
                    aggregateVersion = event.envelope.aggregateVersion,
                    snapshotSchemaVersion = snapshot.snapshotSchemaVersion,
                    snapshotHash = snapshot.canonicalSnapshotHash,
                    units = snapshot.units.map { it.toPaymentUnit() },
                    processedAt = processedAt,
                ),
            )
        loyaltyOperations.accrue(
            AccrueCompletedOrderPointsCommand(
                orderId = event.orderId,
                customerId = event.customerId,
                completedAt = event.completedAt,
                completionSourceReference = completionSource,
                completionAggregateVersion = event.envelope.aggregateVersion,
                snapshotSchemaVersion = snapshot.snapshotSchemaVersion,
                snapshotHash = snapshot.canonicalSnapshotHash,
                snapshotGrossAmountKrw = snapshot.grossAccrualAmountKrw,
                issuerType = PointIssuerType.valueOf(snapshot.policy.issuerType.name),
                issuerReference = snapshot.policy.issuerReference,
                expiresAt = accrualCalculator.expiresAt(snapshot.policy, event.completedAt),
                units =
                    snapshot.units.map {
                        AccrualUnitAmount(it.orderLineId, it.unitPosition, it.accruedAmountKrw)
                    },
                excludedUnits =
                    eligibility.excludedUnits.mapTo(linkedSetOf()) {
                        AccrualUnitKey(it.orderLineId, it.unitPosition)
                    },
                correlationId = event.envelope.correlationId,
                processedAt = processedAt,
            ),
        )
    }

    fun claimDue(
        now: Instant,
        limit: Int,
    ): List<ClaimedRefundPointRecovery> = paymentOperations.claimDue(now, limit)

    fun recover(
        claim: ClaimedRefundPointRecovery,
        now: Instant,
    ): RefundPointRecoveryResult? {
        val order =
            orderRepository.findById(claim.orderId).orElse(null)
                ?: dependency("Refund recovery Order source is missing")
        val source = pointRecoverySource(order)
        val prepared = paymentOperations.prepareRecovery(claim, source, now) ?: return null
        validatePrepared(prepared, order)
        val result =
            loyaltyOperations.recover(
                RecoverRefundEarnedPointsCommand(
                    refundId = prepared.refundId,
                    orderId = prepared.orderId,
                    customerId = order.customerId,
                    refundSucceededAt = prepared.refundSucceededAt,
                    refundSourceReference = prepared.refundSourceReference,
                    completedAt = prepared.completedAt,
                    completionSourceReference = prepared.completionSourceReference,
                    completionAggregateVersion = prepared.completionAggregateVersion,
                    snapshotSchemaVersion = prepared.snapshotSchemaVersion,
                    snapshotHash = prepared.snapshotHash,
                    targetAmountKrw = prepared.targetAmountKrw,
                    processedAt = now,
                ),
            )
        return RefundPointRecoveryResult(result.recoveredAmountKrw, result.pendingAmountKrw)
    }

    fun recordSuccess(
        claim: ClaimedRefundPointRecovery,
        result: RefundPointRecoveryResult,
        now: Instant,
    ) = paymentOperations.recordSuccess(claim, result, now)

    fun recordFailure(
        claim: ClaimedRefundPointRecovery,
        failure: RuntimeException,
        now: Instant,
    ) = paymentOperations.recordFailure(claim, failure, now)

    private fun pointRecoverySource(order: OrderEntity): RefundPointAccrualSnapshotSource {
        val outcomeAt =
            when (order.state) {
                OrderState.COMPLETED -> order.completedAt ?: dependency("Completed Order time is missing")
                OrderState.REJECTED -> order.rejectedAt ?: dependency("Rejected Order time is missing")
                OrderState.EXPIRED, OrderState.CANCELLED -> order.updatedAt
                else -> null
            }
        val outcomeSource = outcomeAt?.let { orderOutcomeSource(order) }
        val source =
            if (order.state == OrderState.COMPLETED) {
                snapshotOperations.read(order.id)
            } else {
                null
            }
        val snapshot = source?.snapshot
        return RefundPointAccrualSnapshotSource(
            orderId = order.id,
            orderState = order.state.name,
            pointAccrualSourceState = source?.sourceState?.name?.let(RefundPointAccrualSourceState::valueOf),
            outcomeAt = outcomeAt,
            outcomeSourceReference = outcomeSource,
            aggregateVersion = outcomeAt?.let { order.version },
            snapshotSchemaVersion = snapshot?.snapshotSchemaVersion,
            snapshotHash = snapshot?.canonicalSnapshotHash,
            units = snapshot?.units?.map { it.toPaymentUnit() }.orEmpty(),
        )
    }

    private fun validateCompletionEvent(event: OrderCompletedV1) {
        if (event.envelope.eventType != "OrderCompletedV1" || event.envelope.payloadVersion != 1 ||
            event.envelope.aggregateId != event.orderId || event.envelope.aggregateVersion < 0 ||
            event.envelope.occurredAt != event.completedAt
        ) {
            dependency("OrderCompletedV1 source is inconsistent")
        }
    }

    private fun validatePrepared(
        prepared: PreparedRefundPointRecovery,
        order: OrderEntity,
    ) {
        if (prepared.orderId != order.id || order.state != OrderState.COMPLETED ||
            prepared.completedAt != order.completedAt ||
            prepared.completionAggregateVersion != order.version
        ) {
            dependency("Prepared Refund recovery no longer matches its completed Order")
        }
    }

    private fun orderOutcomeSource(order: OrderEntity): String =
        if (order.state == OrderState.COMPLETED) {
            completionSource(order.id, order.version)
        } else {
            "order:${order.id}:${order.state.name.lowercase()}:${order.version}"
        }

    private fun completionSource(
        orderId: UUID,
        aggregateVersion: Long,
    ): String = "order:$orderId:completed:$aggregateVersion"

    private fun io.github.kdh949.beanflow.ordering.api.OrderPointAccrualUnitSnapshot.toPaymentUnit() =
        RefundPointAccrualUnit(orderLineId, unitPosition, accruedAmountKrw)

    private fun dependency(message: String): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)
}

@Component
internal class OrderCompletedPointAccrualListener(
    private val coordinator: RefundEarnedPointRecoveryCoordinator,
    private val clock: Clock,
) {
    @ApplicationModuleListener
    fun on(event: OrderCompletedV1) {
        coordinator.completeAccrual(event, clock.instant())
    }
}

@Component
internal class RefundEarnedPointRecoveryWorker(
    private val coordinator: RefundEarnedPointRecoveryCoordinator,
    private val clock: Clock,
    @Value("\${beanflow.payment.point-recovery.chunk-size:50}")
    private val chunkSize: Int,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${beanflow.payment.point-recovery.fixed-delay-ms:5000}",
        initialDelayString = "\${beanflow.payment.point-recovery.initial-delay-ms:15000}",
    )
    fun runScheduled() {
        runOnce()
    }

    fun runOnce(): Int {
        val claims = coordinator.claimDue(clock.instant(), chunkSize)
        claims.forEach { claim ->
            try {
                val result = coordinator.recover(claim, clock.instant()) ?: return@forEach
                coordinator.recordSuccess(claim, result, clock.instant())
            } catch (failure: RuntimeException) {
                try {
                    coordinator.recordFailure(claim, failure, clock.instant())
                } catch (recordFailure: RuntimeException) {
                    logger.error(
                        "refund_earned_point_recovery refundId={} workId={} outcome=CLAIM_RETAINED attempt={}",
                        claim.refundId,
                        claim.workId,
                        claim.attemptCount,
                        recordFailure,
                    )
                }
            }
        }
        return claims.size
    }
}

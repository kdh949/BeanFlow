package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.loyalty.api.PartialRefundPointOperations
import io.github.kdh949.beanflow.loyalty.api.PartialRefundPointPolicyMode
import io.github.kdh949.beanflow.loyalty.api.PartialRefundPointSlice
import io.github.kdh949.beanflow.loyalty.api.PointIssuerType
import io.github.kdh949.beanflow.loyalty.api.RestorePartialRefundPointsCommand
import io.github.kdh949.beanflow.payment.api.ClaimedPartialRefundRestoration
import io.github.kdh949.beanflow.payment.api.PartialRefundPaymentOperations
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

@Service
internal class PartialRefundRestorationService(
    private val paymentOperations: PartialRefundPaymentOperations,
    private val pointOperations: PartialRefundPointOperations,
) {
    fun claimDue(
        now: Instant,
        limit: Int,
    ): List<ClaimedPartialRefundRestoration> = paymentOperations.claimDueRestorations(now, limit)

    fun callLoyalty(claim: ClaimedPartialRefundRestoration): Long {
        val source = paymentOperations.restorationCommand(claim.refundId)
        return pointOperations
            .restore(
                RestorePartialRefundPointsCommand(
                    refundId = source.refundId,
                    orderId = source.orderId,
                    refundSucceededAt = source.refundSucceededAt,
                    sourceReference = source.sourceReference,
                    policyVersionId = source.policyVersionId,
                    policyMode = PartialRefundPointPolicyMode.valueOf(source.policyMode.name),
                    compensationValidityDays = source.compensationValidityDays,
                    slices =
                        source.slices.map { slice ->
                            PartialRefundPointSlice(
                                orderLineId = slice.orderLineId,
                                pointReservationAllocationId = slice.pointReservationAllocationId,
                                originalPointLotId = slice.originalPointLotId,
                                issuerType = PointIssuerType.valueOf(slice.issuerType.name),
                                issuerReference = slice.issuerReference,
                                amountKrw = slice.amountKrw,
                            )
                        },
                ),
            ).restoredAmountKrw
    }

    fun recordSuccess(
        claim: ClaimedPartialRefundRestoration,
        restoredAmountKrw: Long,
        now: Instant,
    ) {
        paymentOperations.recordRestorationSuccess(claim, restoredAmountKrw, now)
    }

    fun recordFailure(
        claim: ClaimedPartialRefundRestoration,
        failure: RuntimeException,
        now: Instant,
    ) {
        paymentOperations.recordRestorationFailure(claim, failure, now)
    }
}

@Component
internal class PartialRefundRestorationWorker(
    private val service: PartialRefundRestorationService,
    private val clock: Clock,
    @Value("\${beanflow.payment.refund-restoration.chunk-size:50}")
    private val chunkSize: Int,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${beanflow.payment.refund-restoration.fixed-delay-ms:5000}",
        initialDelayString = "\${beanflow.payment.refund-restoration.initial-delay-ms:15000}",
    )
    fun runScheduled() {
        runOnce()
    }

    fun runOnce(): Int {
        val claims = service.claimDue(clock.instant(), chunkSize)
        claims.forEach { claim ->
            try {
                val amount = service.callLoyalty(claim)
                service.recordSuccess(claim, amount, clock.instant())
            } catch (failure: RuntimeException) {
                try {
                    service.recordFailure(claim, failure, clock.instant())
                } catch (recordFailure: RuntimeException) {
                    logger.error(
                        "partial_refund_restoration refundId={} workId={} outcome=CLAIM_RETAINED attempt={}",
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

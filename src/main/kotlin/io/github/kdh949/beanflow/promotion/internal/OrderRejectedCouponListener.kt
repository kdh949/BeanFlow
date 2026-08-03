package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.eventing.api.BenefitRestorationPolicySnapshotV1
import io.github.kdh949.beanflow.eventing.api.OrderCancelledV1
import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import io.github.kdh949.beanflow.operations.api.OrderCompensationOperations
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepState
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepType
import io.github.kdh949.beanflow.promotion.api.CouponReservationOperations
import io.github.kdh949.beanflow.promotion.api.ExpiredCouponRestorationMode
import io.github.kdh949.beanflow.promotion.api.RestoreCouponAfterTerminationCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OrderTerminationTrigger
import io.github.kdh949.beanflow.shared.api.ReservationTransitionResult
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
internal class OrderRejectedCouponListener(
    private val couponOperations: CouponReservationOperations,
    private val compensationOperations: OrderCompensationOperations,
) {
    @ApplicationModuleListener(id = "beanflow.order-compensation.order-rejected.coupon.v1")
    fun on(event: OrderRejectedV1) {
        if (!event.couponRequired) return
        restore(
            event.orderId,
            event.rejectedAt,
            "event:${event.envelope.eventId}:coupon",
            OrderTerminationTrigger.STORE_REJECTION,
            event.couponPolicy,
        )
    }

    @ApplicationModuleListener(id = "beanflow.order-compensation.order-cancelled.coupon.v1")
    fun on(event: OrderCancelledV1) {
        if (!event.couponRequired) return
        restore(
            event.orderId,
            event.cancelledAt,
            "order:${event.orderId}:customer-cancellation:${event.envelope.aggregateVersion}:coupon",
            OrderTerminationTrigger.CUSTOMER_CANCELLATION,
            event.couponPolicy,
        )
    }

    private fun restore(
        orderId: UUID,
        terminatedAt: Instant,
        sourceReference: String,
        trigger: OrderTerminationTrigger,
        policy: BenefitRestorationPolicySnapshotV1,
    ) {
        val report =
            couponOperations.restoreUsedAfterTermination(
                RestoreCouponAfterTerminationCommand(
                    orderId = orderId,
                    terminatedAt = terminatedAt,
                    sourceReference = sourceReference,
                    trigger = trigger,
                    policyVersionId = policy.policyVersionId,
                    mode = ExpiredCouponRestorationMode.valueOf(policy.mode),
                    compensationValidityDays = policy.compensationValidityDays,
                ),
            )
        if (report.result == ReservationTransitionResult.NOT_ELIGIBLE) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Used coupon could not be restored",
            )
        }
        compensationOperations.recordStep(
            orderId,
            OrderCompensationStepType.COUPON,
            OrderCompensationStepState.SUCCEEDED,
            null,
            terminatedAt,
        )
    }
}

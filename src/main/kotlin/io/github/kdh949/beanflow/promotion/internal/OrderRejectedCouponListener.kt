package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import io.github.kdh949.beanflow.operations.api.RejectionCompensationOperations
import io.github.kdh949.beanflow.operations.api.RejectionCompensationStepState
import io.github.kdh949.beanflow.operations.api.RejectionCompensationStepType
import io.github.kdh949.beanflow.promotion.api.CouponReservationOperations
import io.github.kdh949.beanflow.promotion.api.ExpiredCouponRestorationMode
import io.github.kdh949.beanflow.promotion.api.RestoreCouponByRejectionCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.ReservationTransitionResult
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

@Component
internal class OrderRejectedCouponListener(
    private val couponOperations: CouponReservationOperations,
    private val compensationOperations: RejectionCompensationOperations,
) {
    @ApplicationModuleListener
    fun on(event: OrderRejectedV1) {
        if (!event.couponRequired) return
        val report =
            couponOperations.restoreUsedByRejection(
                RestoreCouponByRejectionCommand(
                    orderId = event.orderId,
                    rejectedAt = event.rejectedAt,
                    sourceReference = "event:${event.envelope.eventId}:coupon",
                    mode = ExpiredCouponRestorationMode.valueOf(event.policyMode),
                    compensationValidityDays = event.policyValidityDays,
                ),
            )
        if (report.result == ReservationTransitionResult.NOT_ELIGIBLE) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Used coupon could not be restored",
            )
        }
        compensationOperations.recordStep(
            event.orderId,
            RejectionCompensationStepType.COUPON,
            RejectionCompensationStepState.SUCCEEDED,
            null,
            event.rejectedAt,
        )
    }
}

package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import io.github.kdh949.beanflow.operations.api.OrderCompensationOperations
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepState
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepType
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
    private val compensationOperations: OrderCompensationOperations,
) {
    @ApplicationModuleListener(id = "beanflow.order-compensation.order-rejected.coupon.v1")
    fun on(event: OrderRejectedV1) {
        if (!event.couponRequired) return
        val report =
            couponOperations.restoreUsedByRejection(
                RestoreCouponByRejectionCommand(
                    orderId = event.orderId,
                    rejectedAt = event.rejectedAt,
                    sourceReference = "event:${event.envelope.eventId}:coupon",
                    mode = ExpiredCouponRestorationMode.valueOf(event.couponPolicy.mode),
                    compensationValidityDays = event.couponPolicy.compensationValidityDays,
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
            OrderCompensationStepType.COUPON,
            OrderCompensationStepState.SUCCEEDED,
            null,
            event.rejectedAt,
        )
    }
}

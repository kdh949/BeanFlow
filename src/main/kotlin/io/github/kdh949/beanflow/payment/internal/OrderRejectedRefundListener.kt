package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

@Component
internal class OrderRejectedRefundListener(
    private val refundService: RejectionRefundService,
) {
    @ApplicationModuleListener
    fun on(event: OrderRejectedV1) {
        refundService.request(event)
    }
}

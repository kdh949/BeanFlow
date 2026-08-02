package io.github.kdh949.beanflow.notification.internal

import io.github.kdh949.beanflow.eventing.api.OrderReadyV1
import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import io.github.kdh949.beanflow.eventing.api.StoreAcceptanceWarningRequestedV1
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

@Component
internal class StoreAcceptanceWarningNotificationListener(
    private val deliveryService: NotificationDeliveryService,
) {
    @ApplicationModuleListener
    fun on(event: StoreAcceptanceWarningRequestedV1) {
        deliveryService.requestWarning(event)
    }
}

@Component
internal class OrderRejectedNotificationListener(
    private val deliveryService: NotificationDeliveryService,
) {
    @ApplicationModuleListener(id = "beanflow.order-compensation.order-rejected.customer-notification.v1")
    fun on(event: OrderRejectedV1) {
        deliveryService.requestRejection(event)
    }
}

@Component
internal class OrderReadyNotificationListener(
    private val deliveryService: NotificationDeliveryService,
) {
    @ApplicationModuleListener
    fun on(event: OrderReadyV1) {
        deliveryService.requestReady(event)
    }
}

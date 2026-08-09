package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.CreateOrderCommand
import io.github.kdh949.beanflow.ordering.api.StoredHttpResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class OrderCreationTransaction(
    private val workflow: OrderCreationWorkflow,
    private val idempotencyService: OrderIdempotencyService,
    private val responseFactory: OrderCreationResponseFactory,
) {
    @Transactional
    fun create(
        idempotencyRecordId: UUID,
        orderId: UUID,
        command: CreateOrderCommand,
    ): StoredHttpResponse {
        val outcome = workflow.create(orderId, command)
        val response = responseFactory.create(outcome.order, outcome.benefitOnlyPayment)
        idempotencyService.complete(idempotencyRecordId, outcome.order.id, response)
        return response
    }

    internal companion object {
        fun pickupSource(orderId: UUID) = "order:$orderId:pickup"

        fun stockSource(orderId: UUID) = "order:$orderId:stock"

        fun couponSource(orderId: UUID) = "order:$orderId:coupon"

        fun pointsSource(orderId: UUID) = "order:$orderId:points"

        fun paymentSource(orderId: UUID) = "order:$orderId:benefit-only-payment"

        fun benefitSnapshotSource(orderId: UUID) = "order:$orderId:benefit-snapshot"

        fun createAuditSource(orderId: UUID) = "order:$orderId:create"
    }
}

package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.CreateOrderCommand
import io.github.kdh949.beanflow.ordering.api.StoredHttpResponse
import io.github.kdh949.beanflow.shared.api.PerformanceOperation
import io.github.kdh949.beanflow.shared.api.PerformancePhaseTelemetry
import io.github.kdh949.beanflow.shared.api.PerformanceStage
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class OrderCreationTransaction(
    private val workflow: OrderCreationWorkflow,
    private val quoteCoordinator: OrderQuoteCoordinator,
    private val idempotencyService: OrderIdempotencyService,
    private val responseFactory: OrderCreationResponseFactory,
    private val phaseTelemetry: PerformancePhaseTelemetry,
) {
    @Transactional
    fun create(
        idempotencyRecordId: UUID,
        orderId: UUID,
        command: CreateOrderCommand,
    ): StoredHttpResponse {
        val expectedFingerprint =
            command.expectedQuoteFingerprint
                ?: throw io.github.kdh949.beanflow.shared.api.DomainFailure(
                    io.github.kdh949.beanflow.shared.api.FailureCode.INVALID_REQUEST,
                    "Expected quote fingerprint is required",
                )
        val currentQuote =
            phaseTelemetry.observe(PerformanceOperation.ORDER_CREATE, PerformanceStage.QUOTE_REVALIDATION) {
                quoteCoordinator.lockForOrderCreation(command)
            }
        if (currentQuote.response.quoteFingerprint != expectedFingerprint) {
            throw OrderQuoteStaleFailure(currentQuote.response)
        }
        val outcome =
            phaseTelemetry.observe(PerformanceOperation.ORDER_CREATE, PerformanceStage.WORKFLOW) {
                workflow.create(orderId, command, preparedQuote = currentQuote)
            }
        val response = responseFactory.create(outcome.order, outcome.benefitOnlyPayment)
        idempotencyService.complete(idempotencyRecordId, outcome.order.id, response)
        return response
    }

    internal companion object {
        fun pickupSource(orderId: UUID) = "order:$orderId:pickup"

        fun couponSource(orderId: UUID) = "order:$orderId:coupon"

        fun pointsSource(orderId: UUID) = "order:$orderId:points"

        fun paymentSource(orderId: UUID) = "order:$orderId:benefit-only-payment"

        fun benefitSnapshotSource(orderId: UUID) = "order:$orderId:benefit-snapshot"

        fun createAuditSource(orderId: UUID) = "order:$orderId:create"
    }
}

internal class OrderQuoteStaleFailure(
    val currentQuote: io.github.kdh949.beanflow.ordering.api.OrderQuoteResponse,
) : RuntimeException("Order quote is stale")

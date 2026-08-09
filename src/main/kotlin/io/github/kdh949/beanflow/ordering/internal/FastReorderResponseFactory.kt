package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.StoredHttpResponse
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
internal class FastReorderResponseFactory(
    private val objectMapper: ObjectMapper,
    private val creationResponses: OrderCreationResponseFactory,
) {
    fun create(
        outcome: OrderCreationOutcome,
        priceComparison: ReorderPriceComparison,
    ): StoredHttpResponse {
        val representation =
            outcome.benefitOnlyPayment?.let { payment ->
                val base = creationResponses.benefitOnly(outcome.order, payment)
                BenefitOnlyReorderOrderCreationResponse(base.order, base.payment, priceComparison)
            } ?: run {
                val base = creationResponses.pending(outcome.order)
                PendingPaymentReorderOrderCreationResponse(base.order, priceComparison)
            }
        return StoredHttpResponse(status = 201, body = objectMapper.writeValueAsString(representation))
    }
}

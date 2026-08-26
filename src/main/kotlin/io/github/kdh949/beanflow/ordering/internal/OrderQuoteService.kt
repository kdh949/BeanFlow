package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.OrderQuoteCommand
import io.github.kdh949.beanflow.ordering.api.OrderQuoteResponse
import io.github.kdh949.beanflow.ordering.api.OrderQuoteUseCase
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
internal class OrderQuoteService(
    private val coordinator: OrderQuoteCoordinator,
    private val meterRegistry: MeterRegistry,
) : OrderQuoteUseCase {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun quote(command: OrderQuoteCommand): OrderQuoteResponse {
        val sample = Timer.start(meterRegistry)
        return try {
            coordinator.inspect(command).response.also {
                meterRegistry.counter("beanflow.order.quote.attempts", "outcome", "success").increment()
            }
        } catch (failure: RuntimeException) {
            meterRegistry.counter("beanflow.order.quote.attempts", "outcome", "failure").increment()
            throw failure
        } finally {
            sample.stop(meterRegistry.timer("beanflow.order.quote.duration"))
        }
    }
}

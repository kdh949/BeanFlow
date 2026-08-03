package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.eventing.api.SettlementDisputeDecidedV1
import io.github.kdh949.beanflow.eventing.api.SettlementDisputeFiledV1
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

@Component
internal class SettlementDisputeEventListeners(
    private val meterRegistry: MeterRegistry,
) {
    @ApplicationModuleListener(id = "beanflow.operations.settlement-dispute-filed-v1")
    fun onFiled(event: SettlementDisputeFiledV1) {
        meterRegistry
            .counter(
                "beanflow.operations.settlement_dispute.event.count",
                "event_type",
                "filed",
                "state",
                event.state,
            ).increment()
    }

    @ApplicationModuleListener(id = "beanflow.operations.settlement-dispute-decided-v1")
    fun onDecided(event: SettlementDisputeDecidedV1) {
        meterRegistry
            .counter(
                "beanflow.operations.settlement_dispute.event.count",
                "event_type",
                "decided",
                "state",
                event.state,
            ).increment()
    }
}

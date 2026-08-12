package io.github.kdh949.beanflow.settlement.api

import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineFact
import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineQuery

interface SettlementSupportTimelineOperations {
    fun findTimelineFacts(query: SupportOwnerTimelineQuery): List<SupportOwnerTimelineFact>
}

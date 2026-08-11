package io.github.kdh949.beanflow.loyalty.api

import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineFact
import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineQuery

interface LoyaltySupportTimelineOperations {
    fun findTimelineFacts(query: SupportOwnerTimelineQuery): List<SupportOwnerTimelineFact>
}

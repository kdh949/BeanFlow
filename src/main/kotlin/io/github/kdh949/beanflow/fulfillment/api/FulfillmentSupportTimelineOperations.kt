package io.github.kdh949.beanflow.fulfillment.api

import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineFact
import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineQuery

interface FulfillmentSupportTimelineOperations {
    fun findTimelineFacts(query: SupportOwnerTimelineQuery): List<SupportOwnerTimelineFact>
}

package io.github.kdh949.beanflow.notification.api

import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineFact
import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineQuery

interface NotificationSupportTimelineOperations {
    fun findTimelineFacts(query: SupportOwnerTimelineQuery): List<SupportOwnerTimelineFact>
}

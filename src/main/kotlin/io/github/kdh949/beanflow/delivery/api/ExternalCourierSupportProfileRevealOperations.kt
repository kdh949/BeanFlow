package io.github.kdh949.beanflow.delivery.api

import io.github.kdh949.beanflow.shared.api.RevealPersonalDataCommand
import io.github.kdh949.beanflow.shared.api.RevealedPersonalData

interface ExternalCourierSupportProfileRevealOperations {
    fun reveal(command: RevealPersonalDataCommand): RevealedPersonalData
}

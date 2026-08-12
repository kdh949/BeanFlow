package io.github.kdh949.beanflow.identity.api

import io.github.kdh949.beanflow.shared.api.RevealPersonalDataCommand
import io.github.kdh949.beanflow.shared.api.RevealedPersonalData

interface CustomerSupportProfileRevealOperations {
    fun reveal(command: RevealPersonalDataCommand): RevealedPersonalData
}

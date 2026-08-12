package io.github.kdh949.beanflow.merchant.api

import io.github.kdh949.beanflow.shared.api.RevealPersonalDataCommand
import io.github.kdh949.beanflow.shared.api.RevealedPersonalData

interface StoreSupportProfileRevealOperations {
    fun reveal(command: RevealPersonalDataCommand): RevealedPersonalData
}

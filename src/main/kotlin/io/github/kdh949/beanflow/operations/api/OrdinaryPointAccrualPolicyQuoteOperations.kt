package io.github.kdh949.beanflow.operations.api

import java.util.UUID

interface OrdinaryPointAccrualPolicyQuoteOperations {
    fun inspectForQuote(storeId: UUID): SelectedOrdinaryPointAccrualPolicy

    fun lockForOrderCreation(storeId: UUID): SelectedOrdinaryPointAccrualPolicy
}

package io.github.kdh949.beanflow.merchant.api

import java.util.UUID

data class MerchantOrderQuoteSnapshot(
    val storeAcceptingOrders: Boolean,
    val storePickupEnabled: Boolean,
    val lines: List<MenuLineQuote>,
)

/** Owner-verified menu pricing and availability inputs used by the order quote fingerprint. */
interface MerchantOrderQuoteOperations {
    fun inspectForQuote(
        storeId: UUID,
        lines: List<QuoteOrderLine>,
    ): MerchantOrderQuoteSnapshot

    fun lockForOrderCreation(
        storeId: UUID,
        lines: List<QuoteOrderLine>,
    ): MerchantOrderQuoteSnapshot
}

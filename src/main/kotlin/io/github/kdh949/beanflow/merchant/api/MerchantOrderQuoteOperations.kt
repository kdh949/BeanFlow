package io.github.kdh949.beanflow.merchant.api

import java.util.UUID

data class MerchantMenuQuoteMaterial(
    val menuId: UUID,
    val menuVersion: Long,
    val configurationId: UUID,
    val configurationVersion: Long,
)

data class MerchantOrderQuoteSnapshot(
    val storeVersion: Long,
    val lines: List<MenuLineQuote>,
    val materials: List<MerchantMenuQuoteMaterial>,
)

/** Owner-verified menu pricing and availability inputs used by the order quote fingerprint. */
interface MerchantOrderQuoteOperations {
    fun quoteForOrder(
        storeId: UUID,
        lines: List<QuoteOrderLine>,
    ): MerchantOrderQuoteSnapshot
}

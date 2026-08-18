package io.github.kdh949.beanflow.demo

import java.util.UUID

/**
 * Deterministic identifiers for the `local-demo` fixture.
 *
 * Every value is synthetic. There is no real person, no customer location, no card number and no
 * CVC anywhere in this fixture; the payment method carries only an opaque scripted token reference
 * whose suffix tells the local gateway which result to return.
 */
internal object LocalDemoFixture {
    val CUSTOMER_ID: UUID = UUID.fromString(LocalDemoIdentity.CUSTOMER_ID)
    const val CUSTOMER_LOGIN_ID = "demo.customer"
    const val CUSTOMER_PASSWORD = "local demo customer password 123!"
    const val CUSTOMER_DISPLAY_NAME = "BeanFlow Demo Customer"
    val STORE_OWNER_ID: UUID = UUID.fromString(LocalDemoIdentity.STORE_OWNER_ID)
    val OTHER_STORE_OWNER_ID: UUID = UUID.fromString(LocalDemoIdentity.OTHER_STORE_OWNER_ID)
    const val MERCHANT_LOGIN_ID = "demo.merchant"
    const val MERCHANT_INITIAL_PASSWORD = "local demo merchant temporary password 123!"
    const val MERCHANT_PASSWORD = "local demo merchant password changed 123!"
    const val MERCHANT_DISPLAY_NAME = "BeanFlow Demo Merchant"
    const val OTHER_MERCHANT_LOGIN_ID = "demo.othermerchant"
    const val OTHER_MERCHANT_PASSWORD = "local demo other merchant password 123!"
    const val OTHER_MERCHANT_DISPLAY_NAME = "BeanFlow Demo Other Merchant"

    val STORE_ID: UUID = UUID.fromString("d1000000-0000-4000-8000-000000000001")
    val OTHER_STORE_ID: UUID = UUID.fromString("d1000000-0000-4000-8000-000000000002")

    /** Synthetic store coordinates. Customer coordinates are never stored (BR-28). */
    const val STORE_LONGITUDE = 127.0
    const val STORE_LATITUDE = 37.5
    const val OTHER_STORE_LONGITUDE = 127.004
    const val OTHER_STORE_LATITUDE = 37.5
    const val STORE_NAME = "BeanFlow Demo Roastery"
    const val OTHER_STORE_NAME = "BeanFlow Demo Annex"

    /**
     * 법정동 codes for the demo stores (ADR-112 3절).
     *
     * `region_code` is `NOT NULL`, so a seeded store without one would fail on insert rather than
     * quietly disappear from region search. The two differ so the demo shows more than one region.
     */
    const val STORE_REGION_CODE = "1168010100"
    const val OTHER_STORE_REGION_CODE = "1168010500"

    val AMERICANO_MENU_ID: UUID = UUID.fromString("d2000000-0000-4000-8000-000000000001")
    val SOLD_OUT_MENU_ID: UUID = UUID.fromString("d2000000-0000-4000-8000-000000000002")
    val EXTRA_SHOT_OPTION_ID: UUID = UUID.fromString("d3000000-0000-4000-8000-000000000001")
    val OAT_MILK_OPTION_ID: UUID = UUID.fromString("d3000000-0000-4000-8000-000000000002")

    /** One configuration per orderable option combination. */
    val PLAIN_CONFIGURATION_ID: UUID = UUID.fromString("d4000000-0000-4000-8000-000000000001")
    val EXTRA_SHOT_CONFIGURATION_ID: UUID = UUID.fromString("d4000000-0000-4000-8000-000000000002")
    val PLAIN_REQUIREMENT_ID: UUID = UUID.fromString("d4000000-0000-4000-8000-000000000101")
    val EXTRA_SHOT_REQUIREMENT_ID: UUID = UUID.fromString("d4000000-0000-4000-8000-000000000102")

    val COFFEE_SELLABLE_UNIT_ID: UUID = UUID.fromString("d5000000-0000-4000-8000-000000000001")

    val PICKUP_SLOT_IDS: List<UUID> =
        listOf(
            UUID.fromString("d6000000-0000-4000-8000-000000000001"),
            UUID.fromString("d6000000-0000-4000-8000-000000000002"),
            UUID.fromString("d6000000-0000-4000-8000-000000000003"),
        )

    val POINT_ACCOUNT_ID: UUID = UUID.fromString("d7000000-0000-4000-8000-000000000001")
    val SETTLEMENT_TERMS_ID: UUID = UUID.fromString("d8000000-0000-4000-8000-000000000001")
    val PAYMENT_METHOD_ID: UUID = UUID.fromString("d9000000-0000-4000-8000-000000000001")
    val CAMPAIGN_ID: UUID = UUID.fromString("da000000-0000-4000-8000-000000000001")
    val CAMPAIGN_MENU_ID: UUID = UUID.fromString("da000000-0000-4000-8000-000000000011")
    val COUPON_ISSUANCE_ID: UUID = UUID.fromString("da000000-0000-4000-8000-000000000101")

    // Historical, fully synthetic financial-tail fixture. These identifiers are never emitted by
    // the scripts; the runtime smoke discovers the public resources through their own APIs.
    val HISTORICAL_ORDER_ID: UUID = UUID.fromString("dc000000-0000-4000-8000-000000000001")
    val HISTORICAL_ORDER_LINE_ID: UUID = UUID.fromString("dc000000-0000-4000-8000-000000000011")
    val HISTORICAL_PAYMENT_ID: UUID = UUID.fromString("dc000000-0000-4000-8000-000000000021")
    val HISTORICAL_REFUND_ID: UUID = UUID.fromString("dc000000-0000-4000-8000-000000000031")
    val HISTORICAL_SETTLEMENT_BATCH_ID: UUID = UUID.fromString("dc000000-0000-4000-8000-000000000041")
    val HISTORICAL_SETTLEMENT_ITEM_ID: UUID = UUID.fromString("dc000000-0000-4000-8000-000000000051")
    val ADJUSTED_ORDER_ID: UUID = UUID.fromString("dc000000-0000-4000-8000-000000000002")
    val ADJUSTED_ORDER_LINE_ID: UUID = UUID.fromString("dc000000-0000-4000-8000-000000000012")
    val ADJUSTED_PAYMENT_ID: UUID = UUID.fromString("dc000000-0000-4000-8000-000000000022")
    val ADJUSTED_SETTLEMENT_BATCH_ID: UUID = UUID.fromString("dc000000-0000-4000-8000-000000000042")
    val ADJUSTED_SETTLEMENT_ITEM_ID: UUID = UUID.fromString("dc000000-0000-4000-8000-000000000052")
    val REFUND_SETTLEMENT_ADJUSTMENT_ID: UUID = UUID.fromString("dc000000-0000-4000-8000-000000000061")
    const val HISTORICAL_ORDER_REFERENCE = "BF-D3M2-S9F4"
    const val ADJUSTED_ORDER_REFERENCE = "BF-D3M3-S9F4"
    const val HISTORICAL_ORDER_GROSS_KRW = 10_000L
    const val HISTORICAL_REFUND_KRW = 5_000L

    val OWNER_MEMBERSHIP_ID: UUID = UUID.fromString("db000000-0000-4000-8000-000000000001")
    val OTHER_OWNER_MEMBERSHIP_ID: UUID = UUID.fromString("db000000-0000-4000-8000-000000000002")

    const val AMERICANO_PRICE_KRW = 4_500L
    const val SOLD_OUT_PRICE_KRW = 6_000L
    const val EXTRA_SHOT_PRICE_KRW = 500L
    const val OAT_MILK_PRICE_KRW = 800L
    const val STOCK_QUANTITY = 500L
    const val INITIAL_POINT_BALANCE_KRW = 0L
    const val COUPON_DISCOUNT_KRW = 1_000L
    const val SETTLEMENT_FEE_RATE_BPS = 300

    /**
     * Scripted token reference. The local gateway keys its result off the suffix, so this is an
     * explicit instruction to approve — never card data.
     */
    const val SCRIPTED_TOKEN_REFERENCE = "scripted-token:local-demo:approved"
    const val PAYMENT_PROVIDER = "local-scripted"
}

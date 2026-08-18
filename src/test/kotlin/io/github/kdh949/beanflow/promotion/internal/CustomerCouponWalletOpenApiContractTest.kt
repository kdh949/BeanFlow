package io.github.kdh949.beanflow.promotion.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

internal class CustomerCouponWalletOpenApiContractTest {
    @Test
    fun `target and runtime expose the actor and store scoped coupon wallet without source identifiers`() {
        val target = Path.of("openapi/beanflow-v1.yaml").readText()
        val runtime = Path.of("openapi/beanflow-v1-runtime.yaml").readText()

        assertThat(pathItem(target, "/me/coupons"))
            .contains(
                "Promotion",
                "listCurrentCustomerCoupons",
                "storeId",
                "Cursor",
                "Limit",
                "CustomerCouponWalletPage",
                "\"400\"",
                "\"401\"",
                "\"403\"",
                "\"503\"",
            )
        assertThat(pathItem(runtime, "/me/coupons"))
            .contains("./beanflow-v1.yaml#/paths/~1me~1coupons")
        assertThat(schema(target, "CustomerCouponWalletItem"))
            .contains("couponIssuanceId", "benefit", "minimumOrderKrw", "couponExpiresAt", "applicable", "reasonCode")
            .doesNotContain("campaignId:", "customerId:", "originalIssuanceId:")
        assertThat(schema(target, "CouponWalletInapplicableReason")).contains("STORE_NOT_APPLICABLE")
    }

    private fun pathItem(
        document: String,
        path: String,
    ): String =
        document
            .substringAfter("  $path:\n", missingDelimiterValue = "")
            .substringBefore("\n  /")

    private fun schema(
        document: String,
        name: String,
    ): String =
        Regex("(?ms)^    ${Regex.escape(name)}:\\n(.*?)(?=^    [A-Za-z][^\\n]*:\\n|\\z)")
            .find(document)
            ?.groupValues
            ?.get(1)
            .orEmpty()
}

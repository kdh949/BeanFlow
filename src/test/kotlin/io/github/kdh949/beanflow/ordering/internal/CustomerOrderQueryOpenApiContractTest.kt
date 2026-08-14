package io.github.kdh949.beanflow.ordering.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

internal class CustomerOrderQueryOpenApiContractTest {
    @Test
    fun `target and runtime expose the customer order page and detail without internal identifiers`() {
        val target = Path.of("openapi/beanflow-v1.yaml").readText()
        val runtime = Path.of("openapi/beanflow-v1-runtime.yaml").readText()

        assertThat(pathItem(target, "/me/orders"))
            .contains("CustomerOrderPage", "ACTIVE", "PAST", "Cursor", "Limit", "\"400\"", "\"503\"")
        assertThat(pathItem(target, "/me/orders/{orderReference}"))
            .contains("CustomerOrderDetail", "OrderReference", "\"403\"", "\"404\"", "\"503\"")
        assertThat(pathItem(runtime, "/me/orders"))
            .contains("./beanflow-v1.yaml#/paths/~1me~1orders")
        assertThat(pathItem(runtime, "/me/orders/{orderReference}"))
            .contains("./beanflow-v1.yaml#/paths/~1me~1orders~1{orderReference}")
        assertThat(runtime).doesNotContain("RuntimePublicCustomerOrder")

        assertThat(schema(target, "CustomerOrderAllowedAction"))
            .contains("CANCEL", "REORDER", "VIEW_REFUND")
        assertThat(schema(target, "CustomerOrderSummary"))
            .contains("orderReference", "pickupNumber", "itemSummary", "allowedActions")
            .doesNotContain("orderId:", "paymentId:", "providerReference:", "failureCode:", "cancellationDetail:")
        assertThat(schema(target, "CustomerOrderDetail"))
            .contains("orderReference", "lines", "allowedActions", "paymentRecovery")
            .doesNotContain("orderId:", "orderLineId:", "menuId:", "paymentId:", "providerReference:", "failureCode:")
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

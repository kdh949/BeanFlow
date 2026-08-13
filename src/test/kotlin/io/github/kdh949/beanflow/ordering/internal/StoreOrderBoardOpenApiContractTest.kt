package io.github.kdh949.beanflow.ordering.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

internal class StoreOrderBoardOpenApiContractTest {
    @Test
    fun `target and runtime expose the conditional board and expected-status action without private data`() {
        val target = Path.of("openapi/beanflow-v1.yaml").readText()
        val runtime = Path.of("openapi/beanflow-v1-runtime.yaml").readText()

        assertThat(pathItem(target, "/stores/{storeId}/orders"))
            .contains("StoreOrderBoard", "If-None-Match", "ETag", "PENDING_ACCEPTANCE", "\"304\"", "\"503\"")
        assertThat(pathItem(target, "/stores/{storeId}/orders/{orderReference}"))
            .contains("StoreOrderBoardItem", "\"403\"", "\"404\"", "\"503\"")
        assertThat(pathItem(target, "/stores/{storeId}/orders/{orderReference}/transitions"))
            .contains("StoreOrderActionRequest", "StoreOrderBoardItem", "\"202\"", "\"409\"", "\"422\"")
        assertThat(pathItem(runtime, "/stores/{storeId}/orders"))
            .contains("./beanflow-v1.yaml#/paths/~1stores~1{storeId}~1orders")
        assertThat(pathItem(runtime, "/stores/{storeId}/orders/{orderReference}"))
            .contains("./beanflow-v1.yaml#/paths/~1stores~1{storeId}~1orders~1{orderReference}")
        assertThat(pathItem(runtime, "/stores/{storeId}/orders/{orderReference}/transitions"))
            .contains("./beanflow-v1.yaml#/paths/~1stores~1{storeId}~1orders~1{orderReference}~1transitions")
        assertThat(runtime).doesNotContain("RuntimePublicStoreOrder")

        assertThat(schema(target, "StoreOrderActionRequest"))
            .contains("action", "expectedStatus", "PAID", "ACCEPTED", "PREPARING", "READY", "reason")
        assertThat(schema(target, "MerchantStoreList"))
            .contains("type: array", "#/components/schemas/MerchantStore")
            .doesNotContain("properties:")
        assertThat(schema(target, "StoreOrderBoardItem"))
            .contains(
                "orderReference",
                "pickupNumber",
                "pickupBusinessDate",
                "lane",
                "status",
                "itemSummary",
                "acceptancePhase",
                "allowedActions",
                "compensationRecovery",
            ).doesNotContain(
                "orderId:",
                "customerId:",
                "paymentId:",
                "providerReference:",
                "subtotalKrw:",
                "payableKrw:",
                "steps:",
                "attemptCount:",
                "lastErrorCode:",
            )
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

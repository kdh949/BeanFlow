package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.architecture.assertOpenApiResponseStatuses
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

internal class StoreOrderBoardOpenApiContractTest {
    @Test
    fun `target and runtime expose the conditional board and expected-status action without private data`() {
        val target = Path.of("openapi/beanflow-v1.yaml").readText()
        val runtime = Path.of("openapi/beanflow-v1-runtime.yaml").readText()

        val orderBoard = pathItem(target, "/stores/{storeId}/orders")
        assertThat(orderBoard)
            .contains(
                "StoreOrderBoard",
                "If-None-Match",
                "ETag",
                "약한(weak)",
                "overflow 커서의 TTL을 연장하지 않습니다",
                "PENDING_ACCEPTANCE",
                "overflow",
            )
        assertOpenApiResponseStatuses(orderBoard, 304, 503)

        val overflow = pathItem(target, "/stores/{storeId}/orders/overflow")
        assertThat(overflow).contains("StoreOrderBoardOverflowPage", "lane", "cursor")
        assertOpenApiResponseStatuses(overflow, 400, 403, 503)

        val order = pathItem(target, "/stores/{storeId}/orders/{orderReference}")
        assertThat(order).contains("StoreOrderBoardItem")
        assertOpenApiResponseStatuses(order, 403, 404, 503)

        val transition = pathItem(target, "/stores/{storeId}/orders/{orderReference}/transitions")
        assertThat(transition).contains("StoreOrderActionRequest", "StoreOrderBoardItem")
        assertOpenApiResponseStatuses(transition, 202, 409, 422)
        assertThat(pathItem(runtime, "/stores/{storeId}/orders"))
            .contains("./beanflow-v1.yaml#/paths/~1stores~1{storeId}~1orders")
        assertThat(pathItem(runtime, "/stores/{storeId}/orders/overflow"))
            .contains("./beanflow-v1.yaml#/paths/~1stores~1{storeId}~1orders~1overflow")
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
        assertThat(schema(target, "StoreOrderBoard"))
            .contains("required: [groups, overflow]", "StoreOrderBoardOverflow")
        assertThat(schema(target, "StoreOrderBoardOverflow"))
            .contains("lane", "overflowCount", "nextCursor", "minimum: 1")
        assertThat(schema(target, "StoreOrderBoardOverflowPage"))
            .contains("lane", "items", "maxItems: 50", "nextCursor")
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

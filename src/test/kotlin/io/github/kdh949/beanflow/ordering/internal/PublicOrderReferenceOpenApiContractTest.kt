package io.github.kdh949.beanflow.ordering.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

internal class PublicOrderReferenceOpenApiContractTest {
    @Test
    fun `target and runtime contracts expose public reference routes without an order id`() {
        val target = Path.of("openapi/beanflow-v1.yaml").readText()
        val runtime = Path.of("openapi/beanflow-v1-runtime.yaml").readText()
        val paths =
            listOf(
                "/me/orders/{orderReference}",
                "/me/orders/{orderReference}/cancellations",
                "/stores/{storeId}/orders/{orderReference}",
                "/stores/{storeId}/orders/{orderReference}/transitions",
            )

        paths.forEach { path ->
            assertThat(pathItem(target, path)).contains("OrderReference", "\"400\"", "\"403\"", "\"404\"")
            assertThat(pathItem(runtime, path)).contains("OrderReference", "\"400\"", "\"403\"", "\"404\"")
        }
        val inputPattern = Regex("(?ms)^    OrderReference:\\n.*?pattern: '([^']+)'").find(target)!!.groupValues[1]
        assertThat(Regex(inputPattern).matches("bf-7k3m-9q2p")).isTrue()
        assertThat(Regex(inputPattern).matches("BF-7K3I-9Q2P")).isFalse()
        assertThat(schema(runtime, "RuntimePublicCustomerOrder"))
            .contains("not:", "required: [orderId]", "publicReference", "pickupNumber")
        assertThat(schema(runtime, "RuntimePublicStoreOrder"))
            .contains("not:", "required: [orderId]", "publicReference", "pickupNumber")
        assertThat(schema(target, "CustomerCancellationResult"))
            .contains("required: [orderReference")
            .doesNotContain("orderId:")
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

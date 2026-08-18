package io.github.kdh949.beanflow.ordering.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

internal class OneTimePaymentPublicTrackingOpenApiContractTest {
    @Test
    fun `customer payment status and confirmation contract use a public order reference`() {
        val target = Path.of("openapi/beanflow-v1.yaml").readText()
        val runtime = Path.of("openapi/beanflow-v1-runtime.yaml").readText()

        listOf("/payments/{paymentId}", "/payments/{paymentId}/confirmations").forEach { path ->
            assertThat(pathItem(target, path)).contains("PaymentConfirmation")
            assertThat(pathItem(runtime, path)).contains(
                "./beanflow-v1.yaml#/paths/${path.replace("/", "~1")}",
            )
        }
        assertThat(schema(target, "PaymentConfirmation"))
            .contains("- orderReference", "orderReference:")
            .doesNotContain("- orderId", "orderId:")
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

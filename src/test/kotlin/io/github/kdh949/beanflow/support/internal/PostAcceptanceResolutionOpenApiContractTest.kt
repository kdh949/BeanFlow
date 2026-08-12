package io.github.kdh949.beanflow.support.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

internal class PostAcceptanceResolutionOpenApiContractTest {
    @Test
    fun `target and runtime expose the closed S80 resolution contract`() {
        val target = Path.of("openapi/beanflow-v1.yaml").readText()
        val runtime = Path.of("openapi/beanflow-v1-runtime.yaml").readText()
        val operations =
            mapOf(
                "/support/orders/{orderId}/post-acceptance-resolutions" to "createPostAcceptanceResolution",
                "/support/post-acceptance-resolutions/{resolutionId}" to "getPostAcceptanceResolution",
                "/support/post-acceptance-resolutions/{resolutionId}/executions" to "executePostAcceptanceResolution",
                "/support/post-acceptance-resolutions/{resolutionId}/reconciliations" to "reconcilePostAcceptanceResolution",
            )

        operations.forEach { (path, operationId) ->
            assertThat(pathItem(target, path))
                .contains("operationId: $operationId", "Cache-Control", "\"403\"", "\"503\"")
            assertThat(runtime).contains("  $path:\n    \$ref: \"./beanflow-v1.yaml#/paths/${pointer(path)}\"")
        }
        operations.keys.filterNot { it.endsWith("{resolutionId}") }.forEach { path ->
            assertThat(pathItem(target, path)).contains("#/components/parameters/IdempotencyKey", "\"409\"")
        }

        listOf(
            "CreatePostAcceptanceResolutionRequest",
            "ExecutePostAcceptanceResolutionRequest",
            "ReconcilePostAcceptanceResolutionRequest",
            "PostAcceptanceResolutionStepResource",
            "PostAcceptanceResolutionResource",
        ).forEach { name ->
            assertThat(schema(target, name)).contains("type: object", "additionalProperties: false")
        }
        assertThat(schema(target, "PostAcceptanceResolutionState"))
            .contains("PARTIALLY_RESOLVED", "RECONCILING", "MANUAL_REVIEW")
        assertThat(schema(target, "PostAcceptanceResolutionStepState"))
            .contains("UNKNOWN", "RECONCILING", "BLOCKED")
        assertThat(schema(target, "PostAcceptanceResolutionResponsibility"))
            .contains("CUSTOMER", "STORE", "PLATFORM", "SHARED", "UNDETERMINED")
        assertThat(schema(target, "PostAcceptanceResolutionResource"))
            .contains("triggerOrderState", "settlementAdjustmentKrw", "steps")
            .doesNotContain("evidenceDigest", "providerPayload", "reason", "customerName", "phone", "email")
        assertThat(pathItem(target, "/support/post-acceptance-resolutions/{resolutionId}/executions"))
            .contains("outside the Support", "UNKNOWN/RECONCILING", "never assumes success")
        assertThat(pathItem(target, "/support/orders/{orderId}/post-acceptance-resolutions"))
            .contains("exact approved S60", "No owner command")
            .doesNotContain("/approvals")
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

    private fun pointer(path: String): String = path.replace("/", "~1")
}

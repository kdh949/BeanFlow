package io.github.kdh949.beanflow.support.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

internal class SupportTimelineOpenApiContractTest {
    @Test
    fun `target and runtime OpenAPI expose the closed S50 timeline and action evaluation contract`() {
        val target = Path.of("openapi/beanflow-v1.yaml").readText()
        val runtime = Path.of("openapi/beanflow-v1-runtime.yaml").readText()
        val operations =
            mapOf(
                "/support/cases/{caseId}/timeline" to "listSupportCaseTimeline",
                "/support/orders/{orderId}/timeline" to "listSupportOrderTimeline",
                "/support/cases/{caseId}/action-evaluations" to "evaluateSupportAction",
            )

        operations.forEach { (path, operationId) ->
            assertThat(pathItem(target, path))
                .describedAs("OpenAPI path item %s", path)
                .contains("tags: [Support]", "operationId: $operationId", "Cache-Control", "\"503\"")
            assertThat(runtime).contains("  $path:\n    \$ref: \"./beanflow-v1.yaml#/paths/${pointer(path)}\"")
        }

        assertThat(pathItem(target, "/support/cases/{caseId}/timeline"))
            .contains("#/components/parameters/SupportTimelineCursor", "#/components/parameters/SupportTimelineLimit")
        assertThat(pathItem(target, "/support/orders/{orderId}/timeline"))
            .contains("#/components/parameters/SupportOrderTimelineCaseId")
        assertThat(pathItem(target, "/support/cases/{caseId}/action-evaluations"))
            .contains("#/components/schemas/EvaluateSupportActionRequest")
            .doesNotContain("IdempotencyKey")

        listOf(
            "SupportTimelineItem",
            "SupportTimelinePage",
            "EvaluateSupportActionRequest",
            "SupportActionEvaluationResource",
        ).forEach { name ->
            assertThat(schema(target, name))
                .describedAs("OpenAPI schema %s", name)
                .contains("type: object", "additionalProperties: false")
        }
        assertThat(schema(target, "SupportTimelineSource"))
            .contains("SUPPORT", "ORDERING", "PAYMENT", "OPERATIONS")
        assertThat(schema(target, "SupportActionType"))
            .contains("ORDER_CANCELLATION", "PICKUP_RESCHEDULE", "POST_ACCEPTANCE_RESOLUTION")
        assertThat(schema(target, "SupportActionDecision")).contains("ALLOWED", "APPROVAL_REQUIRED", "DENIED")
        assertThat(schema(target, "VerificationActionScope")).contains("PERSONAL_DATA_REVEAL", "SUPPORT_ACTION")
        assertThat(schema(target, "VerificationSessionResource"))
            .contains("actionScope:", "#/components/schemas/VerificationActionScope")
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

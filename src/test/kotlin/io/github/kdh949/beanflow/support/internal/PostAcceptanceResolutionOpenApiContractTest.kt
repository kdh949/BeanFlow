package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.architecture.assertOpenApiResponseStatuses
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
            val operation = pathItem(target, path)
            assertThat(operation).contains("operationId: $operationId", "Cache-Control")
            assertOpenApiResponseStatuses(operation, 403, 503)
            assertThat(runtime).contains("  $path:\n    \$ref: \"./beanflow-v1.yaml#/paths/${pointer(path)}\"")
        }
        operations.keys.filterNot { it.endsWith("{resolutionId}") }.forEach { path ->
            val operation = pathItem(target, path)
            assertThat(operation).contains("#/components/parameters/IdempotencyKey")
            assertOpenApiResponseStatuses(operation, 409)
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
            .contains(
                "고객센터 감사 기록·상태 저장이 모두 끝난 뒤에만 성공으로 표시합니다",
                "`UNKNOWN` 또는 `RECONCILING`",
                "같은 환불 요청을 자동으로 다시 보내지 않습니다",
            )
        assertThat(pathItem(target, "/support/orders/{orderId}/post-acceptance-resolutions"))
            .contains("이미 승인받은 요청 내용만 사용할 수 있으며", "이 단계에서는 실제 환불이나 복원을 실행하지 않고")
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

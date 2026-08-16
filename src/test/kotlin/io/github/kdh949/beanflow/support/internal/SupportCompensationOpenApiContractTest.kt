package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.architecture.assertOpenApiResponseStatuses
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

internal class SupportCompensationOpenApiContractTest {
    @Test
    fun `target and runtime expose the closed S90 goodwill contract`() {
        val target = Path.of("openapi/beanflow-v1.yaml").readText()
        val runtime = Path.of("openapi/beanflow-v1-runtime.yaml").readText()
        val operations =
            mapOf(
                "/support/cases/{caseId}/compensation-evaluations" to "evaluateSupportCompensation",
                "/support/cases/{caseId}/compensations" to "createSupportCompensation",
                "/support/compensations/{compensationRequestId}" to "getSupportCompensation",
                "/support/compensations/{compensationRequestId}/executions" to "executeSupportCompensation",
                "/support/compensations/{compensationRequestId}/notification-retries" to
                    "retrySupportCompensationNotification",
            )

        operations.forEach { (path, operationId) ->
            val operation = pathItem(target, path)
            assertThat(operation).contains("operationId: $operationId", "Cache-Control")
            assertOpenApiResponseStatuses(operation, 403, 503)
            assertThat(runtime).contains("  $path:\n    \$ref: \"./beanflow-v1.yaml#/paths/${pointer(path)}\"")
        }
        setOf(
            "/support/cases/{caseId}/compensations",
            "/support/compensations/{compensationRequestId}/executions",
            "/support/compensations/{compensationRequestId}/notification-retries",
        ).forEach { path ->
            val operation = pathItem(target, path)
            assertThat(operation).contains("#/components/parameters/IdempotencyKey")
            assertOpenApiResponseStatuses(operation, 409)
        }

        listOf(
            "EvaluateSupportCompensationRequest",
            "CreateSupportCompensationRequest",
            "ExecuteSupportCompensationRequest",
            "SupportCompensationEvaluationResource",
            "SupportCompensationResource",
        ).forEach { name ->
            assertThat(schema(target, name)).contains("type: object", "additionalProperties: false")
        }
        assertThat(schema(target, "SupportCompensationBand"))
            .contains("LOW", "MEDIUM", "HIGH", "EXCEPTIONAL")
        assertThat(schema(target, "SupportCompensationRequestState"))
            .contains("BENEFIT_ISSUED", "NOTIFICATION_RETRY", "NOTIFICATION_ACCEPTED")
        assertThat(schema(target, "SupportCompensationResource"))
            .contains("policyVersionId", "terminalBenefitId", "notificationState")
            .doesNotContain("customerId", "evidenceDigest", "costEvidenceDigest", "providerPayload")
        assertThat(pathItem(target, "/support/compensations/{compensationRequestId}/executions"))
            .contains("최근 기간 한도", "한 트랜잭션에서 함께 저장", "감사 기록 저장에 실패하면 혜택 지급도 취소합니다")
        assertThat(pathItem(target, "/support/compensations/{compensationRequestId}/notification-retries"))
            .contains("이미 발급된 포인트나 쿠폰을 다시 발급하지 않습니다")
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

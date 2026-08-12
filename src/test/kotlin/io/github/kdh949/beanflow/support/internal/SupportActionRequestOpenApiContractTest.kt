package io.github.kdh949.beanflow.support.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

internal class SupportActionRequestOpenApiContractTest {
    @Test
    fun `target and runtime expose the complete closed S60 approval contract`() {
        val target = Path.of("openapi/beanflow-v1.yaml").readText()
        val runtime = Path.of("openapi/beanflow-v1-runtime.yaml").readText()
        val operations =
            mapOf(
                "/support/cases/{caseId}/action-requests" to "createSupportActionRequest",
                "/support/action-requests/{requestId}" to "getSupportActionRequest",
                "/support/action-requests/{requestId}/revisions" to "reviseSupportActionRequest",
                "/support/action-requests/{requestId}/support-manager-decisions" to "decideSupportManagerApproval",
                "/support/action-requests/{requestId}/reassignments" to "reassignSupportActionRequest",
                "/operations/investigations/{investigationId}/decisions" to "decideOperationsSupportInvestigation",
            )

        operations.forEach { (path, operationId) ->
            assertThat(pathItem(target, path))
                .describedAs("OpenAPI path item %s", path)
                .contains("operationId: $operationId", "Cache-Control", "\"403\"", "\"503\"")
            assertThat(runtime).contains("  $path:\n    \$ref: \"./beanflow-v1.yaml#/paths/${pointer(path)}\"")
        }
        operations.keys.filterNot { it == "/support/action-requests/{requestId}" }.forEach { path ->
            assertThat(pathItem(target, path)).contains("#/components/parameters/IdempotencyKey", "\"409\"")
        }

        listOf(
            "CreateSupportActionRequest",
            "ReviseSupportActionRequest",
            "DecideSupportManagerApprovalRequest",
            "ReassignSupportActionRequest",
            "DecideOperationsSupportInvestigationRequest",
            "SupportApprovalStepResource",
            "SupportActionRequestResource",
            "OperationsSupportInvestigationDecisionResource",
        ).forEach { name ->
            assertThat(schema(target, name))
                .describedAs("OpenAPI schema %s", name)
                .contains("type: object", "additionalProperties: false")
        }

        assertThat(schema(target, "SupportActionRequestState"))
            .contains(
                "AWAITING_SUPPORT_MANAGER",
                "AWAITING_OPERATIONS",
                "READY_FOR_EXECUTION",
                "REASSIGNMENT_REQUIRED",
                "EXECUTED",
                "RESOLUTION_REQUIRED",
            )
        assertThat(schema(target, "OperationsSupportInvestigationDecision"))
            .contains("APPROVE", "DENY", "RETURN_FOR_REVISION", "ESCALATE")
        assertThat(schema(target, "SupportActionRequestResource"))
            .contains("actionPayloadDigest", "evidenceDigest", "approvalSteps", "terminalExecutionId", "terminalResolutionId")
            .doesNotContain("reason:", "rawPayload", "proof", "otp", "token")
        assertThat(pathItem(target, "/operations/investigations/{investigationId}/decisions"))
            .contains("callback or Audit failure rolls")
            .doesNotContain("200 response proves execution")
        assertThat(pathItem(target, "/support/cases/{caseId}/action-requests"))
            .contains("never executes an owner command")
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

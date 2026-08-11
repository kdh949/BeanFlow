package io.github.kdh949.beanflow.support.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

internal class SupportCaseOpenApiContractTest {
    @Test
    fun `target and runtime OpenAPI expose the complete typed S20 Case contract`() {
        val target = Path.of("openapi/beanflow-v1.yaml").readText()
        val runtime = Path.of("openapi/beanflow-v1-runtime.yaml").readText()

        val operations =
            mapOf(
                "/support/cases" to listOf("createSupportCase", "listSupportCases"),
                "/support/cases/{caseId}" to listOf("getSupportCase"),
                "/support/cases/{caseId}/assignments" to listOf("assignSupportCase"),
                "/support/cases/{caseId}/status-transitions" to listOf("transitionSupportCase"),
                "/support/cases/{caseId}/interactions" to listOf("appendSupportInteraction"),
                "/support/cases/{caseId}/notes" to listOf("appendSupportNote"),
                "/support/cases/{caseId}/subject-links" to listOf("linkSupportSubject"),
                "/support/cases/{caseId}/subject-links/{linkId}" to listOf("unlinkSupportSubject"),
            )

        operations.forEach { (path, operationIds) ->
            val operation = pathItem(target, path)
            assertThat(operation)
                .describedAs("OpenAPI path item %s", path)
                .contains("tags: [Support]", "Cache-Control", "\"503\"")
            operationIds.forEach { operationId -> assertThat(operation).contains("operationId: $operationId") }
            assertThat(runtime).contains("  $path:\n    \$ref: \"./beanflow-v1.yaml#/paths/${pointer(path)}\"")
        }

        listOf(
            "CreateSupportCaseRequest",
            "AssignSupportCaseRequest",
            "TransitionSupportCaseRequest",
            "AppendSupportInteractionRequest",
            "AppendSupportNoteRequest",
            "LinkSupportSubjectRequest",
            "UnlinkSupportSubjectRequest",
            "SupportCase",
            "SupportCasePage",
            "SupportCaseAssignment",
            "SupportCaseTransition",
            "SupportInteraction",
            "SupportNote",
            "SupportSubjectLink",
            "SupportSubjectUnlink",
        ).forEach { schema ->
            assertThat(schema(target, schema)).contains("type: object", "additionalProperties: false")
        }

        assertThat(pathItem(target, "/support/cases/{caseId}/status-transitions"))
            .contains("OPEN→IN_PROGRESS", "RESOLVED→CLOSED", "CLOSED is terminal")
        assertThat(schema(target, "AppendSupportNoteRequest"))
            .contains("successful response never returns content")
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

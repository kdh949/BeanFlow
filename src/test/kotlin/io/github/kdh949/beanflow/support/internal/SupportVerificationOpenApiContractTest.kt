package io.github.kdh949.beanflow.support.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

internal class SupportVerificationOpenApiContractTest {
    @Test
    fun `target and runtime OpenAPI expose the complete typed S40 security contract`() {
        val target = Path.of("openapi/beanflow-v1.yaml").readText()
        val runtime = Path.of("openapi/beanflow-v1-runtime.yaml").readText()
        val operations =
            mapOf(
                "/support/cases/{caseId}/verification-sessions" to "createSupportVerificationSession",
                "/support/verification-sessions/{sessionId}" to "getSupportVerificationSession",
                "/support/verification-sessions/{sessionId}/challenges" to "issueSupportVerificationChallenge",
                "/support/verification-challenges/{challengeId}/verifications" to "verifySupportVerificationChallenge",
                "/support/verification-sessions/{sessionId}/revocations" to "revokeSupportVerificationSession",
                "/support/cases/{caseId}/data-access-grants" to "requestSupportDataAccessGrant",
                "/support/data-access-grants/{grantId}/approvals" to "decideSupportDataAccessGrant",
                "/support/data-access-grants/{grantId}/reveals" to "revealSupportPersonalData",
                "/support/cases/{caseId}/break-glass-requests" to "requestSupportBreakGlass",
                "/support/break-glass-requests/{requestId}/approvals" to "decideSupportBreakGlass",
                "/support/break-glass-requests/{requestId}/reveals" to "revealSupportBreakGlassData",
                "/support/break-glass-requests/{requestId}/reviews" to "reviewSupportBreakGlass",
            )

        operations.forEach { (path, operationId) ->
            val operation = pathItem(target, path)
            assertThat(operation)
                .describedAs("OpenAPI path item %s", path)
                .contains("tags: [Support]", "operationId: $operationId", "Cache-Control", "\"503\"")
            assertThat(runtime).contains("  $path:\n    \$ref: \"./beanflow-v1.yaml#/paths/${pointer(path)}\"")
        }

        operations.keys.filterNot { it == "/support/verification-sessions/{sessionId}" }.forEach { path ->
            assertThat(pathItem(target, path)).contains("#/components/parameters/IdempotencyKey")
        }

        listOf(
            "CreateVerificationSessionRequest",
            "IssueVerificationChallengeRequest",
            "VerifyVerificationChallengeRequest",
            "VerificationSessionResource",
            "VerificationChallengeResource",
            "VerificationResultResource",
            "RequestDataAccessGrantRequest",
            "DecideDataAccessGrantRequest",
            "RevealGrantedPersonalDataRequest",
            "DataAccessGrantResource",
            "RevealedPersonalDataResource",
            "RequestBreakGlassRequest",
            "DecideBreakGlassRequest",
            "RevealBreakGlassRequest",
            "ReviewBreakGlassRequest",
            "BreakGlassResource",
            "BreakGlassRevealResource",
        ).forEach { name ->
            assertThat(schema(target, name)).describedAs("OpenAPI schema %s", name).contains("type: object", "additionalProperties: false")
        }

        assertThat(schema(target, "VerifyVerificationChallengeRequest"))
            .contains("writeOnly: true")
            .doesNotContain("example:")
        assertThat(schema(target, "RevealedPersonalDataResource"))
            .contains("additionalProperties: false", "values:", "SupportPersonalDataValues")
        assertThat(schema(target, "SupportPersonalDataValues"))
            .contains("additionalProperties: false")
            .doesNotContain("secret", "password", "token")
        assertThat(pathItem(target, "/support/data-access-grants/{grantId}/reveals"))
            .contains("Audit record and reveal-attempt reservation commit before owner decryption")
        assertThat(pathItem(target, "/support/break-glass-requests/{requestId}/reveals"))
            .contains("mandatory post-review")
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

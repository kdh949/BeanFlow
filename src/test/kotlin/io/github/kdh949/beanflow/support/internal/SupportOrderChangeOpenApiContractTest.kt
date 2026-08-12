package io.github.kdh949.beanflow.support.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

internal class SupportOrderChangeOpenApiContractTest {
    @Test
    fun `target and runtime expose the closed S70 authorization and execution contract`() {
        val target = Path.of("openapi/beanflow-v1.yaml").readText()
        val runtime = Path.of("openapi/beanflow-v1-runtime.yaml").readText()
        val operations =
            mapOf(
                "/support/action-requests/{requestId}/executions" to "executeSupportOrderChange",
                "/stores/{storeId}/support-order-change-authorizations" to "createSupportOrderChangeAuthorization",
            )

        operations.forEach { (path, operationId) ->
            assertThat(pathItem(target, path))
                .contains(
                    "operationId: $operationId",
                    "#/components/parameters/IdempotencyKey",
                    "Cache-Control",
                    "\"403\"",
                    "\"409\"",
                    "\"503\"",
                )
            assertThat(runtime).contains("  $path:\n    \$ref: \"./beanflow-v1.yaml#/paths/${pointer(path)}\"")
        }

        assertThat(pathItem(target, "/support/action-requests/{requestId}/executions"))
            .contains("latest owner state", "RESOLUTION_REQUIRED", "rolls all of them back")
        assertThat(pathItem(target, "/stores/{storeId}/support-order-change-authorizations"))
            .contains("ten minutes", "thirty", "now >= expiresAt", "do not consume")

        assertThat(schema(target, "ExecuteSupportOrderChangeRequest"))
            .contains("oneOf:", "discriminator:", "ORDER_CANCELLATION", "PICKUP_RESCHEDULE", "authorizationId is not")
        listOf(
            "ExecuteSupportOrderCancellationRequest",
            "ExecuteSupportPickupRescheduleRequest",
            "CreateSupportOrderChangeAuthorizationRequest",
            "SupportOrderChangeAuthorizationResource",
            "SupportOrderChangeExecutionResource",
        ).forEach { name ->
            assertThat(schema(target, name)).contains("type: object", "additionalProperties: false")
        }
        assertThat(schema(target, "SupportOrderChangeAuthorizationResource"))
            .contains("maxSuccessfulUses", "successfulUses", "costResponsibility")
            .doesNotContain("rawPayload", "reasonDetail", "customerNote", "otp", "token")
        assertThat(schema(target, "SupportOrderChangeExecutionResource"))
            .contains("paymentRecoveryState", "targetVersionAfter", "requestState")
            .doesNotContain("rawPayload", "reasonDetail", "customerNote", "providerPayload")
        assertThat(schema(target, "SupportActionRequestState")).contains("EXECUTED", "RESOLUTION_REQUIRED")
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

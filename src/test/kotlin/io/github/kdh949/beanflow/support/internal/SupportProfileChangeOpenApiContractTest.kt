package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.architecture.assertOpenApiResponseStatuses
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

internal class SupportProfileChangeOpenApiContractTest {
    @Test
    fun `target and runtime expose every typed S100 owner workflow`() {
        val target = Path.of("openapi/beanflow-v1.yaml").readText()
        val runtime = Path.of("openapi/beanflow-v1-runtime.yaml").readText()
        val createSuffixes =
            listOf(
                "customer-display-name-corrections",
                "customer-legal-name-corrections",
                "customer-primary-phone-requests",
                "customer-credential-reset-requests",
                "store-public-profile-corrections",
                "store-operations-contact-corrections",
                "store-representative-requests",
                "store-settlement-account-requests",
                "store-access-reregistration-requests",
                "courier-display-name-corrections",
                "courier-relay-contact-corrections",
                "courier-provider-identity-requests",
                "courier-payout-reference-requests",
                "courier-provider-reregistration-requests",
            )
        val revisionSuffixes =
            listOf(
                "customer-primary-phone-revisions",
                "customer-credential-reset-revisions",
                "store-representative-revisions",
                "store-settlement-account-revisions",
                "store-access-reregistration-revisions",
                "courier-provider-identity-revisions",
                "courier-payout-reference-revisions",
                "courier-provider-reregistration-revisions",
            )
        val executionSuffixes = revisionSuffixes.map { it.removeSuffix("-revisions") + "-executions" }
        val paths =
            createSuffixes.map { "/support/cases/{caseId}/profile-changes/$it" } +
                revisionSuffixes.map { "/support/profile-changes/{profileChangeId}/$it" } +
                executionSuffixes.map { "/support/profile-changes/{profileChangeId}/$it" } +
                listOf(
                    "/support/profile-changes/{profileChangeId}",
                    "/support/profile-changes/{profileChangeId}/notification-retries",
                )

        assertThat(paths).hasSize(32)
        paths.forEach { path ->
            assertOpenApiResponseStatuses(pathItem(target, path), 403, 503)
            assertThat(runtime).contains("  $path:\n    \$ref: \"./beanflow-v1.yaml#/paths/${pointer(path)}\"")
        }
        assertThat(target).contains(
            "SupportProfileChangeCreated:\n      description:",
            "SupportProfileChangeOk:\n      description:",
            "Cache-Control:\n          \$ref: \"#/components/headers/NoStore\"",
        )
        (paths - "/support/profile-changes/{profileChangeId}").forEach { path ->
            assertThat(pathItem(target, path)).contains("#/components/parameters/IdempotencyKey")
        }
        assertThat(target).doesNotContain("/profile-changes/{profileChangeId}:\n    patch:")
        assertThat(schema(target, "SupportProfileChangeResource"))
            .contains("additionalProperties: false", "maskedBefore", "maskedAfter", "payloadDigest", "notificationState")
            .doesNotContain("primaryPhone:", "legalName:", "accountReference:", "providerReference:", "payoutReference:")
        listOf(
            "CustomerPrimaryPhoneProfileChangeRequest",
            "StoreSettlementAccountProfileChangeRequest",
            "CourierProviderIdentityProfileChangeRequest",
            "CourierPayoutReferenceProfileChangeRequest",
        ).forEach { request ->
            assertThat(schema(target, request)).contains("writeOnly: true", "additionalProperties: false")
        }
        assertThat(schema(target, "CustomerCredentialResetProfileChangeRequest"))
            .doesNotContain("password", "secret", "token")
        assertThat(pathItem(target, "/support/cases/{caseId}/profile-changes/customer-primary-phone-requests"))
            .contains("기존에 등록된 연락 수단", "강화 본인 확인")
    }

    private fun pathItem(
        document: String,
        path: String,
    ): String = document.substringAfter("  $path:\n", missingDelimiterValue = "").substringBefore("\n  /")

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

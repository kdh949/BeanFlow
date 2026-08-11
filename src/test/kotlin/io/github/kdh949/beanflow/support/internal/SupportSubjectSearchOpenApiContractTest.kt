package io.github.kdh949.beanflow.support.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

internal class SupportSubjectSearchOpenApiContractTest {
    @Test
    fun `target and runtime OpenAPI expose the strict masked S30 exact-search contract`() {
        val target = Path.of("openapi/beanflow-v1.yaml").readText()
        val runtime = Path.of("openapi/beanflow-v1-runtime.yaml").readText()
        val operation = pathItem(target, "/support/searches")

        assertThat(operation)
            .contains(
                "operationId: searchSupportSubjects",
                "#/components/schemas/SearchSupportSubjectsRequest",
                "#/components/schemas/SupportSubjectSearchResult",
                "Cache-Control",
                "\"400\"",
                "\"403\"",
                "\"429\"",
                "\"503\"",
            ).doesNotContain("in: query")
        assertThat(runtime)
            .contains("  /support/searches:\n    \$ref: \"./beanflow-v1.yaml#/paths/~1support~1searches\"")

        listOf(
            "SupportSearchCriterion",
            "SearchSupportSubjectsRequest",
            "SupportSubjectSearchCandidate",
            "SupportSubjectSearchResult",
        ).forEach { schema ->
            assertThat(schema(target, schema)).contains("type: object", "additionalProperties: false")
        }
        assertThat(schema(target, "SearchSupportSubjectsRequest"))
            .contains("required: [criterion, subjectTypes, reasonCode]", "uniqueItems: true")
        assertThat(schema(target, "SupportSubjectSearchCandidate"))
            .contains("maskedDisplayName", "maskedMatchedValue")
            .doesNotContain("ciphertext", "blindIndex", "criterionValue", "keyVersion")
        assertThat(schema(target, "SupportSubjectSearchResult"))
            .contains("maxItems: 20", "maximum: 21")
            .doesNotContain("criterion:", "normalized")
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

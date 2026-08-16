package io.github.kdh949.beanflow.discovery.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

internal class StoreCatalogOpenApiContractTest {
    /**
     * The assertions follow the spec's own language. These two operation descriptions were
     * translated to Korean when the API documentation was introduced, so pinning the original
     * English wording would only assert that the translation had not happened.
     */
    @Test
    fun `OpenAPI records the catalogue time empty-list and bound semantics`() {
        val document = Path.of("openapi/beanflow-v1.yaml").readText()

        val menus = operation(document, "/stores/{storeId}/menus")
        assertThat(menus)
            .contains("완전한 목록")
            .contains("1,000개")
            .contains("5,000개")
            .contains("503")

        val pickupSlots = operation(document, "/stores/{storeId}/pickup-slots")
        assertThat(pickupSlots)
            .contains("서버 현재 시각보다 미래")
            .contains("7일")
            .contains("빈 목록")
            .contains("1,001")
            .contains("503")
    }

    private fun operation(
        document: String,
        path: String,
    ): String =
        document
            .substringAfter("  $path:\n", missingDelimiterValue = "")
            .substringBefore("\n  /", missingDelimiterValue = "")
}

package io.github.kdh949.beanflow.discovery.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

internal class StoreCatalogOpenApiContractTest {
    @Test
    fun `OpenAPI records the catalogue time empty-list and bound semantics`() {
        val document = Path.of("openapi/beanflow-v1.yaml").readText()

        val menus = operation(document, "/stores/{storeId}/menus")
        assertThat(menus)
            .contains("complete list")
            .contains("1,000 menus")
            .contains("5,000 options")
            .contains("503")

        val pickupSlots = operation(document, "/stores/{storeId}/pickup-slots")
        assertThat(pickupSlots)
            .contains("startsAt > server time")
            .contains("seven days")
            .contains("200 empty list")
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

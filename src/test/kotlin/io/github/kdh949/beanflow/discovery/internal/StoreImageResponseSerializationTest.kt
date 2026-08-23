package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.discovery.api.StorefrontImageView
import io.github.kdh949.beanflow.shared.api.StoreSearchTermKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

internal class StoreImageResponseSerializationTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val storeId = UUID.randomUUID()

    @Test
    fun `store image is omitted when absent across compact nearby and search responses`() {
        assertThat(mapper.writeValueAsString(CustomerStoreResponse(storeId, "Store", true, null, null))).doesNotContain("image")
        assertThat(mapper.writeValueAsString(NearbyStoreItemResponse(storeId, "Store", 10, true, true, null))).doesNotContain("image")
        assertThat(
            mapper.writeValueAsString(
                StoreSearchItemResponse(
                    storeId,
                    "Store",
                    null,
                    null,
                    listOf(StoreSearchTermKind.STORE_NAME),
                    null,
                    true,
                    true,
                    emptyList(),
                    null,
                ),
            ),
        ).doesNotContain("image")
    }

    @Test
    fun `store image contains only signed URL and expiry when present`() {
        val image = StorefrontImageView("https://media.beanflow.test/signed", Instant.parse("2026-08-24T00:15:00Z"))

        val json = mapper.writeValueAsString(CustomerStoreResponse(storeId, "Store", true, null, image))

        assertThat(json)
            .contains("\"image\"")
            .contains("\"url\":\"https://media.beanflow.test/signed\"")
            .contains("\"expiresAt\":\"2026-08-24T00:15:00Z\"")
            .doesNotContain("thumbnailKey", "sha256", "originalKey")
    }
}

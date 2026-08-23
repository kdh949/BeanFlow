package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.merchant.api.NormalizedStorefrontImageUpload
import io.github.kdh949.beanflow.merchant.api.PreparedStorefrontImage
import io.github.kdh949.beanflow.merchant.api.StorefrontImageAccess
import io.github.kdh949.beanflow.merchant.api.StorefrontImageStorageOperations
import io.github.kdh949.beanflow.merchant.api.StorefrontImageTarget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete as deleteRequest

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class OperatorMenuImageControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
) {
    @MockitoBean
    private lateinit var storage: StorefrontImageStorageOperations

    private val actorId = UUID.fromString("70000000-0000-0000-0000-000000000002")

    @BeforeEach
    fun cleanDatabase() {
        jdbc.execute("TRUNCATE TABLE operations_operator_permission_grant, operations_audit_record, merchant_menu, merchant_store CASCADE")
        reset(storage)
    }

    @Test
    fun `operator grant and reason replace a menu image`() {
        val storeId = seedStore()
        val menuId = seedMenu(storeId)
        grant()
        stubStorage(menuId)

        request(storeId, menuId, "catalog correction")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.url").value(SIGNED_URL))

        assertThat(jdbc.queryForObject("SELECT image_sha256 FROM merchant_menu WHERE id = ?", String::class.java, menuId))
            .isEqualTo(HASH)
        assertThat(
            jdbc.queryForList("SELECT action FROM operations_audit_record WHERE target_id = ?", String::class.java, menuId),
        ).containsExactly("MENU_IMAGE_UPDATED")
    }

    @Test
    fun `operator rejects wrong store missing grant and missing reason before pointer write`() {
        val requestedStore = seedStore()
        val actualStore = seedStore()
        val menuId = seedMenu(actualStore)

        request(requestedStore, menuId, "catalog correction").andExpect(status().isForbidden)
        grant()
        request(requestedStore, menuId, null).andExpect(status().isBadRequest)
        request(requestedStore, menuId, "catalog correction")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        assertThat(jdbc.queryForObject("SELECT image_sha256 FROM merchant_menu WHERE id = ?", String::class.java, menuId)).isNull()
        verify(storage, never()).normalize(anyValue())
    }

    @Test
    fun `operator grant and reason delete a menu image`() {
        val storeId = seedStore()
        val menuId = seedMenu(storeId)
        grant()
        jdbc.update(
            """
            UPDATE merchant_menu
               SET image_original_key = ?, image_thumbnail_key = ?, image_sha256 = ?, image_updated_at = now()
             WHERE id = ?
            """.trimIndent(),
            PREPARED.originalKey,
            PREPARED.thumbnailKey,
            HASH,
            menuId,
        )

        deleteMenu(storeId, menuId).andExpect(status().isNoContent)

        assertThat(jdbc.queryForObject("SELECT image_sha256 FROM merchant_menu WHERE id = ?", String::class.java, menuId)).isNull()
        assertThat(
            jdbc.queryForList("SELECT action FROM operations_audit_record WHERE target_id = ?", String::class.java, menuId),
        ).containsExactly("MENU_IMAGE_DELETED")
    }

    private fun request(
        storeId: UUID,
        menuId: UUID,
        reason: String?,
    ) = mockMvc.perform(
        multipart("/api/v1/operations/stores/$storeId/menus/$menuId/image")
            .file(MockMultipartFile("image", "menu.jpg", MediaType.IMAGE_JPEG_VALUE, byteArrayOf(1, 2, 3)))
            .with {
                it.method = "PUT"
                it
            }.with(
                jwt()
                    .jwt { it.subject(actorId.toString()) }
                    .authorities(SimpleGrantedAuthority("ROLE_PLATFORM_OPERATOR")),
            ).also { builder -> reason?.let { builder.header("X-Access-Reason", it) } },
    )

    private fun deleteMenu(
        storeId: UUID,
        menuId: UUID,
    ) = mockMvc.perform(
        deleteRequest("/api/v1/operations/stores/$storeId/menus/$menuId/image")
            .header("X-Access-Reason", "catalog correction")
            .with(
                jwt()
                    .jwt { it.subject(actorId.toString()) }
                    .authorities(SimpleGrantedAuthority("ROLE_PLATFORM_OPERATOR")),
            ),
    )

    private fun stubStorage(menuId: UUID) {
        `when`(storage.normalize(anyValue())).thenReturn(NORMALIZED)
        `when`(storage.store(StorefrontImageTarget.MENU, menuId, NORMALIZED)).thenReturn(PREPARED)
        `when`(storage.access(PREPARED.thumbnailKey)).thenReturn(StorefrontImageAccess(SIGNED_URL, EXPIRES_AT))
    }

    private fun seedStore(): UUID =
        UUID.randomUUID().also {
            jdbc.update("INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)", it)
        }

    private fun seedMenu(storeId: UUID): UUID =
        UUID.randomUUID().also {
            jdbc.update(
                "INSERT INTO merchant_menu (id, store_id, name, base_price_krw, available, version) " +
                    "VALUES (?, ?, 'Latte', 5000, true, 0)",
                it,
                storeId,
            )
        }

    private fun grant() {
        jdbc.update(
            """
            INSERT INTO operations_operator_permission_grant
                (actor_id, permission, state, granted_at, revoked_at, version, audit_source_reference)
            VALUES (?, 'STORE_MEDIA_MANAGE', 'ACTIVE', now(), null, 1, ?)
            """.trimIndent(),
            actorId,
            "operator-menu-image-test:${UUID.randomUUID()}",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyValue(): T {
        org.mockito.Mockito.any<T>()
        return null as T
    }

    private companion object {
        const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val SIGNED_URL = "https://media.beanflow.test/menu-signed"
        val EXPIRES_AT: Instant = Instant.parse("2026-08-24T00:15:00Z")
        val NORMALIZED = NormalizedStorefrontImageUpload(byteArrayOf(1), byteArrayOf(2), "image/jpeg", "jpg", HASH)
        val PREPARED = PreparedStorefrontImage("menus/id/$HASH/original.jpg", "menus/id/$HASH/thumbnail.jpg", HASH)
    }
}

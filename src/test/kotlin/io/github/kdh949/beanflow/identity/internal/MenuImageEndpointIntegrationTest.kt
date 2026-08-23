package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.merchant.api.NormalizedStorefrontImageUpload
import io.github.kdh949.beanflow.merchant.api.PreparedStorefrontImage
import io.github.kdh949.beanflow.merchant.api.StorefrontImageAccess
import io.github.kdh949.beanflow.merchant.api.StorefrontImageStorageOperations
import io.github.kdh949.beanflow.merchant.api.StorefrontImageTarget
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete as deleteRequest

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class MenuImageEndpointIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val passwords: CustomerPasswordSecurity,
) {
    @MockitoBean
    private lateinit var storage: StorefrontImageStorageOperations

    @BeforeEach
    fun cleanDatabase() {
        jdbc.execute(
            """
            TRUNCATE TABLE spring_session_attributes, spring_session, identity_login_attempt,
                identity_store_membership, identity_merchant_account, operations_audit_record,
                merchant_menu, merchant_store CASCADE
            """.trimIndent(),
        )
        reset(storage)
    }

    @Test
    fun `OWNER and STAFF can replace a menu image and same hash is a no-op`() {
        listOf("OWNER", "STAFF").forEachIndexed { index, role ->
            val storeId = seedStore()
            val menuId = seedMenu(storeId)
            val session = signIn("menu.image.$index", storeId, role)
            stubStorage(menuId)

            replace(session, storeId, menuId)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.url").value(SIGNED_URL))
            replace(session, storeId, menuId).andExpect(status().isOk)

            assertThat(jdbc.queryForObject("SELECT image_sha256 FROM merchant_menu WHERE id = ?", String::class.java, menuId))
                .isEqualTo(HASH)
            assertThat(
                jdbc.queryForList(
                    "SELECT actor_type FROM operations_audit_record WHERE target_id = ?",
                    String::class.java,
                    menuId,
                ),
            ).containsExactly(if (role == "OWNER") "STORE_OWNER" else "STORE_STAFF")
            verify(storage, times(1)).store(StorefrontImageTarget.MENU, menuId, NORMALIZED)
        }
    }

    @Test
    fun `a menu from another store is 404 before image processing`() {
        val requestedStore = seedStore()
        val actualStore = seedStore()
        val menuId = seedMenu(actualStore)
        val session = signIn("menu.image.scope", requestedStore, "OWNER")

        replace(session, requestedStore, menuId)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        verify(storage, org.mockito.Mockito.never()).normalize(anyValue())
    }

    @Test
    fun `missing merchant CSRF is rejected`() {
        val storeId = seedStore()
        val menuId = seedMenu(storeId)
        val session = signIn("menu.image.csrf", storeId, "STAFF")

        mockMvc
            .perform(
                multipart("/api/v1/stores/$storeId/menus/$menuId/image")
                    .file(imagePart())
                    .with {
                        it.method = "PUT"
                        it
                    }.cookie(session.session),
            ).andExpect(status().isForbidden)
        verify(storage, org.mockito.Mockito.never()).normalize(anyValue())
    }

    @Test
    fun `STAFF deletes the current menu image and deleting absence is a no-op`() {
        val storeId = seedStore()
        val menuId = seedMenu(storeId)
        val session = signIn("menu.image.delete", storeId, "STAFF")
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

        delete(session, storeId, menuId).andExpect(status().isNoContent)
        delete(session, storeId, menuId).andExpect(status().isNoContent)

        assertThat(jdbc.queryForObject("SELECT image_sha256 FROM merchant_menu WHERE id = ?", String::class.java, menuId)).isNull()
        assertThat(
            jdbc.queryForList("SELECT action FROM operations_audit_record WHERE target_id = ?", String::class.java, menuId),
        ).containsExactly("MENU_IMAGE_DELETED")
    }

    private fun replace(
        session: MerchantSession,
        storeId: UUID,
        menuId: UUID,
    ) = mockMvc.perform(
        multipart("/api/v1/stores/$storeId/menus/$menuId/image")
            .file(imagePart())
            .with {
                it.method = "PUT"
                it
            }.cookie(session.session, session.csrf)
            .header(CSRF_HEADER, session.csrf.value),
    )

    private fun delete(
        session: MerchantSession,
        storeId: UUID,
        menuId: UUID,
    ) = mockMvc.perform(
        deleteRequest("/api/v1/stores/$storeId/menus/$menuId/image")
            .cookie(session.session, session.csrf)
            .header(CSRF_HEADER, session.csrf.value),
    )

    private fun stubStorage(menuId: UUID) {
        `when`(storage.normalize(anyValue())).thenReturn(NORMALIZED)
        `when`(storage.store(StorefrontImageTarget.MENU, menuId, NORMALIZED)).thenReturn(PREPARED)
        `when`(storage.access(PREPARED.thumbnailKey)).thenReturn(StorefrontImageAccess(SIGNED_URL, EXPIRES_AT))
    }

    private fun imagePart() = MockMultipartFile("image", "menu.jpg", MediaType.IMAGE_JPEG_VALUE, byteArrayOf(1, 2, 3))

    private fun seedStore(): UUID =
        UUID.randomUUID().also { storeId ->
            jdbc.update("INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)", storeId)
        }

    private fun seedMenu(storeId: UUID): UUID =
        UUID.randomUUID().also { menuId ->
            jdbc.update(
                "INSERT INTO merchant_menu (id, store_id, name, base_price_krw, available, version) " +
                    "VALUES (?, ?, 'Latte', 5000, true, 0)",
                menuId,
                storeId,
            )
        }

    private fun signIn(
        loginId: String,
        storeId: UUID,
        role: String,
    ): MerchantSession {
        val accountId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO identity_merchant_account
                (id, login_id, password_hash, credential_version, display_name, state,
                 temporary_password_expires_at, password_changed_at, locked_until,
                 created_at, updated_at, version)
            VALUES (?, ?, ?, 0, 'Menu Merchant', 'ACTIVE', NULL, ?, NULL, ?, ?, 0)
            """.trimIndent(),
            accountId,
            loginId,
            passwords.encode(PASSWORD),
            Timestamp.from(NOW),
            Timestamp.from(NOW.minusSeconds(1)),
            Timestamp.from(NOW),
        )
        jdbc.update(
            """
            INSERT INTO identity_store_membership
                (id, actor_id, store_id, membership_role, status, created_at, updated_at, version)
            VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, 0)
            """.trimIndent(),
            UUID.randomUUID(),
            accountId,
            storeId,
            role,
            Timestamp.from(NOW),
            Timestamp.from(NOW),
        )
        val csrf =
            requireNotNull(
                mockMvc
                    .perform(get("/api/v1/auth/merchant/csrf"))
                    .andReturn()
                    .response
                    .getCookie(CSRF_COOKIE),
            )
        val session =
            requireNotNull(
                mockMvc
                    .perform(
                        post("/api/v1/auth/merchant/sessions")
                            .cookie(csrf)
                            .header(CSRF_HEADER, csrf.value)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"loginId":"$loginId","password":"$PASSWORD"}"""),
                    ).andExpect(status().isOk)
                    .andReturn()
                    .response
                    .getCookie(SESSION_COOKIE),
            )
        return MerchantSession(session, csrf)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyValue(): T {
        org.mockito.Mockito.any<T>()
        return null as T
    }

    private data class MerchantSession(
        val session: Cookie,
        val csrf: Cookie,
    )

    private companion object {
        const val CSRF_COOKIE = "BEANFLOW_MERCHANT_XSRF"
        const val SESSION_COOKIE = "BEANFLOW_MERCHANT_SESSION"
        const val CSRF_HEADER = "X-BEANFLOW-CSRF"
        const val PASSWORD = "merchant-current-password-2026"
        const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val SIGNED_URL = "https://media.beanflow.test/menu-signed"
        val NOW: Instant = Instant.parse("2026-08-24T00:00:00Z")
        val EXPIRES_AT: Instant = NOW.plusSeconds(900)
        val NORMALIZED = NormalizedStorefrontImageUpload(byteArrayOf(1), byteArrayOf(2), "image/jpeg", "jpg", HASH)
        val PREPARED = PreparedStorefrontImage("menus/id/$HASH/original.jpg", "menus/id/$HASH/thumbnail.jpg", HASH)
    }
}

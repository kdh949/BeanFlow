package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.merchant.api.NormalizedStorefrontImageUpload
import io.github.kdh949.beanflow.merchant.api.PreparedStorefrontImage
import io.github.kdh949.beanflow.merchant.api.StorefrontImageAccess
import io.github.kdh949.beanflow.merchant.api.StorefrontImageStorageOperations
import io.github.kdh949.beanflow.merchant.api.StorefrontImageTarget
import io.github.kdh949.beanflow.merchant.api.StorefrontImageUpload
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
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
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
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
@BeanflowIsolatedSpringContext("verifies committed image and audit state across the request transaction boundary")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class StoreImageEndpointIntegrationTest(
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
                merchant_store_discovery_profile, merchant_store CASCADE
            """.trimIndent(),
        )
        reset(storage)
    }

    @Test
    fun `OWNER replaces an image and the same normalized hash writes neither object nor audit twice`() {
        val storeId = seedStore()
        val session = signIn("image.owner", storeId, "OWNER")
        stubStorage(storeId)

        replace(session, storeId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.url").value(SIGNED_URL))
            .andExpect(jsonPath("$.expiresAt").value("2026-08-24T00:15:00Z"))
        replace(session, storeId).andExpect(status().isOk)

        assertThat(jdbc.queryForObject("SELECT image_sha256 FROM merchant_store WHERE id = ?", String::class.java, storeId))
            .isEqualTo(HASH)
        assertThat(auditActions(storeId)).containsExactly("STORE_IMAGE_UPDATED")
        verify(storage, times(1)).store(StorefrontImageTarget.STORE, storeId, NORMALIZED)
    }

    @Test
    fun `STAFF and missing CSRF are rejected before image processing`() {
        val staffStore = seedStore()
        val staff = signIn("image.staff", staffStore, "STAFF")

        replace(staff, staffStore)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

        val ownerStore = seedStore()
        val owner = signIn("image.csrf", ownerStore, "OWNER")
        mockMvc
            .perform(
                multipart("/api/v1/stores/$ownerStore/image")
                    .file(imagePart())
                    .with {
                        it.method = "PUT"
                        it
                    }.cookie(owner.session),
            ).andExpect(status().isForbidden)
        verify(storage, never()).normalize(anyValue())
    }

    @Test
    fun `AIStor failure returns 503 without changing the pointer audit or overall health`() {
        val storeId = seedStore()
        val session = signIn("image.failure", storeId, "OWNER")
        `when`(storage.normalize(anyValue())).thenReturn(NORMALIZED)
        doThrow(DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "AIStor unavailable"))
            .`when`(storage)
            .store(StorefrontImageTarget.STORE, storeId, NORMALIZED)

        replace(session, storeId)
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))

        assertThat(jdbc.queryForObject("SELECT image_sha256 FROM merchant_store WHERE id = ?", String::class.java, storeId)).isNull()
        assertThat(auditActions(storeId)).isEmpty()
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk).andExpect(jsonPath("$.status").value("UP"))
        mockMvc
            .perform(
                get("/api/v1/stores/$storeId")
                    .with(
                        jwt()
                            .jwt { it.subject(UUID.randomUUID().toString()).claim("roles", listOf("CUSTOMER")) }
                            .authorities(SimpleGrantedAuthority("ROLE_CUSTOMER")),
                    ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Image Store"))
            .andExpect(jsonPath("$.image").doesNotExist())
        verify(storage, never()).access(org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    fun `OWNER deletes the current store image and deleting absence is a no-op`() {
        val storeId = seedStore()
        val session = signIn("image.delete", storeId, "OWNER")
        jdbc.update(
            """
            UPDATE merchant_store
               SET image_original_key = ?, image_thumbnail_key = ?, image_sha256 = ?, image_updated_at = now()
             WHERE id = ?
            """.trimIndent(),
            PREPARED.originalKey,
            PREPARED.thumbnailKey,
            HASH,
            storeId,
        )

        delete(session, storeId).andExpect(status().isNoContent)
        delete(session, storeId).andExpect(status().isNoContent)

        assertThat(jdbc.queryForObject("SELECT image_sha256 FROM merchant_store WHERE id = ?", String::class.java, storeId)).isNull()
        assertThat(auditActions(storeId)).containsExactly("STORE_IMAGE_DELETED")
    }

    private fun stubStorage(storeId: UUID) {
        `when`(storage.normalize(anyValue())).thenReturn(NORMALIZED)
        `when`(storage.store(StorefrontImageTarget.STORE, storeId, NORMALIZED)).thenReturn(PREPARED)
        `when`(storage.access(PREPARED.thumbnailKey)).thenReturn(StorefrontImageAccess(SIGNED_URL, EXPIRES_AT))
    }

    private fun replace(
        session: MerchantSession,
        storeId: UUID,
    ) = mockMvc.perform(
        multipart("/api/v1/stores/$storeId/image")
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
    ) = mockMvc.perform(
        deleteRequest("/api/v1/stores/$storeId/image")
            .cookie(session.session, session.csrf)
            .header(CSRF_HEADER, session.csrf.value),
    )

    private fun imagePart() = MockMultipartFile("image", "store.jpg", MediaType.IMAGE_JPEG_VALUE, byteArrayOf(1, 2, 3))

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyValue(): T {
        org.mockito.Mockito.any<T>()
        return null as T
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
            VALUES (?, ?, ?, 0, 'Image Merchant', 'ACTIVE', NULL, ?, NULL, ?, ?, 0)
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

    private fun seedStore(): UUID =
        UUID.randomUUID().also { storeId ->
            jdbc.update("INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)", storeId)
            jdbc.update(
                "INSERT INTO merchant_store_discovery_profile (store_id, name, location, region_code) " +
                    "VALUES (?, 'Image Store', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, '1100000000')",
                storeId,
            )
        }

    private fun auditActions(storeId: UUID): List<String> =
        jdbc.query(
            "SELECT action FROM operations_audit_record WHERE target_id = ? ORDER BY action",
            { row, _ -> row.getString(1) },
            storeId,
        )

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
        const val SIGNED_URL = "https://media.beanflow.test/store-signed"
        val NOW: Instant = Instant.parse("2026-08-24T00:00:00Z")
        val EXPIRES_AT: Instant = NOW.plusSeconds(900)
        val NORMALIZED = NormalizedStorefrontImageUpload(byteArrayOf(1), byteArrayOf(2), "image/jpeg", "jpg", HASH)
        val PREPARED = PreparedStorefrontImage("stores/id/$HASH/original.jpg", "stores/id/$HASH/thumbnail.jpg", HASH)
    }
}

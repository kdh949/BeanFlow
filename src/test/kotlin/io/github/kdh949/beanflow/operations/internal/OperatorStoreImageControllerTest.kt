package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.merchant.api.NormalizedStorefrontImageUpload
import io.github.kdh949.beanflow.merchant.api.PreparedStorefrontImage
import io.github.kdh949.beanflow.merchant.api.StorefrontImageAccess
import io.github.kdh949.beanflow.merchant.api.StorefrontImageStorageOperations
import io.github.kdh949.beanflow.merchant.api.StorefrontImageTarget
import io.github.kdh949.beanflow.merchant.api.StorefrontImageUpload
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.reset
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

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies committed image and audit state across the request transaction boundary")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class OperatorStoreImageControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
) {
    @MockitoBean
    private lateinit var storage: StorefrontImageStorageOperations

    private val actorId = UUID.fromString("70000000-0000-0000-0000-000000000001")

    @BeforeEach
    fun cleanDatabase() {
        jdbc.execute("TRUNCATE TABLE operations_operator_permission_grant, operations_audit_record, merchant_store CASCADE")
        reset(storage)
    }

    @Test
    fun `operator role grant and access reason replace a store image with one audit`() {
        val storeId = seedStore()
        grant()
        stubStorage(storeId)

        request(storeId, reason = "catalog correction")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.url").value(SIGNED_URL))

        assertThat(jdbc.queryForObject("SELECT image_sha256 FROM merchant_store WHERE id = ?", String::class.java, storeId))
            .isEqualTo(HASH)
        assertThat(
            jdbc.queryForList("SELECT action FROM operations_audit_record WHERE target_id = ?", String::class.java, storeId),
        ).containsExactly("STORE_IMAGE_UPDATED")
    }

    @Test
    fun `missing grant reason or platform role is rejected before a pointer write`() {
        val storeId = seedStore()
        stubStorage(storeId)

        request(storeId, reason = "catalog correction")
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

        grant()
        request(storeId, reason = null).andExpect(status().isBadRequest)
        request(storeId, reason = "catalog correction", platformRole = false).andExpect(status().isForbidden)

        assertThat(jdbc.queryForObject("SELECT image_sha256 FROM merchant_store WHERE id = ?", String::class.java, storeId)).isNull()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operations_audit_record", Long::class.java)).isZero()
    }

    private fun request(
        storeId: UUID,
        reason: String?,
        platformRole: Boolean = true,
    ) = mockMvc.perform(
        multipart("/api/v1/operations/stores/$storeId/image")
            .file(MockMultipartFile("image", "store.jpg", MediaType.IMAGE_JPEG_VALUE, byteArrayOf(1, 2, 3)))
            .with { request ->
                request.method = "PUT"
                request
            }.with(
                jwt()
                    .jwt { it.subject(actorId.toString()) }
                    .authorities(
                        if (platformRole) {
                            listOf(SimpleGrantedAuthority("ROLE_PLATFORM_OPERATOR"))
                        } else {
                            listOf(SimpleGrantedAuthority("ROLE_CUSTOMER"))
                        },
                    ),
            ).also { builder -> reason?.let { builder.header("X-Access-Reason", it) } },
    )

    private fun stubStorage(storeId: UUID) {
        `when`(storage.normalize(anyValue())).thenReturn(NORMALIZED)
        `when`(storage.store(StorefrontImageTarget.STORE, storeId, NORMALIZED)).thenReturn(PREPARED)
        `when`(storage.access(PREPARED.thumbnailKey)).thenReturn(StorefrontImageAccess(SIGNED_URL, EXPIRES_AT))
    }

    private fun seedStore(): UUID =
        UUID.randomUUID().also {
            jdbc.update("INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)", it)
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyValue(): T {
        org.mockito.Mockito.any<T>()
        return null as T
    }

    private fun grant() {
        jdbc.update(
            """
            INSERT INTO operations_operator_permission_grant
                (actor_id, permission, state, granted_at, revoked_at, version, audit_source_reference)
            VALUES (?, 'STORE_MEDIA_MANAGE', 'ACTIVE', now(), null, 1, ?)
            """.trimIndent(),
            actorId,
            "operator-store-image-test:${UUID.randomUUID()}",
        )
    }

    private companion object {
        const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val SIGNED_URL = "https://media.beanflow.test/store-signed"
        val EXPIRES_AT: Instant = Instant.parse("2026-08-24T00:15:00Z")
        val NORMALIZED = NormalizedStorefrontImageUpload(byteArrayOf(1), byteArrayOf(2), "image/jpeg", "jpg", HASH)
        val PREPARED = PreparedStorefrontImage("stores/id/$HASH/original.jpg", "stores/id/$HASH/thumbnail.jpg", HASH)
    }
}

package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies versioned Store and Menu display authoring with audit atomicity")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class MerchantDisplayContentEndpointIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val passwords: CustomerPasswordSecurity,
) {
    @BeforeEach
    fun cleanDatabase() {
        jdbc.execute(
            """
            TRUNCATE TABLE spring_session_attributes, spring_session, identity_login_attempt,
                identity_store_membership, identity_merchant_account, operations_audit_record,
                merchant_store_operating_hours, merchant_store_customer_display_profile,
                merchant_menu, merchant_store CASCADE
            """.trimIndent(),
        )
    }

    @Test
    fun `OWNER reads absent profile at version zero and identical replacement is a no-op`() {
        val storeId = seedStore()
        val session = signIn("display.owner", storeId, "OWNER")

        getProfile(session, storeId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(0))
            .andExpect(jsonPath("$.addressLine").doesNotExist())
            .andExpect(jsonPath("$.operatingHours").doesNotExist())

        putProfile(session, storeId, expectedVersion = 0)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.addressLine").value(ADDRESS))
            .andExpect(jsonPath("$.directionsHint").value(DIRECTIONS))
            .andExpect(jsonPath("$.operatingHours.timezone").value("Asia/Seoul"))
            .andExpect(jsonPath("$.operatingHours.days.length()").value(7))

        putProfile(session, storeId, expectedVersion = 1)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(1))

        assertThat(profileVersion(storeId)).isEqualTo(1)
        assertThat(hoursCount(storeId)).isEqualTo(7)
        assertThat(auditActions(storeId)).containsExactly("STORE_CUSTOMER_DISPLAY_UPDATED")

        putProfile(session, storeId, expectedVersion = 0, addressLine = "서울시 성동구 다른길 2")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("MERCHANT_CONTENT_STALE"))
        assertThat(
            jdbc.queryForObject(
                "SELECT address_line FROM merchant_store_customer_display_profile WHERE store_id = ?",
                String::class.java,
                storeId,
            ),
        ).isEqualTo(ADDRESS)
        assertThat(auditActions(storeId)).containsExactly("STORE_CUSTOMER_DISPLAY_UPDATED")
    }

    @Test
    fun `Store display requires OWNER complete schedule and CSRF`() {
        val staffStore = seedStore()
        val staff = signIn("display.staff", staffStore, "STAFF")
        getProfile(staff, staffStore)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
        putProfile(staff, staffStore, expectedVersion = 0)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

        val ownerStore = seedStore()
        val owner = signIn("display.validation", ownerStore, "OWNER")
        mockMvc
            .perform(
                put("/api/v1/stores/$ownerStore/customer-display")
                    .cookie(owner.session, owner.csrf)
                    .header(CSRF_HEADER, owner.csrf.value)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(partialScheduleBody()),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        mockMvc
            .perform(
                put("/api/v1/stores/$ownerStore/customer-display")
                    .cookie(owner.session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(profileBody(0, ADDRESS)),
            ).andExpect(status().isForbidden)

        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM merchant_store_customer_display_profile WHERE store_id = ?",
                Long::class.java,
                ownerStore,
            ),
        ).isZero()
        assertThat(auditActions(ownerStore)).isEmpty()
    }

    @Test
    fun `Store display full replacement may remove all optional content and schedule`() {
        val storeId = seedStore()
        val session = signIn("display.remove", storeId, "OWNER")

        putProfile(session, storeId, expectedVersion = 0).andExpect(status().isOk)
        mockMvc
            .perform(
                put("/api/v1/stores/$storeId/customer-display")
                    .cookie(session.session, session.csrf)
                    .header(CSRF_HEADER, session.csrf.value)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "expectedVersion": 1,
                          "addressLine": null,
                          "directionsHint": null,
                          "operatingHours": null
                        }
                        """.trimIndent(),
                    ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.addressLine").doesNotExist())
            .andExpect(jsonPath("$.directionsHint").doesNotExist())
            .andExpect(jsonPath("$.operatingHours").doesNotExist())

        assertThat(profileVersion(storeId)).isEqualTo(2)
        assertThat(hoursCount(storeId)).isZero()
        assertThat(auditActions(storeId)).containsExactly(
            "STORE_CUSTOMER_DISPLAY_UPDATED",
            "STORE_CUSTOMER_DISPLAY_UPDATED",
        )
    }

    @Test
    fun `OWNER and STAFF read and replace Menu display content with the existing Menu version`() {
        listOf("OWNER", "STAFF").forEachIndexed { index, role ->
            val storeId = seedStore()
            val menuId = seedMenu(storeId)
            val session = signIn("menu.display.$index", storeId, role)

            getMenuContent(session, storeId, menuId)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.displayCategory").doesNotExist())
                .andExpect(jsonPath("$.description").doesNotExist())
            putMenuContent(session, storeId, menuId, expectedVersion = 0)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.displayCategory").value("커피"))
                .andExpect(jsonPath("$.description").value("고소한 카페라떼"))
            putMenuContent(session, storeId, menuId, expectedVersion = 1)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.version").value(1))

            assertThat(
                jdbc.queryForList(
                    "SELECT actor_type FROM operations_audit_record WHERE target_id = ?",
                    String::class.java,
                    menuId,
                ),
            ).containsExactly(if (role == "OWNER") "STORE_OWNER" else "STORE_STAFF")
        }
    }

    @Test
    fun `Menu display rejects stale invalid and cross-store requests without changing content`() {
        val requestedStore = seedStore()
        val actualStore = seedStore()
        val foreignMenu = seedMenu(actualStore)
        val ownMenu = seedMenu(requestedStore)
        val session = signIn("menu.display.scope", requestedStore, "OWNER")

        getMenuContent(session, requestedStore, foreignMenu)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        putMenuContent(session, requestedStore, foreignMenu, expectedVersion = 0)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        putMenuContent(session, requestedStore, ownMenu, expectedVersion = 0).andExpect(status().isOk)
        putMenuContent(session, requestedStore, ownMenu, expectedVersion = 0, category = "티")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("MERCHANT_CONTENT_STALE"))
        putMenuContent(session, requestedStore, ownMenu, expectedVersion = 1, category = "bad\ncategory")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

        assertThat(jdbc.queryForObject("SELECT display_category FROM merchant_menu WHERE id = ?", String::class.java, ownMenu))
            .isEqualTo("커피")
        assertThat(auditActions(ownMenu)).containsExactly("MENU_DISPLAY_CONTENT_UPDATED")
    }

    @Test
    fun `revoked membership cannot read or replace Store and Menu display content`() {
        val storeId = seedStore()
        val menuId = seedMenu(storeId)
        val session = signIn("display.revoked", storeId, "OWNER")
        jdbc.update("UPDATE identity_store_membership SET status = 'REVOKED' WHERE store_id = ?", storeId)

        getProfile(session, storeId).andExpect(status().isForbidden)
        putProfile(session, storeId, expectedVersion = 0).andExpect(status().isForbidden)
        getMenuContent(session, storeId, menuId).andExpect(status().isForbidden)
        putMenuContent(session, storeId, menuId, expectedVersion = 0).andExpect(status().isForbidden)

        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_store_customer_display_profile", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT version FROM merchant_menu WHERE id = ?", Long::class.java, menuId)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operations_audit_record", Long::class.java)).isZero()
    }

    private fun getProfile(
        session: MerchantSession,
        storeId: UUID,
    ) = mockMvc.perform(get("/api/v1/stores/$storeId/customer-display").cookie(session.session))

    private fun putProfile(
        session: MerchantSession,
        storeId: UUID,
        expectedVersion: Long,
        addressLine: String = ADDRESS,
    ) = mockMvc.perform(
        put("/api/v1/stores/$storeId/customer-display")
            .cookie(session.session, session.csrf)
            .header(CSRF_HEADER, session.csrf.value)
            .contentType(MediaType.APPLICATION_JSON)
            .content(profileBody(expectedVersion, addressLine)),
    )

    private fun getMenuContent(
        session: MerchantSession,
        storeId: UUID,
        menuId: UUID,
    ) = mockMvc.perform(get("/api/v1/stores/$storeId/menus/$menuId/display-content").cookie(session.session))

    private fun putMenuContent(
        session: MerchantSession,
        storeId: UUID,
        menuId: UUID,
        expectedVersion: Long,
        category: String = "커피",
    ) = mockMvc.perform(
        put("/api/v1/stores/$storeId/menus/$menuId/display-content")
            .cookie(session.session, session.csrf)
            .header(CSRF_HEADER, session.csrf.value)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "expectedVersion": $expectedVersion,
                  "displayCategory": ${json(category)},
                  "description": "고소한 카페라떼"
                }
                """.trimIndent(),
            ),
    )

    private fun profileBody(
        expectedVersion: Long,
        addressLine: String,
    ) = """
        {
          "expectedVersion": $expectedVersion,
          "addressLine": ${json(addressLine)},
          "directionsHint": "$DIRECTIONS",
          "operatingHours": {
            "timezone": "Asia/Seoul",
            "days": [
              {"dayOfWeek":"MONDAY","closed":false,"opensAt":"09:00","closesAt":"18:00"},
              {"dayOfWeek":"TUESDAY","closed":false,"opensAt":"09:00","closesAt":"18:00"},
              {"dayOfWeek":"WEDNESDAY","closed":false,"opensAt":"09:00","closesAt":"18:00"},
              {"dayOfWeek":"THURSDAY","closed":false,"opensAt":"09:00","closesAt":"18:00"},
              {"dayOfWeek":"FRIDAY","closed":false,"opensAt":"09:00","closesAt":"18:00"},
              {"dayOfWeek":"SATURDAY","closed":false,"opensAt":"10:00","closesAt":"17:00"},
              {"dayOfWeek":"SUNDAY","closed":true}
            ]
          }
        }
        """.trimIndent()

    private fun partialScheduleBody() =
        """
        {
          "expectedVersion": 0,
          "operatingHours": {
            "timezone": "Asia/Seoul",
            "days": [{"dayOfWeek":"MONDAY","closed":true}]
          }
        }
        """.trimIndent()

    private fun json(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun profileVersion(storeId: UUID): Long =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT version FROM merchant_store_customer_display_profile WHERE store_id = ?",
                Long::class.java,
                storeId,
            ),
        )

    private fun hoursCount(storeId: UUID): Long =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM merchant_store_operating_hours WHERE store_id = ?",
                Long::class.java,
                storeId,
            ),
        )

    private fun auditActions(targetId: UUID): List<String> =
        jdbc
            .queryForList(
                "SELECT action FROM operations_audit_record WHERE target_id = ? ORDER BY occurred_at, id",
                String::class.java,
                targetId,
            ).filterNotNull()

    private fun seedStore(): UUID =
        UUID.randomUUID().also { storeId ->
            jdbc.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                storeId,
            )
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
            VALUES (?, ?, ?, 0, 'Display Merchant', 'ACTIVE', NULL, ?, NULL, ?, ?, 0)
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

    private data class MerchantSession(
        val session: Cookie,
        val csrf: Cookie,
    )

    private companion object {
        const val CSRF_COOKIE = "BEANFLOW_MERCHANT_XSRF"
        const val SESSION_COOKIE = "BEANFLOW_MERCHANT_SESSION"
        const val CSRF_HEADER = "X-BEANFLOW-CSRF"
        const val PASSWORD = "merchant-current-password-2026"
        const val ADDRESS = "서울시 성동구 연무장길 1"
        const val DIRECTIONS = "성수역 3번 출구에서 5분"
        val NOW: Instant = Instant.parse("2026-08-25T00:00:00Z")
    }
}

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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * `PUT /stores/{storeId}/region` and `GET /regions` end to end on the merchant browser chain.
 *
 * The authorization assertions are the point of this file. Region is the first command in BeanFlow
 * whose authority comes from owning one store rather than from a platform-wide operator grant, so
 * the tests that matter are the two denials ADR-112 4절 requires: a `STORE_STAFF` member of the same
 * store, and the owner of a different store.
 */
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("query audit requires committed fixture visibility")
@SpringBootTest(
    properties = [
        "beanflow.toss.client-key=test_ck_store_region",
        "beanflow.authentication.attempt-retention-initial-delay-ms=3600000",
        "beanflow.store-region-command.retention.initial-delay-ms=3600000",
    ],
)
internal class StoreRegionEndpointIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val passwords: CustomerPasswordSecurity,
) {
    @BeforeEach
    fun cleanDatabase() {
        jdbc.execute(
            """
            TRUNCATE TABLE
                spring_session_attributes,
                spring_session,
                merchant_store_region_command,
                discovery_store_search_term,
                identity_login_attempt,
                identity_store_membership,
                identity_merchant_account,
                operations_audit_record,
                merchant_store_discovery_profile,
                merchant_store
            CASCADE
            """.trimIndent(),
        )
    }

    @Test
    fun `an owner assigns a region and the store gains its region terms and one audit record`() {
        val storeId = seedStore("역삼점")
        val session = signIn("owner.one", storeId, "OWNER")

        assign(session, storeId, YEOKSAM, "owner-region-0001")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.storeId").value(storeId.toString()))
            .andExpect(jsonPath("$.regionCode").value(YEOKSAM))
            .andExpect(jsonPath("$.regionFullName").value("서울특별시 강남구 역삼동"))

        assertThat(regionTermCount(storeId)).isEqualTo(3)
        assertThat(auditActions(storeId)).containsExactly("STORE_REGION_ASSIGNED")
        // 감사 요약은 법정동 코드를 그 코드 자체의 계층으로 끊어 담는다. 10자리 숫자를 그대로
        // 넣으면 원시 PII 판정기가 휴대전화 번호로 보고 append를 거절한다.
        assertThat(auditRegionCodes(storeId))
            .containsExactly("""{"regionCode":"11-000-000-00"}""" to """{"regionCode":"11-680-101-00"}""")
    }

    @Test
    fun `a replayed command returns the first result without a second audit record`() {
        val storeId = seedStore("재실행점")
        val session = signIn("owner.replay", storeId, "OWNER")

        assign(session, storeId, YEOKSAM, "owner-region-0002").andExpect(status().isOk)
        assign(session, storeId, YEOKSAM, "owner-region-0002")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.regionCode").value(YEOKSAM))

        // 재실행은 아무것도 바꾸지 않았으므로 두 번째 감사 기록은 일어나지 않은 변경을 주장하게 된다.
        assertThat(auditActions(storeId)).hasSize(1)
        assertThat(commandCount()).isEqualTo(1)
    }

    @Test
    fun `the same key with a different region is a reuse rather than a second assignment`() {
        val storeId = seedStore("키 재사용점")
        val session = signIn("owner.reuse", storeId, "OWNER")

        assign(session, storeId, YEOKSAM, "owner-region-0003").andExpect(status().isOk)
        assign(session, storeId, GUNNAE_RI, "owner-region-0003")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))

        assertThat(storeRegionCode(storeId)).isEqualTo(YEOKSAM)
    }

    @Test
    fun `store staff cannot change the region`() {
        val storeId = seedStore("직원 거부점")
        val session = signIn("staff.one", storeId, "STAFF")

        assign(session, storeId, YEOKSAM, "staff-region-0001")
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

        assertThat(storeRegionCode(storeId)).isEqualTo(SEOUL)
        assertThat(auditActions(storeId)).isEmpty()
    }

    @Test
    fun `the owner of another store cannot change this store's region`() {
        val ownedStoreId = seedStore("내 매장")
        val otherStoreId = seedStore("남의 매장")
        val session = signIn("owner.other", ownedStoreId, "OWNER")

        assign(session, otherStoreId, YEOKSAM, "other-region-0001")
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

        assertThat(storeRegionCode(otherStoreId)).isEqualTo(SEOUL)
    }

    @Test
    fun `a revoked membership cannot change the region`() {
        val storeId = seedStore("해지된 소속점")
        val session = signIn("owner.revoked", storeId, "OWNER", membershipStatus = "REVOKED")

        assign(session, storeId, YEOKSAM, "revoked-region-01")
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
    }

    @Test
    fun `an unauthenticated request never reaches the command`() {
        val storeId = seedStore("비인증점")

        // CSRF 없이 온 브라우저 쓰기 요청은 인증 판정보다 먼저 막힌다. 순서가 뒤바뀌면
        // 세션 없는 요청이 계정 존재 여부를 401과 403으로 구분해 알려주게 된다.
        mockMvc
            .perform(
                put("/api/v1/stores/$storeId/region")
                    .header("Idempotency-Key", "anonymous-region-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"regionCode":"$YEOKSAM","reason":"주소 등록"}"""),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

        val csrf =
            requireNotNull(
                mockMvc
                    .perform(get("/api/v1/auth/merchant/csrf"))
                    .andReturn()
                    .response
                    .getCookie(CSRF_COOKIE),
            )
        mockMvc
            .perform(
                put("/api/v1/stores/$storeId/region")
                    .cookie(csrf)
                    .header(CSRF_HEADER, csrf.value)
                    .header("Idempotency-Key", "anonymous-region-2")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"regionCode":"$YEOKSAM","reason":"주소 등록"}"""),
            ).andExpect(status().isUnauthorized)

        assertThat(storeRegionCode(storeId)).isEqualTo(SEOUL)
        assertThat(commandCount()).isZero()
    }

    @Test
    fun `the idempotency key, the reason and a well formed region code are all required`() {
        val storeId = seedStore("검증점")
        val session = signIn("owner.validation", storeId, "OWNER")
        val csrf = session.csrf

        mockMvc
            .perform(
                put("/api/v1/stores/$storeId/region")
                    .cookie(session.cookie, csrf)
                    .header(CSRF_HEADER, csrf.value)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"regionCode":"$YEOKSAM","reason":"주소 등록"}"""),
            ).andExpect(status().isBadRequest)

        assign(session, storeId, YEOKSAM, "validation-key-01", reason = "")
            .andExpect(status().isBadRequest)
        assign(session, storeId, "116801", "validation-key-02")
            .andExpect(status().isBadRequest)
        assign(session, storeId, "9999999999", "validation-key-03")
            .andExpect(status().isNotFound)

        assertThat(storeRegionCode(storeId)).isEqualTo(SEOUL)
        assertThat(commandCount()).isZero()
    }

    @Test
    fun `the region catalog filters on every word and pages with a signed cursor`() {
        val storeId = seedStore("어휘 조회점")
        val session = signIn("owner.catalog", storeId, "OWNER")

        mockMvc
            .perform(get("/api/v1/regions").param("query", "서울 강남 역삼").cookie(session.cookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].fullName").value("서울특별시 강남구 역삼동"))
            .andExpect(jsonPath("$.items[0].ri").value(""))

        val firstPage =
            mockMvc
                .perform(get("/api/v1/regions").param("limit", "2").cookie(session.cookie))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.page.nextCursor").isNotEmpty)
                .andReturn()
                .response.contentAsString
        val cursor = CURSOR.find(firstPage)!!.groupValues[1]

        mockMvc
            .perform(get("/api/v1/regions").param("limit", "2").param("cursor", cursor).cookie(session.cookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))

        // 다른 질의의 cursor는 다른 결과 집합의 위치다. filter digest가 질의를 포함하므로 거절된다.
        mockMvc
            .perform(
                get("/api/v1/regions")
                    .param("query", "부산")
                    .param("cursor", cursor)
                    .cookie(session.cookie),
            ).andExpect(status().isBadRequest)

        mockMvc
            .perform(get("/api/v1/regions").param("limit", "101").cookie(session.cookie))
            .andExpect(status().isBadRequest)
    }

    private fun assign(
        session: MerchantSession,
        storeId: UUID,
        regionCode: String,
        idempotencyKey: String,
        reason: String = "매장 주소 등록",
    ) = mockMvc.perform(
        put("/api/v1/stores/$storeId/region")
            .cookie(session.cookie, session.csrf)
            .header(CSRF_HEADER, session.csrf.value)
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"regionCode":"$regionCode","reason":"$reason"}"""),
    )

    private fun signIn(
        loginId: String,
        storeId: UUID,
        role: String,
        membershipStatus: String = "ACTIVE",
    ): MerchantSession {
        val accountId = seedAccount(loginId)
        seedMembership(accountId, storeId, role, membershipStatus)
        val csrf =
            requireNotNull(
                mockMvc
                    .perform(get("/api/v1/auth/merchant/csrf"))
                    .andExpect(status().isNoContent)
                    .andReturn()
                    .response
                    .getCookie(CSRF_COOKIE),
            )
        val cookie =
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
        return MerchantSession(cookie, csrf)
    }

    private fun seedAccount(loginId: String): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO identity_merchant_account
                (id, login_id, password_hash, credential_version, display_name, state,
                 temporary_password_expires_at, password_changed_at, locked_until,
                 created_at, updated_at, version)
            VALUES (?, ?, ?, 0, 'Demo Merchant', 'ACTIVE', NULL, ?, NULL, ?, ?, 0)
            """.trimIndent(),
            id,
            loginId,
            passwords.encode(PASSWORD),
            Timestamp.from(NOW),
            Timestamp.from(NOW.minusSeconds(1)),
            Timestamp.from(NOW),
        )
        return id
    }

    private fun seedMembership(
        accountId: UUID,
        storeId: UUID,
        role: String,
        status: String,
    ) {
        jdbc.update(
            """
            INSERT INTO identity_store_membership
                (id, actor_id, store_id, membership_role, status, created_at, updated_at, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0)
            """.trimIndent(),
            UUID.randomUUID(),
            accountId,
            storeId,
            role,
            status,
            Timestamp.from(NOW),
            Timestamp.from(NOW),
        )
    }

    private fun seedStore(name: String): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)", id)
        jdbc.update(
            "INSERT INTO merchant_store_discovery_profile (store_id, name, location, region_code) " +
                "VALUES (?, ?, ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, ?)",
            id,
            name,
            SEOUL,
        )
        return id
    }

    private fun storeRegionCode(storeId: UUID): String? =
        jdbc
            .queryForList(
                "SELECT region_code FROM merchant_store_discovery_profile WHERE store_id = ?",
                String::class.java,
                storeId,
            ).firstOrNull()

    private fun regionTermCount(storeId: UUID): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM discovery_store_search_term WHERE store_id = ? AND term_kind LIKE 'REGION%'",
            Long::class.java,
            storeId,
        ) ?: 0

    private fun auditActions(storeId: UUID): List<String> =
        jdbc.query(
            "SELECT action FROM operations_audit_record WHERE target_id = ? ORDER BY action",
            { row, _ -> row.getString("action") },
            storeId,
        )

    private fun auditRegionCodes(storeId: UUID): List<Pair<String, String>> =
        jdbc.query(
            "SELECT before_summary, after_summary FROM operations_audit_record WHERE target_id = ?",
            { row, _ -> row.getString("before_summary") to row.getString("after_summary") },
            storeId,
        )

    private fun commandCount(): Long = jdbc.queryForObject("SELECT count(*) FROM merchant_store_region_command", Long::class.java) ?: 0

    private data class MerchantSession(
        val cookie: Cookie,
        val csrf: Cookie,
    )

    private companion object {
        const val CSRF_COOKIE = "BEANFLOW_MERCHANT_XSRF"
        const val SESSION_COOKIE = "BEANFLOW_MERCHANT_SESSION"
        const val CSRF_HEADER = "X-BEANFLOW-CSRF"
        const val PASSWORD = "merchant-current-password-2026"
        const val SEOUL = "1100000000"
        const val YEOKSAM = "1168010100"
        const val GUNNAE_RI = "1213025021"
        val NOW: Instant = Instant.parse("2026-08-15T00:00:00Z")
        val CURSOR = Regex("\"nextCursor\":\"([^\"]+)\"")
    }
}

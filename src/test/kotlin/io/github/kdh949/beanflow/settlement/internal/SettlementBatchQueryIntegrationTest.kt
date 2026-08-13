package io.github.kdh949.beanflow.settlement.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.refund.initial-delay-ms=3600000",
        "beanflow.payment.point-recovery.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
        "beanflow.settlement.batch.initial-delay-ms=3600000",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class SettlementBatchQueryIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        @BeforeEach
        fun cleanData() {
            jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    identity_store_membership,
                    settlement_dispute,
                    settlement_adjustment,
                    settlement_item,
                    settlement_batch,
                    merchant_store
                CASCADE
                """.trimIndent(),
            )
        }

        @Test
        fun `batch list uses descending signed cursor and does not expose uncalculated summary`() {
            val storeId = insertStore()
            val actorId = insertMembership(storeId, "OWNER", "ACTIVE")
            val olderId = UUID.fromString("11111111-1111-1111-1111-111111111111")
            val sameDateLowerId = UUID.fromString("22222222-2222-2222-2222-222222222222")
            val sameDateHigherId = UUID.fromString("33333333-3333-3333-3333-333333333333")
            insertCalculatedBatch(olderId, storeId, LocalDate.of(2026, 8, 1), confirmed = true, net = 700)
            insertCalculatedBatch(sameDateLowerId, storeId, LocalDate.of(2026, 8, 2), confirmed = false, net = 800)
            insertCalculatedBatch(sameDateHigherId, storeId, LocalDate.of(2026, 8, 3), confirmed = true, net = 900)
            insertOpenBatch(storeId, LocalDate.of(2026, 8, 4))

            val first =
                mockMvc
                    .perform(
                        get(path(storeId)).param("limit", "1").with(storeJwt(actorId, "STORE_OWNER")),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].settlementBatchId").value(sameDateHigherId.toString()))
                    .andExpect(jsonPath("$.items[0].state").value("CONFIRMED"))
                    .andExpect(jsonPath("$.items[0].netSettlementKrw").value(900))
                    .andExpect(jsonPath("$.page.nextCursor").isString)
                    .andReturn()
            val firstCursor = cursor(first.response.contentAsString)
            val second =
                mockMvc
                    .perform(
                        get(path(storeId))
                            .param("limit", "1")
                            .param("cursor", firstCursor)
                            .with(storeJwt(actorId, "STORE_OWNER")),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.items[0].settlementBatchId").value(sameDateLowerId.toString()))
                    .andReturn()
            val secondCursor = cursor(second.response.contentAsString)
            mockMvc
                .perform(
                    get(path(storeId))
                        .param("limit", "1")
                        .param("cursor", secondCursor)
                        .with(storeJwt(actorId, "STORE_OWNER")),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.items[0].settlementBatchId").value(olderId.toString()))
                .andExpect(jsonPath("$.page.nextCursor").doesNotExist())

            assertThat(
                count("SELECT count(*) FROM settlement_batch WHERE state = 'OPEN' AND store_id = ?", storeId),
            ).isOne()
        }

        @Test
        fun `batch cursor is store scoped and only active owner membership is allowed`() {
            val storeId = insertStore()
            val ownerId = insertMembership(storeId, "OWNER", "ACTIVE")
            val staffId = insertMembership(storeId, "STAFF", "ACTIVE")
            val revokedOwnerId = insertMembership(storeId, "OWNER", "REVOKED")
            insertCalculatedBatch(UUID.randomUUID(), storeId, LocalDate.of(2026, 8, 3), confirmed = true, net = 800)
            insertCalculatedBatch(UUID.randomUUID(), storeId, LocalDate.of(2026, 8, 2), confirmed = true, net = 700)
            val first =
                mockMvc
                    .perform(get(path(storeId)).param("limit", "1").with(storeJwt(ownerId, "STORE_OWNER")))
                    .andExpect(status().isOk)
                    .andReturn()
            val cursor = cursor(first.response.contentAsString)
            val otherStore = insertStore()
            val otherOwner = insertMembership(otherStore, "OWNER", "ACTIVE")

            mockMvc
                .perform(
                    get(path(otherStore))
                        .param("cursor", cursor)
                        .with(storeJwt(otherOwner, "STORE_OWNER")),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            mockMvc
                .perform(get(path(storeId)).with(storeJwt(staffId, "STORE_STAFF")))
                .andExpect(status().isForbidden)
            mockMvc
                .perform(get(path(storeId)).with(storeJwt(revokedOwnerId, "STORE_OWNER")))
                .andExpect(status().isForbidden)
            mockMvc
                .perform(get(path(storeId)).param("limit", "101").with(storeJwt(ownerId, "STORE_OWNER")))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }

        private fun insertStore(): UUID =
            UUID.randomUUID().also {
                jdbcTemplate.update(
                    "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) " +
                        "VALUES (?, true, true, 0)",
                    it,
                )
            }

        private fun insertMembership(
            storeId: UUID,
            role: String,
            status: String,
        ): UUID =
            UUID.randomUUID().also { actorId ->
                jdbcTemplate.update(
                    """
                    INSERT INTO identity_store_membership (
                        id, actor_id, store_id, membership_role, status,
                        created_at, updated_at, version
                    ) VALUES (?, ?, ?, ?, ?, now(), now(), 0)
                    """.trimIndent(),
                    UUID.randomUUID(),
                    actorId,
                    storeId,
                    role,
                    status,
                )
            }

        private fun insertOpenBatch(
            storeId: UUID,
            date: LocalDate,
        ) {
            jdbcTemplate.update(
                "INSERT INTO settlement_batch (id, store_id, settlement_date, state, created_at, version) " +
                    "VALUES (?, ?, ?, 'OPEN', now(), 0)",
                UUID.randomUUID(),
                storeId,
                date,
            )
        }

        private fun insertCalculatedBatch(
            id: UUID,
            storeId: UUID,
            date: LocalDate,
            confirmed: Boolean,
            net: Long,
        ) {
            val confirmedAt = if (confirmed) "'2026-08-05T00:01:00Z'" else "NULL"
            jdbcTemplate.update(
                """
                INSERT INTO settlement_batch (
                    id, store_id, settlement_date, state, created_at,
                    item_count, gross_paid_krw, fee_krw, benefit_cost_krw,
                    item_net_settlement_krw, adjustment_krw,
                    carry_forward_in_krw, carry_forward_out_krw,
                    calculated_at, confirmed_at, version
                ) VALUES (?, ?, ?, ?, now(), 1, ?, 50, 50, ?, 0, 0, 0,
                          '2026-08-05T00:00:00Z', $confirmedAt, 1)
                """.trimIndent(),
                id,
                storeId,
                date,
                if (confirmed) "CONFIRMED" else "CALCULATED",
                net + 100,
                net,
            )
        }

        private fun cursor(body: String): String =
            tools.jackson.databind.json.JsonMapper
                .builder()
                .build()
                .readTree(body)["page"]["nextCursor"]
                .stringValue()

        private fun count(
            sql: String,
            vararg arguments: Any,
        ): Long = requireNotNull(jdbcTemplate.queryForObject(sql, Long::class.java, *arguments))

        private fun path(storeId: UUID): String = "/api/v1/stores/$storeId/settlements"

        private fun storeJwt(
            actorId: UUID,
            role: String,
        ) = jwt()
            .jwt {
                it
                    .subject(actorId.toString())
                    .claim("roles", listOf(role))
            }.authorities(SimpleGrantedAuthority("ROLE_MERCHANT"))
    }

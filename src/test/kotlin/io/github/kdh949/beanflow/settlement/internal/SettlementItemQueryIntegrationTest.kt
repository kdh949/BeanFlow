package io.github.kdh949.beanflow.settlement.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.MerchantAccountDatabaseFixture
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.internal.OrderCreationDatabaseFixture
import io.github.kdh949.beanflow.tamperSignedCursorSignature
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("query audit requires committed fixture visibility")
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
    ],
)
internal class SettlementItemQueryIntegrationTest
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
                    identity_merchant_account,
                    settlement_item,
                    settlement_batch,
                    ordering_order,
                    merchant_store
                CASCADE
                """.trimIndent(),
            )
        }

        @Test
        fun `equal completion times paginate by item ID and reject another batch or tampered cursor`() {
            val storeId = insertStore()
            val actorId = insertMembership(storeId, "OWNER", "ACTIVE")
            val batchId = insertBatch(storeId)
            val otherBatchId = insertBatch(storeId, LocalDate.of(2026, 8, 4))
            val completedAt = Instant.parse("2026-08-03T01:02:03Z")
            val firstId = UUID.fromString("11111111-1111-1111-1111-111111111111")
            val secondId = UUID.fromString("22222222-2222-2222-2222-222222222222")
            val thirdId = UUID.fromString("33333333-3333-3333-3333-333333333333")
            insertItem(firstId, batchId, storeId, completedAt)
            insertItem(secondId, batchId, storeId, completedAt)
            insertItem(thirdId, batchId, storeId, completedAt.plusSeconds(1))

            val first =
                mockMvc
                    .perform(
                        get(path(storeId, batchId)).param("limit", "1").with(storeJwt(actorId, "STORE_OWNER")),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].settlementItemId").value(firstId.toString()))
                    .andExpect(jsonPath("$.items[0].settlementBatchId").value(batchId.toString()))
                    .andExpect(jsonPath("$.items[0].netSettlementKrw").value(800))
                    .andExpect(jsonPath("$.page.nextCursor").isString)
                    .andReturn()
            val firstCursor = cursor(first.response.contentAsString)
            val second =
                mockMvc
                    .perform(
                        get(path(storeId, batchId))
                            .param("limit", "1")
                            .param("cursor", firstCursor)
                            .with(storeJwt(actorId, "STORE_OWNER")),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.items[0].settlementItemId").value(secondId.toString()))
                    .andExpect(jsonPath("$.page.nextCursor").isString)
                    .andReturn()
            val secondCursor = cursor(second.response.contentAsString)
            mockMvc
                .perform(
                    get(path(storeId, batchId))
                        .param("limit", "1")
                        .param("cursor", secondCursor)
                        .with(storeJwt(actorId, "STORE_OWNER")),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.items[0].settlementItemId").value(thirdId.toString()))
                .andExpect(jsonPath("$.page.nextCursor").doesNotExist())

            mockMvc
                .perform(
                    get(path(storeId, otherBatchId))
                        .param("limit", "1")
                        .param("cursor", firstCursor)
                        .with(storeJwt(actorId, "STORE_OWNER")),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            val tampered = tamperSignedCursorSignature(firstCursor)
            mockMvc
                .perform(
                    get(path(storeId, batchId))
                        .param("cursor", tampered)
                        .with(storeJwt(actorId, "STORE_OWNER")),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }

        @Test
        fun `default page is twenty and limit over one hundred is rejected`() {
            val storeId = insertStore()
            val actorId = insertMembership(storeId, "OWNER", "ACTIVE")
            val batchId = insertBatch(storeId)
            repeat(21) { index ->
                insertItem(
                    UUID.randomUUID(),
                    batchId,
                    storeId,
                    Instant.parse("2026-08-03T01:00:00Z").plusSeconds(index.toLong()),
                )
            }

            mockMvc
                .perform(get(path(storeId, batchId)).with(storeJwt(actorId, "STORE_OWNER")))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.page.nextCursor").isString)
            mockMvc
                .perform(
                    get(path(storeId, batchId)).param("limit", "101").with(storeJwt(actorId, "STORE_OWNER")),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }

        @Test
        fun `only the active owner reads the settlement item ledger`() {
            val storeId = insertStore()
            val batchId = insertBatch(storeId)
            val ownerId = insertMembership(storeId, "OWNER", "ACTIVE")
            val staffId = insertMembership(storeId, "STAFF", "ACTIVE")
            val revokedOwnerId = insertMembership(storeId, "OWNER", "REVOKED")
            val otherStoreOwner = insertMembership(insertStore(), "OWNER", "ACTIVE")

            mockMvc
                .perform(get(path(storeId, batchId)).with(storeJwt(ownerId, "STORE_OWNER")))
                .andExpect(status().isOk)
            // 명세는 수수료·혜택 원가·실지급액이라 STAFF에게는 열지 않는다.
            mockMvc
                .perform(get(path(storeId, batchId)).with(storeJwt(staffId, "STORE_STAFF")))
                .andExpect(status().isForbidden)
            mockMvc
                .perform(get(path(storeId, batchId)).with(storeJwt(UUID.randomUUID(), "STORE_OWNER")))
                .andExpect(status().isForbidden)
            mockMvc
                .perform(get(path(storeId, batchId)).with(storeJwt(revokedOwnerId, "STORE_OWNER")))
                .andExpect(status().isForbidden)
            // JWT role은 인가 근거가 아니다. 현재 DB membership이 OWNER이면 허용한다.
            mockMvc
                .perform(get(path(storeId, batchId)).with(storeJwt(ownerId, "STORE_STAFF")))
                .andExpect(status().isOk)
            mockMvc
                .perform(get(path(storeId, batchId)).with(storeJwt(otherStoreOwner, "STORE_OWNER")))
                .andExpect(status().isForbidden)
        }

        @Test
        fun `missing batch and store path mismatch are hidden as not found after membership`() {
            val pathStoreId = insertStore()
            val actorId = insertMembership(pathStoreId, "OWNER", "ACTIVE")
            val otherStoreId = insertStore()
            val otherBatchId = insertBatch(otherStoreId)

            mockMvc
                .perform(get(path(pathStoreId, UUID.randomUUID())).with(storeJwt(actorId, "STORE_OWNER")))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            mockMvc
                .perform(get(path(pathStoreId, otherBatchId)).with(storeJwt(actorId, "STORE_OWNER")))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
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
            state: String,
        ): UUID =
            UUID.randomUUID().also { actorId ->
                MerchantAccountDatabaseFixture.insertActive(jdbcTemplate, actorId)
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
                    state,
                )
            }

        private fun insertBatch(
            storeId: UUID,
            settlementDate: LocalDate = LocalDate.of(2026, 8, 3),
        ): UUID =
            UUID.randomUUID().also {
                jdbcTemplate.update(
                    """
                    INSERT INTO settlement_batch (
                        id, store_id, settlement_date, state, created_at, version
                    ) VALUES (?, ?, ?, 'OPEN', now(), 0)
                    """.trimIndent(),
                    it,
                    storeId,
                    settlementDate,
                )
            }

        private fun insertItem(
            itemId: UUID,
            batchId: UUID,
            storeId: UUID,
            completedAt: Instant,
        ) {
            val orderId = insertSyntheticCompletedOrder(storeId, completedAt)
            jdbcTemplate.update(
                """
                INSERT INTO settlement_item (
                    id, settlement_batch_id, order_id, store_id, item_source,
                    completed_at, settlement_date, currency,
                    gross_paid_krw, fee_rate_bps, fee_krw,
                    coupon_cost_krw, point_cost_krw, benefit_cost_krw,
                    net_settlement_krw, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, '2026-08-03', 'KRW',
                          1000, 500, 50, 100, 50, 150, 800, now())
                """.trimIndent(),
                itemId,
                batchId,
                orderId,
                storeId,
                "order:$orderId:completed:7",
                Timestamp.from(completedAt),
            )
        }

        private fun insertSyntheticCompletedOrder(
            storeId: UUID,
            completedAt: Instant,
        ): UUID =
            UUID.randomUUID().also { orderId ->
                val publicReference = OrderCreationDatabaseFixture.registerPublicReference(jdbcTemplate, orderId)
                jdbcTemplate.execute("ALTER TABLE ordering_order DISABLE TRIGGER USER")
                try {
                    jdbcTemplate.update(
                        """
                        INSERT INTO ordering_order (
                            id, customer_id, store_id, pickup_slot_id,
                            public_reference, pickup_business_date, pickup_sequence,
                            store_name_snapshot, pickup_window_start_snapshot, pickup_window_end_snapshot,
                            state,
                            subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw,
                            currency, reservation_expires_at, paid_at, acceptance_warning_at,
                            acceptance_deadline_at, accepted_at, preparing_at, ready_at, completed_at,
                            created_at, updated_at, version
                        ) VALUES (?, ?, ?, ?, ?, DATE '2026-08-03', ?,
                                  'Test Store', '2026-08-03T00:00:00Z', '2026-08-03T00:10:00Z',
                                  'COMPLETED', 1000, 100, 100, 800,
                                  'KRW', NULL,
                                  '2026-08-03T00:10:00Z', '2026-08-03T00:12:00Z',
                                  '2026-08-03T00:13:00Z', '2026-08-03T00:11:00Z',
                                  '2026-08-03T00:12:00Z', '2026-08-03T00:13:00Z', ?,
                                  '2026-08-03T00:00:00Z', ?, 7)
                        """.trimIndent(),
                        orderId,
                        UUID.randomUUID(),
                        storeId,
                        UUID.randomUUID(),
                        publicReference,
                        OrderCreationDatabaseFixture.pickupSequence(orderId),
                        Timestamp.from(completedAt),
                        Timestamp.from(completedAt),
                    )
                } finally {
                    jdbcTemplate.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
                }
            }

        private fun cursor(body: String): String =
            tools.jackson.databind.json.JsonMapper
                .builder()
                .build()
                .readTree(body)["page"]["nextCursor"]
                .stringValue()

        private fun path(
            storeId: UUID,
            batchId: UUID,
        ): String = "/api/v1/stores/$storeId/settlements/$batchId/items"

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

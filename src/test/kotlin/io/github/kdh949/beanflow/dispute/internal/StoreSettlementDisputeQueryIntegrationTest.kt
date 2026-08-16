package io.github.kdh949.beanflow.dispute.internal

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
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
private val BASE_COMPLETED_AT: Instant = Instant.parse("2026-09-01T01:00:00Z")

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
        "beanflow.settlement.dispute-decision.initial-delay-ms=3600000",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class StoreSettlementDisputeQueryIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        private val filedAt = Instant.parse("2026-08-16T00:00:00Z")
        private var settlementDays = 0L

        @BeforeEach
        fun cleanData() {
            settlementDays = 0
            jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    settlement_dispute,
                    settlement_adjustment,
                    identity_store_membership,
                    identity_merchant_account,
                    settlement_item,
                    settlement_batch,
                    ordering_order,
                    ordering_public_reference_registry,
                    merchant_store
                CASCADE
                """.trimIndent(),
            )
        }

        @Test
        fun `the page is scoped to its store and ordered by newest filing first`() {
            val storeId = insertStore()
            val otherStoreId = insertStore()
            val owner = insertMembership(storeId, "OWNER", "ACTIVE")
            val older = insertDispute(storeId, filedAt)
            val newer = insertDispute(storeId, filedAt.plusSeconds(60))
            insertDispute(otherStoreId, filedAt.plusSeconds(120))

            mockMvc
                .perform(get(path(storeId)).with(storeJwt(owner, "STORE_OWNER")))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].disputeId").value(newer.toString()))
                .andExpect(jsonPath("$.items[1].disputeId").value(older.toString()))
                .andExpect(jsonPath("$.page.nextCursor").doesNotExist())
        }

        @Test
        fun `the summary hides internal filing evidence and actor credentials`() {
            val storeId = insertStore()
            val owner = insertMembership(storeId, "OWNER", "ACTIVE")
            val disputeId = insertDispute(storeId, filedAt)

            val body =
                mockMvc
                    .perform(get(path(storeId)).with(storeJwt(owner, "STORE_OWNER")))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.items[0].disputeId").value(disputeId.toString()))
                    .andExpect(jsonPath("$.items[0].state").value("FILED"))
                    .andExpect(jsonPath("$.items[0].expectedAdjustmentKrw").value(500))
                    .andExpect(jsonPath("$.items[0].heldAmountKrw").value(500))
                    .andExpect(jsonPath("$.items[0].filedAt").exists())
                    .andExpect(jsonPath("$.items[0].decidedAt").doesNotExist())
                    .andReturn()
                    .response.contentAsString

            assertThat(body).doesNotContain("reason", "evidenceReferences", "actorId", "idempotencyKey", "correlationId")
        }

        @Test
        fun `equal filing times paginate by dispute ID without gaps or repeats`() {
            val storeId = insertStore()
            val owner = insertMembership(storeId, "OWNER", "ACTIVE")
            val disputes = (1..5).map { insertDispute(storeId, filedAt) }.toSet()

            val seen = mutableListOf<String>()
            var cursor: String? = null
            do {
                val request = get(path(storeId)).param("limit", "2").with(storeJwt(owner, "STORE_OWNER"))
                cursor?.let { request.param("cursor", it) }
                val body =
                    json(
                        mockMvc
                            .perform(request)
                            .andExpect(status().isOk)
                            .andReturn()
                            .response.contentAsString,
                    )
                body["items"].forEach { seen += it["disputeId"].stringValue() }
                cursor = body["page"]["nextCursor"]?.stringValue()
            } while (cursor != null)

            assertThat(seen).doesNotHaveDuplicates()
            assertThat(seen.map(UUID::fromString).toSet()).isEqualTo(disputes)
        }

        @Test
        fun `a cursor is bound to its store and state filter and its signature`() {
            val storeId = insertStore()
            val otherStoreId = insertStore()
            val owner = insertMembership(storeId, "OWNER", "ACTIVE")
            insertMembership(otherStoreId, "OWNER", "ACTIVE", actorId = owner)
            repeat(3) { insertDispute(storeId, filedAt.plusSeconds(it.toLong())) }
            repeat(3) { insertDispute(otherStoreId, filedAt.plusSeconds(it.toLong())) }

            val cursor =
                json(
                    mockMvc
                        .perform(get(path(storeId)).param("limit", "1").with(storeJwt(owner, "STORE_OWNER")))
                        .andExpect(status().isOk)
                        .andReturn()
                        .response.contentAsString,
                )["page"]["nextCursor"].stringValue()

            mockMvc
                .perform(get(path(otherStoreId)).param("cursor", cursor).with(storeJwt(owner, "STORE_OWNER")))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            mockMvc
                .perform(
                    get(path(storeId))
                        .param("state", "FILED")
                        .param("cursor", cursor)
                        .with(storeJwt(owner, "STORE_OWNER")),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            mockMvc
                .perform(
                    get(path(storeId))
                        .param("cursor", tamperSignedCursorSignature(cursor))
                        .with(storeJwt(owner, "STORE_OWNER")),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }

        @Test
        fun `the state filter selects only that state`() {
            val storeId = insertStore()
            val owner = insertMembership(storeId, "OWNER", "ACTIVE")
            val filed = insertDispute(storeId, filedAt)
            val reviewing = insertDispute(storeId, filedAt.plusSeconds(30))
            jdbcTemplate.update("UPDATE settlement_dispute SET state = 'UNDER_REVIEW' WHERE id = ?", reviewing)

            mockMvc
                .perform(get(path(storeId)).param("state", "FILED").with(storeJwt(owner, "STORE_OWNER")))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].disputeId").value(filed.toString()))
            mockMvc
                .perform(get(path(storeId)).param("state", "UNDER_REVIEW").with(storeJwt(owner, "STORE_OWNER")))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items[0].disputeId").value(reviewing.toString()))
            mockMvc
                .perform(get(path(storeId)).param("state", "SETTLED").with(storeJwt(owner, "STORE_OWNER")))
                .andExpect(status().isBadRequest)
        }

        @Test
        fun `default page is twenty and a limit over one hundred is rejected`() {
            val storeId = insertStore()
            val owner = insertMembership(storeId, "OWNER", "ACTIVE")
            repeat(21) { insertDispute(storeId, filedAt.plusSeconds(it.toLong())) }

            mockMvc
                .perform(get(path(storeId)).with(storeJwt(owner, "STORE_OWNER")))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.page.nextCursor").isString)
            mockMvc
                .perform(get(path(storeId)).param("limit", "101").with(storeJwt(owner, "STORE_OWNER")))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }

        @Test
        fun `staff revoked owner and another store owner are forbidden`() {
            val storeId = insertStore()
            val owner = insertMembership(storeId, "OWNER", "ACTIVE")
            val staff = insertMembership(storeId, "STAFF", "ACTIVE")
            val revoked = insertMembership(storeId, "OWNER", "REVOKED")
            val otherStoreOwner = insertMembership(insertStore(), "OWNER", "ACTIVE")
            insertDispute(storeId, filedAt)

            mockMvc
                .perform(get(path(storeId)).with(storeJwt(owner, "STORE_OWNER")))
                .andExpect(status().isOk)
            listOf(staff, revoked, otherStoreOwner, UUID.randomUUID()).forEach { actorId ->
                mockMvc
                    .perform(get(path(storeId)).with(storeJwt(actorId, "STORE_OWNER")))
                    .andExpect(status().isForbidden)
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            }
        }

        @Test
        fun `an owner with no dispute yet reads an empty page rather than an error`() {
            val storeId = insertStore()
            val owner = insertMembership(storeId, "OWNER", "ACTIVE")

            mockMvc
                .perform(get(path(storeId)).with(storeJwt(owner, "STORE_OWNER")))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.page.nextCursor").doesNotExist())
        }

        private fun path(storeId: UUID): String = "/api/v1/stores/$storeId/disputes"

        private fun json(body: String) =
            JsonMapper
                .builder()
                .build()
                .readTree(body)

        private fun storeJwt(
            actorId: UUID,
            role: String,
        ) = jwt()
            .jwt { it.subject(actorId.toString()).claim("roles", listOf(role)) }
            .authorities(SimpleGrantedAuthority("ROLE_MERCHANT"))

        private fun insertStore(): UUID =
            UUID.randomUUID().also {
                jdbcTemplate.update(
                    "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                    it,
                )
            }

        private fun insertMembership(
            storeId: UUID,
            role: String,
            state: String,
            actorId: UUID = UUID.randomUUID(),
        ): UUID =
            actorId.also {
                if (jdbcTemplate.queryForObject(
                        "select count(*) from identity_merchant_account where id = ?",
                        Long::class.java,
                        it,
                    ) == 0L
                ) {
                    MerchantAccountDatabaseFixture.insertActive(jdbcTemplate, it)
                }
                jdbcTemplate.update(
                    """
                    INSERT INTO identity_store_membership (
                        id, actor_id, store_id, membership_role, status, created_at, updated_at, version
                    ) VALUES (?, ?, ?, ?, ?, now(), now(), 0)
                    """.trimIndent(),
                    UUID.randomUUID(),
                    it,
                    storeId,
                    role,
                    state,
                )
            }

        private fun insertDispute(
            storeId: UUID,
            at: Instant,
        ): UUID {
            val itemId = insertConfirmedItem(storeId)
            val disputeId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO settlement_dispute (
                    id, settlement_item_id, store_id, previous_dispute_id, refile_count,
                    state, expected_adjustment_krw, held_amount_krw, reason, evidence_references,
                    actor_id, operation, idempotency_key, payload_hash,
                    response_status, response_body, correlation_id, filed_at, version
                ) VALUES (?, ?, ?, NULL, 0, 'FILED', 500, 500, 'query fixture', ?::jsonb,
                          ?, 'SETTLEMENT_DISPUTE_FILE', ?, ?, 201, '{}', ?, ?, 0)
                """.trimIndent(),
                disputeId,
                itemId,
                storeId,
                """["evidence:$disputeId"]""",
                UUID.randomUUID(),
                "key-$disputeId",
                "a".repeat(64),
                "correlation:$disputeId",
                Timestamp.from(at),
            )
            return disputeId
        }

        /**
         * Each fixture Item needs its own confirmed Batch: an Item can only be
         * attached while its Batch is OPEN, a Batch is unique per store and
         * settlement date, and only one dispute can be active per Item. The
         * settlement day therefore advances per Item while `filedAt` stays
         * whatever the test asked for.
         */
        private fun insertConfirmedItem(storeId: UUID): UUID {
            val completedAt = BASE_COMPLETED_AT.plus(settlementDays++, ChronoUnit.DAYS)
            val settlementDate = LocalDate.ofInstant(completedAt, SEOUL)
            val batchId = openBatch(storeId, settlementDate)
            val itemId = UUID.randomUUID()
            val orderId = insertSyntheticCompletedOrder(storeId, completedAt)
            jdbcTemplate.update(
                """
                INSERT INTO settlement_item (
                    id, settlement_batch_id, order_id, store_id, item_source,
                    completed_at, settlement_date, currency,
                    gross_paid_krw, fee_rate_bps, fee_krw,
                    coupon_cost_krw, point_cost_krw, benefit_cost_krw,
                    net_settlement_krw, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'KRW',
                          1000, 500, 50, 100, 50, 150, 800, now())
                """.trimIndent(),
                itemId,
                batchId,
                orderId,
                storeId,
                "order:$orderId:completed:7",
                Timestamp.from(completedAt),
                settlementDate,
            )
            confirm(batchId)
            return itemId
        }

        private fun openBatch(
            storeId: UUID,
            settlementDate: LocalDate,
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

        private fun confirm(batchId: UUID) {
            jdbcTemplate.update(
                """
                UPDATE settlement_batch
                   SET state = 'CALCULATED', item_count = 1,
                       gross_paid_krw = 1000, fee_krw = 50, benefit_cost_krw = 150,
                       item_net_settlement_krw = 800, adjustment_krw = 0,
                       carry_forward_in_krw = 0, carry_forward_out_krw = 0,
                       calculated_at = now(), version = version + 1
                 WHERE id = ?
                """.trimIndent(),
                batchId,
            )
            jdbcTemplate.update(
                "UPDATE settlement_batch SET state = 'CONFIRMED', confirmed_at = now(), version = version + 1 WHERE id = ?",
                batchId,
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
    }

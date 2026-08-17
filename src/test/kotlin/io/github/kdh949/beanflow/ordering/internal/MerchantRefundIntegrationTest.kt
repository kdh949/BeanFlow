package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import io.github.kdh949.beanflow.payment.internal.GatewayRefundResult
import io.github.kdh949.beanflow.payment.internal.ScriptedTestPaymentGateway
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.refund.initial-delay-ms=3600000",
        "beanflow.payment.refund-restoration.initial-delay-ms=3600000",
        "beanflow.payment.point-recovery.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class MerchantRefundIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val createOrders: io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase,
        private val confirmationService: PaymentConfirmationService,
        private val paymentGateway: ScriptedTestPaymentGateway,
        private val objectMapper: ObjectMapper,
        private val meterRegistry: MeterRegistry,
    ) {
        private val now = Instant.parse("2026-08-17T03:00:00Z")

        @BeforeEach
        fun cleanDatabase() {
            OrderCreationDatabaseFixture.clean(jdbcTemplate)
            jdbcTemplate.execute("TRUNCATE TABLE ordering_store_command_idempotency CASCADE")
            paymentGateway.reset()
        }

        @Test
        fun `an omitted selection previews the refundable catalog without selecting or writing anything`() {
            val order = paidOrder()
            val owner = merchantActor(order.storeId, "OWNER", "ACTIVE")
            val auditsBeforePreview = auditCount()

            val body = json(preview(owner, order, null).andExpect(status().isOk))

            assertThat(body["orderReference"].asText()).isEqualTo(order.reference)
            assertThat(body["lines"]).hasSize(1)
            val line = body["lines"].single()
            assertThat(line["lineSequence"].asInt()).isZero()
            assertThat(line["menuName"].asText()).isEqualTo("Americano")
            assertThat(line["remainingQuantity"].asLong()).isEqualTo(3)
            assertThat(line["selectedQuantity"].asLong()).isZero()
            assertThat(line["cashRefundKrw"].asLong()).isZero()
            assertThat(body["totals"]["cashRefundKrw"].asLong()).isZero()
            assertThat(body["totals"]["currency"].asText()).isEqualTo("KRW")
            assertThat(body["previewVersion"].asText()).matches("[0-9a-f]{64}")
            assertThat(refundCount()).isZero()
            assertThat(auditCount()).isEqualTo(auditsBeforePreview)
            assertThat(paymentGateway.rejectionRefundCalls.get()).isZero()
        }

        @Test
        fun `a selection previews server-calculated amounts without exposing internal identifiers`() {
            val order = paidOrder()
            val owner = merchantActor(order.storeId, "OWNER", "ACTIVE")

            val response = preview(owner, order, selection(2)).andExpect(status().isOk).andReturn().response
            val body = objectMapper.readTree(response.contentAsString)

            val line = body["lines"].single()
            assertThat(line["selectedQuantity"].asLong()).isEqualTo(2)
            assertThat(line["cashRefundKrw"].asLong()).isEqualTo(2_000)
            assertThat(body["totals"]["cashRefundKrw"].asLong()).isEqualTo(2_000)
            assertThat(response.contentAsString).doesNotContain("paymentId", "orderLineId", "refundId")
        }

        @Test
        fun `both owner and staff of the store may preview and refund their own order`() {
            val order = paidOrder()
            val owner = merchantActor(order.storeId, "OWNER", "ACTIVE")
            val staff = merchantActor(order.storeId, "STAFF", "ACTIVE")
            paymentGateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-refund-owner"))
            paymentGateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-refund-staff"))

            refund(owner, order, selection(1), previewVersion(owner, order), "owner-key-0001")
                .andExpect(status().isOk)
            refund(staff, order, selection(1), previewVersion(staff, order), "staff-key-0001")
                .andExpect(status().isOk)

            assertThat(remainingQuantity(owner, order)).isEqualTo(1)
            assertThat(paymentGateway.rejectionRefundCalls.get()).isEqualTo(2)
        }

        @Test
        fun `a refund executed by staff is audited as staff, never blended with owner`() {
            val order = paidOrder()
            val staff = merchantActor(order.storeId, "STAFF", "ACTIVE")
            paymentGateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-refund-staff-audit"))

            refund(staff, order, selection(1), previewVersion(staff, order), "staff-audit-key-0001")
                .andExpect(status().isOk)

            assertThat(lastRefundAuditActorType()).isEqualTo("STORE_STAFF")
        }

        @Test
        fun `a refund executed by owner is audited as owner`() {
            val order = paidOrder()
            val owner = merchantActor(order.storeId, "OWNER", "ACTIVE")
            paymentGateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-refund-owner-audit"))

            refund(owner, order, selection(1), previewVersion(owner, order), "owner-audit-key-0001")
                .andExpect(status().isOk)

            assertThat(lastRefundAuditActorType()).isEqualTo("STORE_OWNER")
        }

        @Test
        fun `an unresolved Provider outcome is metered under its own state, not as a false success`() {
            val order = paidOrder()
            val owner = merchantActor(order.storeId, "OWNER", "ACTIVE")
            paymentGateway.enqueueRejectionRefund(GatewayRefundResult.Unknown("PROVIDER_TIMEOUT"))
            val unknownBefore = executionMetric("UNKNOWN")
            val succeededBefore = executionMetric("SUCCEEDED")

            refund(owner, order, selection(1), previewVersion(owner, order), "unresolved-key-0001")
                .andExpect(status().isAccepted)
                .andExpect(jsonPath("$.state").value("UNKNOWN"))

            assertThat(executionMetric("UNKNOWN")).isEqualTo(unknownBefore + 1)
            assertThat(executionMetric("SUCCEEDED")).isEqualTo(succeededBefore)
        }

        @Test
        fun `a refund answers the merchant contract and moves the preview version`() {
            val order = paidOrder()
            val owner = merchantActor(order.storeId, "OWNER", "ACTIVE")
            val before = previewVersion(owner, order)
            paymentGateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-refund-contract"))

            val response =
                refund(owner, order, selection(2), before, "contract-key-0001")
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.orderReference").value(order.reference))
                    .andExpect(jsonPath("$.state").value("SUCCEEDED"))
                    .andExpect(jsonPath("$.cashRefundRequestedKrw").value(2_000))
                    .andExpect(jsonPath("$.cashRefundedKrw").value(2_000))
                    .andExpect(jsonPath("$.pointsRestorationState").value("NOT_REQUIRED"))
                    .andReturn()
                    .response
                    .contentAsString

            assertThat(response).doesNotContain("paymentId", "orderLineId", "refundId")
            assertThat(previewVersion(owner, order)).isNotEqualTo(before)
            assertThat(remainingQuantity(owner, order)).isEqualTo(1)
        }

        @Test
        fun `a preview taken before another refund is stale and creates no second Refund`() {
            val order = paidOrder()
            val owner = merchantActor(order.storeId, "OWNER", "ACTIVE")
            val stale = previewVersion(owner, order)
            paymentGateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-refund-first"))
            refund(owner, order, selection(1), stale, "stale-key-0001").andExpect(status().isOk)

            refund(owner, order, selection(1), stale, "stale-key-0002")
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("REFUND_PREVIEW_STALE"))

            assertThat(refundCount()).isEqualTo(1)
            assertThat(paymentGateway.rejectionRefundCalls.get()).isEqualTo(1)
        }

        @Test
        fun `a quantity over the remaining units is unprocessable and refunds nothing`() {
            val order = paidOrder()
            val owner = merchantActor(order.storeId, "OWNER", "ACTIVE")

            refund(owner, order, selection(4), previewVersion(owner, order), "quantity-key-0001")
                .andExpect(status().isUnprocessableEntity)
                .andExpect(jsonPath("$.code").value("REFUND_QUANTITY_UNAVAILABLE"))

            assertThat(refundCount()).isZero()
            assertThat(paymentGateway.rejectionRefundCalls.get()).isZero()
        }

        @Test
        fun `an unknown line sequence is rejected before any refund state is read`() {
            val order = paidOrder()
            val owner = merchantActor(order.storeId, "OWNER", "ACTIVE")

            preview(owner, order, """{"lines":[{"lineSequence":7,"quantity":1}]}""")
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }

        @Test
        fun `the same Idempotency-Key replays one Provider refund`() {
            val order = paidOrder()
            val owner = merchantActor(order.storeId, "OWNER", "ACTIVE")
            val version = previewVersion(owner, order)
            paymentGateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-refund-replay"))

            val first =
                refund(owner, order, selection(1), version, "replay-key-0001")
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString
            val replay =
                refund(owner, order, selection(1), version, "replay-key-0001")
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString

            assertThat(replay).isEqualTo(first)
            assertThat(refundCount()).isEqualTo(1)
            assertThat(paymentGateway.rejectionRefundCalls.get()).isEqualTo(1)
        }

        @Test
        fun `foreign store revoked membership and missing membership are forbidden on both endpoints`() {
            val order = paidOrder()
            val revoked = merchantActor(order.storeId, "STAFF", "REVOKED")
            val otherStoreActor = merchantActor(UUID.randomUUID(), "OWNER", "ACTIVE")
            val stranger = UUID.randomUUID()

            listOf(revoked, otherStoreActor, stranger).forEach { actor ->
                preview(actor, order, selection(1)).andExpect(status().isForbidden)
                refund(actor, order, selection(1), "0".repeat(64), "forbidden-key-0001")
                    .andExpect(status().isForbidden)
            }
            assertThat(refundCount()).isZero()
        }

        @Test
        fun `an order of another store is forbidden and an unknown reference is not found`() {
            val order = paidOrder()
            val otherOrder = paidOrder()
            val owner = merchantActor(order.storeId, "OWNER", "ACTIVE")

            mockMvc
                .perform(
                    post("/api/v1/stores/${order.storeId}/orders/${otherOrder.reference}/refund-previews")
                        .with(merchantJwt(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selection(1)),
                ).andExpect(status().isForbidden)
            mockMvc
                .perform(
                    post("/api/v1/stores/${order.storeId}/orders/BF-2222-2222/refund-previews")
                        .with(merchantJwt(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selection(1)),
                ).andExpect(status().isNotFound)
        }

        private fun selection(quantity: Long) = """{"lines":[{"lineSequence":0,"quantity":$quantity}]}"""

        private fun preview(
            actorId: UUID,
            order: PaidOrder,
            body: String?,
        ): ResultActions =
            mockMvc.perform(
                post("/api/v1/stores/${order.storeId}/orders/${order.reference}/refund-previews")
                    .with(merchantJwt(actorId))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body ?: "{}"),
            )

        private fun refund(
            actorId: UUID,
            order: PaidOrder,
            lines: String,
            previewVersion: String,
            idempotencyKey: String,
        ): ResultActions {
            val selection = objectMapper.readTree(lines)["lines"]
            return mockMvc.perform(
                post("/api/v1/stores/${order.storeId}/orders/${order.reference}/refunds")
                    .with(merchantJwt(actorId))
                    .with(csrf())
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "lines": $selection,
                          "previewVersion": "$previewVersion",
                          "reason": "테스트 부분 환불"
                        }
                        """.trimIndent(),
                    ),
            )
        }

        private fun previewVersion(
            actorId: UUID,
            order: PaidOrder,
        ): String = json(preview(actorId, order, null).andExpect(status().isOk))["previewVersion"].asText()

        private fun remainingQuantity(
            actorId: UUID,
            order: PaidOrder,
        ): Long =
            json(preview(actorId, order, null).andExpect(status().isOk))["lines"]
                .single()["remainingQuantity"]
                .asLong()

        private fun paidOrder(): PaidOrder {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, slotCapacity = 10, stockAvailable = 10)
            val key = "merchant-refund-${UUID.randomUUID()}"
            val created = createOrders.create(key, fixture.command(quantity = 3))
            assertThat(created.status).isEqualTo(201)
            val order = objectMapper.readTree(created.body)["order"]
            val orderId = UUID.fromString(order["orderId"].asText())
            pay(orderId, fixture.customerId, key)
            return PaidOrder(fixture.storeId, orderId, order["publicReference"].asText())
        }

        private fun pay(
            orderId: UUID,
            customerId: UUID,
            key: String,
        ) {
            val paymentMethodId = UUID.randomUUID()
            val timestamp = Timestamp.from(now)
            jdbcTemplate.update(
                """
                INSERT INTO payment_method (
                    id, customer_id, provider, token_reference, display_alias, card_brand,
                    last_four, status, created_at, updated_at, version
                ) VALUES (?, ?, 'SCRIPTED', ?, 'Refund test', 'TEST', '4242', 'ACTIVE', ?, ?, 0)
                """.trimIndent(),
                paymentMethodId,
                customerId,
                "test-token:$paymentMethodId",
                timestamp,
                timestamp,
            )
            paymentGateway.enqueueApproval(ProviderPaymentResult.Approved("provider-$key", 3_000, "KRW"))
            assertThat(confirmationService.confirm(customerId, orderId, paymentMethodId, "$key-payment").status)
                .isEqualTo(200)
        }

        private fun merchantActor(
            storeId: UUID,
            role: String,
            membershipStatus: String,
        ): UUID {
            val actorId = UUID.randomUUID()
            val timestamp = Timestamp.from(now)
            jdbcTemplate.update(
                """
                INSERT INTO identity_merchant_account (
                    id, login_id, password_hash, credential_version, display_name, state,
                    temporary_password_expires_at, password_changed_at, locked_until,
                    created_at, updated_at, version
                ) VALUES (?, ?, 'test-only-password-hash', 0, 'Refund actor', 'ACTIVE',
                          null, ?, null, ?, ?, 0)
                """.trimIndent(),
                actorId,
                "refund.${actorId.toString().take(8)}",
                timestamp,
                timestamp,
                timestamp,
            )
            jdbcTemplate.update(
                """
                INSERT INTO identity_store_membership (
                    id, actor_id, store_id, membership_role, status, created_at, updated_at, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                """.trimIndent(),
                UUID.randomUUID(),
                actorId,
                storeId,
                role,
                membershipStatus,
                timestamp,
                timestamp,
            )
            return actorId
        }

        private fun refundCount(): Long = jdbcTemplate.queryForObject("select count(*) from payment_refund", Long::class.java) ?: 0

        private fun auditCount(): Long = jdbcTemplate.queryForObject("select count(*) from operations_audit_record", Long::class.java) ?: 0

        private fun lastRefundAuditActorType(): String =
            jdbcTemplate.queryForObject(
                """
                select actor_type from operations_audit_record
                 where action = 'PARTIAL_REFUND_REQUESTED'
                 order by occurred_at desc, id desc
                 limit 1
                """.trimIndent(),
                String::class.java,
            ) ?: error("No PARTIAL_REFUND_REQUESTED audit record was written")

        private fun executionMetric(outcome: String): Double =
            meterRegistry
                .find("beanflow.refund.merchant_execution.count")
                .tag("outcome", outcome)
                .counter()
                ?.count() ?: 0.0

        private fun json(actions: ResultActions): JsonNode = objectMapper.readTree(actions.andReturn().response.contentAsString)

        private fun merchantJwt(actorId: UUID) =
            jwt()
                .jwt { it.subject(actorId.toString()).claim("roles", listOf("STORE_STAFF")) }
                .authorities(SimpleGrantedAuthority("ROLE_MERCHANT"))

        private data class PaidOrder(
            val storeId: UUID,
            val orderId: UUID,
            val reference: String,
        )
    }

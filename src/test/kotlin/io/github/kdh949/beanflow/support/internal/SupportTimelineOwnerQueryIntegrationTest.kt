package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.fulfillment.api.FulfillmentSupportTimelineOperations
import io.github.kdh949.beanflow.loyalty.api.LoyaltySupportTimelineOperations
import io.github.kdh949.beanflow.notification.api.NotificationSupportTimelineOperations
import io.github.kdh949.beanflow.operations.api.OperationsSupportTimelineOperations
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.api.OrderQuoteUseCase
import io.github.kdh949.beanflow.ordering.api.OrderingSupportTimelineOperations
import io.github.kdh949.beanflow.ordering.internal.OrderCreationDatabaseFixture
import io.github.kdh949.beanflow.ordering.internal.OrderCreationFixture
import io.github.kdh949.beanflow.ordering.internal.attachCurrentQuote
import io.github.kdh949.beanflow.payment.api.PaymentSupportTimelineOperations
import io.github.kdh949.beanflow.promotion.api.PromotionSupportTimelineOperations
import io.github.kdh949.beanflow.settlement.api.SettlementSupportTimelineOperations
import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineFact
import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineQuery
import io.github.kdh949.beanflow.shared.api.SupportTimelineSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest(
    properties = [
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
        "beanflow.support-case-idempotency.retention.initial-delay-ms=3600000",
    ],
)
internal class SupportTimelineOwnerQueryIntegrationTest
    @Autowired
    constructor(
        private val jdbcTemplate: JdbcTemplate,
        private val ordering: OrderingSupportTimelineOperations,
        private val payment: PaymentSupportTimelineOperations,
        private val loyalty: LoyaltySupportTimelineOperations,
        private val promotion: PromotionSupportTimelineOperations,
        private val fulfillment: FulfillmentSupportTimelineOperations,
        private val settlement: SettlementSupportTimelineOperations,
        private val notification: NotificationSupportTimelineOperations,
        private val operations: OperationsSupportTimelineOperations,
        private val createOrder: CreateOrderUseCase,
        private val orderQuoteUseCase: OrderQuoteUseCase,
    ) {
        private lateinit var orderId: UUID
        private lateinit var customerId: UUID
        private lateinit var storeId: UUID
        private val now = Instant.parse("2026-08-12T05:00:00Z")

        @BeforeEach
        fun resetAndSeed() {
            OrderCreationDatabaseFixture.clean(jdbcTemplate)
            seedOrderAndOwnerFacts()
        }

        @Test
        fun `all owners expose bounded masked facts through public query APIs`() {
            val query = SupportOwnerTimelineQuery(setOf(orderId), null, 101)
            val facts =
                listOf(
                    ordering.findTimelineFacts(query),
                    payment.findTimelineFacts(query),
                    loyalty.findTimelineFacts(query),
                    promotion.findTimelineFacts(query),
                    fulfillment.findTimelineFacts(query),
                    settlement.findTimelineFacts(query),
                    notification.findTimelineFacts(query),
                    operations.findTimelineFacts(query),
                ).flatten()

            assertThat(facts.map(SupportOwnerTimelineFact::source).toSet())
                .containsExactlyInAnyOrder(
                    SupportTimelineSource.ORDERING,
                    SupportTimelineSource.PAYMENT,
                    SupportTimelineSource.LOYALTY,
                    SupportTimelineSource.PROMOTION,
                    SupportTimelineSource.FULFILLMENT,
                    SupportTimelineSource.SETTLEMENT,
                    SupportTimelineSource.NOTIFICATION,
                    SupportTimelineSource.OPERATIONS,
                )
            assertThat(ordering.findOrderSnapshots(setOf(orderId)).single())
                .extracting("customerId", "storeId", "version")
                .containsExactly(customerId, storeId, 0L)
            assertThat(facts.joinToString("|"))
                .doesNotContain("provider-secret", "recipient@example.com", "audit-private-summary")
        }

        private fun seedOrderAndOwnerFacts() {
            val fixture = OrderCreationFixture()
            customerId = fixture.customerId
            storeId = fixture.storeId
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, priceKrw = 1_000)
            OrderCreationDatabaseFixture.insertPoints(jdbcTemplate, customerId, 100)
            val couponId = OrderCreationDatabaseFixture.insertFixedCoupon(jdbcTemplate, fixture, 100)
            val response =
                createOrder.create(
                    "support-timeline-owner-query-0001",
                    orderQuoteUseCase.attachCurrentQuote(fixture.command(100, couponId)),
                )
            assertThat(response.status).isEqualTo(201)
            orderId = UUID.fromString(requireNotNull(Regex("\\\"orderId\\\":\\\"([^\\\"]+)\\\"").find(response.body)).groupValues[1])
            seedPayment()
            seedSettlement()
            seedNotification()
            seedAudit()
        }

        private fun seedPayment() {
            jdbcTemplate.update(
                """
                INSERT INTO payment_payment (
                    id, order_id, type, approval_state, approved_amount_krw, requested_amount_krw,
                    currency, benefit_snapshot_reference, source_reference, correlation_id,
                    approved_at, created_at, updated_at
                ) VALUES (?, ?, 'BENEFIT_ONLY', 'APPROVED', 0, 0, 'KRW', 'benefit-ref',
                          'timeline-payment', 'timeline-payment', ?, ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                orderId,
                Timestamp.from(now.minusSeconds(40)),
                Timestamp.from(now.minusSeconds(40)),
                Timestamp.from(now.minusSeconds(40)),
            )
        }

        private fun seedSettlement() {
            val batchId = UUID.randomUUID()
            jdbcTemplate.update(
                "INSERT INTO settlement_batch (id, store_id, settlement_date, state, created_at) VALUES (?, ?, ?, 'OPEN', ?)",
                batchId,
                storeId,
                Date.valueOf(LocalDate.of(2026, 8, 12)),
                Timestamp.from(now.minusSeconds(35)),
            )
            jdbcTemplate.update(
                """
                INSERT INTO settlement_item (
                    id, settlement_batch_id, order_id, store_id, item_source, completed_at, settlement_date,
                    currency, gross_paid_krw, fee_rate_bps, fee_krw, coupon_cost_krw, point_cost_krw,
                    benefit_cost_krw, net_settlement_krw, created_at
                ) VALUES (?, ?, ?, ?, 'timeline-settlement', ?, ?, 'KRW', 1000, 1000, 100, 0, 0, 0, 900, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                batchId,
                orderId,
                storeId,
                Timestamp.from(now.minusSeconds(30)),
                Date.valueOf(LocalDate.of(2026, 8, 12)),
                Timestamp.from(now.minusSeconds(30)),
            )
        }

        private fun seedNotification() {
            jdbcTemplate.update(
                """
                INSERT INTO notification_delivery (
                    id, event_id, event_type, order_id, classification, recipient_type, recipient_id, logical_channel,
                    template, payload_json, state, attempt_count, next_attempt_at, provider_idempotency_key,
                    correlation_id, logical_source, created_at, updated_at
                ) VALUES (?, ?, 'OrderCreated', ?, 'TRANSACTIONAL', 'CUSTOMER', ?, 'CUSTOMER_APP', 'ORDER_READY',
                          '{"contact":"recipient@example.com"}', 'PENDING', 0, ?, 'provider-secret',
                          'timeline-notification', 'timeline-notification-source', ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                orderId,
                customerId,
                Timestamp.from(now.plusSeconds(60)),
                Timestamp.from(now.minusSeconds(25)),
                Timestamp.from(now.minusSeconds(25)),
            )
        }

        private fun seedAudit() {
            jdbcTemplate.update(
                """
                INSERT INTO operations_audit_record (
                    id, actor_id, actor_type, action, target_type, target_id, occurred_at, reason,
                    before_summary, after_summary, correlation_id, source_reference, retention_expires_at
                ) VALUES (?, 'system', 'SYSTEM', 'ORDER_CREATED', 'ORDER', ?, ?, 'timeline-test',
                          'audit-private-summary', 'audit-private-summary', 'timeline-audit', 'timeline-audit', ?)
                """.trimIndent(),
                UUID.randomUUID(),
                orderId,
                Timestamp.from(now.minusSeconds(20)),
                Timestamp.from(now.plusSeconds(86400)),
            )
        }
    }

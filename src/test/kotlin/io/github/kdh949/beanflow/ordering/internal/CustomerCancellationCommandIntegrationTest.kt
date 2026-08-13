package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.MerchantAccountDatabaseFixture
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.notification.internal.ScriptedTestNotificationProvider
import io.github.kdh949.beanflow.operations.internal.PaymentCancellationSetupIntegrityWorker
import io.github.kdh949.beanflow.ordering.api.OrderingSupportOrderCancellationOperations
import io.github.kdh949.beanflow.ordering.api.OrderingSupportPickupRescheduleOperations
import io.github.kdh949.beanflow.ordering.api.ReservationExpiryUseCase
import io.github.kdh949.beanflow.ordering.api.SupportOrderCancellationCommand
import io.github.kdh949.beanflow.ordering.api.SupportOrderChangeOwnerResult
import io.github.kdh949.beanflow.ordering.api.SupportPickupRescheduleCommand
import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import io.github.kdh949.beanflow.payment.internal.ScriptedTestPaymentGateway
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.refund.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
        "beanflow.payment-cancellation-setup.initial-delay-ms=3600000",
    ],
)
internal class CustomerCancellationCommandIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val paymentGateway: ScriptedTestPaymentGateway,
        private val notificationProvider: ScriptedTestNotificationProvider,
        private val cancellationTransaction: CustomerCancellationTransaction,
        private val timeoutWorkWorker: AcceptanceTimeoutWorkWorker,
        private val customerCancellationService: CustomerCancellationService,
        private val storeTransitionService: StoreOrderTransitionService,
        private val reservationExpiryUseCase: ReservationExpiryUseCase,
        private val meterRegistry: MeterRegistry,
        private val setupIntegrityWorker: PaymentCancellationSetupIntegrityWorker,
        private val supportPickupReschedules: OrderingSupportPickupRescheduleOperations,
        private val supportOrderCancellations: OrderingSupportOrderCancellationOperations,
    ) {
        @BeforeEach
        fun cleanDatabase() {
            awaitPublicationsSettled()
            OrderCreationDatabaseFixture.clean(jdbcTemplate)
            paymentGateway.reset()
            notificationProvider.reset()
        }

        @AfterEach
        fun waitForOwnerListeners() {
            awaitPublicationsSettled()
        }

        @Test
        fun `public reference customer routes canonicalize authorize and omit the internal order id`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val orderId = createOrder(fixture, "public-customer-order")
            val reference = value("SELECT public_reference FROM ordering_order WHERE id = ?", orderId)
            jdbcTemplate.update(
                "UPDATE merchant_store_discovery_profile SET name = 'Renamed Store' WHERE store_id = ?",
                fixture.storeId,
            )
            jdbcTemplate.update(
                "UPDATE fulfillment_pickup_slot SET starts_at = ?, ends_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2030-01-02T00:10:00Z")),
                Timestamp.from(Instant.parse("2030-01-02T00:20:00Z")),
                fixture.pickupSlotId,
            )

            mockMvc
                .perform(
                    get("/api/v1/me/orders/{orderReference}", reference.lowercase())
                        .with(customerJwt(fixture.customerId)),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.orderId").doesNotExist())
                .andExpect(jsonPath("$.orderReference").value(reference))
                .andExpect(jsonPath("$.pickupNumber").value("A-1"))
                .andExpect(jsonPath("$.storeName").value("BeanFlow Test Store"))
                .andExpect(jsonPath("$.pickupWindowStart").value("2030-01-01T00:10:00Z"))
                .andExpect(jsonPath("$.pickupWindowEnd").value("2030-01-01T00:20:00Z"))
            mockMvc
                .perform(
                    get("/api/v1/me/orders/{orderReference}", reference)
                        .with(customerJwt(UUID.randomUUID())),
                ).andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            mockMvc
                .perform(
                    get("/api/v1/me/orders/{orderReference}", "BF-2222-2222")
                        .with(customerJwt(fixture.customerId)),
                ).andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            mockMvc
                .perform(
                    get("/api/v1/me/orders/{orderReference}", "BF-I234-5678")
                        .with(customerJwt(fixture.customerId)),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

            mockMvc
                .perform(
                    post("/api/v1/me/orders/{orderReference}/cancellations", reference.lowercase())
                        .with(customerJwt(fixture.customerId))
                        .header("Idempotency-Key", "public-cancel-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"reasonCode":"ORDER_MISTAKE"}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.orderId").doesNotExist())
                .andExpect(jsonPath("$.publicReference").value(reference))
                .andExpect(jsonPath("$.orderState").value("CANCELLED"))

            mockMvc
                .perform(
                    get("/api/v1/me/orders/{orderReference}", reference)
                        .with(customerJwt(fixture.customerId)),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.allowedActions.length()").value(2))
                .andExpect(jsonPath("$.allowedActions[0]").value("REORDER"))
                .andExpect(jsonPath("$.allowedActions[1]").value("VIEW_REFUND"))
                .andExpect(jsonPath("$.paymentRecovery.state").value("NOT_REQUIRED"))
                .andExpect(jsonPath("$.paymentId").doesNotExist())
                .andExpect(jsonPath("$.providerReference").doesNotExist())
                .andExpect(jsonPath("$.cancellationDetail").doesNotExist())
        }

        @Test
        fun `C0 atomically cancels and releases all used owners with target audits and exact replay`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val couponId = OrderCreationDatabaseFixture.insertFixedCoupon(jdbcTemplate, fixture, 200)
            OrderCreationDatabaseFixture.insertPoints(jdbcTemplate, fixture.customerId, 300)
            val orderId = createOrder(fixture, "c0-create-key", couponId, 300)

            val first = cancel(orderId, fixture.customerId, "c0-cancel-key", "ORDER_MISTAKE", "  ordered twice  ")
            val replay = cancel(orderId, fixture.customerId, "c0-cancel-key", "ORDER_MISTAKE", "ordered twice")

            assertThat(first.status).isEqualTo(200)
            assertThat(replay.status).isEqualTo(200)
            assertThat(replay.contentAsString).isEqualTo(first.contentAsString)
            assertThat(first.contentAsString).doesNotContain("ordered twice", "detail")
            assertThat(value("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("CANCELLED")
            assertThat(value("SELECT state FROM fulfillment_pickup_reservation WHERE order_id = ?", orderId))
                .isEqualTo("RELEASED")
            assertThat(value("SELECT state FROM inventory_stock_reservation WHERE order_id = ?", orderId))
                .isEqualTo("RELEASED")
            assertThat(value("SELECT state FROM promotion_coupon_reservation WHERE order_id = ?", orderId))
                .isEqualTo("RELEASED")
            assertThat(value("SELECT state FROM loyalty_point_reservation WHERE order_id = ?", orderId))
                .isEqualTo("RELEASED")
            assertThat(count("ordering_cancellation_command_idempotency")).isEqualTo(1)
            assertThat(count("notification_delivery")).isEqualTo(1)
            assertThat(countCommandAudits()).isEqualTo(6)
            assertThat(count("operations_order_compensation_case")).isZero()
            assertThat(count("payment_refund")).isZero()
            assertThat(count("event_publication")).isZero()
            assertThat(notificationProvider.calls.get()).isZero()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM operations_audit_record WHERE reason = 'ORDER_MISTAKE' " +
                        "AND action IN ($COMMAND_AUDIT_ACTIONS)",
                    Long::class.java,
                ),
            ).isEqualTo(6)
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM operations_audit_record WHERE action IN ($COMMAND_AUDIT_ACTIONS) " +
                        "AND (before_summary LIKE '%ordered twice%' OR after_summary LIKE '%ordered twice%')",
                    Long::class.java,
                ),
            ).isZero()

            cancel(orderId, fixture.customerId, "c0-cancel-key", "OTHER", null)
                .also { assertThat(it.status).isEqualTo(409) }
            cancel(orderId, fixture.customerId, "c0-another-key", "ORDER_MISTAKE", null)
                .also { assertThat(it.status).isEqualTo(409) }
            assertThat(countCommandAudits()).isEqualTo(6)
        }

        @Test
        fun `support pickup reschedule updates owner models once and replays exact source`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val orderId = createOrder(fixture, "support-reschedule-create")
            val nextSlotId = UUID.randomUUID()
            insertPickupSlot(nextSlotId, fixture.storeId)
            val orderVersion = number("SELECT version FROM ordering_order WHERE id = ?", orderId)
            val command =
                SupportPickupRescheduleCommand(
                    supportRequestId = UUID.randomUUID(),
                    supportExecutionId = UUID.randomUUID(),
                    actorId = UUID.randomUUID(),
                    orderId = orderId,
                    expectedOrderVersion = orderVersion,
                    newPickupSlotId = nextSlotId,
                    acceptedStoreAuthorizationId = null,
                    sourceReference = "support-pickup-reschedule:$orderId",
                )

            val first = supportPickupReschedules.reschedule(command)
            val replay = supportPickupReschedules.reschedule(command)

            assertThat(first.result).isEqualTo(SupportOrderChangeOwnerResult.APPLIED)
            assertThat(replay.result).isEqualTo(SupportOrderChangeOwnerResult.ALREADY_APPLIED)
            assertThat(first.orderVersion).isEqualTo(orderVersion + 1)
            assertThat(value("SELECT pickup_slot_id::text FROM ordering_order WHERE id = ?", orderId))
                .isEqualTo(nextSlotId.toString())
            assertThat(value("SELECT slot_id::text FROM fulfillment_pickup_reservation WHERE order_id = ?", orderId))
                .isEqualTo(nextSlotId.toString())
            assertThat(count("ordering_support_order_change_history")).isOne()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM operations_audit_record WHERE action = 'ORDER_SUPPORT_PICKUP_RESCHEDULED'",
                    Long::class.java,
                ),
            ).isOne()
        }

        @Test
        fun `support cancellation uses dedicated cause releases pending resources and replays once`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val orderId = createOrder(fixture, "support-cancel-create")
            val command = supportCancellationCommand(orderId, expectedVersion = 0)

            val first = supportOrderCancellations.cancel(command)
            val replay = supportOrderCancellations.cancel(command)

            assertThat(first.result).isEqualTo(SupportOrderChangeOwnerResult.APPLIED)
            assertThat(replay.result).isEqualTo(SupportOrderChangeOwnerResult.ALREADY_APPLIED)
            assertThat(value("SELECT cancellation_cause FROM ordering_order WHERE id = ?", orderId))
                .isEqualTo("SUPPORT_REQUEST")
            assertThat(value("SELECT state FROM fulfillment_pickup_reservation WHERE order_id = ?", orderId))
                .isEqualTo("RELEASED")
            assertThat(value("SELECT state FROM inventory_stock_reservation WHERE order_id = ?", orderId))
                .isEqualTo("RELEASED")
            assertThat(count("ordering_support_order_change_history")).isOne()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM operations_audit_record " +
                        "WHERE action = 'ORDER_SUPPORT_CANCELLED' AND actor_type = 'PLATFORM_OPERATOR'",
                    Long::class.java,
                ),
            ).isOne()
        }

        @Test
        fun `preparing support cancellation returns resolution required without rewriting order fact`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val orderId = createOrder(fixture, "support-preparing-create")
            approvePayment(orderId, fixture.customerId, 1_000)
            jdbcTemplate.update(
                "UPDATE ordering_order SET state = 'PREPARING', accepted_at = now(), preparing_at = now(), version = version + 2 " +
                    "WHERE id = ?",
                orderId,
            )
            val currentVersion = number("SELECT version FROM ordering_order WHERE id = ?", orderId)

            val report = supportOrderCancellations.cancel(supportCancellationCommand(orderId, currentVersion - 1))

            assertThat(report.result).isEqualTo(SupportOrderChangeOwnerResult.RESOLUTION_REQUIRED)
            assertThat(report.currentState).isEqualTo("PREPARING")
            assertThat(value("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("PREPARING")
            assertThat(count("ordering_support_order_change_history")).isZero()
        }

        @Test
        fun `accepted support cancellation requires store authorization and exposes refund recovery`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val orderId = createOrder(fixture, "support-accepted-create")
            approvePayment(orderId, fixture.customerId, 1_000)
            val storeActorId = UUID.randomUUID()
            insertMembership(storeActorId, fixture.storeId)
            storeTransitionService.transition(
                StoreTransitionActor(storeActorId, setOf(StoreActorRole.STAFF)),
                orderId,
                "support-accepted-store",
                StoreOrderTransitionRequest(StoreOrderTargetState.ACCEPTED, null),
            )
            val currentVersion = number("SELECT version FROM ordering_order WHERE id = ?", orderId)
            val unauthorized = supportCancellationCommand(orderId, currentVersion)

            val failure = runCatching { supportOrderCancellations.cancel(unauthorized) }.exceptionOrNull()
            assertThat(failure).isInstanceOfSatisfying(io.github.kdh949.beanflow.shared.api.DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(io.github.kdh949.beanflow.shared.api.FailureCode.ACCESS_DENIED)
            }

            val report =
                supportOrderCancellations.cancel(
                    unauthorized.copy(acceptedStoreAuthorizationId = UUID.randomUUID()),
                )

            assertThat(report.result).isEqualTo(SupportOrderChangeOwnerResult.APPLIED)
            assertThat(report.previousState).isEqualTo("ACCEPTED")
            assertThat(report.paymentRecoveryState).isEqualTo("REQUESTED")
            assertThat(value("SELECT cancellation_cause FROM ordering_order WHERE id = ?", orderId))
                .isEqualTo("SUPPORT_REQUEST")
            assertThat(value("SELECT state FROM payment_refund WHERE order_id = ?", orderId)).isEqualTo("REQUESTED")
            assertThat(paymentGateway.rejectionRefundCalls.get()).isZero()
        }

        @Test
        fun `C1 commits refund case delivery audits and exactly four owner publications without provider calls`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val orderId = createOrder(fixture, "c1-create-key")
            approvePayment(orderId, fixture.customerId, 1_000)

            val response = cancel(orderId, fixture.customerId, "c1-cancel-key", "CHANGED_MIND", "private detail")

            assertThat(response.status).isEqualTo(202)
            assertThat(response.contentAsString).contains("\"state\":\"REQUESTED\"")
            assertThat(response.contentAsString).contains("\"approvedAmountKrw\":1000")
            assertThat(response.contentAsString).doesNotContain("private detail", "detail")
            assertThat(value("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("CANCELLED")
            assertThat(count("payment_cancellation_recovery_snapshot")).isEqualTo(1)
            assertThat(count("payment_refund")).isEqualTo(1)
            assertThat(value("SELECT state FROM payment_refund WHERE order_id = ?", orderId)).isEqualTo("REQUESTED")
            assertThat(count("operations_order_compensation_case")).isEqualTo(1)
            assertThat(count("operations_order_compensation_step")).isEqualTo(6)
            assertThat(count("operations_order_compensation_benefit_policy_snapshot")).isEqualTo(2)
            assertThat(count("notification_delivery")).isEqualTo(1)
            assertThat(count("ordering_cancellation_command_idempotency")).isEqualTo(1)
            assertThat(countCommandAudits()).isEqualTo(5)
            assertThat(count("event_publication")).isEqualTo(4)
            assertThat(
                jdbcTemplate.queryForList("SELECT listener_id FROM event_publication", String::class.java),
            ).containsExactlyInAnyOrder(
                "beanflow.order-compensation.order-cancelled.pickup.v1",
                "beanflow.order-compensation.order-cancelled.stock.v1",
                "beanflow.order-compensation.order-cancelled.coupon.v1",
                "beanflow.order-compensation.order-cancelled.points.v1",
            )
            assertThat(paymentGateway.rejectionRefundCalls.get()).isZero()
            assertThat(notificationProvider.calls.get()).isZero()

            mockMvc
                .perform(get("/api/v1/orders/{orderId}", orderId).with(customerJwt(fixture.customerId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.cancellationReasonCode").value("CHANGED_MIND"))
                .andExpect(jsonPath("$.paymentRecovery.state").value("REQUESTED"))
                .andExpect(jsonPath("$.paymentRecovery.cancellationRequestedRefundAmountKrw").value(1_000))
                .andExpect(jsonPath("$.cancellationDetail").doesNotExist())
            val storeActorId = UUID.randomUUID()
            insertMembership(storeActorId, fixture.storeId)
            mockMvc
                .perform(get("/api/v1/store-orders/{orderId}", orderId).with(storeJwt(storeActorId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.order.cancellationCause").value("CUSTOMER_REQUEST"))
                .andExpect(jsonPath("$.order.cancellationReasonCode").doesNotExist())
                .andExpect(jsonPath("$.order.cancellationDetail").doesNotExist())
        }

        @Test
        fun `benefit-only C1 stores zero snapshot and NOT_REQUIRED payment step without Refund`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            OrderCreationDatabaseFixture.insertPoints(jdbcTemplate, fixture.customerId, 1_000)
            val orderId = createOrder(fixture, "benefit-create", pointsToUseKrw = 1_000)

            val response = cancel(orderId, fixture.customerId, "benefit-cancel", "WAIT_TOO_LONG", null)

            assertThat(response.status).isEqualTo(202)
            assertThat(response.contentAsString).contains("\"state\":\"NOT_REQUIRED\"")
            assertThat(response.contentAsString).contains("\"approvedAmountKrw\":0")
            assertThat(count("payment_cancellation_recovery_snapshot")).isEqualTo(1)
            assertThat(count("payment_refund")).isZero()
            assertThat(
                value(
                    "SELECT state FROM operations_order_compensation_step WHERE step_type = 'PAYMENT'",
                ),
            ).isEqualTo("NOT_REQUIRED")
            assertThat(count("event_publication")).isEqualTo(4)
            assertThat(countCommandAudits()).isEqualTo(4)
        }

        @Test
        fun `customer order read records missing setup and returns delayed without inferred amounts`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val orderId = createOrder(fixture, "setup-inline-create")
            approvePayment(orderId, fixture.customerId, 1_000)
            cancel(orderId, fixture.customerId, "setup-inline-cancel", "CHANGED_MIND", null)
            jdbcTemplate.update("DELETE FROM payment_cancellation_recovery_snapshot WHERE order_id = ?", orderId)

            mockMvc
                .perform(get("/api/v1/orders/{orderId}", orderId).with(customerJwt(fixture.customerId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.paymentRecovery.state").value("PROCESSING"))
                .andExpect(jsonPath("$.paymentRecovery.noticeCode").value("REFUND_DELAYED"))
                .andExpect(jsonPath("$.paymentRecovery.approvedAmountKrw").doesNotExist())
                .andExpect(jsonPath("$.paymentRecovery.remainingRefundableAmountKrw").doesNotExist())
            assertSetupIntegrityEvidence()
        }

        @Test
        fun `bounded setup scanner detects cancellation damage without customer access`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val orderId = createOrder(fixture, "setup-scan-create")
            approvePayment(orderId, fixture.customerId, 1_000)
            cancel(orderId, fixture.customerId, "setup-scan-cancel", "CHANGED_MIND", null)
            jdbcTemplate.update("DELETE FROM payment_cancellation_recovery_snapshot WHERE order_id = ?", orderId)

            setupIntegrityWorker.runScheduled()
            setupIntegrityWorker.runScheduled()

            assertSetupIntegrityEvidence()
        }

        @Test
        fun `deadline CT commits one timeout work and audit before returning 409 for every key`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val orderId = createOrder(fixture, "ct-create-key")
            approvePayment(orderId, fixture.customerId, 1_000)
            moveAcceptanceDeadlineToPast(orderId)

            val first =
                cancellationTransaction.execute(
                    fixture.customerId,
                    orderId,
                    "ct-cancel-key",
                    CustomerCancellationRequest(
                        io.github.kdh949.beanflow.ordering.api.CustomerCancellationReasonCode.WAIT_TOO_LONG,
                        null,
                    ),
                )
            val replay =
                cancellationTransaction.execute(
                    fixture.customerId,
                    orderId,
                    "ct-other-key",
                    CustomerCancellationRequest(
                        io.github.kdh949.beanflow.ordering.api.CustomerCancellationReasonCode.WAIT_TOO_LONG,
                        null,
                    ),
                )

            assertThat(first).isInstanceOf(CustomerCancellationTransactionOutcome.AcceptanceDeadlineReached::class.java)
            assertThat(replay).isInstanceOf(CustomerCancellationTransactionOutcome.AcceptanceDeadlineReached::class.java)
            val workId = (first as CustomerCancellationTransactionOutcome.AcceptanceDeadlineReached).workId
            assertThat((replay as CustomerCancellationTransactionOutcome.AcceptanceDeadlineReached).workId).isEqualTo(workId)

            assertThat(value("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("PAID")
            assertThat(count("ordering_acceptance_timeout_work")).isEqualTo(1)
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM operations_audit_record WHERE action = 'ACCEPTANCE_TIMEOUT_WORK_REQUESTED'",
                    Long::class.java,
                ),
            ).isEqualTo(1)
            assertThat(count("ordering_cancellation_command_idempotency")).isZero()
            assertThat(count("payment_refund")).isZero()
            assertThat(count("operations_order_compensation_case")).isZero()
            assertThat(count("notification_delivery")).isZero()

            cancel(orderId, fixture.customerId, "ct-http-key", "WAIT_TOO_LONG", null)
                .also { response ->
                    assertThat(response.status).isEqualTo(409)
                    assertThat(response.contentAsString).contains("ORDER_STATE_CONFLICT")
                }
            timeoutWorkWorker.process(workId)
            awaitValue("SELECT state FROM ordering_acceptance_timeout_work WHERE id = ?", workId, "COMPLETED")
            awaitPublicationsSettled()
            assertThat(value("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("REJECTED")
            assertThat(value("SELECT completion_outcome FROM ordering_acceptance_timeout_work WHERE id = ?", workId))
                .isEqualTo("REJECTED")
            assertThat(value("SELECT rejection_reason FROM ordering_order WHERE id = ?", orderId))
                .isEqualTo("STORE_ACCEPTANCE_TIMEOUT")
            assertThat(paymentGateway.rejectionRefundCalls.get()).isZero()
        }

        @Test
        fun `CT work or audit persistence failure returns 503 without a false winner`() {
            listOf(
                FaultTarget("ordering_acceptance_timeout_work", "INSERT"),
                FaultTarget("operations_audit_record", "INSERT"),
            ).forEachIndexed { index, target ->
                resetBetweenFaultScenarios()
                val fixture = OrderCreationFixture()
                OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
                val orderId = createOrder(fixture, "ct-fault-create-$index")
                approvePayment(orderId, fixture.customerId, 1_000)
                moveAcceptanceDeadlineToPast(orderId)

                val response =
                    withPersistenceFault(target) {
                        cancel(orderId, fixture.customerId, "ct-fault-cancel-$index", "WAIT_TOO_LONG", null)
                    }

                assertThat(response.status).describedAs(target.table).isEqualTo(503)
                assertThat(value("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("PAID")
                assertThat(count("ordering_acceptance_timeout_work")).isZero()
                assertThat(
                    jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM operations_audit_record WHERE action = 'ACCEPTANCE_TIMEOUT_WORK_REQUESTED'",
                        Long::class.java,
                    ),
                ).isZero()
                assertThat(count("ordering_cancellation_command_idempotency")).isZero()
            }
        }

        @Test
        fun `due pending cancellation materializes expiry then returns RESERVATION_EXPIRED`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val orderId = createOrder(fixture, "expiry-create-key")
            val dueAt = Timestamp.from(Instant.now().minusSeconds(1))
            jdbcTemplate.update("UPDATE ordering_order SET reservation_expires_at = ? WHERE id = ?", dueAt, orderId)
            jdbcTemplate.update("UPDATE fulfillment_pickup_reservation SET expires_at = ? WHERE order_id = ?", dueAt, orderId)
            jdbcTemplate.update("UPDATE inventory_stock_reservation SET expires_at = ? WHERE order_id = ?", dueAt, orderId)

            val response = cancel(orderId, fixture.customerId, "expiry-cancel-key", "OTHER", null)

            assertThat(response.status).isEqualTo(409)
            assertThat(response.contentAsString).contains("RESERVATION_EXPIRED")
            assertThat(value("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("EXPIRED")
            assertThat(count("ordering_cancellation_command_idempotency")).isZero()
            assertThat(count("notification_delivery")).isZero()
        }

        @Test
        fun `same actor key cannot replay the first cancellation body for another order`() {
            val firstFixture = OrderCreationFixture()
            val secondFixture = OrderCreationFixture(customerId = firstFixture.customerId)
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, firstFixture)
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, secondFixture)
            val firstOrderId = createOrder(firstFixture, "cross-order-create-1")
            val secondOrderId = createOrder(secondFixture, "cross-order-create-2")

            assertThat(cancel(firstOrderId, firstFixture.customerId, "shared-cancel-key", "OTHER", null).status)
                .isEqualTo(200)
            val rejected = cancel(secondOrderId, firstFixture.customerId, "shared-cancel-key", "OTHER", null)

            assertThat(rejected.status).isEqualTo(409)
            assertThat(rejected.contentAsString).contains("IDEMPOTENCY_KEY_REUSED")
            assertThat(value("SELECT state FROM ordering_order WHERE id = ?", secondOrderId)).isEqualTo("PENDING_PAYMENT")
            assertThat(count("ordering_cancellation_command_idempotency")).isEqualTo(1)
        }

        @Test
        fun `another customer cannot cancel an owned order`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val orderId = createOrder(fixture, "ownership-create-key")

            val response = cancel(orderId, UUID.randomUUID(), "ownership-cancel-key", "OTHER", null)

            assertThat(response.status).isEqualTo(403)
            assertThat(value("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("PENDING_PAYMENT")
            assertThat(count("ordering_cancellation_command_idempotency")).isZero()
        }

        @Test
        fun `reason and detail validation rejects malformed input and accepts exactly two hundred characters`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val orderId = createOrder(fixture, "detail-boundary-create")

            assertThat(cancel(orderId, fixture.customerId, "detail-control-key", "OTHER", "unsafe\\u0000detail").status)
                .isEqualTo(400)
            assertThat(cancel(orderId, fixture.customerId, "detail-long-key", "OTHER", "x".repeat(201)).status)
                .isEqualTo(400)
            assertThat(cancel(orderId, fixture.customerId, "detail-reason-key", "NOT_A_REASON", null).status)
                .isEqualTo(400)
            assertThat(value("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("PENDING_PAYMENT")
            assertThat(count("ordering_cancellation_command_idempotency")).isZero()

            val accepted = cancel(orderId, fixture.customerId, "detail-valid-key", "OTHER", "x".repeat(200))
            assertThat(accepted.status).isEqualTo(200)
            assertThat(accepted.contentAsString).doesNotContain("x".repeat(20), "detail")
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT length(cancellation_detail) FROM ordering_order WHERE id = ?",
                    Int::class.java,
                    orderId,
                ),
            ).isEqualTo(200)
            val cancellationMeters =
                meterRegistry.meters.filter { it.id.name.startsWith("beanflow.order.customer_cancellation") }
            assertThat(cancellationMeters).isNotEmpty()
            assertThat(cancellationMeters.flatMap { it.id.tags }.map { it.value })
                .noneMatch {
                    it.contains(orderId.toString()) ||
                        it.contains(fixture.customerId.toString()) ||
                        it.contains("detail-valid-key") ||
                        it.contains("x".repeat(20))
                }
        }

        @Test
        fun `one hundred concurrent paid cancellation requests commit one durable work set and one body`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val orderId = createOrder(fixture, "concurrent-create-key")
            approvePayment(orderId, fixture.customerId, 1_000)
            val start = java.util.concurrent.CountDownLatch(1)
            val executor =
                java.util.concurrent.Executors
                    .newVirtualThreadPerTaskExecutor()
            val futures =
                (1..100).map {
                    executor.submit<CustomerCancellationHttpResult> {
                        start.await()
                        customerCancellationService.cancel(
                            fixture.customerId,
                            orderId,
                            "concurrent-cancel-key",
                            CustomerCancellationRequest(
                                io.github.kdh949.beanflow.ordering.api.CustomerCancellationReasonCode.CHANGED_MIND,
                                "same payload",
                            ),
                        )
                    }
                }
            start.countDown()
            val responses = futures.map { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
            executor.shutdown()
            awaitPublicationsSettled()

            assertThat(responses).allMatch { it.status == 202 }
            assertThat(responses.map(CustomerCancellationHttpResult::body).distinct()).hasSize(1)
            assertThat(count("ordering_cancellation_command_idempotency")).isEqualTo(1)
            assertThat(count("payment_cancellation_recovery_snapshot")).isEqualTo(1)
            assertThat(count("payment_refund")).isEqualTo(1)
            assertThat(count("operations_order_compensation_case")).isEqualTo(1)
            assertThat(count("notification_delivery")).isEqualTo(1)
            assertThat(count("event_publication")).isEqualTo(4)
            assertThat(countCommandAudits()).isEqualTo(5)
            assertThat(paymentGateway.rejectionRefundCalls.get()).isZero()
            assertThat(notificationProvider.calls.get()).isZero()
        }

        @Test
        fun `every C0 commit gate failure rolls back the order owners delivery audits and response`() {
            val targets =
                listOf(
                    FaultTarget("ordering_order", "UPDATE"),
                    FaultTarget("fulfillment_pickup_reservation", "UPDATE"),
                    FaultTarget("inventory_stock_reservation", "UPDATE"),
                    FaultTarget("promotion_coupon_reservation", "UPDATE"),
                    FaultTarget("loyalty_point_reservation", "UPDATE"),
                    FaultTarget("notification_delivery", "INSERT"),
                    FaultTarget("operations_audit_record", "INSERT"),
                    FaultTarget("ordering_cancellation_command_idempotency", "INSERT"),
                )
            targets.forEachIndexed { index, target ->
                resetBetweenFaultScenarios()
                val fixture = OrderCreationFixture()
                OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
                val couponId = OrderCreationDatabaseFixture.insertFixedCoupon(jdbcTemplate, fixture, 200)
                OrderCreationDatabaseFixture.insertPoints(jdbcTemplate, fixture.customerId, 300)
                val orderId = createOrder(fixture, "c0-fault-create-$index", couponId, 300)

                val response =
                    withPersistenceFault(target) {
                        cancel(orderId, fixture.customerId, "c0-fault-cancel-$index", "OTHER", "private fault detail")
                    }

                assertThat(response.status).describedAs(target.table).isEqualTo(503)
                assertThat(response.contentAsString).doesNotContain("private fault detail")
                assertThat(value("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("PENDING_PAYMENT")
                assertThat(value("SELECT state FROM fulfillment_pickup_reservation WHERE order_id = ?", orderId))
                    .isEqualTo("RESERVED")
                assertThat(value("SELECT state FROM inventory_stock_reservation WHERE order_id = ?", orderId))
                    .isEqualTo("RESERVED")
                assertThat(value("SELECT state FROM promotion_coupon_reservation WHERE order_id = ?", orderId))
                    .isEqualTo("RESERVED")
                assertThat(value("SELECT state FROM loyalty_point_reservation WHERE order_id = ?", orderId))
                    .isEqualTo("RESERVED")
                assertThat(count("ordering_cancellation_command_idempotency")).isZero()
                assertThat(count("notification_delivery")).isZero()
                assertThat(countCommandAudits()).isZero()
            }
        }

        @Test
        fun `every C1 commit gate failure rolls back all durable work without provider calls`() {
            val targets =
                listOf(
                    FaultTarget("ordering_order", "UPDATE"),
                    FaultTarget("payment_cancellation_recovery_snapshot", "INSERT"),
                    FaultTarget("payment_refund", "INSERT"),
                    FaultTarget("operations_order_compensation_case", "INSERT"),
                    FaultTarget("operations_order_compensation_step", "INSERT"),
                    FaultTarget("operations_order_compensation_benefit_policy_snapshot", "INSERT"),
                    FaultTarget("notification_delivery", "INSERT"),
                    FaultTarget("operations_audit_record", "INSERT"),
                    FaultTarget("event_publication", "INSERT"),
                    FaultTarget("ordering_cancellation_command_idempotency", "INSERT"),
                )
            targets.forEachIndexed { index, target ->
                resetBetweenFaultScenarios()
                val fixture = OrderCreationFixture()
                OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
                val orderId = createOrder(fixture, "c1-fault-create-$index")
                approvePayment(orderId, fixture.customerId, 1_000)
                awaitPublicationsSettled()

                val response =
                    withPersistenceFault(target) {
                        cancel(orderId, fixture.customerId, "c1-fault-cancel-$index", "PAYMENT_ISSUE", "private fault detail")
                    }

                assertThat(response.status).describedAs(target.table).isEqualTo(503)
                assertThat(response.contentAsString).doesNotContain("private fault detail")
                assertThat(value("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("PAID")
                assertThat(count("payment_cancellation_recovery_snapshot")).isZero()
                assertThat(count("payment_refund")).isZero()
                assertThat(count("operations_order_compensation_case")).isZero()
                assertThat(count("operations_order_compensation_step")).isZero()
                assertThat(count("notification_delivery")).isZero()
                assertThat(count("ordering_cancellation_command_idempotency")).isZero()
                assertThat(count("event_publication")).isZero()
                assertThat(countCommandAudits()).isZero()
                assertThat(paymentGateway.rejectionRefundCalls.get()).isZero()
                assertThat(notificationProvider.calls.get()).isZero()
            }
        }

        @Test
        fun `customer cancellation and store acceptance race to one guarded terminal result`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val orderId = createOrder(fixture, "accept-race-create")
            approvePayment(orderId, fixture.customerId, 1_000)
            val storeActorId = UUID.randomUUID()
            insertMembership(storeActorId, fixture.storeId)
            val barrier = java.util.concurrent.CyclicBarrier(2)
            val executor =
                java.util.concurrent.Executors
                    .newFixedThreadPool(2)
            val cancellation =
                executor.submit<Result<CustomerCancellationHttpResult>> {
                    barrier.await()
                    runCatching {
                        customerCancellationService.cancel(
                            fixture.customerId,
                            orderId,
                            "accept-race-cancel",
                            CustomerCancellationRequest(
                                io.github.kdh949.beanflow.ordering.api.CustomerCancellationReasonCode.WAIT_TOO_LONG,
                                null,
                            ),
                        )
                    }
                }
            val acceptance =
                executor.submit<Result<StoreTransitionHttpResult>> {
                    barrier.await()
                    runCatching {
                        storeTransitionService.transition(
                            StoreTransitionActor(storeActorId, setOf(StoreActorRole.STAFF)),
                            orderId,
                            "accept-race-store",
                            StoreOrderTransitionRequest(StoreOrderTargetState.ACCEPTED, null),
                        )
                    }
                }
            val cancellationResult = cancellation.get(20, java.util.concurrent.TimeUnit.SECONDS)
            val acceptanceResult = acceptance.get(20, java.util.concurrent.TimeUnit.SECONDS)
            executor.shutdown()
            awaitPublicationsSettled()

            assertThat(cancellationResult.isSuccess.xor(acceptanceResult.isSuccess)).isTrue()
            assertThat(value("SELECT state FROM ordering_order WHERE id = ?", orderId)).isIn("CANCELLED", "ACCEPTED")
            if (cancellationResult.isSuccess) {
                assertThat(cancellationResult.getOrThrow().status).isEqualTo(202)
                assertThat(count("ordering_cancellation_command_idempotency")).isEqualTo(1)
                assertThat(count("operations_order_compensation_case")).isEqualTo(1)
            } else {
                assertThat(acceptanceResult.getOrThrow().status).isEqualTo(200)
                assertThat(count("ordering_cancellation_command_idempotency")).isZero()
                assertThat(count("operations_order_compensation_case")).isZero()
            }
        }

        @Test
        fun `customer cancellation and reservation expiry race to one expired order`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val orderId = createOrder(fixture, "expiry-race-create")
            val dueAt = Timestamp.from(Instant.now().minusSeconds(1))
            jdbcTemplate.update("UPDATE ordering_order SET reservation_expires_at = ? WHERE id = ?", dueAt, orderId)
            jdbcTemplate.update("UPDATE fulfillment_pickup_reservation SET expires_at = ? WHERE order_id = ?", dueAt, orderId)
            jdbcTemplate.update("UPDATE inventory_stock_reservation SET expires_at = ? WHERE order_id = ?", dueAt, orderId)
            val barrier = java.util.concurrent.CyclicBarrier(2)
            val executor =
                java.util.concurrent.Executors
                    .newFixedThreadPool(2)
            val cancellation =
                executor.submit<Throwable?> {
                    barrier.await()
                    try {
                        customerCancellationService.cancel(
                            fixture.customerId,
                            orderId,
                            "expiry-race-cancel",
                            CustomerCancellationRequest(
                                io.github.kdh949.beanflow.ordering.api.CustomerCancellationReasonCode.OTHER,
                                null,
                            ),
                        )
                        null
                    } catch (failure: Throwable) {
                        failure
                    }
                }
            val expiry =
                executor.submit<io.github.kdh949.beanflow.ordering.api.ReservationExpiryResult> {
                    barrier.await()
                    reservationExpiryUseCase.expireIfDue(orderId, Instant.now())
                }
            val cancellationFailure = cancellation.get(20, java.util.concurrent.TimeUnit.SECONDS)
            val expiryResult = expiry.get(20, java.util.concurrent.TimeUnit.SECONDS)
            executor.shutdown()

            assertThat(cancellationFailure).isInstanceOf(io.github.kdh949.beanflow.shared.api.DomainFailure::class.java)
            assertThat(expiryResult.outcome)
                .isIn(
                    io.github.kdh949.beanflow.ordering.api.ReservationExpiryOutcome.EXPIRED,
                    io.github.kdh949.beanflow.ordering.api.ReservationExpiryOutcome.NOT_ELIGIBLE,
                )
            assertThat(value("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("EXPIRED")
            assertThat(value("SELECT state FROM fulfillment_pickup_reservation WHERE order_id = ?", orderId))
                .isEqualTo("EXPIRED")
            assertThat(value("SELECT state FROM inventory_stock_reservation WHERE order_id = ?", orderId))
                .isEqualTo("EXPIRED")
            assertThat(count("ordering_cancellation_command_idempotency")).isZero()
            assertThat(count("notification_delivery")).isZero()
        }

        private fun createOrder(
            fixture: OrderCreationFixture,
            key: String,
            couponIssuanceId: UUID? = null,
            pointsToUseKrw: Long = 0,
        ): UUID {
            val coupon = couponIssuanceId?.let { "\"couponIssuanceId\":\"$it\"," }.orEmpty()
            mockMvc
                .perform(
                    post("/api/v1/orders")
                        .with(customerJwt(fixture.customerId))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                              "storeId":"${fixture.storeId}",
                              "pickupSlotId":"${fixture.pickupSlotId}",
                              "lines":[{"menuId":"${fixture.menuId}","optionIds":[],"quantity":1}],
                              $coupon
                              "pointsToUseKrw":$pointsToUseKrw
                            }
                            """.trimIndent(),
                        ),
                ).andExpect(status().isCreated)
            return requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT order_id FROM ordering_idempotency_record WHERE actor_id = ? AND idempotency_key = ?",
                    UUID::class.java,
                    fixture.customerId,
                    key,
                ),
            )
        }

        private fun approvePayment(
            orderId: UUID,
            customerId: UUID,
            amountKrw: Long,
        ) {
            mockMvc
                .perform(
                    post("/api/v1/orders/{orderId}/payment-attempts", orderId)
                        .with(customerJwt(customerId))
                        .header("Idempotency-Key", "prepare-$orderId"),
                ).andExpect(status().isOk)
            val paymentId =
                requireNotNull(
                    jdbcTemplate.queryForObject(
                        "SELECT id FROM payment_payment WHERE order_id = ?",
                        UUID::class.java,
                        orderId,
                    ),
                )
            val providerOrderId =
                requireNotNull(
                    jdbcTemplate.queryForObject(
                        "SELECT provider_order_id FROM payment_one_time_attempt WHERE payment_id = ?",
                        String::class.java,
                        paymentId,
                    ),
                )
            paymentGateway.enqueueOneTimeConfirmation(
                ProviderPaymentResult.Approved("provider:$orderId", amountKrw, "KRW"),
            )
            mockMvc
                .perform(
                    post("/api/v1/payments/{paymentId}/confirmations", paymentId)
                        .with(customerJwt(customerId))
                        .header("Idempotency-Key", "confirm-$orderId")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"paymentKey":"test:$orderId","orderId":"$providerOrderId","amount":$amountKrw}"""),
                ).andExpect(status().isOk)
        }

        private fun cancel(
            orderId: UUID,
            customerId: UUID,
            key: String,
            reason: String,
            detail: String?,
        ) = mockMvc
            .perform(
                post("/api/v1/orders/{orderId}/cancellations", orderId)
                    .with(customerJwt(customerId))
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        if (detail == null) {
                            """{"reasonCode":"$reason"}"""
                        } else {
                            """{"reasonCode":"$reason","detail":"$detail"}"""
                        },
                    ),
            ).andReturn()
            .response

        private fun customerJwt(customerId: UUID) =
            jwt()
                .jwt { it.subject(customerId.toString()) }
                .authorities(SimpleGrantedAuthority("ROLE_CUSTOMER"))

        private fun storeJwt(actorId: UUID) =
            jwt()
                .jwt {
                    it
                        .subject(actorId.toString())
                        .claim("roles", listOf("STORE_STAFF"))
                }.authorities(SimpleGrantedAuthority("ROLE_STORE_STAFF"))

        private fun insertMembership(
            actorId: UUID,
            storeId: UUID,
        ) {
            MerchantAccountDatabaseFixture.insertActive(jdbcTemplate, actorId)
            val now = Timestamp.from(Instant.now())
            jdbcTemplate.update(
                """
                INSERT INTO identity_store_membership (
                    id, actor_id, store_id, membership_role, status, created_at, updated_at, version
                ) VALUES (?, ?, ?, 'STAFF', 'ACTIVE', ?, ?, 0)
                """.trimIndent(),
                UUID.randomUUID(),
                actorId,
                storeId,
                now,
                now,
            )
        }

        private fun supportCancellationCommand(
            orderId: UUID,
            expectedVersion: Long,
        ) = SupportOrderCancellationCommand(
            supportRequestId = UUID.randomUUID(),
            supportExecutionId = UUID.randomUUID(),
            actorId = UUID.randomUUID(),
            orderId = orderId,
            expectedOrderVersion = expectedVersion,
            reasonCode = io.github.kdh949.beanflow.ordering.api.CustomerCancellationReasonCode.OTHER,
            reasonDetail = null,
            acceptedStoreAuthorizationId = null,
            sourceReference = "support-order-cancel:$orderId",
        )

        private fun insertPickupSlot(
            slotId: UUID,
            storeId: UUID,
        ) {
            val startsAt = Instant.now().plusSeconds(3600)
            jdbcTemplate.update(
                """
                INSERT INTO fulfillment_pickup_slot (
                    id, store_id, starts_at, ends_at, capacity, reserved_count, confirmed_count, version
                ) VALUES (?, ?, ?, ?, 5, 0, 0, 0)
                """.trimIndent(),
                slotId,
                storeId,
                Timestamp.from(startsAt),
                Timestamp.from(startsAt.plusSeconds(600)),
            )
        }

        private fun number(
            sql: String,
            vararg args: Any,
        ): Long = requireNotNull(jdbcTemplate.queryForObject(sql, Long::class.java, *args))

        private fun count(table: String): Long =
            requireNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Long::class.java))

        private fun assertSetupIntegrityEvidence() {
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM operations_reprocessing_case " +
                        "WHERE case_type = 'PAYMENT_CANCELLATION_SETUP'",
                    Long::class.java,
                ),
            ).isEqualTo(1)
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM operations_audit_record " +
                        "WHERE action = 'PAYMENT_CANCELLATION_SETUP_INCOMPLETE_DETECTED'",
                    Long::class.java,
                ),
            ).isEqualTo(1)
        }

        private fun countCommandAudits(): Long =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM operations_audit_record WHERE action IN ($COMMAND_AUDIT_ACTIONS)",
                    Long::class.java,
                ),
            )

        private fun awaitPublicationsSettled() {
            repeat(100) {
                val outstanding =
                    jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM event_publication WHERE completion_date IS NULL",
                        Long::class.java,
                    ) ?: 0
                if (outstanding == 0L) return
                Thread.sleep(50)
            }
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM event_publication WHERE completion_date IS NULL",
                    Long::class.java,
                ),
            ).isZero()
        }

        private fun awaitValue(
            sql: String,
            id: UUID,
            expected: String,
        ) {
            repeat(100) {
                if (jdbcTemplate.queryForObject(sql, String::class.java, id) == expected) return
                Thread.sleep(50)
            }
            assertThat(jdbcTemplate.queryForObject(sql, String::class.java, id)).isEqualTo(expected)
        }

        private fun resetBetweenFaultScenarios() {
            awaitPublicationsSettled()
            OrderCreationDatabaseFixture.clean(jdbcTemplate)
            paymentGateway.reset()
            notificationProvider.reset()
        }

        private fun moveAcceptanceDeadlineToPast(orderId: UUID) {
            val paidAt = Instant.now().minusSeconds(4 * 60)
            jdbcTemplate.update(
                """
                UPDATE ordering_order
                SET created_at = ?, paid_at = ?, acceptance_warning_at = ?, acceptance_deadline_at = ?
                WHERE id = ?
                """.trimIndent(),
                Timestamp.from(paidAt.minusSeconds(60)),
                Timestamp.from(paidAt),
                Timestamp.from(paidAt.plusSeconds(2 * 60)),
                Timestamp.from(paidAt.plusSeconds(3 * 60)),
                orderId,
            )
        }

        private fun <T> withPersistenceFault(
            target: FaultTarget,
            operation: () -> T,
        ): T {
            require(target.operation == "INSERT" || target.operation == "UPDATE")
            jdbcTemplate.execute(
                """
                CREATE OR REPLACE FUNCTION test_customer_cancellation_fault()
                RETURNS trigger AS ${'$'}${'$'}
                BEGIN
                    RAISE EXCEPTION 'injected customer cancellation persistence failure';
                END;
                ${'$'}${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            )
            jdbcTemplate.execute(
                """
                CREATE TRIGGER test_customer_cancellation_fault_trigger
                BEFORE ${target.operation} ON ${target.table}
                FOR EACH ROW EXECUTE FUNCTION test_customer_cancellation_fault()
                """.trimIndent(),
            )
            return try {
                operation()
            } finally {
                jdbcTemplate.execute(
                    "DROP TRIGGER IF EXISTS test_customer_cancellation_fault_trigger ON ${target.table}",
                )
            }
        }

        private fun value(
            sql: String,
            vararg args: Any,
        ): String = requireNotNull(jdbcTemplate.queryForObject(sql, String::class.java, *args))

        private companion object {
            const val COMMAND_AUDIT_ACTIONS =
                "'ORDER_CUSTOMER_CANCELLED'," +
                    "'PICKUP_RESERVATION_RELEASED_BY_CUSTOMER_CANCELLATION'," +
                    "'STOCK_RESERVATION_RELEASED_BY_CUSTOMER_CANCELLATION'," +
                    "'COUPON_RESERVATION_RELEASED_BY_CUSTOMER_CANCELLATION'," +
                    "'POINT_RESERVATION_RELEASED_BY_CUSTOMER_CANCELLATION'," +
                    "'ORDER_COMPENSATION_CASE_CREATED'," +
                    "'PAYMENT_CANCELLATION_RECOVERY_SNAPSHOT_CREATED'," +
                    "'CUSTOMER_CANCELLATION_REFUND_REQUESTED'," +
                    "'ORDER_CANCELLATION_ACCEPTED_DELIVERY_CREATED'"
        }

        private data class FaultTarget(
            val table: String,
            val operation: String,
        )
    }

package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.notification.internal.NotificationDeliveryWorker
import io.github.kdh949.beanflow.notification.internal.NotificationProviderResult
import io.github.kdh949.beanflow.notification.internal.ScriptedTestNotificationProvider
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import io.github.kdh949.beanflow.payment.internal.GatewayRefundResult
import io.github.kdh949.beanflow.payment.internal.RejectionRefundWorker
import io.github.kdh949.beanflow.payment.internal.ScriptedTestPaymentGateway
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.refund.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
    ],
)
internal class StoreOrderLifecycleIntegrationTest
    @Autowired
    constructor(
        private val createOrderUseCase: CreateOrderUseCase,
        private val confirmationService: PaymentConfirmationService,
        private val transitionService: StoreOrderTransitionService,
        private val deadlineService: StoreAcceptanceDeadlineService,
        private val orderRepository: OrderJpaRepository,
        private val refundWorker: RejectionRefundWorker,
        private val notificationWorker: NotificationDeliveryWorker,
        private val paymentGateway: ScriptedTestPaymentGateway,
        private val notificationProvider: ScriptedTestNotificationProvider,
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        @BeforeEach
        fun cleanDatabase() {
            await("previous event publications to complete") {
                count(
                    "SELECT count(*) FROM event_publication WHERE completion_date IS NULL",
                ) == 0L
            }
            OrderCreationDatabaseFixture.clean(jdbcTemplate)
            jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    notification_delivery,
                    payment_refund,
                    operations_rejection_compensation_step,
                    operations_rejection_compensation_case,
                    identity_store_membership,
                    event_publication
                CASCADE
                """.trimIndent(),
            )
            paymentGateway.reset()
            notificationProvider.reset()
        }

        @Test
        fun `store API requires matching active membership in addition to JWT role`() {
            val fixture = OrderCreationFixture()
            val orderId = paidOrder(fixture, "store-auth-order")
            val noMembershipActor = UUID.randomUUID()
            val otherStoreActor = UUID.randomUUID()
            val revokedActor = UUID.randomUUID()
            val activeActor = UUID.randomUUID()
            val activeOwner = UUID.randomUUID()
            insertMembership(otherStoreActor, UUID.randomUUID(), "STAFF", "ACTIVE")
            insertMembership(revokedActor, fixture.storeId, "STAFF", "REVOKED")
            insertMembership(activeActor, fixture.storeId, "STAFF", "ACTIVE")
            insertMembership(activeOwner, fixture.storeId, "OWNER", "ACTIVE")

            mockMvc
                .perform(get("/api/v1/store-orders/{orderId}", orderId).with(storeJwt(noMembershipActor)))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            mockMvc
                .perform(get("/api/v1/store-orders/{orderId}", orderId).with(storeJwt(otherStoreActor)))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            mockMvc
                .perform(get("/api/v1/store-orders/{orderId}", orderId).with(storeJwt(revokedActor)))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            mockMvc
                .perform(get("/api/v1/store-orders/{orderId}", orderId).with(storeJwt(activeActor)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.order.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.order.state").value("PAID"))
            mockMvc
                .perform(
                    get("/api/v1/store-orders/{orderId}", orderId)
                        .with(storeJwt(activeOwner, "STORE_OWNER")),
                ).andExpect(status().isOk)

            mockMvc
                .perform(
                    get("/api/v1/store-orders/{orderId}", orderId)
                        .with(
                            jwt()
                                .jwt {
                                    it
                                        .subject(activeActor.toString())
                                        .claim("roles", listOf("CUSTOMER"))
                                }.authorities(SimpleGrantedAuthority("ROLE_CUSTOMER")),
                        ),
                ).andExpect(status().isForbidden)
        }

        @Test
        fun `store transition replays same command and rejects key or state conflicts`() {
            val fixture = OrderCreationFixture()
            val orderId = paidOrder(fixture, "store-idempotency-order")
            val actorId = UUID.randomUUID()
            insertMembership(actorId, fixture.storeId, "STAFF", "ACTIVE")

            patchStatus(actorId, orderId, "store-accept-key", "ACCEPTED")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.replayed").value(false))
            patchStatus(actorId, orderId, "store-accept-key", "ACCEPTED")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.replayed").value(true))
            patchStatus(actorId, orderId, "store-accept-key", "PREPARING")
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
            patchStatus(actorId, orderId, "store-another-key", "ACCEPTED")
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"))
        }

        @Test
        fun `store completes the manufacturing lifecycle and creates one ready notification`() {
            val fixture = OrderCreationFixture()
            val orderId = paidOrder(fixture, "store-lifecycle-order")
            val actorId = UUID.randomUUID()
            insertMembership(actorId, fixture.storeId, "STAFF", "ACTIVE")

            patchStatus(actorId, orderId, "store-accepted-key", "ACCEPTED").andExpect(status().isOk)
            patchStatus(actorId, orderId, "store-preparing-key", "PREPARING").andExpect(status().isOk)
            patchStatus(actorId, orderId, "store-ready-key", "READY")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.order.state").value("READY"))
            patchStatus(actorId, orderId, "store-completed-key", "COMPLETED")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.order.state").value("COMPLETED"))

            await("ready notification delivery") {
                count(
                    "SELECT count(*) FROM notification_delivery " +
                        "WHERE order_id = ? AND template = 'ORDER_READY'",
                    orderId,
                ) == 1L
            }
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId))
                .isEqualTo("COMPLETED")
            awaitNoOutstandingPublications()
        }

        @Test
        fun `rejection reaches succeeded only after every required compensation finishes`() {
            val fixture = OrderCreationFixture()
            val orderId = paidOrder(fixture, "store-rejection-order")
            val actorId = UUID.randomUUID()
            insertMembership(actorId, fixture.storeId, "STAFF", "ACTIVE")

            patchStatus(actorId, orderId, "store-rejected-key", "REJECTED", "OUT_OF_STOCK")
                .andExpect(status().isAccepted)
                .andExpect(jsonPath("$.order.state").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionRecovery.state").value("PROCESSING"))

            await("rejection listeners to create durable external work") {
                count("SELECT count(*) FROM payment_refund WHERE order_id = ?", orderId) == 1L &&
                    count(
                        "SELECT count(*) FROM notification_delivery " +
                            "WHERE order_id = ? AND template = 'ORDER_REJECTED'",
                        orderId,
                    ) == 1L &&
                    value<String>(
                        "SELECT state FROM inventory_stock_reservation WHERE order_id = ?",
                        orderId,
                    ) == "RELEASED_BY_REJECTION" &&
                    value<String>(
                        "SELECT state FROM fulfillment_pickup_reservation WHERE order_id = ?",
                        orderId,
                    ) == "RELEASED_BY_REJECTION"
            }
            assertThat(
                value<String>(
                    "SELECT state FROM operations_rejection_compensation_case WHERE order_id = ?",
                    orderId,
                ),
            ).isEqualTo("PROCESSING")

            paymentGateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-rejection-refund"))
            notificationProvider.enqueue(
                NotificationProviderResult.Acknowledged("provider-rejection-notification"),
            )
            assertThat(refundWorker.runOnce()).isEqualTo(1)
            assertThat(notificationWorker.runOnce()).isEqualTo(1)

            await("rejection compensation case to finish") {
                value<String>(
                    "SELECT state FROM operations_rejection_compensation_case WHERE order_id = ?",
                    orderId,
                ) == "SUCCEEDED"
            }
            assertThat(
                value<Long>(
                    "SELECT succeeded_refund_amount_krw FROM payment_payment WHERE order_id = ?",
                    orderId,
                ),
            ).isEqualTo(1_000)
            assertThat(
                value<String>("SELECT state FROM payment_refund WHERE order_id = ?", orderId),
            ).isEqualTo("SUCCEEDED")
            assertThat(
                value<String>(
                    "SELECT state FROM notification_delivery " +
                        "WHERE order_id = ? AND template = 'ORDER_REJECTED'",
                    orderId,
                ),
            ).isEqualTo("SUCCEEDED")
            assertThat(
                value<Long>(
                    "SELECT available_quantity FROM inventory_sellable_stock WHERE id = ?",
                    fixture.sellableUnitId,
                ),
            ).isEqualTo(10)
            assertThat(
                value<Long>(
                    "SELECT confirmed_count FROM fulfillment_pickup_slot WHERE id = ?",
                    fixture.pickupSlotId,
                ),
            ).isZero()
            awaitNoOutstandingPublications()
        }

        @Test
        fun `acceptance and timeout race produces exactly one guarded transition`() {
            val fixture = OrderCreationFixture()
            val orderId = paidOrder(fixture, "store-accept-timeout-order")
            val actorId = UUID.randomUUID()
            insertMembership(actorId, fixture.storeId, "STAFF", "ACTIVE")
            val deadline = requireNotNull(orderRepository.findById(orderId).orElseThrow().acceptanceDeadlineAt)
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)

            val accepted =
                executor.submit<Result<StoreTransitionHttpResult>> {
                    barrier.await()
                    runCatching {
                        transitionService.transition(
                            StoreTransitionActor(actorId, setOf(StoreActorRole.STAFF)),
                            orderId,
                            "accept-race-key",
                            StoreOrderTransitionRequest(StoreOrderTargetState.ACCEPTED, null),
                        )
                    }
                }
            val timedOut =
                executor.submit<StoreAcceptanceDeadlineOutcome> {
                    barrier.await()
                    deadlineService.rejectTimedOut(orderId, deadline)
                }
            val acceptWon = accepted.get(10, TimeUnit.SECONDS).isSuccess
            val timeoutWon =
                timedOut.get(10, TimeUnit.SECONDS) == StoreAcceptanceDeadlineOutcome.APPLIED
            executor.shutdown()

            assertThat(acceptWon.xor(timeoutWon)).isTrue()
            assertThat(orderRepository.findById(orderId).orElseThrow().state)
                .isIn(OrderState.ACCEPTED, OrderState.REJECTED)
            if (timeoutWon) awaitNoOutstandingPublications()
        }

        @Test
        fun `manual rejection and timeout race create one rejection case and one event`() {
            val fixture = OrderCreationFixture()
            val orderId = paidOrder(fixture, "store-reject-timeout-order")
            val actorId = UUID.randomUUID()
            insertMembership(actorId, fixture.storeId, "STAFF", "ACTIVE")
            val deadline = requireNotNull(orderRepository.findById(orderId).orElseThrow().acceptanceDeadlineAt)
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)

            val manual =
                executor.submit<Result<StoreTransitionHttpResult>> {
                    barrier.await()
                    runCatching {
                        transitionService.transition(
                            StoreTransitionActor(actorId, setOf(StoreActorRole.STAFF)),
                            orderId,
                            "reject-race-key",
                            StoreOrderTransitionRequest(StoreOrderTargetState.REJECTED, "MANUAL_REJECTION"),
                        )
                    }
                }
            val timeout =
                executor.submit<StoreAcceptanceDeadlineOutcome> {
                    barrier.await()
                    deadlineService.rejectTimedOut(orderId, deadline)
                }
            val manualWon = manual.get(10, TimeUnit.SECONDS).isSuccess
            val timeoutWon = timeout.get(10, TimeUnit.SECONDS) == StoreAcceptanceDeadlineOutcome.APPLIED
            executor.shutdown()

            assertThat(manualWon.xor(timeoutWon)).isTrue()
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId))
                .isEqualTo("REJECTED")
            assertThat(
                count(
                    "SELECT count(*) FROM operations_rejection_compensation_case WHERE order_id = ?",
                    orderId,
                ),
            ).isEqualTo(1)
            awaitNoOutstandingPublications()
        }

        @Test
        fun `one hundred concurrent identical store commands apply once and replay ninety nine times`() {
            val fixture = OrderCreationFixture()
            val orderId = paidOrder(fixture, "store-concurrent-order")
            val actorId = UUID.randomUUID()
            insertMembership(actorId, fixture.storeId, "STAFF", "ACTIVE")
            val actor = StoreTransitionActor(actorId, setOf(StoreActorRole.STAFF))
            val start = CountDownLatch(1)
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            val futures =
                (1..100).map {
                    executor.submit<StoreTransitionHttpResult> {
                        start.await()
                        transitionService.transition(
                            actor,
                            orderId,
                            "concurrent-store-key",
                            StoreOrderTransitionRequest(StoreOrderTargetState.ACCEPTED, null),
                        )
                    }
                }
            start.countDown()
            val responses = futures.map { it.get(20, TimeUnit.SECONDS) }
            executor.shutdown()

            assertThat(responses).allMatch { it.status == 200 }
            assertThat(responses.count { it.body.contains("\"replayed\":false") }).isEqualTo(1)
            assertThat(responses.count { it.body.contains("\"replayed\":true") }).isEqualTo(99)
            assertThat(
                count(
                    "SELECT count(*) FROM ordering_store_command_idempotency " +
                        "WHERE order_id = ? AND idempotency_key = 'concurrent-store-key'",
                    orderId,
                ),
            ).isEqualTo(1)
            assertThat(orderRepository.findById(orderId).orElseThrow().state).isEqualTo(OrderState.ACCEPTED)
        }

        private fun paidOrder(
            fixture: OrderCreationFixture,
            key: String,
        ): UUID {
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            assertThat(createOrderUseCase.create(key, fixture.command()).status).isEqualTo(201)
            val orderId = value<UUID>("SELECT id FROM ordering_order WHERE customer_id = ?", fixture.customerId)
            val paymentMethodId = insertPaymentMethod(fixture.customerId)
            paymentGateway.enqueueApproval(
                ProviderPaymentResult.Approved("provider-$key", 1_000, "KRW"),
            )
            assertThat(
                confirmationService
                    .confirm(
                        fixture.customerId,
                        orderId,
                        paymentMethodId,
                        "$key-payment",
                    ).status,
            ).isEqualTo(200)
            return orderId
        }

        private fun insertPaymentMethod(customerId: UUID): UUID {
            val id = UUID.randomUUID()
            val now = Timestamp.from(Instant.now())
            jdbcTemplate.update(
                """
                INSERT INTO payment_method (
                    id, customer_id, provider, token_reference, display_alias, card_brand,
                    last_four, status, created_at, updated_at, version
                )
                VALUES (?, ?, 'SCRIPTED', ?, 'Store lifecycle', 'TEST', '4242', 'ACTIVE', ?, ?, 0)
                """.trimIndent(),
                id,
                customerId,
                "test-token:$id",
                now,
                now,
            )
            return id
        }

        private fun insertMembership(
            actorId: UUID,
            storeId: UUID,
            role: String,
            membershipStatus: String,
        ) {
            val now = Timestamp.from(Instant.now())
            jdbcTemplate.update(
                """
                INSERT INTO identity_store_membership (
                    id, actor_id, store_id, membership_role, status, created_at, updated_at, version
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                """.trimIndent(),
                UUID.randomUUID(),
                actorId,
                storeId,
                role,
                membershipStatus,
                now,
                now,
            )
        }

        private fun patchStatus(
            actorId: UUID,
            orderId: UUID,
            idempotencyKey: String,
            targetState: String,
            reason: String? = null,
        ) = mockMvc.perform(
            patch("/api/v1/store-orders/{orderId}/status", orderId)
                .with(storeJwt(actorId))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "targetState": "$targetState",
                      "reason": ${reason?.let { "\"$it\"" } ?: "null"}
                    }
                    """.trimIndent(),
                ),
        )

        private fun storeJwt(
            actorId: UUID,
            role: String = "STORE_STAFF",
        ) = jwt()
            .jwt {
                it
                    .subject(actorId.toString())
                    .claim("roles", listOf(role))
            }.authorities(SimpleGrantedAuthority("ROLE_$role"))

        private fun awaitNoOutstandingPublications() {
            await("event publications to complete") {
                count(
                    "SELECT count(*) FROM event_publication WHERE completion_date IS NULL",
                ) == 0L
            }
        }

        private fun await(
            description: String,
            assertion: () -> Boolean,
        ) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                if (runCatching(assertion).getOrDefault(false)) return
                Thread.sleep(20)
            }
            check(assertion()) { "Timed out waiting for $description" }
        }

        private fun count(
            sql: String,
            vararg args: Any,
        ): Long = value(sql, *args)

        private inline fun <reified T : Any> value(
            sql: String,
            vararg args: Any,
        ): T = requireNotNull(jdbcTemplate.queryForObject(sql, T::class.java, *args))
    }

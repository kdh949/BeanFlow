package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import io.github.kdh949.beanflow.payment.internal.ScriptedTestPaymentGateway
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Import(TestcontainersConfiguration::class, StoreOrderBoardTestConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
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
internal class StoreOrderBoardIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val createOrders: CreateOrderUseCase,
        private val orderQuoteUseCase: io.github.kdh949.beanflow.ordering.api.OrderQuoteUseCase,
        private val confirmationService: PaymentConfirmationService,
        private val transitionService: StoreOrderTransitionService,
        private val paymentGateway: ScriptedTestPaymentGateway,
        private val objectMapper: ObjectMapper,
        private val meterRegistry: MeterRegistry,
        private val clock: StoreOrderBoardMutableClock,
        private val etags: ControllableStoreOrderBoardEtagGenerator,
    ) {
        private val now = Instant.parse("2026-08-14T03:00:00Z")

        @BeforeEach
        fun cleanDatabase() {
            await("previous event publications to complete") { outstandingPublicationCount() == 0L }
            OrderCreationDatabaseFixture.clean(jdbcTemplate)
            jdbcTemplate.execute("TRUNCATE TABLE ordering_store_command_idempotency CASCADE")
            clock.set(now)
            etags.fail = false
            paymentGateway.reset()
        }

        @Test
        fun `board scopes in SQL and groups every active state including a future paid order without private data`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, slotCapacity = 10, stockAvailable = 10)
            val actorId = UUID.randomUUID()
            insertMembership(actorId, fixture.storeId, "ACTIVE")
            val nextDayFixture = addPickupSlot(fixture, "2030-01-02T00:10:00Z", "2030-01-02T00:20:00Z")
            val paid = create(nextDayFixture, "board-paid")
            val accepted = create(fixture, "board-accepted")
            val preparing = create(fixture, "board-preparing")
            val ready = create(fixture, "board-ready")
            val pending = create(fixture, "board-pending")
            val completed = create(fixture, "board-completed")
            activate(paid.orderId, "PAID", now.plusSeconds(120), now.plusSeconds(180))
            activate(accepted.orderId, "ACCEPTED")
            activate(preparing.orderId, "PREPARING")
            activate(ready.orderId, "READY")
            activate(completed.orderId, "COMPLETED")

            val otherStore = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, otherStore)
            val other = create(otherStore, "board-other-store")
            activate(other.orderId, "PAID", now.plusSeconds(120), now.plusSeconds(180))

            val beforeSql = listSqlCount()
            val response =
                mockMvc
                    .perform(get(boardPath(fixture.storeId)).with(merchantJwt(actorId)))
                    .andExpect(status().isOk)
                    .andExpect(header().exists(HttpHeaders.ETAG))
                    .andExpect(jsonPath("$.groups.length()").value(2))
                    .andReturn()
            assertThat(listSqlCount() - beforeSql).isEqualTo(2.0)

            val body = json(response.response.contentAsString)
            val items = boardItems(body)
            assertThat(items.map { it["status"].asText() })
                .containsExactlyInAnyOrder("PAID", "ACCEPTED", "PREPARING", "READY")
            assertThat(items.map { it["orderReference"].asText() })
                .doesNotContain(pending.reference, completed.reference, other.reference)
            assertThat(items.single { it["status"].asText() == "PAID" }["pickupBusinessDate"].asText())
                .isEqualTo("2030-01-02")
            assertThat(response.response.contentAsString)
                .contains("Americano × 1", "PENDING_ACCEPTANCE", "START_PREPARING", "MARK_READY", "COMPLETE")
                .doesNotContain(
                    "customerId",
                    "paymentId",
                    "providerReference",
                    "subtotalKrw",
                    "payableKrw",
                    "orderId",
                    "steps",
                    "attemptCount",
                    "lastErrorCode",
                )
        }

        @Test
        fun `board query keeps two projection statements for one and fifty active orders`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, slotCapacity = 60, stockAvailable = 60)
            val actorId = UUID.randomUUID()
            insertMembership(actorId, fixture.storeId, "ACTIVE")
            activate(create(fixture, "board-count-00").orderId, "ACCEPTED")

            val beforeOne = listSqlCount()
            mockMvc
                .perform(get(boardPath(fixture.storeId)).with(merchantJwt(actorId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.groups[0].items.length()").value(1))
            assertThat(listSqlCount() - beforeOne).isEqualTo(2.0)

            repeat(49) { index ->
                activate(create(fixture, "board-count-${(index + 1).toString().padStart(2, '0')}").orderId, "ACCEPTED")
            }
            val beforeFifty = listSqlCount()
            mockMvc
                .perform(get(boardPath(fixture.storeId)).with(merchantJwt(actorId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.groups[0].items.length()").value(50))
            assertThat(listSqlCount() - beforeFifty).isEqualTo(2.0)
        }

        @Test
        fun `board bounds every lane and exposes exact older work through a signed queue`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, slotCapacity = 240, stockAvailable = 240)
            val actorId = UUID.randomUUID()
            insertMembership(actorId, fixture.storeId, "ACTIVE")
            val referencesByState = linkedMapOf<String, MutableSet<String>>()
            listOf("PAID", "ACCEPTED", "PREPARING", "READY").forEach { state ->
                referencesByState[state] = linkedSetOf()
                repeat(51) { index ->
                    val created = create(fixture, "board-bound-${state.lowercase()}-${index.toString().padStart(2, '0')}")
                    activate(created.orderId, state, now.plusSeconds(120), now.plusSeconds(180))
                    referencesByState.getValue(state) += created.reference
                }
            }

            val beforeList = listSqlCount()
            val beforeEtagCount = etagTimerCount()
            val beforeEtagNanos = etagTimerNanos()
            val snapshot =
                mockMvc
                    .perform(get(boardPath(fixture.storeId)).with(merchantJwt(actorId)))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.overflow.length()").value(4))
                    .andReturn()
            assertThat(listSqlCount() - beforeList).isEqualTo(3.0)

            val body = json(snapshot.response.contentAsString)
            val etag = requireNotNull(snapshot.response.getHeader(HttpHeaders.ETAG))
            val responseBytes = snapshot.response.contentAsByteArray.size
            assertThat(responseBytes).isPositive()
            assertThat(etagTimerCount() - beforeEtagCount).isEqualTo(1)
            println(
                "STORE_ORDER_BOARD_BOUNDED_RESPONSE cards=${boardItems(body).size} bytes=$responseBytes " +
                    "etag_nanos=${etagTimerNanos() - beforeEtagNanos}",
            )
            val visibleByLane = boardItems(body).groupBy { it["lane"].asText() }
            assertThat(visibleByLane.values.map(List<JsonNode>::size)).containsOnly(50)
            val overflowByLane = body["overflow"].associateBy { it["lane"].asText() }
            assertThat(overflowByLane.keys).containsExactlyInAnyOrder("PENDING_ACCEPTANCE", "ACCEPTED", "PREPARING", "READY")
            assertThat(overflowByLane.values.map { it["overflowCount"].asLong() }).containsOnly(1L)

            clock.set(now.plusSeconds(1))
            mockMvc
                .perform(get(boardPath(fixture.storeId)).with(merchantJwt(actorId)).header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified)
                .andExpect(header().string(HttpHeaders.ETAG, etag))

            val queuedReferencesByLane = linkedMapOf<String, Set<String>>()
            val beforeQueue = querySqlCount("overflow")
            overflowByLane.forEach { (lane, overflow) ->
                val page =
                    mockMvc
                        .perform(
                            get("${boardPath(fixture.storeId)}/overflow")
                                .with(merchantJwt(actorId))
                                .param("lane", lane)
                                .param("cursor", overflow["nextCursor"].asText()),
                        ).andExpect(status().isOk)
                        .andExpect(jsonPath("$.lane").value(lane))
                        .andExpect(jsonPath("$.items.length()").value(1))
                        .andExpect(jsonPath("$.nextCursor").isEmpty)
                        .andReturn()
                queuedReferencesByLane[lane] =
                    json(page.response.contentAsString)["items"].toList().map { it["orderReference"].asText() }.toSet()
            }
            assertThat(querySqlCount("overflow") - beforeQueue).isEqualTo(8.0)

            val stateByLane =
                mapOf("PENDING_ACCEPTANCE" to "PAID", "ACCEPTED" to "ACCEPTED", "PREPARING" to "PREPARING", "READY" to "READY")
            stateByLane.forEach { (lane, state) ->
                val visible = visibleByLane.getValue(lane).map { it["orderReference"].asText() }.toSet()
                assertThat(visible + queuedReferencesByLane.getValue(lane)).isEqualTo(referencesByState.getValue(state))
            }

            val cursor = overflowByLane.getValue("PENDING_ACCEPTANCE")["nextCursor"].asText()
            val beforeRejected = querySqlCount("overflow")
            mockMvc
                .perform(
                    get("${boardPath(fixture.storeId)}/overflow")
                        .with(merchantJwt(actorId))
                        .param("lane", "READY")
                        .param("cursor", cursor),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            val otherStore = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, otherStore)
            insertStoreMembership(actorId, otherStore.storeId, "ACTIVE")
            mockMvc
                .perform(
                    get("${boardPath(otherStore.storeId)}/overflow")
                        .with(merchantJwt(actorId))
                        .param("lane", "PENDING_ACCEPTANCE")
                        .param("cursor", cursor),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            clock.set(now.plusSeconds(15 * 60 + 1))
            mockMvc
                .perform(
                    get("${boardPath(fixture.storeId)}/overflow")
                        .with(merchantJwt(actorId))
                        .param("lane", "PENDING_ACCEPTANCE")
                        .param("cursor", cursor),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            assertThat(querySqlCount("overflow") - beforeRejected).isZero()
        }

        @Test
        fun `conditional polling changes ETag at time boundaries and fails closed on hash failure`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val actorId = UUID.randomUUID()
            insertMembership(actorId, fixture.storeId, "ACTIVE")
            val order = create(fixture, "board-etag")
            activate(order.orderId, "PAID", now.plusSeconds(120), now.plusSeconds(180))

            val first =
                mockMvc
                    .perform(get(boardPath(fixture.storeId)).with(merchantJwt(actorId)))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.groups[0].items[0].acceptancePhase").value("OPEN"))
                    .andReturn()
            val openEtag = requireNotNull(first.response.getHeader(HttpHeaders.ETAG))
            assertThat(openEtag).startsWith("W/")
            mockMvc
                .perform(
                    get(boardPath(fixture.storeId))
                        .with(merchantJwt(actorId))
                        .header(HttpHeaders.IF_NONE_MATCH, "${openEtag.removePrefix("W/")}, \"another\""),
                ).andExpect(status().isNotModified)
                .andExpect(header().string(HttpHeaders.ETAG, openEtag))
                .andExpect { assertThat(it.response.contentAsByteArray).isEmpty() }

            clock.set(now.plusSeconds(120))
            val warning =
                mockMvc
                    .perform(get(boardPath(fixture.storeId)).with(merchantJwt(actorId)).header(HttpHeaders.IF_NONE_MATCH, openEtag))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.groups[0].items[0].acceptancePhase").value("WARNING"))
                    .andReturn()
            val warningEtag = requireNotNull(warning.response.getHeader(HttpHeaders.ETAG))
            assertThat(warningEtag).isNotEqualTo(openEtag)

            clock.set(now.plusSeconds(180))
            val timeout =
                mockMvc
                    .perform(get(boardPath(fixture.storeId)).with(merchantJwt(actorId)).header(HttpHeaders.IF_NONE_MATCH, warningEtag))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.groups[0].items[0].acceptancePhase").value("TIMEOUT_PENDING"))
                    .andExpect(jsonPath("$.groups[0].items[0].allowedActions.length()").value(0))
                    .andReturn()
            assertThat(timeout.response.getHeader(HttpHeaders.ETAG)).isNotEqualTo(warningEtag)

            etags.fail = true
            mockMvc
                .perform(get(boardPath(fixture.storeId)).with(merchantJwt(actorId)))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.groups").doesNotExist())
        }

        @Test
        fun `membership and reference scope distinguish forbidden from missing and revoke clears access`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val order = create(fixture, "board-detail")
            activate(order.orderId, "ACCEPTED")
            val activeActor = UUID.randomUUID()
            val revokedActor = UUID.randomUUID()
            insertMembership(activeActor, fixture.storeId, "ACTIVE")
            insertMembership(revokedActor, fixture.storeId, "REVOKED")

            mockMvc
                .perform(get("${boardPath(fixture.storeId)}/${order.reference.lowercase()}").with(merchantJwt(activeActor)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.orderReference").value(order.reference))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.orderId").doesNotExist())
                .andExpect(jsonPath("$.customerId").doesNotExist())
            mockMvc
                .perform(get(boardPath(fixture.storeId)).with(merchantJwt(revokedActor)))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            transition(revokedActor, fixture.storeId, order.reference, "revoked-board-transition", "START_PREPARING", "ACCEPTED")
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            transition(UUID.randomUUID(), fixture.storeId, "BF-2222-2222", "missing-membership-transition", "ACCEPT", "PAID")
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

            val other = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, other)
            val otherOrder = create(other, "board-detail-other")
            activate(otherOrder.orderId, "ACCEPTED")
            mockMvc
                .perform(get("${boardPath(fixture.storeId)}/${otherOrder.reference}").with(merchantJwt(activeActor)))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            mockMvc
                .perform(get("${boardPath(fixture.storeId)}/BF-2222-2222").with(merchantJwt(activeActor)))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

            jdbcTemplate.update(
                "UPDATE identity_store_membership SET status = 'REVOKED' WHERE actor_id = ? AND store_id = ?",
                activeActor,
                fixture.storeId,
            )
            mockMvc
                .perform(get(boardPath(fixture.storeId)).with(merchantJwt(activeActor)))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.groups").doesNotExist())
        }

        @Test
        fun `board transition replays exactly and separates invalid action from stale state`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val order = create(fixture, "board-transition")
            activate(order.orderId, "PAID", now.plusSeconds(120), now.plusSeconds(180))
            val actorId = UUID.randomUUID()
            insertMembership(actorId, fixture.storeId, "ACTIVE")

            transition(actorId, fixture.storeId, order.reference, "invalid-action", "COMPLETE", "PAID")
                .andExpect(status().isUnprocessableEntity)
                .andExpect(jsonPath("$.code").value("ORDER_ACTION_NOT_ALLOWED"))

            val first =
                transition(actorId, fixture.storeId, order.reference, "board-accept-key", "ACCEPT", "PAID")
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.status").value("ACCEPTED"))
                    .andExpect(jsonPath("$.lane").value("ACCEPTED"))
                    .andExpect(jsonPath("$.allowedActions[0]").value("START_PREPARING"))
                    .andExpect(jsonPath("$.orderId").doesNotExist())
                    .andReturn()
                    .response
                    .contentAsString
            val replay =
                transition(actorId, fixture.storeId, order.reference, "board-accept-key", "ACCEPT", "PAID")
                    .andExpect(status().isOk)
                    .andReturn()
                    .response
                    .contentAsString
            assertThat(replay).isEqualTo(first)

            transition(actorId, fixture.storeId, order.reference, "board-stale-key", "ACCEPT", "PAID")
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"))
            transition(actorId, fixture.storeId, order.reference, "board-accept-key", "START_PREPARING", "ACCEPTED")
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
        }

        @Test
        fun `every server advertised board action succeeds from its matching source state`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val order = create(fixture, "board-all-actions")
            pay(order.orderId, fixture.customerId, "board-all-actions")
            val actorId = UUID.randomUUID()
            insertMembership(actorId, fixture.storeId, "ACTIVE")

            transition(actorId, fixture.storeId, order.reference, "board-action-accept", "ACCEPT", "PAID")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.allowedActions[0]").value("START_PREPARING"))
            transition(actorId, fixture.storeId, order.reference, "board-action-prepare", "START_PREPARING", "ACCEPTED")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("PREPARING"))
                .andExpect(jsonPath("$.allowedActions[0]").value("MARK_READY"))
            transition(actorId, fixture.storeId, order.reference, "board-action-ready", "MARK_READY", "PREPARING")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.allowedActions[0]").value("COMPLETE"))
            transition(actorId, fixture.storeId, order.reference, "board-action-complete", "COMPLETE", "READY")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.lane").doesNotExist())
                .andExpect(jsonPath("$.allowedActions.length()").value(0))
        }

        @Test
        fun `two concurrent board transitions have one success and one 409 loser`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val order = create(fixture, "board-concurrent")
            activate(order.orderId, "PAID", now.plusSeconds(120), now.plusSeconds(180))
            val actorId = UUID.randomUUID()
            insertMembership(actorId, fixture.storeId, "ACTIVE")
            val actor = StoreTransitionActor(actorId, setOf(StoreActorRole.STAFF))
            val request = StoreOrderActionRequest(StoreOrderAction.ACCEPT, StoreOrderExpectedStatus.PAID, null)
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            val results =
                listOf("board-race-one", "board-race-two")
                    .map { key ->
                        executor.submit<Result<StoreTransitionHttpResult>> {
                            barrier.await()
                            runCatching { transitionService.transitionBoard(actor, order.orderId, key, request) }
                        }
                    }.map { it.get(10, TimeUnit.SECONDS) }
            executor.shutdown()

            assertThat(results.count { it.isSuccess }).isEqualTo(1)
            val loser = results.single { it.isFailure }.exceptionOrNull()
            assertThat(loser).isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.ORDER_STATE_CONFLICT)
            }
            assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", order.orderId)).isEqualTo("ACCEPTED")
        }

        private fun create(
            fixture: OrderCreationFixture,
            key: String,
        ): CreatedOrder {
            val result = createOrders.create(key, orderQuoteUseCase.attachCurrentQuote(fixture.command()))
            assertThat(result.status).isEqualTo(201)
            val body = json(result.body)["order"]
            return CreatedOrder(UUID.fromString(body["orderId"].asText()), body["publicReference"].asText())
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
                ) VALUES (?, ?, 'SCRIPTED', ?, 'Board test', 'TEST', '4242', 'ACTIVE', ?, ?, 0)
                """.trimIndent(),
                paymentMethodId,
                customerId,
                "test-token:$paymentMethodId",
                timestamp,
                timestamp,
            )
            paymentGateway.enqueueApproval(ProviderPaymentResult.Approved("provider-$key", 1_000, "KRW"))
            assertThat(confirmationService.confirm(customerId, orderId, paymentMethodId, "$key-payment").status).isEqualTo(200)
        }

        private fun activate(
            orderId: UUID,
            state: String,
            warningAt: Instant? = null,
            deadlineAt: Instant? = null,
        ) {
            val paidAt = now
            val effectiveWarning = warningAt ?: paidAt.plusSeconds(120)
            val effectiveDeadline = deadlineAt ?: paidAt.plusSeconds(180)
            val acceptedAt = if (state in setOf("ACCEPTED", "PREPARING", "READY", "COMPLETED")) paidAt.plusSeconds(10) else null
            val preparingAt = if (state in setOf("PREPARING", "READY", "COMPLETED")) paidAt.plusSeconds(20) else null
            val readyAt = if (state in setOf("READY", "COMPLETED")) paidAt.plusSeconds(30) else null
            val completedAt = if (state == "COMPLETED") paidAt.plusSeconds(40) else null
            jdbcTemplate.update(
                """
                UPDATE ordering_order
                   SET state = ?, reservation_expires_at = NULL,
                       paid_at = CASE WHEN ? IN ('PAID','ACCEPTED','PREPARING','READY','COMPLETED') THEN ? ELSE paid_at END,
                       acceptance_warning_at = ?, acceptance_deadline_at = ?,
                       accepted_at = ?, preparing_at = ?, ready_at = ?, completed_at = ?,
                       updated_at = ?, version = version + 1
                 WHERE id = ?
                """.trimIndent(),
                state,
                state,
                Timestamp.from(paidAt),
                Timestamp.from(effectiveWarning),
                Timestamp.from(effectiveDeadline),
                acceptedAt?.let(Timestamp::from),
                preparingAt?.let(Timestamp::from),
                readyAt?.let(Timestamp::from),
                completedAt?.let(Timestamp::from),
                Timestamp.from(now),
                orderId,
            )
        }

        private fun addPickupSlot(
            fixture: OrderCreationFixture,
            startsAt: String,
            endsAt: String,
        ): OrderCreationFixture {
            val slotId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO fulfillment_pickup_slot (
                    id, store_id, starts_at, ends_at, capacity, reserved_count, confirmed_count
                ) VALUES (?, ?, ?, ?, 10, 0, 0)
                """.trimIndent(),
                slotId,
                fixture.storeId,
                Timestamp.from(Instant.parse(startsAt)),
                Timestamp.from(Instant.parse(endsAt)),
            )
            return fixture.copy(pickupSlotId = slotId)
        }

        private fun insertMembership(
            actorId: UUID,
            storeId: UUID,
            membershipStatus: String,
        ) {
            val timestamp = Timestamp.from(now)
            jdbcTemplate.update(
                """
                INSERT INTO identity_merchant_account (
                    id, login_id, password_hash, credential_version, display_name, state,
                    temporary_password_expires_at, password_changed_at, locked_until,
                    created_at, updated_at, version
                ) VALUES (?, ?, 'test-only-password-hash', 0, 'Board actor', 'ACTIVE',
                          null, ?, null, ?, ?, 0)
                """.trimIndent(),
                actorId,
                "board.${actorId.toString().take(8)}",
                timestamp,
                timestamp,
                timestamp,
            )
            insertStoreMembership(actorId, storeId, membershipStatus)
        }

        private fun insertStoreMembership(
            actorId: UUID,
            storeId: UUID,
            membershipStatus: String,
        ) {
            val timestamp = Timestamp.from(now)
            jdbcTemplate.update(
                """
                INSERT INTO identity_store_membership (
                    id, actor_id, store_id, membership_role, status, created_at, updated_at, version
                ) VALUES (?, ?, ?, 'STAFF', ?, ?, ?, 0)
                """.trimIndent(),
                UUID.randomUUID(),
                actorId,
                storeId,
                membershipStatus,
                timestamp,
                timestamp,
            )
        }

        private fun transition(
            actorId: UUID,
            storeId: UUID,
            reference: String,
            idempotencyKey: String,
            action: String,
            expectedStatus: String,
            reason: String? = null,
        ) = mockMvc.perform(
            post("${boardPath(storeId)}/$reference/transitions")
                .with(merchantJwt(actorId))
                .with(csrf())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "action": "$action",
                      "expectedStatus": "$expectedStatus",
                      "reason": ${reason?.let { "\"$it\"" } ?: "null"}
                    }
                    """.trimIndent(),
                ),
        )

        private fun merchantJwt(actorId: UUID) =
            jwt()
                .jwt { it.subject(actorId.toString()).claim("roles", listOf("STORE_STAFF")) }
                .authorities(SimpleGrantedAuthority("ROLE_MERCHANT"))

        private fun boardPath(storeId: UUID): String = "/api/v1/stores/$storeId/orders"

        private fun json(body: String): JsonNode = objectMapper.readTree(body)

        private fun boardItems(body: JsonNode): List<JsonNode> = body["groups"].flatMap { group -> group["items"].toList() }

        private fun listSqlCount(): Double = querySqlCount("list")

        private fun querySqlCount(operation: String): Double =
            meterRegistry.counter("beanflow.store.order.board.query.sql", "operation", operation).count()

        private fun etagTimerCount(): Long = meterRegistry.find("beanflow.store.order.board.etag.duration").timer()?.count() ?: 0L

        private fun etagTimerNanos(): Long =
            meterRegistry
                .find("beanflow.store.order.board.etag.duration")
                .timer()
                ?.totalTime(TimeUnit.NANOSECONDS)
                ?.toLong() ?: 0L

        private fun outstandingPublicationCount(): Long =
            value(
                """
                SELECT count(*)
                  FROM event_publication
                 WHERE completion_date IS NULL
                   AND listener_id NOT IN (
                       'beanflow.settlement.payment-refunded-v1',
                       'beanflow.analytics.payment-refunded-v1',
                       'beanflow.analytics.points-accrued-v1',
                       'beanflow.analytics.points-restored-v1',
                       'beanflow.analytics.settlement-item-created-v1'
                   )
                """.trimIndent(),
            )

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

        private inline fun <reified T : Any> value(
            sql: String,
            vararg args: Any,
        ): T = requireNotNull(jdbcTemplate.queryForObject(sql, T::class.java, *args))

        private data class CreatedOrder(
            val orderId: UUID,
            val reference: String,
        )
    }

@TestConfiguration(proxyBeanMethods = false)
internal class StoreOrderBoardTestConfiguration {
    @Bean
    @Primary
    fun storeOrderBoardClock(): StoreOrderBoardMutableClock = StoreOrderBoardMutableClock(Instant.parse("2026-08-14T03:00:00Z"))

    @Bean
    @Primary
    fun controllableStoreOrderBoardEtagGenerator(
        objectMapper: ObjectMapper,
        meterRegistry: MeterRegistry,
    ): ControllableStoreOrderBoardEtagGenerator = ControllableStoreOrderBoardEtagGenerator(objectMapper, meterRegistry)
}

internal class StoreOrderBoardMutableClock(
    initial: Instant,
) : Clock() {
    private val current = AtomicReference(initial)

    fun set(value: Instant) = current.set(value)

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)

    override fun instant(): Instant = current.get()
}

internal class ControllableStoreOrderBoardEtagGenerator(
    objectMapper: ObjectMapper,
    meterRegistry: MeterRegistry,
) : StoreOrderBoardEtagGenerator {
    private val delegate = Sha256StoreOrderBoardEtagGenerator(objectMapper, meterRegistry)
    var fail: Boolean = false

    override fun generate(board: StoreOrderBoardResponse): String {
        if (fail) throw StoreOrderBoardEtagFailure(IllegalStateException("injected board hash failure"))
        return delegate.generate(board)
    }
}

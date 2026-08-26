package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.tamperSignedCursorSignature
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@Import(TestcontainersConfiguration::class, CustomerOrderQueryTestClockConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("query scenarios require committed fixture visibility")
@SpringBootTest(
    properties = [
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
    ],
)
internal class CustomerOrderQueryIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val createOrders: CreateOrderUseCase,
        private val orderQuoteUseCase: io.github.kdh949.beanflow.ordering.api.OrderQuoteUseCase,
        private val objectMapper: ObjectMapper,
        private val meterRegistry: MeterRegistry,
        private val clock: CustomerOrderQueryMutableClock,
    ) {
        private val now = Instant.parse("2026-08-14T03:00:00Z")

        @BeforeEach
        fun cleanDatabase() {
            dropAuditFailureTrigger()
            OrderCreationDatabaseFixture.clean(jdbcTemplate)
            clock.set(now)
        }

        @AfterEach
        fun removeFailureTrigger() = dropAuditFailureTrigger()

        @Test
        fun `customer list and detail use immutable display snapshots without internal identifiers`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val reference = create(fixture, "customer-query-owner-001")
            val other = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, other)
            create(other, "customer-query-other-001")

            jdbcTemplate.update(
                "UPDATE merchant_store_discovery_profile SET name = 'Renamed Store' WHERE store_id = ?",
                fixture.storeId,
            )

            mockMvc
                .perform(get("/api/v1/me/orders").param("status", "ACTIVE").with(customerJwt(fixture.customerId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].orderReference").value(reference))
                .andExpect(jsonPath("$.items[0].pickupNumber").isString)
                .andExpect(jsonPath("$.items[0].storeName").value("BeanFlow Test Store"))
                .andExpect(jsonPath("$.items[0].status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.items[0].itemSummary").value("Americano"))
                .andExpect(jsonPath("$.items[0].allowedActions[0]").value("CANCEL"))
                .andExpect(jsonPath("$.items[0].orderId").doesNotExist())
                .andExpect(jsonPath("$.items[0].storeId").doesNotExist())
                .andExpect(jsonPath("$.page.nextCursor").doesNotExist())

            mockMvc
                .perform(get("/api/v1/me/orders/{orderReference}", reference.lowercase()).with(customerJwt(fixture.customerId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.orderReference").value(reference))
                .andExpect(jsonPath("$.storeName").value("BeanFlow Test Store"))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.lines[0].lineSequence").value(0))
                .andExpect(jsonPath("$.lines[0].menuName").value("Americano"))
                .andExpect(jsonPath("$.lines[0].lineTotalKrw").value(1_000))
                .andExpect(jsonPath("$.orderId").doesNotExist())
                .andExpect(jsonPath("$.rejectionReason").doesNotExist())
                .andExpect(jsonPath("$.cancellationDetail").doesNotExist())
        }

        @Test
        fun `list and detail display the final payable amount after coupon and point benefits`() {
            val couponOnly = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, couponOnly, priceKrw = 10_000)
            val couponOnlyReference =
                create(
                    couponOnly,
                    "customer-query-coupon-only",
                    couponIssuanceId = OrderCreationDatabaseFixture.insertFixedCoupon(jdbcTemplate, couponOnly, 2_000),
                )

            val pointsOnly = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, pointsOnly, priceKrw = 10_000)
            OrderCreationDatabaseFixture.insertPoints(jdbcTemplate, pointsOnly.customerId, 3_000)
            val pointsOnlyReference = create(pointsOnly, "customer-query-points-only", pointsToUseKrw = 3_000)

            val couponAndPoints = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, couponAndPoints, priceKrw = 10_000)
            OrderCreationDatabaseFixture.insertPoints(jdbcTemplate, couponAndPoints.customerId, 3_000)
            val couponAndPointsReference =
                create(
                    couponAndPoints,
                    "customer-query-coupon-and-points",
                    pointsToUseKrw = 3_000,
                    couponIssuanceId = OrderCreationDatabaseFixture.insertFixedCoupon(jdbcTemplate, couponAndPoints, 2_000),
                )

            val benefitOnly = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, benefitOnly, priceKrw = 10_000)
            OrderCreationDatabaseFixture.insertPoints(jdbcTemplate, benefitOnly.customerId, 10_000)
            val benefitOnlyReference = create(benefitOnly, "customer-query-benefit-only", pointsToUseKrw = 10_000)

            assertDisplayedTotal(couponOnly.customerId, couponOnlyReference, 8_000)
            assertDisplayedTotal(pointsOnly.customerId, pointsOnlyReference, 7_000)
            assertDisplayedTotal(couponAndPoints.customerId, couponAndPointsReference, 5_000)
            assertDisplayedTotal(benefitOnly.customerId, benefitOnlyReference, 0)
        }

        @Test
        fun `list uses a fixed three SQL statements for one and more than one hundred orders`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, slotCapacity = 120, stockAvailable = 120)
            create(fixture, "customer-query-count-000")

            val beforeOne = listSqlCount()
            mockMvc
                .perform(get("/api/v1/me/orders").param("limit", "100").with(customerJwt(fixture.customerId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(1))
            assertThat(listSqlCount() - beforeOne).isEqualTo(3.0)

            repeat(100) { index ->
                clock.set(now.plusSeconds(index + 1L))
                create(fixture, "customer-query-count-${(index + 1).toString().padStart(3, '0')}")
            }
            val beforeMany = listSqlCount()
            mockMvc
                .perform(get("/api/v1/me/orders").param("limit", "100").with(customerJwt(fixture.customerId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(100))
                .andExpect(jsonPath("$.page.nextCursor").isString)
            assertThat(listSqlCount() - beforeMany).isEqualTo(3.0)
        }

        @Test
        fun `signed keyset cursor remains stable and rejects changed scope or signature`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, slotCapacity = 30, stockAvailable = 30)
            repeat(21) { index ->
                clock.set(now.plusSeconds(index.toLong()))
                create(fixture, "customer-query-page-${index.toString().padStart(3, '0')}")
            }
            val first =
                mockMvc
                    .perform(
                        get("/api/v1/me/orders")
                            .param("from", "2026-08-01")
                            .param("to", "2026-08-31")
                            .param("limit", "20")
                            .with(customerJwt(fixture.customerId)),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.items.length()").value(20))
                    .andExpect(jsonPath("$.page.nextCursor").isString)
                    .andReturn()
            val firstBody = json(first.response.contentAsString)
            val cursor = firstBody["page"]["nextCursor"].asText()
            val firstReferences = references(firstBody)

            clock.set(now.plusSeconds(100))
            create(fixture, "customer-query-page-newer")
            val second =
                mockMvc
                    .perform(
                        get("/api/v1/me/orders")
                            .param("from", "2026-08-01")
                            .param("to", "2026-08-31")
                            .param("limit", "20")
                            .param("cursor", cursor)
                            .with(customerJwt(fixture.customerId)),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andReturn()
            assertThat(references(json(second.response.contentAsString))).doesNotContainAnyElementsOf(firstReferences)

            val otherCustomerId = UUID.randomUUID()
            mockMvc
                .perform(
                    get("/api/v1/me/orders")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31")
                        .param("cursor", cursor)
                        .with(customerJwt(otherCustomerId)),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            mockMvc
                .perform(
                    get("/api/v1/me/orders")
                        .param("status", "PAST")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31")
                        .param("cursor", cursor)
                        .with(customerJwt(fixture.customerId)),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            mockMvc
                .perform(
                    get("/api/v1/me/orders")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31")
                        .param("cursor", tamperSignedCursorSignature(cursor))
                        .with(customerJwt(fixture.customerId)),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }

        @Test
        fun `default range is thirty Seoul days while explicit historical range has no cap`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, slotCapacity = 10, stockAvailable = 10)
            clock.set(Instant.parse("2026-06-01T03:00:00Z"))
            val oldReference = create(fixture, "customer-query-old-order")
            clock.set(now)
            val recentReference = create(fixture, "customer-query-recent")

            mockMvc
                .perform(get("/api/v1/me/orders").with(customerJwt(fixture.customerId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].orderReference").value(recentReference))
            mockMvc
                .perform(
                    get("/api/v1/me/orders")
                        .param("from", "2026-01-01")
                        .param("to", "2026-08-14")
                        .with(customerJwt(fixture.customerId)),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[1].orderReference").value(oldReference))
            mockMvc
                .perform(
                    get("/api/v1/me/orders")
                        .param("from", "2026-08-15")
                        .param("to", "2026-08-14")
                        .with(customerJwt(fixture.customerId)),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            mockMvc
                .perform(get("/api/v1/me/orders").param("from", "not-a-date").with(customerJwt(fixture.customerId)))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }

        @Test
        fun `detail distinguishes another owner from a missing public reference`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val reference = create(fixture, "customer-query-scope-001")

            mockMvc
                .perform(get("/api/v1/me/orders/{orderReference}", reference).with(customerJwt(UUID.randomUUID())))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            mockMvc
                .perform(get("/api/v1/me/orders/BF-2222-2222").with(customerJwt(fixture.customerId)))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        }

        @Test
        fun `active read materializes due orders and rolls every expiry back on dependency failure`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, slotCapacity = 10, stockAvailable = 10)
            create(fixture, "customer-query-expiry-001")
            create(fixture, "customer-query-expiry-002")
            val deadlines =
                jdbcTemplate.query(
                    "SELECT reservation_expires_at FROM ordering_order ORDER BY id",
                    { resultSet, _ -> resultSet.getTimestamp(1).toInstant() },
                )
            clock.set(deadlines.max().plusSeconds(1))

            installAuditFailureTrigger()
            mockMvc
                .perform(get("/api/v1/me/orders").param("status", "ACTIVE").with(customerJwt(fixture.customerId)))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
            assertThat(states()).containsOnly("PENDING_PAYMENT")

            dropAuditFailureTrigger()
            mockMvc
                .perform(get("/api/v1/me/orders").param("status", "ACTIVE").with(customerJwt(fixture.customerId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(0))
            assertThat(states()).containsOnly("EXPIRED")
        }

        @Test
        fun `active expiry can return an empty page with a cursor and the next page remains complete`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, slotCapacity = 30, stockAvailable = 30)
            repeat(21) { index ->
                clock.set(now.plusSeconds(index.toLong()))
                create(fixture, "customer-query-expiry-page-${index.toString().padStart(3, '0')}")
            }
            val deadline =
                jdbcTemplate
                    .queryForObject(
                        "SELECT max(reservation_expires_at) FROM ordering_order",
                        java.sql.Timestamp::class.java,
                    )!!
                    .toInstant()
            clock.set(deadline.plusSeconds(1))

            val first =
                mockMvc
                    .perform(
                        get("/api/v1/me/orders")
                            .param("status", "ACTIVE")
                            .param("from", "2026-08-01")
                            .param("to", "2026-08-31")
                            .param("limit", "20")
                            .with(customerJwt(fixture.customerId)),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.items.length()").value(0))
                    .andExpect(jsonPath("$.page.nextCursor").isString)
                    .andReturn()
            val cursor = json(first.response.contentAsString)["page"]["nextCursor"].asText()
            assertThat(states().count { it == "EXPIRED" }).isEqualTo(20)

            mockMvc
                .perform(
                    get("/api/v1/me/orders")
                        .param("status", "ACTIVE")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31")
                        .param("limit", "20")
                        .param("cursor", cursor)
                        .with(customerJwt(fixture.customerId)),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.page.nextCursor").doesNotExist())
            assertThat(states()).containsOnly("EXPIRED")
        }

        private fun create(
            fixture: OrderCreationFixture,
            key: String,
            pointsToUseKrw: Long = 0,
            couponIssuanceId: UUID? = null,
        ): String {
            val response =
                createOrders.create(
                    key,
                    orderQuoteUseCase.attachCurrentQuote(fixture.command(pointsToUseKrw, couponIssuanceId)),
                )
            assertThat(response.status).isEqualTo(201)
            return json(response.body)["order"]["publicReference"].asText()
        }

        private fun assertDisplayedTotal(
            customerId: UUID,
            reference: String,
            expectedPayableKrw: Long,
        ) {
            mockMvc
                .perform(get("/api/v1/me/orders").with(customerJwt(customerId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].orderReference").value(reference))
                .andExpect(jsonPath("$.items[0].totalAmountKrw").value(expectedPayableKrw))
            mockMvc
                .perform(get("/api/v1/me/orders/{orderReference}", reference).with(customerJwt(customerId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.totalAmountKrw").value(expectedPayableKrw))
        }

        private fun customerJwt(customerId: UUID) =
            jwt()
                .jwt { it.subject(customerId.toString()) }
                .authorities(SimpleGrantedAuthority("ROLE_CUSTOMER"))

        private fun json(body: String): JsonNode = objectMapper.readTree(body)

        private fun references(body: JsonNode): List<String> =
            (0 until body["items"].size()).map { index -> body["items"][index]["orderReference"].asText() }

        private fun listSqlCount(): Double = meterRegistry.counter("beanflow.customer.order.query.sql", "operation", "list").count()

        private fun states(): List<String> =
            jdbcTemplate
                .queryForList("SELECT state FROM ordering_order ORDER BY id", String::class.java)
                .filterNotNull()

        private fun installAuditFailureTrigger() {
            jdbcTemplate.execute(
                """
                CREATE OR REPLACE FUNCTION test_reject_customer_order_expiry_audit()
                RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'injected expiry audit failure'; END;
                ${'$'}${'$'}
                """.trimIndent(),
            )
            jdbcTemplate.execute(
                "CREATE TRIGGER test_customer_order_expiry_audit_failure BEFORE INSERT ON operations_audit_record " +
                    "FOR EACH ROW EXECUTE FUNCTION test_reject_customer_order_expiry_audit()",
            )
        }

        private fun dropAuditFailureTrigger() {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_customer_order_expiry_audit_failure ON operations_audit_record")
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_reject_customer_order_expiry_audit()")
        }
    }

@TestConfiguration(proxyBeanMethods = false)
internal class CustomerOrderQueryTestClockConfiguration {
    @Bean
    @Primary
    fun customerOrderQueryClock(): CustomerOrderQueryMutableClock = CustomerOrderQueryMutableClock(Instant.parse("2026-08-14T03:00:00Z"))
}

internal class CustomerOrderQueryMutableClock(
    initial: Instant,
) : Clock() {
    private val current = AtomicReference(initial)

    fun set(value: Instant) = current.set(value)

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)

    override fun instant(): Instant = current.get()
}

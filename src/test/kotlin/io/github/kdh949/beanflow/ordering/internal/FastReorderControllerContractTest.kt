package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.api.ReorderOrderCommand
import org.hamcrest.Matchers.matchesPattern
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@SpringBootTest
internal class FastReorderControllerContractTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val createOrder: CreateOrderUseCase,
    private val jdbcTemplate: JdbcTemplate,
) {
    @BeforeEach
    fun cleanDatabase() = OrderCreationDatabaseFixture.clean(jdbcTemplate)

    @Test
    fun `owning customer creates a pending reorder with required price comparison`() {
        val source = sourceOrder()
        jdbcTemplate.update("UPDATE merchant_menu SET base_price_krw = 1200 WHERE id = ?", source.fixture.menuId)

        mockMvc
            .perform(request(source, "reorder-contract-01"))
            .andExpect(status().isCreated)
            .andExpect(header().string("X-Correlation-Id", matchesPattern(".+")))
            .andExpect(jsonPath("$.order.state").value("PENDING_PAYMENT"))
            .andExpect(jsonPath("$.order.reservationExpiresAt").isString)
            .andExpect(jsonPath("$.priceComparison.hasPriceChanges").value(true))
            .andExpect(jsonPath("$.priceComparison.sourceSubtotalKrw").value(1_000))
            .andExpect(jsonPath("$.priceComparison.currentSubtotalKrw").value(1_200))
            .andExpect(jsonPath("$.priceComparison.subtotalDifferenceKrw").value(200))
            .andExpect(jsonPath("$.priceComparison.items[0].sourceOrderLineId").isString)
            .andExpect(jsonPath("$.priceComparison.items[0].lineDifferenceKrw").value(200))
    }

    @Test
    fun `explicit points create the benefit only reorder response variant`() {
        val source = sourceOrder()
        OrderCreationDatabaseFixture.insertPoints(jdbcTemplate, source.fixture.customerId, 1_000)

        mockMvc
            .perform(request(source, "reorder-benefit-contract", pointsToUseKrw = 1_000))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.order.state").value("PAID"))
            .andExpect(jsonPath("$.order.reservationExpiresAt").doesNotExist())
            .andExpect(jsonPath("$.payment.type").value("BENEFIT_ONLY"))
            .andExpect(jsonPath("$.payment.approvalState").value("APPROVED"))
            .andExpect(jsonPath("$.priceComparison.hasPriceChanges").value(false))
            .andExpect(jsonPath("$.priceComparison.items").isEmpty)
    }

    @Test
    fun `reorder requires authentication customer role and source ownership`() {
        val source = sourceOrder()

        mockMvc
            .perform(
                post("/api/v1/orders/{sourceOrderId}/reorders", source.orderId)
                    .header("Idempotency-Key", "reorder-auth-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(source.fixture.pickupSlotId)),
            ).andExpect(status().isUnauthorized)
        mockMvc
            .perform(
                request(source, "reorder-auth-0002", role = "ROLE_MERCHANT"),
            ).andExpect(status().isForbidden)
        mockMvc
            .perform(
                request(source, "reorder-auth-0003", customerId = UUID.randomUUID()),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            .andExpect(jsonPath("$.details").isEmpty)
    }

    @Test
    fun `missing non terminal and unavailable sources return stable errors`() {
        val fixture = OrderCreationFixture()
        mockMvc
            .perform(
                post("/api/v1/orders/{sourceOrderId}/reorders", UUID.randomUUID())
                    .with(customer(fixture.customerId))
                    .header("Idempotency-Key", "reorder-missing-http")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(fixture.pickupSlotId)),
            ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        val source = sourceOrder(terminal = false)
        mockMvc
            .perform(request(source, "reorder-state-http1"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("REORDER_SOURCE_STATE_INVALID"))

        jdbcTemplate.update("UPDATE ordering_order SET state = 'EXPIRED' WHERE id = ?", source.orderId)
        jdbcTemplate.update("UPDATE merchant_menu SET available = false WHERE id = ?", source.fixture.menuId)
        mockMvc
            .perform(request(source, "reorder-item-http01"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("REORDER_ITEMS_UNAVAILABLE"))
            .andExpect(jsonPath("$.details[0].sourceOrderLineId").isString)
            .andExpect(jsonPath("$.details[0].lineSequence").value(0))
            .andExpect(jsonPath("$.details[0].menuId").isString)
            .andExpect(jsonPath("$.details[0].optionId").doesNotExist())
            .andExpect(jsonPath("$.details[0].reason").value("MENU_NOT_AVAILABLE"))
    }

    @Test
    fun `request validation rejects additional property negative points and invalid key`() {
        val source = sourceOrder()

        mockMvc
            .perform(
                request(
                    source,
                    "reorder-extra-001",
                    requestBody =
                        """{"pickupSlotId":"${source.fixture.pickupSlotId}","pointsToUseKrw":0,"pastPrice":1000}""",
                ),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        mockMvc
            .perform(request(source, "reorder-negative1", pointsToUseKrw = -1))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        mockMvc
            .perform(request(source, "short"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
    }

    @Test
    fun `processing replay returns retry after without executing a new order`() {
        val source = sourceOrder()
        val key = "reorder-processing"
        val command = source.command()
        jdbcTemplate.update(
            """
            INSERT INTO ordering_idempotency_record (
                id, actor_id, operation, idempotency_key, payload_hash, status,
                intended_order_id, started_at
            ) VALUES (?, ?, 'REORDER_ORDER_V1', ?, ?, 'PROCESSING', ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            source.fixture.customerId,
            key,
            CanonicalReorderPayload.hash(command),
            UUID.randomUUID(),
            Timestamp.from(Instant.parse("2026-08-09T00:00:00Z")),
        )

        mockMvc
            .perform(request(source, key))
            .andExpect(status().isConflict)
            .andExpect(header().string("Retry-After", "2"))
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_REQUEST_IN_PROGRESS"))
        org.assertj.core.api.Assertions.assertThat(
            OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order"),
        ).isOne()
    }

    private fun sourceOrder(terminal: Boolean = true): SourceFixture {
        val fixture = OrderCreationFixture()
        OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
        check(createOrder.create("http-source-${UUID.randomUUID()}", fixture.command()).status == 201)
        val orderId = requireNotNull(jdbcTemplate.queryForObject("SELECT id FROM ordering_order", UUID::class.java))
        if (terminal) jdbcTemplate.update("UPDATE ordering_order SET state = 'EXPIRED' WHERE id = ?", orderId)
        return SourceFixture(fixture, orderId)
    }

    private fun request(
        source: SourceFixture,
        key: String,
        customerId: UUID = source.fixture.customerId,
        role: String = "ROLE_CUSTOMER",
        pointsToUseKrw: Long = 0,
        requestBody: String = body(source.fixture.pickupSlotId, pointsToUseKrw),
    ) = post("/api/v1/orders/{sourceOrderId}/reorders", source.orderId)
        .with(customer(customerId, role))
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content(requestBody)

    private fun customer(
        customerId: UUID,
        role: String = "ROLE_CUSTOMER",
    ) = jwt().jwt { it.subject(customerId.toString()) }.authorities(SimpleGrantedAuthority(role))

    private fun body(
        pickupSlotId: UUID,
        pointsToUseKrw: Long = 0,
    ): String = """{"pickupSlotId":"$pickupSlotId","pointsToUseKrw":$pointsToUseKrw}"""

    private data class SourceFixture(
        val fixture: OrderCreationFixture,
        val orderId: UUID,
    ) {
        fun command(): ReorderOrderCommand =
            ReorderOrderCommand(
                customerId = fixture.customerId,
                sourceOrderId = orderId,
                pickupSlotId = fixture.pickupSlotId,
                couponIssuanceId = null,
                pointsToUseKrw = 0,
            )
    }
}

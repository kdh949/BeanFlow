package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * The actor-scoped reorder facade takes the public order reference instead of an
 * internal source order UUID. It must resolve ownership itself and still reuse
 * the existing fast reorder revalidation rather than reimplementing it.
 */
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("invokes REQUIRES_NEW order idempotency registration")
@SpringBootTest
internal class PublicReferenceReorderContractTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val createOrder: CreateOrderUseCase,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        @BeforeEach
        fun cleanDatabase() = OrderCreationDatabaseFixture.clean(jdbcTemplate)

        @Test
        fun `owning customer reorders by public reference and gets the current price comparison`() {
            val source = sourceOrder()
            jdbcTemplate.update("UPDATE merchant_menu SET base_price_krw = 1200 WHERE id = ?", source.fixture.menuId)

            mockMvc
                .perform(request(source, "public-reorder-001"))
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.order.state").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.priceComparison.hasPriceChanges").value(true))
                .andExpect(jsonPath("$.priceComparison.currentSubtotalKrw").value(1_200))
        }

        @Test
        fun `another customer reference is denied and an unknown reference is not found`() {
            val source = sourceOrder()

            mockMvc
                .perform(request(source, "public-reorder-002", customerId = UUID.randomUUID()))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

            mockMvc
                .perform(
                    post("/api/v1/me/orders/{orderReference}/reorders", "BF-2345-6789")
                        .with(csrf())
                        .with(customer(source.fixture.customerId))
                        .header("Idempotency-Key", "public-reorder-003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(source.fixture.pickupSlotId)),
                ).andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        }

        @Test
        fun `current availability is revalidated and no partial order is created`() {
            val source = sourceOrder()
            jdbcTemplate.update("UPDATE merchant_menu SET available = false WHERE id = ?", source.fixture.menuId)

            mockMvc
                .perform(request(source, "public-reorder-004"))
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("REORDER_ITEMS_UNAVAILABLE"))
                .andExpect(jsonPath("$.details[0].reason").value("MENU_NOT_AVAILABLE"))

            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isOne()
        }

        @Test
        fun `the request body carries no source identifier or price`() {
            val source = sourceOrder()

            mockMvc
                .perform(
                    request(
                        source,
                        "public-reorder-005",
                        requestBody =
                            """{"pickupSlotId":"${source.fixture.pickupSlotId}","pointsToUseKrw":0,"sourceOrderId":"${UUID.randomUUID()}"}""",
                    ),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }

        private fun sourceOrder(): SourceFixture {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            check(createOrder.create("public-source-${UUID.randomUUID()}", fixture.command()).status == 201)
            val reference =
                requireNotNull(
                    jdbcTemplate.queryForObject("SELECT public_reference FROM ordering_order", String::class.java),
                )
            jdbcTemplate.update("UPDATE ordering_order SET state = 'EXPIRED'")
            return SourceFixture(fixture, reference)
        }

        private fun request(
            source: SourceFixture,
            key: String,
            customerId: UUID = source.fixture.customerId,
            requestBody: String = body(source.fixture.pickupSlotId),
        ) = post("/api/v1/me/orders/{orderReference}/reorders", source.reference)
            .with(csrf())
            .with(customer(customerId))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody)

        private fun customer(customerId: UUID) =
            jwt().jwt { it.subject(customerId.toString()) }.authorities(SimpleGrantedAuthority("ROLE_CUSTOMER"))

        private fun body(pickupSlotId: UUID): String = """{"pickupSlotId":"$pickupSlotId","pointsToUseKrw":0}"""

        private data class SourceFixture(
            val fixture: OrderCreationFixture,
            val reference: String,
        )
    }

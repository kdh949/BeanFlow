package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@SpringBootTest
internal class OrderControllerContractTest @Autowired constructor(
	private val mockMvc: MockMvc,
	private val jdbcTemplate: JdbcTemplate,
) {

	@BeforeEach
	fun cleanDatabase() = OrderCreationDatabaseFixture.clean(jdbcTemplate)

	@Test
	fun `customer creates a pending payment order matching the OpenAPI shape`() {
		val fixture = OrderCreationFixture()
		OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)

		mockMvc.perform(
			post("/api/v1/orders")
				.with(
					jwt()
						.jwt { it.subject(fixture.customerId.toString()) }
						.authorities(SimpleGrantedAuthority("ROLE_CUSTOMER")),
				)
				.header("Idempotency-Key", "contract-key-001")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody(fixture)),
		)
			.andExpect(status().isCreated)
			.andExpect(header().string("X-Correlation-Id", matchesPattern(".+")))
			.andExpect(jsonPath("$.order.state").value("PENDING_PAYMENT"))
			.andExpect(jsonPath("$.order.reservationExpiresAt").isString)
			.andExpect(jsonPath("$.order.payableKrw").value(1_000))
			.andExpect(jsonPath("$.order.currency").value("KRW"))
			.andExpect(jsonPath("$.order.lines[0].cashPaidKrw").value(1_000))
	}

	@Test
	fun `customer creates a benefit only paid order matching the OpenAPI shape`() {
		val fixture = OrderCreationFixture()
		OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
		OrderCreationDatabaseFixture.insertPoints(jdbcTemplate, fixture.customerId, 1_000)

		mockMvc.perform(
			post("/api/v1/orders")
				.with(
					jwt()
						.jwt { it.subject(fixture.customerId.toString()) }
						.authorities(SimpleGrantedAuthority("ROLE_CUSTOMER")),
				)
				.header("Idempotency-Key", "contract-benefit-01")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody(fixture, pointsToUseKrw = 1_000)),
		)
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.order.state").value("PAID"))
			.andExpect(jsonPath("$.order.reservationExpiresAt").doesNotExist())
			.andExpect(jsonPath("$.order.payableKrw").value(0))
			.andExpect(jsonPath("$.payment.type").value("BENEFIT_ONLY"))
			.andExpect(jsonPath("$.payment.approvalState").value("APPROVED"))
			.andExpect(jsonPath("$.payment.approvedAmountKrw").value(0))
	}

	@Test
	fun `missing authentication returns the stable error envelope`() {
		val fixture = OrderCreationFixture()

		mockMvc.perform(
			post("/api/v1/orders")
				.header("Idempotency-Key", "contract-key-002")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody(fixture)),
		)
			.andExpect(status().isUnauthorized)
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.correlationId").isNotEmpty)
			.andExpect(jsonPath("$.details").isArray)
	}

	@Test
	fun `non customer role is forbidden`() {
		val fixture = OrderCreationFixture()

		mockMvc.perform(
			post("/api/v1/orders")
				.with(
					jwt()
						.jwt { it.subject(fixture.customerId.toString()) }
						.authorities(SimpleGrantedAuthority("ROLE_MERCHANT")),
				)
				.header("Idempotency-Key", "contract-key-003")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody(fixture)),
		)
			.andExpect(status().isForbidden)
			.andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
	}

	@Test
	fun `resource contention returns its stable 409 code`() {
		val fixture = OrderCreationFixture()
		OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, stockAvailable = 0)

		mockMvc.perform(
			post("/api/v1/orders")
				.with(
					jwt()
						.jwt { it.subject(fixture.customerId.toString()) }
						.authorities(SimpleGrantedAuthority("ROLE_CUSTOMER")),
				)
				.header("Idempotency-Key", "contract-key-004")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody(fixture)),
		)
			.andExpect(status().isConflict)
			.andExpect(jsonPath("$.code").value("STOCK_NOT_AVAILABLE"))
			.andExpect(jsonPath("$.correlationId").isNotEmpty)
	}

	@Test
	fun `get materializes a due order before returning it`() {
		val fixture = OrderCreationFixture()
		OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
		createThroughHttp(fixture, "contract-get-001")
		val orderId = requireNotNull(jdbcTemplate.queryForObject("SELECT id FROM ordering_order", UUID::class.java))
		makeDue(orderId)

		mockMvc.perform(
			get("/api/v1/orders/{orderId}", orderId)
				.with(
					jwt()
						.jwt { it.subject(fixture.customerId.toString()) }
						.authorities(SimpleGrantedAuthority("ROLE_CUSTOMER")),
				),
		)
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.state").value("EXPIRED"))
	}

	@Test
	fun `get verifies ownership before materializing expiry`() {
		val fixture = OrderCreationFixture()
		OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
		createThroughHttp(fixture, "contract-get-002")
		val orderId = requireNotNull(jdbcTemplate.queryForObject("SELECT id FROM ordering_order", UUID::class.java))
		makeDue(orderId)

		mockMvc.perform(
			get("/api/v1/orders/{orderId}", orderId)
				.with(
					jwt()
						.jwt { it.subject(UUID.randomUUID().toString()) }
						.authorities(SimpleGrantedAuthority("ROLE_CUSTOMER")),
				),
		)
			.andExpect(status().isForbidden)
			.andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
		org.assertj.core.api.Assertions.assertThat(
			jdbcTemplate.queryForObject(
				"SELECT state FROM ordering_order WHERE id = ?",
				String::class.java,
				orderId,
			),
		).isEqualTo("PENDING_PAYMENT")
	}

	@Test
	fun `OpenAPI keeps the state specific create response variants`() {
		val openApi = Files.readString(Path.of("openapi/beanflow-v1.yaml"))

		org.assertj.core.api.Assertions.assertThat(openApi)
			.contains("CreateOrderResult:")
			.contains("PendingPaymentOrderCreation")
			.contains("BenefitOnlyOrderCreation")
			.contains("required: [reservationExpiresAt]")
			.contains("IDEMPOTENCY_REQUEST_IN_PROGRESS")
	}

	private fun requestBody(fixture: OrderCreationFixture, pointsToUseKrw: Long = 0): String =
		"""
		{
		  "storeId": "${fixture.storeId}",
		  "pickupSlotId": "${fixture.pickupSlotId}",
		  "lines": [
		    {
		      "menuId": "${fixture.menuId}",
		      "optionIds": [],
		      "quantity": 1
		    }
		  ],
		  "pointsToUseKrw": $pointsToUseKrw
		}
		""".trimIndent()

	private fun createThroughHttp(fixture: OrderCreationFixture, key: String) {
		mockMvc.perform(
			post("/api/v1/orders")
				.with(
					jwt()
						.jwt { it.subject(fixture.customerId.toString()) }
						.authorities(SimpleGrantedAuthority("ROLE_CUSTOMER")),
				)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody(fixture)),
		).andExpect(status().isCreated)
	}

	private fun makeDue(orderId: UUID) {
		val dueAt = Timestamp.from(Instant.now().minusSeconds(1))
		jdbcTemplate.update("UPDATE ordering_order SET reservation_expires_at = ? WHERE id = ?", dueAt, orderId)
		jdbcTemplate.update("UPDATE fulfillment_pickup_reservation SET expires_at = ? WHERE order_id = ?", dueAt, orderId)
		jdbcTemplate.update("UPDATE inventory_stock_reservation SET expires_at = ? WHERE order_id = ?", dueAt, orderId)
	}
}

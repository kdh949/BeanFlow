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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.nio.file.Path

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
	fun `OpenAPI keeps the state specific create response variants`() {
		val openApi = Files.readString(Path.of("openapi/beanflow-v1.yaml"))

		org.assertj.core.api.Assertions.assertThat(openApi)
			.contains("CreateOrderResult:")
			.contains("PendingPaymentOrderCreation")
			.contains("BenefitOnlyOrderCreation")
			.contains("required: [reservationExpiresAt]")
			.contains("IDEMPOTENCY_REQUEST_IN_PROGRESS")
	}

	private fun requestBody(fixture: OrderCreationFixture): String =
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
		  "pointsToUseKrw": 0
		}
		""".trimIndent()
}

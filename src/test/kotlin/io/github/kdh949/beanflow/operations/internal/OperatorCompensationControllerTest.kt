package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicyOperations
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationTrigger
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitType
import io.github.kdh949.beanflow.operations.api.OpenOrderCompensationCaseCommand
import io.github.kdh949.beanflow.operations.api.OrderCompensationOperations
import io.github.kdh949.beanflow.operations.api.OrderCompensationTrigger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@SpringBootTest
internal class OperatorCompensationControllerTest
    @Autowired
    constructor(
        private val compensationOperations: OrderCompensationOperations,
        private val policies: ExpiredBenefitRestorationPolicyOperations,
        private val jdbcTemplate: JdbcTemplate,
        private val mockMvc: MockMvc,
    ) {
        private lateinit var orderId: UUID
        private lateinit var actorId: UUID

        @BeforeEach
        fun setUp() {
            jdbcTemplate.execute(
                "TRUNCATE TABLE operations_audit_record, operations_operator_permission_grant, " +
                    "operations_order_compensation_case CASCADE",
            )
            orderId = UUID.randomUUID()
            actorId = UUID.randomUUID()
            val couponPolicy =
                policies.current(ExpiredBenefitRestorationTrigger.STORE_REJECTION, ExpiredBenefitType.COUPON)
            val pointsPolicy =
                policies.current(ExpiredBenefitRestorationTrigger.STORE_REJECTION, ExpiredBenefitType.POINTS)
            compensationOperations.open(
                OpenOrderCompensationCaseCommand(
                    caseId = UUID.randomUUID(),
                    eventId = UUID.randomUUID(),
                    orderId = orderId,
                    terminalOrderVersion = 3,
                    customerId = UUID.randomUUID(),
                    storeId = UUID.randomUUID(),
                    trigger = OrderCompensationTrigger.STORE_REJECTION,
                    sourceReference = "order:$orderId:store-rejection:3",
                    couponPolicy = couponPolicy,
                    pointsPolicy = pointsPolicy,
                    paymentRequired = true,
                    couponRequired = false,
                    pointsRequired = true,
                    correlationId = "operator-compensation-$orderId",
                    now = NOW,
                ),
            )
        }

        @Test
        fun `active permission returns only operator compensation shape and commits access audit`() {
            grant()

            mockMvc
                .perform(
                    get("/api/v1/operations/orders/{orderId}/compensation", orderId)
                        .with(operatorJwt())
                        .header("X-Access-Reason", " Support investigation "),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.compensation.caseId").isString)
                .andExpect(jsonPath("$.compensation.trigger").value("STORE_REJECTION"))
                .andExpect(jsonPath("$.compensation.benefitPolicies.length()").value(2))
                .andExpect(jsonPath("$.compensation.steps.length()").value(6))
                .andExpect(jsonPath("$.compensation.orderId").doesNotExist())
                .andExpect(jsonPath("$.compensation.terminalOrderVersion").doesNotExist())
                .andExpect(jsonPath("$.compensation.benefitPolicies[0].mode").doesNotExist())
                .andExpect(jsonPath("$.compensation.benefitPolicies[0].compensationValidityDays").doesNotExist())

            val audit =
                jdbcTemplate.queryForMap(
                    "SELECT action, reason, target_type FROM operations_audit_record " +
                        "WHERE action = 'ORDER_COMPENSATION_READ'",
                )
            assertThat(audit["action"]).isEqualTo("ORDER_COMPENSATION_READ")
            assertThat(audit["reason"]).isEqualTo("Support investigation")
            assertThat(audit["target_type"]).isEqualTo("ORDER_COMPENSATION_CASE")
        }

        @Test
        fun `role only missing reason and non-operator requests are denied`() {
            mockMvc
                .perform(
                    get("/api/v1/operations/orders/{orderId}/compensation", orderId)
                        .with(operatorJwt())
                        .header("X-Access-Reason", "Support"),
                ).andExpect(status().isForbidden)

            grant()
            mockMvc
                .perform(
                    get("/api/v1/operations/orders/{orderId}/compensation", orderId)
                        .with(operatorJwt()),
                ).andExpect(status().isBadRequest)
            mockMvc
                .perform(
                    get("/api/v1/operations/orders/{orderId}/compensation", orderId)
                        .with(customerJwt())
                        .header("X-Access-Reason", "Support"),
                ).andExpect(status().isForbidden)
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM operations_audit_record WHERE action = 'ORDER_COMPENSATION_READ'",
                    Long::class.java,
                ),
            ).isZero()
        }

        private fun grant() {
            jdbcTemplate.update(
                """
                INSERT INTO operations_operator_permission_grant (
                    actor_id, permission, state, granted_at, revoked_at, version, audit_source_reference
                ) VALUES (?, 'ORDER_COMPENSATION_READ', 'ACTIVE', ?, NULL, 1, ?)
                """.trimIndent(),
                actorId,
                Timestamp.from(NOW),
                "test-order-compensation-grant:$actorId",
            )
        }

        private fun operatorJwt() =
            jwt()
                .jwt {
                    it
                        .subject(actorId.toString())
                        .claim("roles", listOf("PLATFORM_OPERATOR"))
                }.authorities(SimpleGrantedAuthority("ROLE_PLATFORM_OPERATOR"))

        private fun customerJwt() =
            jwt()
                .jwt {
                    it
                        .subject(actorId.toString())
                        .claim("roles", listOf("CUSTOMER"))
                }.authorities(SimpleGrantedAuthority("ROLE_CUSTOMER"))

        private companion object {
            val NOW: Instant = Instant.parse("2026-08-03T00:00:00Z")
        }
    }

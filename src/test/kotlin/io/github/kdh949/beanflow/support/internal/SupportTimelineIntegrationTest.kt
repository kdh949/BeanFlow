package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.internal.OrderCreationDatabaseFixture
import io.github.kdh949.beanflow.ordering.internal.OrderCreationFixture
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
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
internal class SupportTimelineIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val createOrder: CreateOrderUseCase,
        private val authorization: SupportTimelineAuthorization,
    ) {
        private val actorId = UUID.fromString("51000000-0000-0000-0000-000000000001")
        private lateinit var orderId: UUID
        private lateinit var caseId: UUID

        @BeforeEach
        fun resetAndSeed() {
            OrderCreationDatabaseFixture.clean(jdbcTemplate)
            jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    support_case_command_idempotency,
                    support_case_subject_link,
                    support_case_note,
                    support_case_interaction,
                    support_case_state_history,
                    support_case_assignment_history,
                    support_case,
                    operations_operator_permission_grant
                CASCADE
                """.trimIndent(),
            )
            orderId = createOrder()
            grant("SUPPORT_CASE_WRITE")
            grant("SUPPORT_CASE_READ")
            caseId = createCase()
            linkOrder(caseId, orderId)
        }

        @Test
        fun `case timeline completes a stable global cursor without duplicates`() {
            val itemIds = mutableListOf<String>()
            var cursor: String? = null
            do {
                val request =
                    get("/api/v1/support/cases/$caseId/timeline")
                        .with(jwt().jwt { it.subject(actorId.toString()) })
                        .param("limit", "2")
                cursor?.let { request.param("cursor", it) }
                val response =
                    mockMvc
                        .perform(request)
                        .andExpect(status().isOk)
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn()
                val body = json(response.response.contentAsString)
                itemIds +=
                    com.jayway.jsonpath.JsonPath.read<List<String>>(
                        response.response.contentAsString,
                        "$.items[*].itemId",
                    )
                cursor = body.get("nextCursor")?.takeUnless { it.isNull }?.asText()
            } while (cursor != null)

            assertThat(itemIds.size).isGreaterThanOrEqualTo(3)
            assertThat(itemIds).doesNotHaveDuplicates()
        }

        @Test
        fun `cursor is bound to filters and type filter returns only its closed facts`() {
            val first =
                json(
                    mockMvc
                        .perform(
                            get("/api/v1/support/cases/$caseId/timeline")
                                .with(jwt().jwt { it.subject(actorId.toString()) })
                                .param("limit", "1"),
                        ).andExpect(status().isOk)
                        .andReturn()
                        .response.contentAsString,
                )
            val cursor = first.get("nextCursor").asText()

            mockMvc
                .perform(
                    get("/api/v1/support/cases/$caseId/timeline")
                        .with(jwt().jwt { it.subject(actorId.toString()) })
                        .param("limit", "1")
                        .param("sources", "ORDERING")
                        .param("cursor", cursor),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

            mockMvc
                .perform(
                    get("/api/v1/support/cases/$caseId/timeline")
                        .with(jwt().jwt { it.subject(actorId.toString()) })
                        .param("types", "ORDER_STATE"),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.items[*].type").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.equalTo("ORDER_STATE"))))
        }

        @Test
        fun `order timeline requires both persistent permissions and an active case link`() {
            mockMvc
                .perform(
                    get("/api/v1/support/orders/$orderId/timeline")
                        .with(jwt().jwt { it.subject(actorId.toString()) })
                        .param("caseId", caseId.toString()),
                ).andExpect(status().isForbidden)

            grant("SUPPORT_ORDER_READ")
            mockMvc
                .perform(
                    get("/api/v1/support/orders/$orderId/timeline")
                        .with(jwt().jwt { it.subject(actorId.toString()) })
                        .param("caseId", caseId.toString()),
                ).andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].source").exists())

            mockMvc
                .perform(
                    get("/api/v1/support/orders/$orderId/timeline")
                        .with(jwt().jwt { it.subject(actorId.toString()) })
                        .param("caseId", caseId.toString())
                        .param("sources", "SUPPORT"),
                ).andExpect(status().isBadRequest)

            mockMvc
                .perform(
                    get("/api/v1/support/orders/${UUID.randomUUID()}/timeline")
                        .with(jwt().jwt { it.subject(actorId.toString()) })
                        .param("caseId", caseId.toString()),
                ).andExpect(status().isForbidden)
        }

        @Test
        fun `timeline denies an operator who is not the current case assignee`() {
            val unrelatedActor = UUID.fromString("51000000-0000-0000-0000-000000000002")
            grant(unrelatedActor, "SUPPORT_CASE_READ")
            grant(unrelatedActor, "SUPPORT_ORDER_READ")

            mockMvc
                .perform(
                    get("/api/v1/support/cases/$caseId/timeline")
                        .with(jwt().jwt { it.subject(unrelatedActor.toString()) }),
                ).andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            mockMvc
                .perform(
                    get("/api/v1/support/orders/$orderId/timeline")
                        .with(jwt().jwt { it.subject(unrelatedActor.toString()) })
                        .param("caseId", caseId.toString()),
                ).andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
        }

        @Test
        fun `timeline denies a terminal case even to its assignee`() {
            jdbcTemplate.update("UPDATE support_case SET state = 'RESOLVED', version = version + 1 WHERE id = ?", caseId)

            mockMvc
                .perform(
                    get("/api/v1/support/cases/$caseId/timeline")
                        .with(jwt().jwt { it.subject(actorId.toString()) }),
                ).andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
        }

        @Test
        fun `case timeline recheck rejects reassignment after owner facts were selected`() {
            val replacementActor = UUID.fromString("51000000-0000-0000-0000-000000000003")
            val scope = authorization.authorizeCase(actorId, caseId)
            jdbcTemplate.update(
                "UPDATE support_case SET current_assignee_id = ?, version = version + 1 WHERE id = ?",
                replacementActor,
                caseId,
            )

            assertAccessDenied { authorization.recheckCase(actorId, scope) }
        }

        @Test
        fun `order timeline recheck rejects link removal after owner facts were selected`() {
            grant("SUPPORT_ORDER_READ")
            val scope = authorization.authorizeOrder(actorId, caseId, orderId)
            jdbcTemplate.update(
                "UPDATE support_case_subject_link SET unlinked_at = linked_at + interval '1 microsecond', unlinked_by_actor_id = ?, unlink_reason = 'TIMELINE_RECHECK', unlink_case_version = 1 WHERE support_case_id = ? AND subject_id = ?",
                actorId,
                caseId,
                orderId,
            )

            assertAccessDenied { authorization.recheckOrder(actorId, scope) }
        }

        private fun createOrder(): UUID {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val response = createOrder.create("support-timeline-api-order-0001", fixture.command())
            assertThat(response.status).isEqualTo(201)
            return UUID.fromString(requireNotNull(Regex("\\\"orderId\\\":\\\"([^\\\"]+)\\\"").find(response.body)).groupValues[1])
        }

        private fun createCase(): UUID {
            val response =
                mockMvc
                    .perform(
                        post("/api/v1/support/cases")
                            .with(jwt().jwt { it.subject(actorId.toString()) })
                            .header("Idempotency-Key", "support-timeline-case-0001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                """
                                {"requesterType":"CUSTOMER","requesterReference":"subject-ref","category":"ORDER_STATUS","priority":"NORMAL","reason":"ORDER_STATUS_TIMELINE"}
                                """.trimIndent(),
                            ),
                    ).andExpect(status().isCreated)
                    .andReturn()
            return UUID.fromString(json(response.response.contentAsString).get("caseId").asText())
        }

        private fun linkOrder(
            caseId: UUID,
            orderId: UUID,
        ) {
            mockMvc
                .perform(
                    post("/api/v1/support/cases/$caseId/subject-links")
                        .with(jwt().jwt { it.subject(actorId.toString()) })
                        .header("Idempotency-Key", "support-timeline-link-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"subjectType":"ORDER","subjectId":"$orderId","relationship":"RELATED_ORDER","reason":"ORDER_TIMELINE_SCOPE"}
                            """.trimIndent(),
                        ),
                ).andExpect(status().isOk)
        }

        private fun grant(permission: String) {
            grant(actorId, permission)
        }

        private fun grant(
            actor: UUID,
            permission: String,
        ) {
            jdbcTemplate.update(
                """
                INSERT INTO operations_operator_permission_grant (
                    actor_id, permission, state, granted_at, version, audit_source_reference
                ) VALUES (?, ?, 'ACTIVE', now(), 1, ?)
                """.trimIndent(),
                actor,
                permission,
                "support-timeline-grant:$permission:$actor",
            )
        }

        private fun assertAccessDenied(block: () -> Unit) {
            assertThatThrownBy(block)
                .isInstanceOfSatisfying(DomainFailure::class.java) { failure ->
                    assertThat(failure.code).isEqualTo(FailureCode.ACCESS_DENIED)
                }
        }

        private fun json(body: String): JsonNode = JsonMapper.builder().build().readTree(body)
    }

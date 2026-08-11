package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class SupportCaseIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        private val actorId = UUID.fromString("20000000-0000-0000-0000-000000000020")
        private val otherActorId = UUID.fromString("20000000-0000-0000-0000-000000000021")

        @BeforeEach
        fun resetSupportState() {
            removeAuditFailureTrigger()
            jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    support_case_command_idempotency,
                    support_case_subject_link,
                    support_case_note,
                    support_case_interaction,
                    support_case_state_history,
                    support_case_assignment_history,
                    support_case
                CASCADE
                """.trimIndent(),
            )
            jdbcTemplate.execute("TRUNCATE TABLE operations_audit_record, operations_operator_permission_grant")
        }

        @AfterEach
        fun removeAuditFailureTriggerAfterTest() {
            removeAuditFailureTrigger()
        }

        @Test
        fun `persistent grants and current assignment protect SupportCase actions`() {
            val body = createBody()
            mockMvc
                .perform(post(BASE).with(operatorJwt(actorId)).header("Idempotency-Key", "support-create-0001").json(body))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

            grant(actorId, "SUPPORT_CASE_WRITE")
            mockMvc
                .perform(
                    post(BASE)
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "support-unexpected-field-0001")
                        .json(createBody().removeSuffix("}") + ",\"unexpected\":true}"),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            assertThat(countAll("support_case")).isZero()
            val created = createCase("support-create-0001")
            grant(otherActorId, "SUPPORT_CASE_WRITE")
            mockMvc
                .perform(
                    post("$BASE/${created.caseId}/notes")
                        .with(operatorJwt(otherActorId))
                        .header("Idempotency-Key", "support-other-note-0001")
                        .json("""{"content":"INTERNAL_NOTE_RECORDED","reason":"CASE_REVIEW"}"""),
                ).andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

            mockMvc
                .perform(get(BASE).with(operatorJwt(actorId)))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            grant(actorId, "SUPPORT_CASE_READ")
            mockMvc
                .perform(get(BASE).with(operatorJwt(actorId)))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
        }

        @Test
        fun `case lifecycle appends durable histories and rejects all closed case work`() {
            grant(actorId, "SUPPORT_CASE_WRITE")
            val created = createCase("support-lifecycle-create-0001")
            val replay = createCase("support-lifecycle-create-0001")
            assertThat(replay.caseId).isEqualTo(created.caseId)
            mockMvc
                .perform(
                    post(BASE)
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "support-lifecycle-create-0001")
                        .json(createBody(externalReference = "case-ref-002")),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
            assertThat(
                count("SELECT count(*) FROM support_case WHERE id = ?", created.caseId),
            ).isOne()

            mockMvc
                .perform(
                    post("$BASE/${created.caseId}/assignments")
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "support-assign-0001")
                        .json("""{"assigneeId":"$actorId","expectedVersion":0,"reason":"QUEUE_ASSIGNMENT"}"""),
                ).andExpect(status().isForbidden)
            grant(actorId, "SUPPORT_CASE_ASSIGN")
            mockMvc
                .perform(
                    post("$BASE/${created.caseId}/assignments")
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "support-assign-0001")
                        .json("""{"assigneeId":"$actorId","expectedVersion":0,"reason":"QUEUE_ASSIGNMENT"}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.caseVersion").value(1))

            transition(created.caseId, "IN_PROGRESS", 1, "support-transition-0001", 2)
            mockMvc
                .perform(
                    post("$BASE/${created.caseId}/interactions")
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "support-interaction-0001")
                        .json(
                            """
                            {"channel":"CHAT","direction":"INBOUND","occurredAt":"2026-08-11T00:00:00Z","redactedSummary":"CUSTOMER_CONTACTED_US"}
                            """.trimIndent(),
                        ),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.summary").value("INTERACTION_RECORDED"))
                .andExpect(jsonPath("$.caseVersion").value(3))
            mockMvc
                .perform(
                    post("$BASE/${created.caseId}/notes")
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "support-note-0001")
                        .json("""{"content":"INTERNAL_NOTE_RECORDED","reason":"CASE_REVIEW"}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.summary").value("NOTE_RECORDED"))
                .andExpect(jsonPath("$.caseVersion").value(4))
            mockMvc
                .perform(
                    post("$BASE/${created.caseId}/notes")
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "support-unsafe-note-0001")
                        .json("""{"content":"password=synthetic-secret","reason":"CASE_REVIEW"}"""),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            assertThat(count("SELECT count(*) FROM support_case_note WHERE support_case_id = ?", created.caseId)).isOne()

            val linked =
                mockMvc
                    .perform(
                        post("$BASE/${created.caseId}/subject-links")
                            .with(operatorJwt(actorId))
                            .header("Idempotency-Key", "support-link-0001")
                            .json(
                                """{"subjectType":"ORDER","subjectId":"30000000-0000-0000-0000-000000000001","relationship":"RELATED_ORDER","reason":"ORDER_CONTEXT"}""",
                            ),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.caseVersion").value(5))
                    .andReturn()
            val linkId = json(linked.response.contentAsString)["linkId"].asText()
            mockMvc
                .perform(
                    delete("$BASE/${created.caseId}/subject-links/$linkId")
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "support-unlink-0001")
                        .json("""{"expectedVersion":5,"reason":"NO_LONGER_RELEVANT"}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.caseVersion").value(6))

            transition(created.caseId, "RESOLVED", 6, "support-transition-0002", 7)
            transition(created.caseId, "CLOSED", 7, "support-transition-0003", 8)
            mockMvc
                .perform(
                    post("$BASE/${created.caseId}/notes")
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "support-closed-note-0001")
                        .json("""{"content":"INTERNAL_NOTE_RECORDED","reason":"CASE_REVIEW"}"""),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"))

            assertThat(count("SELECT count(*) FROM support_case_assignment_history WHERE support_case_id = ?", created.caseId)).isEqualTo(2)
            assertThat(count("SELECT count(*) FROM support_case_state_history WHERE support_case_id = ?", created.caseId)).isEqualTo(4)
            assertThat(count("SELECT count(*) FROM operations_audit_record WHERE target_id = ?", created.caseId)).isEqualTo(9)
            assertThat(
                count(
                    "SELECT count(*) FROM operations_audit_record WHERE target_id = ? AND after_summary LIKE '%INTERNAL_NOTE_RECORDED%'",
                    created.caseId,
                ),
            ).isZero()
        }

        @Test
        fun `concurrent duplicate delivery and stale assignment have one durable winner`() {
            grant(actorId, "SUPPORT_CASE_WRITE")
            val createResults =
                concurrently(
                    {
                        mockMvc
                            .perform(
                                post(BASE)
                                    .with(operatorJwt(actorId))
                                    .header("Idempotency-Key", "support-concurrent-create-0001")
                                    .json(createBody()),
                            ).andReturn()
                    },
                    {
                        mockMvc
                            .perform(
                                post(BASE)
                                    .with(operatorJwt(actorId))
                                    .header("Idempotency-Key", "support-concurrent-create-0001")
                                    .json(createBody()),
                            ).andReturn()
                    },
                )

            assertThat(createResults.map { it.response.status }).containsOnly(201)
            val caseIds = createResults.map { UUID.fromString(json(it.response.contentAsString)["caseId"].asText()) }
            assertThat(caseIds).containsOnly(caseIds.first())
            val caseId = caseIds.first()
            assertThat(count("SELECT count(*) FROM support_case WHERE id = ?", caseId)).isOne()

            grant(actorId, "SUPPORT_CASE_ASSIGN")
            val assignmentResults =
                concurrently(
                    {
                        mockMvc
                            .perform(
                                post("$BASE/$caseId/assignments")
                                    .with(operatorJwt(actorId))
                                    .header("Idempotency-Key", "support-concurrent-assign-0001")
                                    .json("""{"assigneeId":"$actorId","expectedVersion":0,"reason":"QUEUE_ASSIGNMENT"}"""),
                            ).andReturn()
                    },
                    {
                        mockMvc
                            .perform(
                                post("$BASE/$caseId/assignments")
                                    .with(operatorJwt(actorId))
                                    .header("Idempotency-Key", "support-concurrent-assign-0002")
                                    .json("""{"assigneeId":"$actorId","expectedVersion":0,"reason":"QUEUE_ASSIGNMENT"}"""),
                            ).andReturn()
                    },
                )

            assertThat(assignmentResults.map { it.response.status }).containsExactlyInAnyOrder(200, 409)
            assertThat(count("SELECT count(*) FROM support_case_assignment_history WHERE support_case_id = ?", caseId)).isEqualTo(2)
            assertThat(count("SELECT version FROM support_case WHERE id = ?", caseId)).isOne()
        }

        @Test
        fun `audit persistence failure rolls back case and idempotency writes`() {
            grant(actorId, "SUPPORT_CASE_WRITE")
            jdbcTemplate.execute(
                """
                CREATE FUNCTION reject_support_case_test_audit()
                RETURNS trigger
                LANGUAGE plpgsql
                AS ${'$'}${'$'}
                BEGIN
                    RAISE EXCEPTION 'support audit unavailable';
                END;
                ${'$'}${'$'};
                """.trimIndent(),
            )
            jdbcTemplate.execute(
                """
                CREATE TRIGGER trg_reject_support_case_test_audit
                BEFORE INSERT ON operations_audit_record
                FOR EACH ROW EXECUTE FUNCTION reject_support_case_test_audit()
                """.trimIndent(),
            )

            mockMvc
                .perform(
                    post(BASE)
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "support-audit-failure-0001")
                        .json(createBody()),
                ).andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))

            assertThat(countAll("support_case")).isZero()
            assertThat(countAll("support_case_command_idempotency")).isZero()
            assertThat(countAll("operations_audit_record")).isZero()
        }

        @Test
        fun `support case list uses a filter-bound signed cursor without interaction collections`() {
            grant(actorId, "SUPPORT_CASE_WRITE")
            grant(actorId, "SUPPORT_CASE_READ")
            createCase("support-page-create-0001")
            createCase("support-page-create-0002")

            val first =
                mockMvc
                    .perform(get(BASE).param("limit", "1").with(operatorJwt(actorId)))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.nextCursor").isString)
                    .andReturn()
            val firstJson = json(first.response.contentAsString)
            val cursor = firstJson["nextCursor"].asText()
            val firstId = firstJson["items"][0]["caseId"].asText()
            mockMvc
                .perform(get(BASE).param("limit", "1").param("cursor", cursor).with(operatorJwt(actorId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items[0].caseId").value(org.hamcrest.Matchers.not(firstId)))
            mockMvc
                .perform(get(BASE).param("state", "CLOSED").param("cursor", cursor).with(operatorJwt(actorId)))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            val tamperedCursor = cursor.dropLast(1) + if (cursor.last() == 'A') "B" else "A"
            mockMvc
                .perform(get(BASE).param("cursor", tamperedCursor).with(operatorJwt(actorId)))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }

        private fun createCase(idempotencyKey: String): CreatedCase {
            val result =
                mockMvc
                    .perform(post(BASE).with(operatorJwt(actorId)).header("Idempotency-Key", idempotencyKey).json(createBody()))
                    .andExpect(status().isCreated)
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andReturn()
            val response = json(result.response.contentAsString)
            return CreatedCase(UUID.fromString(response["caseId"].asText()))
        }

        private fun transition(
            caseId: UUID,
            targetState: String,
            expectedVersion: Long,
            idempotencyKey: String,
            resultingVersion: Long,
        ) {
            mockMvc
                .perform(
                    post("$BASE/$caseId/status-transitions")
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", idempotencyKey)
                        .json("""{"targetState":"$targetState","expectedVersion":$expectedVersion,"reason":"CASE_STATE_REVIEW"}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.caseVersion").value(resultingVersion))
        }

        private fun createBody(externalReference: String = "case-ref-001"): String =
            """
            {"requesterType":"CUSTOMER","requesterReference":"customer-ref-001","category":"ORDER_STATUS","priority":"NORMAL","externalReference":"$externalReference","reason":"ORDER_STATUS_INQUIRY"}
            """.trimIndent()

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
                "support-test-grant:$permission:$actor",
            )
        }

        private fun operatorJwt(actor: UUID) = jwt().jwt { it.subject(actor.toString()) }

        private fun MockHttpServletRequestBuilder.json(body: String) = contentType(MediaType.APPLICATION_JSON).content(body)

        private fun count(
            sql: String,
            argument: UUID,
        ): Long = jdbcTemplate.queryForObject(sql, Long::class.java, argument)!!

        private fun countAll(table: String): Long = jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!

        private fun concurrently(
            vararg requests: () -> org.springframework.test.web.servlet.MvcResult,
        ): List<org.springframework.test.web.servlet.MvcResult> {
            val executor = Executors.newFixedThreadPool(requests.size)
            val ready = CountDownLatch(requests.size)
            val start = CountDownLatch(1)
            try {
                val futures =
                    requests.map { request ->
                        executor.submit(
                            Callable<org.springframework.test.web.servlet.MvcResult> {
                                ready.countDown()
                                check(start.await(10, TimeUnit.SECONDS)) { "Concurrent request start timed out" }
                                request()
                            },
                        )
                    }
                check(ready.await(10, TimeUnit.SECONDS)) { "Concurrent requests were not ready" }
                start.countDown()
                return futures.map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }
        }

        private fun removeAuditFailureTrigger() {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_reject_support_case_test_audit ON operations_audit_record")
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS reject_support_case_test_audit()")
        }

        private fun json(body: String) = JsonMapper.builder().build().readTree(body)

        private data class CreatedCase(
            val caseId: UUID,
        )

        private companion object {
            const val BASE = "/api/v1/support/cases"
        }
    }

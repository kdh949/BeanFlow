package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
internal class SupportCaseIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val idempotencyCleanup: SupportCaseIdempotencyRetentionCleanup,
    ) {
        private val actorId = UUID.fromString("20000000-0000-0000-0000-000000000020")
        private val otherActorId = UUID.fromString("20000000-0000-0000-0000-000000000021")

        @BeforeEach
        fun resetSupportState() {
            removeAuditFailureTrigger()
            removeIdempotencyCleanupFailureTrigger()
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
            removeIdempotencyCleanupFailureTrigger()
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
                .perform(
                    post("$BASE/${created.caseId}/status-transitions")
                        .with(operatorJwt(otherActorId))
                        .header("Idempotency-Key", "support-other-transition-0001")
                        .json("""{"targetState":"IN_PROGRESS","expectedVersion":0,"reason":"CASE_STATE_REVIEW"}"""),
                ).andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            assertThat(count("SELECT count(*) FROM support_case_state_history WHERE support_case_id = ?", created.caseId)).isOne()
            assertThat(countAll("support_case_command_idempotency")).isOne()
            assertThat(count("SELECT count(*) FROM operations_audit_record WHERE target_id = ?", created.caseId)).isOne()

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
        fun `support console queue is scoped to the current assignee and never cached`() {
            grant(actorId, "SUPPORT_CASE_WRITE")
            val created = createCase("support-console-queue-create-0001")

            mockMvc
                .perform(get("/api/v1/support/case-queue/summary").with(operatorJwt(actorId)))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

            grant(actorId, "SUPPORT_CASE_READ")
            mockMvc
                .perform(get("/api/v1/support/case-queue/summary").with(operatorJwt(actorId)))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.active").value(1))
                .andExpect(jsonPath("$.open").value(1))

            mockMvc
                .perform(get("/api/v1/support/case-queue").param("scope", "MINE").with(operatorJwt(actorId)))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].caseId").value(created.caseId.toString()))
                .andExpect(jsonPath("$.items[0].primarySubject").doesNotExist())

            mockMvc
                .perform(get("/api/v1/support/case-queue").param("scope", "ALL").with(operatorJwt(actorId)))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
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
        fun `idempotency retention cleanup deletes only due chunks and retries after a failure`() {
            val now = Instant.parse("2026-11-09T00:00:00Z")
            insertIdempotency("support-retention-due-first", now.minusSeconds(91L * 24 * 60 * 60))
            insertIdempotency("support-retention-due-second", now.minusSeconds(90L * 24 * 60 * 60))
            insertIdempotency("support-retention-future", now.minusSeconds(89L * 24 * 60 * 60))

            assertThat(idempotencyCleanup.deleteExpired(now, 1)).isOne()
            assertThat(countAll("support_case_command_idempotency")).isEqualTo(2)

            jdbcTemplate.execute(
                """
                CREATE FUNCTION reject_support_case_idempotency_cleanup()
                RETURNS trigger
                LANGUAGE plpgsql
                AS ${'$'}${'$'}
                BEGIN
                    RAISE EXCEPTION 'support idempotency cleanup unavailable';
                END;
                ${'$'}${'$'};
                """.trimIndent(),
            )
            jdbcTemplate.execute(
                """
                CREATE TRIGGER trg_reject_support_case_idempotency_cleanup
                BEFORE DELETE ON support_case_command_idempotency
                FOR EACH ROW EXECUTE FUNCTION reject_support_case_idempotency_cleanup()
                """.trimIndent(),
            )
            org.assertj.core.api.Assertions
                .assertThatThrownBy { idempotencyCleanup.deleteExpired(now, 10) }
                .isInstanceOf(org.springframework.dao.DataAccessException::class.java)
            assertThat(countAll("support_case_command_idempotency")).isEqualTo(2)
            removeIdempotencyCleanupFailureTrigger()

            assertThat(idempotencyCleanup.deleteExpired(now, 10)).isOne()
            assertThat(countAll("support_case_command_idempotency")).isOne()
            assertThat(countIdempotencyKey("support-retention-future")).isOne()
        }

        @Test
        fun `payment card and security code input is rejected before support persistence or audit`() {
            grant(actorId, "SUPPORT_CASE_WRITE")
            mockMvc
                .perform(
                    post(BASE)
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "support-card-create-0001")
                        .json(createBody(reason = "reviewed card 4111-1111-1111-1111")),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            assertThat(countAll("support_case")).isZero()
            assertThat(countAll("support_case_command_idempotency")).isZero()
            assertThat(countAll("operations_audit_record")).isZero()

            val created = createCase("support-card-safe-create-0001")
            mockMvc
                .perform(
                    post("$BASE/${created.caseId}/notes")
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "support-card-note-0001")
                        .json("""{"content":"primary 4242 4242 4242 4242 and backup 4111 1111 1111 1111","reason":"CASE_REVIEW"}"""),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            mockMvc
                .perform(
                    post("$BASE/${created.caseId}/interactions")
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "support-card-interaction-0001")
                        .json(
                            """
                            {"channel":"CHAT","direction":"INBOUND","occurredAt":"2026-08-11T00:00:00Z","redactedSummary":"customer gave CVV: 123"}
                            """.trimIndent(),
                        ),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            mockMvc
                .perform(
                    post("$BASE/${created.caseId}/status-transitions")
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "support-card-transition-0001")
                        .json("""{"targetState":"IN_PROGRESS","expectedVersion":0,"reason":"security code 987"}"""),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

            assertThat(count("SELECT version FROM support_case WHERE id = ?", created.caseId)).isZero()
            assertThat(count("SELECT count(*) FROM support_case_note WHERE support_case_id = ?", created.caseId)).isZero()
            assertThat(count("SELECT count(*) FROM support_case_interaction WHERE support_case_id = ?", created.caseId)).isZero()
            assertThat(count("SELECT count(*) FROM support_case_state_history WHERE support_case_id = ?", created.caseId)).isOne()
            assertThat(countAll("support_case_command_idempotency")).isOne()
            assertThat(count("SELECT count(*) FROM operations_audit_record WHERE target_id = ?", created.caseId)).isOne()
        }

        @Test
        fun `idempotency scope is actor operation bound and payload field boundaries cannot replay`() {
            grant(actorId, "SUPPORT_CASE_WRITE")
            grant(otherActorId, "SUPPORT_CASE_WRITE")
            createCase("support-cross-actor-key-0001")
            mockMvc
                .perform(
                    post(BASE)
                        .with(operatorJwt(otherActorId))
                        .header("Idempotency-Key", "support-cross-actor-key-0001")
                        .json(createBody(externalReference = "case-ref-other-actor")),
                ).andExpect(status().isCreated)

            val created = createCase("support-canonical-create-0001")
            mockMvc
                .perform(
                    post("$BASE/${created.caseId}/notes")
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "support-canonical-note-0001")
                        .json("""{"content":"FIELD|BOUNDARY","reason":"SECOND"}"""),
                ).andExpect(status().isOk)
            mockMvc
                .perform(
                    post("$BASE/${created.caseId}/notes")
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "support-canonical-note-0001")
                        .json("""{"content":"FIELD","reason":"BOUNDARY|SECOND"}"""),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
            mockMvc
                .perform(
                    post("$BASE/${created.caseId}/subject-links")
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "support-canonical-note-0001")
                        .json(
                            """{"subjectType":"ORDER","subjectId":"30000000-0000-0000-0000-000000000099","relationship":"RELATED_ORDER","reason":"ORDER_CONTEXT"}""",
                        ),
                ).andExpect(status().isOk)
        }

        @Test
        fun `invalid OTHER detail returns 400 with no Case idempotency or audit row`() {
            grant(actorId, "SUPPORT_CASE_WRITE")
            mockMvc
                .perform(
                    post(BASE)
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "support-other-input-0001")
                        .json(
                            """
                            {"requesterType":"CUSTOMER","requesterReference":"customer-ref-001","category":"OTHER","priority":"NORMAL","reason":"ok"}
                            """.trimIndent(),
                        ),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            assertThat(countAll("support_case")).isZero()
            assertThat(countAll("support_case_command_idempotency")).isZero()
            assertThat(countAll("operations_audit_record")).isZero()
        }

        @Test
        fun `HTTP responses omit optional support fields instead of serializing null`() {
            grant(actorId, "SUPPORT_CASE_WRITE")
            grant(actorId, "SUPPORT_CASE_READ")
            val created = createCase("support-null-contract-create-0001")
            mockMvc
                .perform(
                    post("$BASE/${created.caseId}/subject-links")
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "support-null-contract-link-0001")
                        .json(
                            """{"subjectType":"ORDER","subjectId":"30000000-0000-0000-0000-000000000088","relationship":"RELATED_ORDER","reason":"ORDER_CONTEXT"}""",
                        ),
                ).andExpect(status().isOk)
            mockMvc
                .perform(get("$BASE/${created.caseId}").with(operatorJwt(actorId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.subjectLinks[0].caseVersion").doesNotExist())
            mockMvc
                .perform(get(BASE).param("limit", "1").with(operatorJwt(actorId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
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
            val tamperedCursor = (if (cursor.first() == 'A') "B" else "A") + cursor.drop(1)
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

        private fun createBody(
            externalReference: String = "case-ref-001",
            reason: String = "ORDER_STATUS_INQUIRY",
        ): String =
            """
            {"requesterType":"CUSTOMER","requesterReference":"customer-ref-001","category":"ORDER_STATUS","priority":"NORMAL","externalReference":"$externalReference","reason":"$reason"}
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

        private fun countIdempotencyKey(key: String): Long =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM support_case_command_idempotency WHERE idempotency_key = ?",
                Long::class.java,
                key,
            )!!

        private fun insertIdempotency(
            key: String,
            createdAt: Instant,
        ) {
            jdbcTemplate.update(
                """
                INSERT INTO support_case_command_idempotency (
                    id, actor_id, operation, idempotency_key, payload_hash, response_status, response_body, created_at, retention_expires_at
                ) VALUES (?, ?, 'CREATE_CASE', ?, ?, 201, '{}', ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                actorId,
                key,
                "a".repeat(64),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt.plusSeconds(90L * 24 * 60 * 60)),
            )
        }

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

        private fun removeIdempotencyCleanupFailureTrigger() {
            jdbcTemplate.execute(
                "DROP TRIGGER IF EXISTS trg_reject_support_case_idempotency_cleanup ON support_case_command_idempotency",
            )
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS reject_support_case_idempotency_cleanup()")
        }

        private fun json(body: String) = JsonMapper.builder().build().readTree(body)

        private data class CreatedCase(
            val caseId: UUID,
        )

        private companion object {
            const val BASE = "/api/v1/support/cases"
        }
    }

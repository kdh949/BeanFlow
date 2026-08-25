package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.shared.api.EncryptedPersonalData
import io.github.kdh949.beanflow.shared.api.PersonalDataEncryptionContext
import io.github.kdh949.beanflow.shared.api.PersonalDataField
import io.github.kdh949.beanflow.shared.api.PersonalDataOwnerContext
import io.github.kdh949.beanflow.shared.internal.VaultTransitPersonalDataAdapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
internal class DataAccessGrantIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val caseService: SupportCaseApplicationService,
    ) {
        @MockitoBean
        private lateinit var crypto: VaultTransitPersonalDataAdapter

        private val requesterId = UUID.fromString("45000000-0000-0000-0000-000000000001")
        private val approverId = UUID.fromString("45000000-0000-0000-0000-000000000002")
        private val decryptCalls = AtomicInteger()

        @BeforeEach
        fun reset() {
            removeAuditFailure()
            jdbcTemplate.execute("TRUNCATE TABLE support_case CASCADE")
            jdbcTemplate.execute("TRUNCATE TABLE identity_customer_support_profile CASCADE")
            jdbcTemplate.execute("TRUNCATE TABLE operations_audit_record, operations_operator_permission_grant")
            decryptCalls.set(0)
            Mockito.reset(crypto)
            grant(requesterId, "SUPPORT_PII_REVEAL_REQUEST")
            grant(requesterId, "SUPPORT_PII_REVEAL_BASIC")
            grant(requesterId, "SUPPORT_PII_REVEAL_SENSITIVE")
        }

        @AfterEach
        fun cleanupTrigger() {
            removeAuditFailure()
        }

        @Test
        fun `basic grant enforces field scope and reveals only after committed audit`() {
            val binding = seedVerifiedBinding(requesterId, "BASIC")
            val grantId = requestGrant(binding, "CUSTOMER_DISPLAY_NAME", "grant-request-basic-0001", "ACTIVE")

            mockMvc
                .perform(
                    post("/api/v1/support/data-access-grants/$grantId/reveals")
                        .with(operatorJwt(requesterId))
                        .header("Idempotency-Key", "grant-reveal-scope-0001")
                        .json("""{"fields":["CUSTOMER_PRIMARY_EMAIL"]}"""),
                ).andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("DATA_ACCESS_SCOPE_MISMATCH"))
            assertThat(decryptCalls.get()).isZero()

            mockMvc
                .perform(
                    post("/api/v1/support/data-access-grants/$grantId/reveals")
                        .with(operatorJwt(requesterId))
                        .header("Idempotency-Key", "grant-reveal-basic-0001")
                        .json("""{"fields":["CUSTOMER_DISPLAY_NAME"]}"""),
                ).andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.values.CUSTOMER_DISPLAY_NAME").value("Synthetic Customer"))
            assertThat(decryptCalls.get()).isOne()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM operations_audit_record WHERE action = 'SUPPORT_PII_ACCESS_RECORDED'",
                    Long::class.java,
                ),
            ).isOne()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM support_reveal_attempt WHERE grant_id = ? AND state = 'REVEALED'",
                    Long::class.java,
                    grantId,
                ),
            ).isOne()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM support_security_command_idempotency WHERE response_body LIKE '%Synthetic Customer%'",
                    Long::class.java,
                ),
            ).isZero()

            mockMvc
                .perform(
                    post("/api/v1/support/data-access-grants/$grantId/reveals")
                        .with(operatorJwt(requesterId))
                        .header("Idempotency-Key", "grant-reveal-basic-0001")
                        .json("""{"fields":["CUSTOMER_DISPLAY_NAME"]}"""),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_MANUAL_REVIEW_REQUIRED"))
            assertThat(decryptCalls.get()).isOne()
        }

        @Test
        fun `audit failure rolls back reveal reservation before owner decrypt`() {
            val binding = seedVerifiedBinding(requesterId, "BASIC")
            val grantId = requestGrant(binding, "CUSTOMER_DISPLAY_NAME", "grant-request-audit-0001", "ACTIVE")
            installAuditFailure()

            mockMvc
                .perform(
                    post("/api/v1/support/data-access-grants/$grantId/reveals")
                        .with(operatorJwt(requesterId))
                        .header("Idempotency-Key", "grant-reveal-audit-0001")
                        .json("""{"fields":["CUSTOMER_DISPLAY_NAME"]}"""),
                ).andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))

            assertThat(decryptCalls.get()).isZero()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT reserved_reveals FROM support_data_access_grant WHERE id = ?",
                    Int::class.java,
                    grantId,
                ),
            ).isZero()
            assertThat(
                jdbcTemplate.queryForObject("SELECT count(*) FROM support_reveal_attempt WHERE grant_id = ?", Long::class.java, grantId),
            ).isZero()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM support_security_command_idempotency WHERE idempotency_key = 'grant-reveal-audit-0001'",
                    Long::class.java,
                ),
            ).isZero()
        }

        @Test
        fun `action scoped verification cannot authorize a personal data grant`() {
            val binding = seedVerifiedBinding(requesterId, "BASIC")
            jdbcTemplate.update(
                "UPDATE support_verification_session SET action_scope = 'SUPPORT_ACTION' WHERE id = ?",
                binding.sessionId,
            )

            mockMvc
                .perform(
                    post("/api/v1/support/cases/${binding.caseId}/data-access-grants")
                        .with(operatorJwt(requesterId))
                        .header("Idempotency-Key", "grant-action-scope-denied")
                        .json(
                            """
                            {"verificationSessionId":"${binding.sessionId}","purpose":"CASE_RESOLUTION",
                             "fields":["CUSTOMER_DISPLAY_NAME"],"reasonCode":"CASE_HANDLING"}
                            """.trimIndent(),
                        ),
                ).andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("VERIFICATION_REQUIRED"))
        }

        @Test
        fun `permission revocation during owner decrypt prevents raw response release`() {
            val binding = seedVerifiedBinding(requesterId, "BASIC")
            val grantId = requestGrant(binding, "CUSTOMER_DISPLAY_NAME", "grant-request-race-0001", "ACTIVE")
            val decryptStarted = CountDownLatch(1)
            val decryptRelease = CountDownLatch(1)
            Mockito
                .doAnswer {
                    check(!TransactionSynchronizationManager.isActualTransactionActive())
                    decryptCalls.incrementAndGet()
                    decryptStarted.countDown()
                    check(decryptRelease.await(5, TimeUnit.SECONDS))
                    "Synthetic Customer".toByteArray(StandardCharsets.UTF_8)
                }.`when`(crypto)
                .decrypt(
                    EncryptedPersonalData("vault:v7:display", 7, 1),
                    PersonalDataEncryptionContext(PersonalDataOwnerContext.IDENTITY, binding.subjectId, PersonalDataField.DISPLAY_NAME),
                )
            val executor = Executors.newSingleThreadExecutor()
            try {
                val response =
                    executor.submit(
                        Callable {
                            mockMvc
                                .perform(
                                    post("/api/v1/support/data-access-grants/$grantId/reveals")
                                        .with(operatorJwt(requesterId))
                                        .header("Idempotency-Key", "grant-reveal-race-0001")
                                        .json("""{"fields":["CUSTOMER_DISPLAY_NAME"]}"""),
                                ).andReturn()
                        },
                    )
                check(decryptStarted.await(5, TimeUnit.SECONDS))
                jdbcTemplate.update(
                    """
                    UPDATE operations_operator_permission_grant
                       SET state = 'REVOKED', revoked_at = now(), version = version + 1
                     WHERE actor_id = ? AND permission = 'SUPPORT_PII_REVEAL_BASIC'
                    """.trimIndent(),
                    requesterId,
                )
                decryptRelease.countDown()
                val result = response.get(10, TimeUnit.SECONDS).response

                assertThat(result.status).isEqualTo(403)
                assertThat(result.contentAsString).doesNotContain("Synthetic Customer")
                assertThat(
                    jdbcTemplate.queryForObject(
                        "SELECT state FROM support_reveal_attempt WHERE grant_id = ?",
                        String::class.java,
                        grantId,
                    ),
                ).isEqualTo("FAILED")
            } finally {
                decryptRelease.countDown()
                executor.shutdownNow()
            }
        }

        @Test
        fun `sensitive grant requires enhanced verification and a distinct approver`() {
            val binding = seedVerifiedBinding(requesterId, "ENHANCED")
            val grantId = requestGrant(binding, "CUSTOMER_PRIMARY_EMAIL", "grant-request-sensitive-0001", "APPROVAL_PENDING")
            grant(requesterId, "SUPPORT_PII_REVEAL_APPROVE")

            mockMvc
                .perform(
                    post("/api/v1/support/data-access-grants/$grantId/approvals")
                        .with(operatorJwt(requesterId))
                        .header("Idempotency-Key", "grant-self-approve-0001")
                        .json("""{"decision":"APPROVE","expectedVersion":0,"reasonCode":"CASE_HANDLING"}"""),
                ).andExpect(status().isForbidden)
            grant(approverId, "SUPPORT_PII_REVEAL_APPROVE")
            mockMvc
                .perform(
                    post("/api/v1/support/data-access-grants/$grantId/approvals")
                        .with(operatorJwt(approverId))
                        .header("Idempotency-Key", "grant-approve-0001")
                        .json("""{"decision":"APPROVE","expectedVersion":0,"reasonCode":"CASE_HANDLING"}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.maxReveals").value(1))

            mockMvc
                .perform(
                    post("/api/v1/support/data-access-grants/$grantId/reveals")
                        .with(operatorJwt(requesterId))
                        .header("Idempotency-Key", "grant-reveal-sensitive-0001")
                        .json("""{"fields":["CUSTOMER_PRIMARY_EMAIL"]}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.values.CUSTOMER_PRIMARY_EMAIL").value("synthetic@example.invalid"))
            assertThat(
                jdbcTemplate.queryForObject("SELECT state FROM support_data_access_grant WHERE id = ?", String::class.java, grantId),
            ).isEqualTo("CONSUMED")
        }

        @Test
        fun `resolving a case atomically revokes its verified session and active grant`() {
            val binding = seedVerifiedBinding(requesterId, "BASIC")
            val grantId = requestGrant(binding, "CUSTOMER_DISPLAY_NAME", "grant-request-terminal-0001", "ACTIVE")
            grant(requesterId, "SUPPORT_CASE_WRITE")

            val transition =
                caseService.transition(
                    TransitionSupportCaseCommand(
                        actorId = requesterId,
                        caseId = binding.caseId,
                        idempotencyKey = "grant-case-resolve-0001",
                        targetState = io.github.kdh949.beanflow.support.internal.domain.SupportCaseState.RESOLVED,
                        expectedVersion = 1,
                        reason = "CASE_RESOLUTION_CONFIRMED",
                        correlationId = "s40-terminal-case-test",
                    ),
                )

            assertThat(transition.currentState.name).isEqualTo("RESOLVED")
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT state FROM support_verification_session WHERE id = ?",
                    String::class.java,
                    binding.sessionId,
                ),
            ).isEqualTo("REVOKED")
            assertThat(
                jdbcTemplate.queryForObject("SELECT state FROM support_data_access_grant WHERE id = ?", String::class.java, grantId),
            ).isEqualTo("REVOKED")
        }

        private fun requestGrant(
            binding: Binding,
            field: String,
            key: String,
            expectedState: String,
        ): UUID {
            val result =
                mockMvc
                    .perform(
                        post("/api/v1/support/cases/${binding.caseId}/data-access-grants")
                            .with(operatorJwt(requesterId))
                            .header("Idempotency-Key", key)
                            .json(
                                """
                                {"verificationSessionId":"${binding.sessionId}","purpose":"CASE_RESOLUTION",
                                 "fields":["$field"],"reasonCode":"CASE_HANDLING"}
                                """.trimIndent(),
                            ),
                    ).andExpect(status().isCreated)
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.state").value(expectedState))
                    .andReturn()
            return UUID.fromString(
                JsonMapper
                    .builder()
                    .build()
                    .readTree(result.response.contentAsString)["grantId"]
                    .asText(),
            )
        }

        private fun seedVerifiedBinding(
            assigneeId: UUID,
            level: String,
        ): Binding {
            val current = Instant.now().minusSeconds(60)
            val caseId = UUID.randomUUID()
            val linkId = UUID.randomUUID()
            val subjectId = UUID.randomUUID()
            val sessionId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO support_case (
                    id, requester_type, requester_reference, category, priority, reason, state,
                    current_assignee_id, opened_at, last_changed_at, version, retention_policy_version_id
                ) VALUES (?, 'CUSTOMER', 'customer-reference', 'PRIVACY', 'HIGH', 'PRIVACY_CASE', 'IN_PROGRESS',
                          ?, ?, ?, 1, 7)
                """.trimIndent(),
                caseId,
                assigneeId,
                Timestamp.from(current),
                Timestamp.from(current),
            )
            jdbcTemplate.update(
                """
                INSERT INTO support_case_state_history (
                    id, support_case_id, sequence, previous_state, current_state, actor_id, case_version, occurred_at
                ) VALUES
                    (?, ?, 0, NULL, 'OPEN', ?, 0, ?),
                    (?, ?, 1, 'OPEN', 'IN_PROGRESS', ?, 1, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                caseId,
                assigneeId,
                Timestamp.from(current.minusSeconds(1)),
                UUID.randomUUID(),
                caseId,
                assigneeId,
                Timestamp.from(current),
            )
            stubDecrypt(subjectId)
            jdbcTemplate.update(
                """
                INSERT INTO support_case_subject_link (
                    id, support_case_id, subject_type, subject_id, relationship, linked_by_actor_id, reason, linked_at
                ) VALUES (?, ?, 'CUSTOMER', ?, 'REQUESTER', ?, 'IDENTITY_SUBJECT', ?)
                """.trimIndent(),
                linkId,
                caseId,
                subjectId,
                assigneeId,
                Timestamp.from(current),
            )
            jdbcTemplate.update(
                """
                INSERT INTO identity_customer_support_profile (
                    customer_id, display_name_ciphertext, display_name_key_version, display_name_aad_version,
                    masked_display_name, primary_email_ciphertext, primary_email_key_version,
                    primary_email_aad_version, masked_primary_email, created_at, updated_at
                ) VALUES (?, 'vault:v7:display', 7, 1, 'S***************r', 'vault:v7:email', 7, 1,
                          's***@e***.invalid', ?, ?)
                """.trimIndent(),
                subjectId,
                Timestamp.from(current),
                Timestamp.from(current),
            )
            jdbcTemplate.update(
                """
                INSERT INTO support_verification_session (
                    id, support_case_id, subject_link_id, subject_type, subject_id, actor_id, purpose, action_scope,
                    requested_level, state, invalid_attempts, started_at, expires_at, verified_at, version
                ) VALUES (?, ?, ?, 'CUSTOMER', ?, ?, 'CASE_RESOLUTION', 'PERSONAL_DATA_REVEAL',
                          ?, 'VERIFIED', 0, ?, ?, ?, 1)
                """.trimIndent(),
                sessionId,
                caseId,
                linkId,
                subjectId,
                assigneeId,
                level,
                Timestamp.from(current),
                Timestamp.from(current.plusSeconds(900)),
                Timestamp.from(current.plusSeconds(1)),
            )
            val channels = if (level == "ENHANCED") listOf("REGISTERED_PHONE", "REGISTERED_EMAIL") else listOf("IN_APP")
            channels.forEachIndexed { index, channel ->
                jdbcTemplate.update(
                    """
                    INSERT INTO support_verification_challenge (
                        id, session_id, channel, state, opaque_provider_reference, requested_at, expires_at, completed_at, version
                    ) VALUES (?, ?, ?, 'VERIFIED', ?, ?, ?, ?, 2)
                    """.trimIndent(),
                    UUID.randomUUID(),
                    sessionId,
                    channel,
                    "test-provider-reference-$index",
                    Timestamp.from(current.plusSeconds(index.toLong())),
                    Timestamp.from(current.plusSeconds(index.toLong() + 300)),
                    Timestamp.from(current.plusSeconds(index.toLong() + 1)),
                )
            }
            return Binding(caseId, sessionId, linkId, subjectId)
        }

        private fun stubDecrypt(subjectId: UUID) {
            fun stub(
                encrypted: EncryptedPersonalData,
                field: PersonalDataField,
                value: String,
            ) {
                Mockito
                    .doAnswer {
                        check(!TransactionSynchronizationManager.isActualTransactionActive())
                        decryptCalls.incrementAndGet()
                        value.toByteArray(StandardCharsets.UTF_8)
                    }.`when`(crypto)
                    .decrypt(
                        encrypted,
                        PersonalDataEncryptionContext(PersonalDataOwnerContext.IDENTITY, subjectId, field),
                    )
            }
            stub(EncryptedPersonalData("vault:v7:display", 7, 1), PersonalDataField.DISPLAY_NAME, "Synthetic Customer")
            stub(EncryptedPersonalData("vault:v7:email", 7, 1), PersonalDataField.PRIMARY_EMAIL, "synthetic@example.invalid")
        }

        private fun installAuditFailure() {
            jdbcTemplate.execute(
                """
                CREATE FUNCTION reject_s40_reveal_audit()
                RETURNS trigger
                LANGUAGE plpgsql
                AS ${'$'}${'$'}
                BEGIN
                    IF NEW.action = 'SUPPORT_PII_ACCESS_RECORDED' THEN
                        RAISE EXCEPTION 's40 reveal audit unavailable';
                    END IF;
                    RETURN NEW;
                END;
                ${'$'}${'$'};
                """.trimIndent(),
            )
            jdbcTemplate.execute(
                """
                CREATE TRIGGER trg_reject_s40_reveal_audit
                BEFORE INSERT ON operations_audit_record
                FOR EACH ROW EXECUTE FUNCTION reject_s40_reveal_audit()
                """.trimIndent(),
            )
        }

        private fun removeAuditFailure() {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_reject_s40_reveal_audit ON operations_audit_record")
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS reject_s40_reveal_audit()")
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
                ON CONFLICT (actor_id, permission) DO UPDATE
                    SET state = 'ACTIVE', revoked_at = NULL, version = operations_operator_permission_grant.version + 1
                """.trimIndent(),
                actor,
                permission,
                "data-access-test-grant:$permission:$actor",
            )
        }

        private fun operatorJwt(actor: UUID) = jwt().jwt { it.subject(actor.toString()) }

        private fun MockHttpServletRequestBuilder.json(body: String) = contentType(MediaType.APPLICATION_JSON).content(body)

        private data class Binding(
            val caseId: UUID,
            val sessionId: UUID,
            val linkId: UUID,
            val subjectId: UUID,
        )
    }

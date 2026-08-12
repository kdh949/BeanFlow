package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.identity.api.IssueVerificationChallengeCommand
import io.github.kdh949.beanflow.identity.api.VerificationChallengeIssueResult
import io.github.kdh949.beanflow.identity.api.VerificationChallengeOperations
import io.github.kdh949.beanflow.identity.api.VerificationChallengeVerifyResult
import io.github.kdh949.beanflow.identity.api.VerifyChallengeCommand
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.Arrays
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Import(TestcontainersConfiguration::class, SupportVerificationIntegrationTest.ProviderConfiguration::class)
@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
        "beanflow.support-case-idempotency.retention.initial-delay-ms=3600000",
        "beanflow.support-verification-recovery.initial-delay-ms=3600000",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class SupportVerificationIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val provider: ScriptedVerificationProvider,
        private val recoveryWorker: SupportVerificationRecoveryWorker,
    ) {
        private val actorId = UUID.fromString("44000000-0000-0000-0000-000000000001")
        private val now = Instant.parse("2026-08-12T00:00:00Z")

        @BeforeEach
        fun reset() {
            jdbcTemplate.execute("TRUNCATE TABLE support_case CASCADE")
            jdbcTemplate.execute("TRUNCATE TABLE operations_audit_record, operations_operator_permission_grant")
            provider.reset()
            grant(actorId, "SUPPORT_VERIFICATION_MANAGE")
        }

        @Test
        fun `basic verification is provider-backed idempotent and no-store`() {
            val binding = insertBinding(actorId)
            val sessionId = createSession(binding, "BASIC", "verification-create-0001")
            val challengeId = issue(sessionId, "REGISTERED_PHONE", "verification-issue-0001")

            mockMvc
                .perform(
                    post("/api/v1/support/verification-challenges/$challengeId/verifications")
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "verification-verify-0001")
                        .json("""{"proof":"VALID_TEST_PROOF"}"""),
                ).andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.sessionState").value("VERIFIED"))
                .andExpect(jsonPath("$.achievedLevel").value("BASIC"))

            mockMvc
                .perform(
                    post("/api/v1/support/verification-challenges/$challengeId/verifications")
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "verification-verify-0001")
                        .json("""{"proof":"DIFFERENT_TRANSIENT_PROOF"}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.sessionState").value("VERIFIED"))
            mockMvc
                .perform(
                    post("/api/v1/support/verification-challenges/$challengeId/verifications")
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "verification-verify-0002")
                        .json("""{"proof":"VALID_TEST_PROOF"}"""),
                ).andExpect(status().isConflict)

            mockMvc
                .perform(get("/api/v1/support/verification-sessions/$sessionId").with(operatorJwt(actorId)))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("VERIFIED"))
            assertThat(provider.issueCalls.get()).isOne()
            assertThat(provider.verifyCalls.get()).isOne()
            assertThat(provider.observedTransaction.get()).isFalse()
            assertThat(
                jdbcTemplate.queryForObject(
                    """
                    SELECT count(*) FROM support_security_command_idempotency
                     WHERE response_body LIKE '%VALID_TEST_PROOF%' OR response_body LIKE '%DIFFERENT_TRANSIENT_PROOF%'
                    """.trimIndent(),
                    Long::class.java,
                ),
            ).isZero()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM operations_audit_record WHERE after_summary LIKE '%VALID_TEST_PROOF%'",
                    Long::class.java,
                ),
            ).isZero()
        }

        @Test
        fun `support action verification exposes its scope and rejects an incompatible purpose`() {
            val binding = insertBinding(actorId)

            mockMvc
                .perform(
                    post("/api/v1/support/cases/${binding.caseId}/verification-sessions")
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "verification-action-create")
                        .json(
                            """{"subjectLinkId":"${binding.linkId}","requestedLevel":"BASIC","purpose":"CASE_RESOLUTION","actionScope":"SUPPORT_ACTION"}""",
                        ),
                ).andExpect(status().isCreated)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.actionScope").value("SUPPORT_ACTION"))

            mockMvc
                .perform(
                    post("/api/v1/support/cases/${binding.caseId}/verification-sessions")
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "verification-action-invalid")
                        .json(
                            """{"subjectLinkId":"${binding.linkId}","requestedLevel":"BASIC","purpose":"CONTACT_CONFIRMATION","actionScope":"SUPPORT_ACTION"}""",
                        ),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }

        @Test
        fun `five invalid one-shot challenges lock the case subject binding for thirty minutes`() {
            val binding = insertBinding(actorId)
            val sessionId = createSession(binding, "BASIC", "verification-create-1001")

            repeat(5) { index ->
                val challengeId = issue(sessionId, "REGISTERED_PHONE", "verification-issue-10$index")
                mockMvc
                    .perform(
                        post("/api/v1/support/verification-challenges/$challengeId/verifications")
                            .with(operatorJwt(actorId))
                            .header("Idempotency-Key", "verification-verify-10$index")
                            .json("""{"proof":"INVALID_TEST_PROOF_$index"}"""),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.invalidAttempts").value(index + 1))
            }

            mockMvc
                .perform(
                    post("/api/v1/support/cases/${binding.caseId}/verification-sessions")
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "verification-create-1002")
                        .json(
                            """{"subjectLinkId":"${binding.linkId}","requestedLevel":"BASIC","purpose":"CASE_RESOLUTION"}""",
                        ),
                ).andExpect(status().isTooManyRequests)
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("VERIFICATION_LOCKED"))
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT invalid_attempts FROM support_verification_session WHERE id = ?",
                    Int::class.java,
                    sessionId,
                ),
            ).isEqualTo(5)

            val replacementLinkId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                UPDATE support_case_subject_link
                   SET unlinked_by_actor_id = ?, unlink_reason = 'SUBJECT_RELINKED', unlinked_at = ?, unlink_case_version = 1
                 WHERE id = ?
                """.trimIndent(),
                actorId,
                Timestamp.from(now.plusSeconds(1)),
                binding.linkId,
            )
            jdbcTemplate.update(
                """
                INSERT INTO support_case_subject_link (
                    id, support_case_id, subject_type, subject_id, relationship, linked_by_actor_id, reason, linked_at
                ) VALUES (?, ?, 'CUSTOMER', ?, 'REQUESTER', ?, 'IDENTITY_SUBJECT_RELINKED', ?)
                """.trimIndent(),
                replacementLinkId,
                binding.caseId,
                binding.subjectId,
                actorId,
                Timestamp.from(now.plusSeconds(2)),
            )
            mockMvc
                .perform(
                    post("/api/v1/support/cases/${binding.caseId}/verification-sessions")
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "verification-create-1003")
                        .json(
                            """{"subjectLinkId":"$replacementLinkId","requestedLevel":"BASIC","purpose":"CASE_RESOLUTION"}""",
                        ),
                ).andExpect(status().isTooManyRequests)
                .andExpect(jsonPath("$.code").value("VERIFICATION_LOCKED"))
        }

        @Test
        fun `verification session cannot be reused by a replacement assignee`() {
            val replacementActor = UUID.fromString("44000000-0000-0000-0000-000000000002")
            val binding = insertBinding(actorId)
            val sessionId = createSession(binding, "BASIC", "verification-owner-create")
            grant(replacementActor, "SUPPORT_VERIFICATION_MANAGE")
            jdbcTemplate.update(
                "UPDATE support_case SET current_assignee_id = ?, version = version + 1 WHERE id = ?",
                replacementActor,
                binding.caseId,
            )

            mockMvc
                .perform(get("/api/v1/support/verification-sessions/$sessionId").with(operatorJwt(replacementActor)))
                .andExpect(status().isForbidden)
            mockMvc
                .perform(
                    post("/api/v1/support/verification-sessions/$sessionId/challenges")
                        .with(operatorJwt(replacementActor))
                        .header("Idempotency-Key", "verification-owner-issue")
                        .json("""{"channel":"REGISTERED_PHONE"}"""),
                ).andExpect(status().isForbidden)
            assertThat(provider.issueCalls.get()).isZero()
        }

        @Test
        fun `expired provider work is recovered to explicit unknown outcomes`() {
            val binding = insertBinding(actorId)
            val sessionId = createSession(binding, "BASIC", "verification-recovery-create")
            val expiredAt = Instant.now().minusSeconds(30)
            val requestedAt = expiredAt.minusSeconds(300)
            val issueChallengeId = UUID.randomUUID()
            val verifyChallengeId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO support_verification_challenge (
                    id, session_id, channel, state, requested_at, expires_at, version
                ) VALUES (?, ?, 'REGISTERED_PHONE', 'PENDING_ISSUE', ?, ?, 0)
                """.trimIndent(),
                issueChallengeId,
                sessionId,
                Timestamp.from(requestedAt),
                Timestamp.from(expiredAt),
            )
            jdbcTemplate.update(
                """
                INSERT INTO support_verification_challenge (
                    id, session_id, channel, state, opaque_provider_reference, requested_at, expires_at, version
                ) VALUES (?, ?, 'REGISTERED_EMAIL', 'VERIFYING', 'stale-provider-reference', ?, ?, 1)
                """.trimIndent(),
                verifyChallengeId,
                sessionId,
                Timestamp.from(requestedAt),
                Timestamp.from(expiredAt),
            )
            insertProcessingCommand(issueChallengeId, "ISSUE_CHALLENGE", "verification-recovery-issue", requestedAt)
            insertProcessingCommand(verifyChallengeId, "VERIFY_CHALLENGE", "verification-recovery-verify", requestedAt)

            recoveryWorker.recoverExpiredWork()

            assertThat(challengeState(issueChallengeId)).isEqualTo("ISSUE_UNKNOWN")
            assertThat(challengeState(verifyChallengeId)).isEqualTo("VERIFICATION_UNKNOWN")
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT outcome FROM support_verification_attempt WHERE challenge_id = ?",
                    String::class.java,
                    verifyChallengeId,
                ),
            ).isEqualTo("UNKNOWN")
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM support_security_command_idempotency WHERE resource_id IN (?, ?) AND state = 'COMPLETED'",
                    Long::class.java,
                    issueChallengeId,
                    verifyChallengeId,
                ),
            ).isEqualTo(2)
        }

        @Test
        fun `case subject mismatch is rejected and concurrent verification has one provider call`() {
            val first = insertBinding(actorId)
            val second = insertBinding(actorId)
            mockMvc
                .perform(
                    post("/api/v1/support/cases/${first.caseId}/verification-sessions")
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "verification-mismatch-0001")
                        .json(
                            """{"subjectLinkId":"${second.linkId}","requestedLevel":"BASIC","purpose":"CASE_RESOLUTION"}""",
                        ),
                ).andExpect(status().isNotFound)

            val sessionId = createSession(first, "BASIC", "verification-concurrent-create")
            val challengeId = issue(sessionId, "IN_APP", "verification-concurrent-issue")
            provider.blockVerification()
            val executor = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)
            try {
                val futures =
                    (1..2).map { index ->
                        executor.submit(
                            Callable {
                                start.await(5, TimeUnit.SECONDS)
                                mockMvc
                                    .perform(
                                        post("/api/v1/support/verification-challenges/$challengeId/verifications")
                                            .with(operatorJwt(actorId))
                                            .header("Idempotency-Key", "verification-concurrent-verify-$index")
                                            .json("""{"proof":"VALID_TEST_PROOF"}"""),
                                    ).andReturn()
                            },
                        )
                    }
                start.countDown()
                provider.awaitVerificationStarted()
                provider.releaseVerification()
                val statuses = futures.map { it.get(10, TimeUnit.SECONDS).response.status }
                assertThat(statuses).containsExactlyInAnyOrder(200, 409)
                assertThat(provider.verifyCalls.get()).isOne()
            } finally {
                provider.releaseVerification()
                executor.shutdownNow()
            }
        }

        private fun createSession(
            binding: Binding,
            level: String,
            key: String,
        ): UUID {
            val result =
                mockMvc
                    .perform(
                        post("/api/v1/support/cases/${binding.caseId}/verification-sessions")
                            .with(operatorJwt(actorId))
                            .header("Idempotency-Key", key)
                            .json(
                                """{"subjectLinkId":"${binding.linkId}","requestedLevel":"$level","purpose":"CASE_RESOLUTION"}""",
                            ),
                    ).andExpect(status().isCreated)
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andReturn()
            return UUID.fromString(
                JsonMapper
                    .builder()
                    .build()
                    .readTree(result.response.contentAsString)["sessionId"]
                    .asText(),
            )
        }

        private fun issue(
            sessionId: UUID,
            channel: String,
            key: String,
        ): UUID {
            val result =
                mockMvc
                    .perform(
                        post("/api/v1/support/verification-sessions/$sessionId/challenges")
                            .with(operatorJwt(actorId))
                            .header("Idempotency-Key", key)
                            .json("""{"channel":"$channel"}"""),
                    ).andExpect(status().isCreated)
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andReturn()
            return UUID.fromString(
                JsonMapper
                    .builder()
                    .build()
                    .readTree(result.response.contentAsString)["challengeId"]
                    .asText(),
            )
        }

        private fun insertBinding(assigneeId: UUID): Binding {
            val caseId = UUID.randomUUID()
            val linkId = UUID.randomUUID()
            val subjectId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO support_case (
                    id, requester_type, requester_reference, category, priority, reason, state,
                    current_assignee_id, opened_at, last_changed_at, version, retention_policy_version_id
                ) VALUES (?, 'CUSTOMER', 'customer-reference', 'ACCOUNT_RECOVERY', 'NORMAL', 'ACCOUNT_ACCESS_CASE', 'OPEN',
                          ?, ?, ?, 0, 7)
                """.trimIndent(),
                caseId,
                assigneeId,
                Timestamp.from(now),
                Timestamp.from(now),
            )
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
                Timestamp.from(now),
            )
            return Binding(caseId, linkId, subjectId)
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
                "support-verification-test-grant:$permission:$actor",
            )
        }

        private fun insertProcessingCommand(
            challengeId: UUID,
            operation: String,
            key: String,
            createdAt: Instant,
        ) {
            jdbcTemplate.update(
                """
                INSERT INTO support_security_command_idempotency (
                    id, actor_id, operation, idempotency_key, payload_hash, resource_id, state,
                    created_at, retention_expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'PROCESSING', ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                actorId,
                operation,
                key,
                "a".repeat(64),
                challengeId,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt.plusSeconds(90L * 24 * 60 * 60)),
            )
        }

        private fun challengeState(challengeId: UUID): String =
            jdbcTemplate.queryForObject(
                "SELECT state FROM support_verification_challenge WHERE id = ?",
                String::class.java,
                challengeId,
            )!!

        private fun operatorJwt(actor: UUID) = jwt().jwt { it.subject(actor.toString()) }

        private fun MockHttpServletRequestBuilder.json(body: String) = contentType(MediaType.APPLICATION_JSON).content(body)

        private data class Binding(
            val caseId: UUID,
            val linkId: UUID,
            val subjectId: UUID,
        )

        @TestConfiguration(proxyBeanMethods = false)
        internal class ProviderConfiguration {
            @Bean
            fun scriptedVerificationProvider(): ScriptedVerificationProvider = ScriptedVerificationProvider()
        }
    }

internal class ScriptedVerificationProvider : VerificationChallengeOperations {
    val issueCalls = AtomicInteger()
    val verifyCalls = AtomicInteger()
    val observedTransaction = AtomicBoolean()
    private var verifyStarted = CountDownLatch(0)
    private var verifyRelease = CountDownLatch(0)

    override fun issue(command: IssueVerificationChallengeCommand): VerificationChallengeIssueResult {
        observedTransaction.compareAndSet(false, TransactionSynchronizationManager.isActualTransactionActive())
        issueCalls.incrementAndGet()
        return VerificationChallengeIssueResult.Issued("test-provider:${command.challengeIntentId}")
    }

    override fun verify(command: VerifyChallengeCommand): VerificationChallengeVerifyResult {
        observedTransaction.compareAndSet(false, TransactionSynchronizationManager.isActualTransactionActive())
        verifyCalls.incrementAndGet()
        verifyStarted.countDown()
        verifyRelease.await(5, TimeUnit.SECONDS)
        val chars = command.proof.copyChars()
        return try {
            if (String(chars) ==
                "VALID_TEST_PROOF"
            ) {
                VerificationChallengeVerifyResult.VERIFIED
            } else {
                VerificationChallengeVerifyResult.INVALID
            }
        } finally {
            Arrays.fill(chars, '\u0000')
        }
    }

    fun reset() {
        issueCalls.set(0)
        verifyCalls.set(0)
        observedTransaction.set(false)
        verifyStarted = CountDownLatch(0)
        verifyRelease = CountDownLatch(0)
    }

    fun blockVerification() {
        verifyStarted = CountDownLatch(1)
        verifyRelease = CountDownLatch(1)
    }

    fun awaitVerificationStarted() {
        check(verifyStarted.await(5, TimeUnit.SECONDS))
    }

    fun releaseVerification() {
        verifyRelease.countDown()
    }
}

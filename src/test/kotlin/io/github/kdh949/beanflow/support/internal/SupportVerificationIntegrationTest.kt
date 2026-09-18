package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
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
        "beanflow.support-verification-recovery.initial-delay-ms=3600000",
    ],
)
internal class SupportVerificationIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val context: org.springframework.context.ApplicationContext,
        private val recoveryWorker: SupportVerificationRecoveryWorker,
    ) {
        private val actorId = UUID.fromString("44000000-0000-0000-0000-000000000001")
        private val now = Instant.parse("2026-08-12T00:00:00Z")

        @BeforeEach
        fun reset() {
            jdbcTemplate.execute("TRUNCATE TABLE support_case CASCADE")
            jdbcTemplate.execute("TRUNCATE TABLE operations_audit_record, operations_operator_permission_grant")
            grant(actorId, "SUPPORT_VERIFICATION_MANAGE")
        }

        @Test
        fun `retired writes return explicit gone without provider or persistent verification`() {
            val binding = insertBinding(actorId)
            val id = UUID.randomUUID()
            val requests =
                listOf(
                    "/api/v1/support/cases/${binding.caseId}/verification-sessions" to
                        """{"subjectLinkId":"${binding.linkId}","requestedLevel":"BASIC","purpose":"CASE_RESOLUTION"}""",
                    "/api/v1/support/verification-sessions/$id/challenges" to """{"channel":"REGISTERED_PHONE"}""",
                    "/api/v1/support/verification-challenges/$id/verifications" to """{"proof":"TRANSIENT_PROOF"}""",
                )
            requests.forEachIndexed { index, (path, body) ->
                mockMvc
                    .perform(post(path).with(operatorJwt(actorId)).header("Idempotency-Key", "retired-command-$index").json(body))
                    .andExpect(status().isGone)
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("SUPPORT_VERIFICATION_RETIRED"))
            }
            assertThat(context.getBeansOfType(VerificationChallengeOperations::class.java)).isEmpty()
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM support_verification_session", Long::class.java)).isZero()
        }

        @Test
        fun `retired writes ignore absent malformed and invalid input but still require authentication`() {
            val id = UUID.randomUUID()
            val paths =
                listOf(
                    "/api/v1/support/cases/$id/verification-sessions",
                    "/api/v1/support/verification-sessions/$id/challenges",
                    "/api/v1/support/verification-challenges/$id/verifications",
                )
            paths.forEach { path ->
                listOf("", "{", "{}", """{"proof":"","requestedLevel":"INVALID"}""").forEach { body ->
                    mockMvc
                        .perform(post(path).with(operatorJwt(actorId)).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isGone)
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.code").value("SUPPORT_VERIFICATION_RETIRED"))
                }
                mockMvc
                    .perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{"))
                    .andExpect(status().isUnauthorized)
            }
            assertThat(context.getBeansOfType(VerificationChallengeOperations::class.java)).isEmpty()
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM support_verification_session", Long::class.java)).isZero()
        }

        @Test
        fun `historical verified session remains readable only by authorized actor`() {
            val binding = insertBinding(actorId)
            val id = createSession(binding, "BASIC", "legacy-history")
            mockMvc
                .perform(get("/api/v1/support/verification-sessions/$id").with(operatorJwt(actorId)))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("VERIFIED"))
            mockMvc
                .perform(get("/api/v1/support/verification-sessions/$id").with(operatorJwt(UUID.randomUUID())))
                .andExpect(status().isForbidden)
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

        private fun createSession(
            binding: Binding,
            level: String,
            key: String,
        ): UUID {
            val id = UUID.randomUUID()
            jdbcTemplate.update(
                """INSERT INTO support_verification_session (id, support_case_id, subject_link_id, subject_type, subject_id, actor_id,
                   purpose, action_scope, requested_level, state, invalid_attempts, started_at, expires_at, verified_at, version)
                   VALUES (?, ?, ?, 'CUSTOMER', ?, ?, 'CASE_RESOLUTION', 'PERSONAL_DATA_REVEAL', ?, 'VERIFIED', 0, now(), now() + interval '15 minutes', now(), 1)""",
                id,
                binding.caseId,
                binding.linkId,
                binding.subjectId,
                actorId,
                level,
            )
            jdbcTemplate.update(
                """INSERT INTO support_verification_challenge (id, session_id, channel, state, opaque_provider_reference, requested_at, expires_at, completed_at, version)
                   VALUES (?, ?, 'REGISTERED_PHONE', 'VERIFIED', 'legacy-opaque-reference', now(), now() + interval '5 minutes', now(), 1)""",
                UUID.randomUUID(),
                id,
            )
            return id
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
    }

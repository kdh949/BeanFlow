package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.notification.api.BreakGlassSecurityNotificationOperations
import io.github.kdh949.beanflow.notification.api.BreakGlassSecurityNotificationResult
import io.github.kdh949.beanflow.notification.api.SendBreakGlassSecurityNotificationCommand
import io.github.kdh949.beanflow.shared.api.EncryptedPersonalData
import io.github.kdh949.beanflow.shared.api.PersonalDataEncryptionContext
import io.github.kdh949.beanflow.shared.api.PersonalDataField
import io.github.kdh949.beanflow.shared.api.PersonalDataOwnerContext
import io.github.kdh949.beanflow.shared.internal.VaultTransitPersonalDataAdapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Import(TestcontainersConfiguration::class, BreakGlassIntegrationTest.NotificationConfiguration::class)
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
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class BreakGlassIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val worker: BreakGlassSecurityNotificationWorker,
        private val notificationProvider: ScriptedSecurityNotificationProvider,
    ) {
        @MockitoBean
        private lateinit var crypto: VaultTransitPersonalDataAdapter

        private val requesterId = UUID.fromString("47000000-0000-0000-0000-000000000001")
        private val approverId = UUID.fromString("47000000-0000-0000-0000-000000000002")
        private val reviewerId = UUID.fromString("47000000-0000-0000-0000-000000000003")

        @BeforeEach
        fun reset() {
            jdbcTemplate.execute("TRUNCATE TABLE support_case CASCADE")
            jdbcTemplate.execute("TRUNCATE TABLE identity_customer_support_profile CASCADE")
            jdbcTemplate.execute("TRUNCATE TABLE operations_audit_record, operations_operator_permission_grant")
            Mockito.reset(crypto)
            notificationProvider.reset()
            grant(requesterId, "SUPPORT_BREAK_GLASS_REQUEST")
            grant(approverId, "SUPPORT_PII_REVEAL_APPROVE")
            grant(reviewerId, "PRIVACY_BREAK_GLASS_REVIEW")
        }

        @Test
        fun `break glass requires separated approval one-field reveal and separated post review`() {
            val binding = seedBinding()
            val requestId = request(binding)
            assertThat(countIntents(requestId)).isEqualTo(1)

            mockMvc
                .perform(
                    post("/api/v1/support/break-glass-requests/$requestId/approvals")
                        .with(operatorJwt(requesterId))
                        .header("Idempotency-Key", "break-glass-self-approve")
                        .json("""{"decision":"APPROVE","expectedVersion":0}"""),
                ).andExpect(status().isForbidden)
            mockMvc
                .perform(
                    post("/api/v1/support/break-glass-requests/$requestId/approvals")
                        .with(operatorJwt(approverId))
                        .header("Idempotency-Key", "break-glass-approve-001")
                        .json("""{"decision":"APPROVE","expectedVersion":0}"""),
                ).andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("ACTIVE"))
            assertThat(countIntents(requestId)).isEqualTo(2)

            mockMvc
                .perform(
                    post("/api/v1/support/break-glass-requests/$requestId/reveals")
                        .with(operatorJwt(requesterId))
                        .header("Idempotency-Key", "break-glass-wrong-field")
                        .json("""{"field":"CUSTOMER_DISPLAY_NAME"}"""),
                ).andExpect(status().isForbidden)
            mockMvc
                .perform(
                    post("/api/v1/support/break-glass-requests/$requestId/reveals")
                        .with(operatorJwt(requesterId))
                        .header("Idempotency-Key", "break-glass-reveal-001")
                        .json("""{"field":"CUSTOMER_PRIMARY_EMAIL"}"""),
                ).andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.value").value("emergency@example.invalid"))
            assertThat(countIntents(requestId)).isEqualTo(3)
            assertThat(
                jdbcTemplate.queryForObject("SELECT state FROM support_break_glass_request WHERE id = ?", String::class.java, requestId),
            ).isEqualTo("REVIEW_PENDING")
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM operations_audit_record WHERE action = 'SUPPORT_PII_ACCESS_RECORDED' AND reason = 'PRIVACY_INCIDENT'",
                    Long::class.java,
                ),
            ).isOne()

            mockMvc
                .perform(
                    post("/api/v1/support/break-glass-requests/$requestId/reviews")
                        .with(operatorJwt(approverId))
                        .header("Idempotency-Key", "break-glass-review-approver")
                        .json(
                            """{"decision":"CONFIRMED","expectedVersion":2,"reasonCode":"POLICY_CONFIRMED"}""",
                        ),
                ).andExpect(status().isForbidden)
            mockMvc
                .perform(
                    post("/api/v1/support/break-glass-requests/$requestId/reviews")
                        .with(operatorJwt(reviewerId))
                        .header("Idempotency-Key", "break-glass-review-001")
                        .json(
                            """{"decision":"CONFIRMED","expectedVersion":2,"reasonCode":"POLICY_CONFIRMED"}""",
                        ),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("REVIEWED"))

            worker.dispatchDue()
            assertThat(notificationProvider.calls.get()).isEqualTo(3)
            assertThat(notificationProvider.observedTransaction.get()).isFalse()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM support_security_notification_intent WHERE break_glass_request_id = ? AND state = 'SENT'",
                    Long::class.java,
                    requestId,
                ),
            ).isEqualTo(3)
        }

        @Test
        fun `unknown security notification result is retained for retry`() {
            val requestId = request(seedBinding())
            notificationProvider.nextResult = BreakGlassSecurityNotificationResult.UNKNOWN

            worker.dispatchDue()

            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT state FROM support_security_notification_intent WHERE break_glass_request_id = ? AND event_type = 'REQUESTED'",
                    String::class.java,
                    requestId,
                ),
            ).isEqualTo("RETRY_SCHEDULED")
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT last_failure_class FROM support_security_notification_intent WHERE break_glass_request_id = ?",
                    String::class.java,
                    requestId,
                ),
            ).isEqualTo("UNKNOWN")
        }

        @Test
        fun `stale processing security notification is reclaimed after worker interruption`() {
            val requestId = request(seedBinding())
            jdbcTemplate.update(
                """
                UPDATE support_security_notification_intent
                   SET state = 'PROCESSING', attempt_count = 1, updated_at = now() - INTERVAL '6 minutes'
                 WHERE break_glass_request_id = ? AND event_type = 'REQUESTED'
                """.trimIndent(),
                requestId,
            )

            worker.dispatchDue()

            assertThat(notificationProvider.calls.get()).isOne()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT state FROM support_security_notification_intent WHERE break_glass_request_id = ? AND event_type = 'REQUESTED'",
                    String::class.java,
                    requestId,
                ),
            ).isEqualTo("SENT")
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT attempt_count FROM support_security_notification_intent WHERE break_glass_request_id = ? AND event_type = 'REQUESTED'",
                    Int::class.java,
                    requestId,
                ),
            ).isEqualTo(2)
        }

        private fun request(binding: Binding): UUID {
            val result =
                mockMvc
                    .perform(
                        post("/api/v1/support/cases/${binding.caseId}/break-glass-requests")
                            .with(operatorJwt(requesterId))
                            .header("Idempotency-Key", "break-glass-request-${binding.caseId}")
                            .json(
                                """
                                {"subjectLinkId":"${binding.linkId}","field":"CUSTOMER_PRIMARY_EMAIL",
                                 "purpose":"PRIVACY_INCIDENT","reasonCode":"PRIVACY_INCIDENT"}
                                """.trimIndent(),
                            ),
                    ).andExpect(status().isCreated)
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.state").value("APPROVAL_PENDING"))
                    .andReturn()
            return UUID.fromString(JsonMapper.builder().build().readTree(result.response.contentAsString)["requestId"].asText())
        }

        private fun seedBinding(): Binding {
            val now = Instant.now().minusSeconds(30)
            val caseId = UUID.randomUUID()
            val linkId = UUID.randomUUID()
            val subjectId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO support_case (
                    id, requester_type, requester_reference, category, priority, reason, state,
                    current_assignee_id, opened_at, last_changed_at, version, retention_policy_version_id
                ) VALUES (?, 'CUSTOMER', 'customer-reference', 'PRIVACY', 'URGENT', 'PRIVACY_INCIDENT', 'IN_PROGRESS',
                          ?, ?, ?, 1, 7)
                """.trimIndent(),
                caseId,
                requesterId,
                Timestamp.from(now),
                Timestamp.from(now),
            )
            jdbcTemplate.update(
                """
                INSERT INTO support_case_subject_link (
                    id, support_case_id, subject_type, subject_id, relationship, linked_by_actor_id, reason, linked_at
                ) VALUES (?, ?, 'CUSTOMER', ?, 'REQUESTER', ?, 'PRIVACY_SUBJECT', ?)
                """.trimIndent(),
                linkId,
                caseId,
                subjectId,
                requesterId,
                Timestamp.from(now),
            )
            jdbcTemplate.update(
                """
                INSERT INTO identity_customer_support_profile (
                    customer_id, display_name_ciphertext, display_name_key_version, display_name_aad_version,
                    masked_display_name, primary_email_ciphertext, primary_email_key_version,
                    primary_email_aad_version, masked_primary_email, created_at, updated_at
                ) VALUES (?, 'vault:v7:display', 7, 1, 'E*******y', 'vault:v7:email', 7, 1,
                          'e***@e***.invalid', ?, ?)
                """.trimIndent(),
                subjectId,
                Timestamp.from(now),
                Timestamp.from(now),
            )
            Mockito.doAnswer {
                check(!TransactionSynchronizationManager.isActualTransactionActive())
                "emergency@example.invalid".toByteArray(StandardCharsets.UTF_8)
            }.`when`(crypto).decrypt(
                EncryptedPersonalData("vault:v7:email", 7, 1),
                PersonalDataEncryptionContext(PersonalDataOwnerContext.IDENTITY, subjectId, PersonalDataField.PRIMARY_EMAIL),
            )
            return Binding(caseId, linkId)
        }

        private fun countIntents(requestId: UUID): Long =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM support_security_notification_intent WHERE break_glass_request_id = ?",
                Long::class.java,
                requestId,
            )!!

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
                "break-glass-test-grant:$permission:$actor",
            )
        }

        private fun operatorJwt(actor: UUID) = jwt().jwt { it.subject(actor.toString()) }

        private fun MockHttpServletRequestBuilder.json(body: String) = contentType(MediaType.APPLICATION_JSON).content(body)

        private data class Binding(
            val caseId: UUID,
            val linkId: UUID,
        )

        @TestConfiguration(proxyBeanMethods = false)
        internal class NotificationConfiguration {
            @Bean
            fun scriptedSecurityNotificationProvider(): ScriptedSecurityNotificationProvider = ScriptedSecurityNotificationProvider()
        }
    }

internal class ScriptedSecurityNotificationProvider : BreakGlassSecurityNotificationOperations {
    val calls = AtomicInteger()
    val observedTransaction = AtomicBoolean()
    var nextResult: BreakGlassSecurityNotificationResult = BreakGlassSecurityNotificationResult.SENT

    override fun send(command: SendBreakGlassSecurityNotificationCommand): BreakGlassSecurityNotificationResult {
        calls.incrementAndGet()
        if (TransactionSynchronizationManager.isActualTransactionActive()) observedTransaction.set(true)
        return nextResult
    }

    fun reset() {
        calls.set(0)
        observedTransaction.set(false)
        nextResult = BreakGlassSecurityNotificationResult.SENT
    }
}

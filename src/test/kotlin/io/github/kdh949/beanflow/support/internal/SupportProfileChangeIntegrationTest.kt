package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.notification.api.AcceptedProfileChangeNotification
import io.github.kdh949.beanflow.notification.api.ProfileChangeNotificationOperations
import io.github.kdh949.beanflow.notification.api.RequestProfileChangeNotificationCommand
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OperationsSupportInvestigationDecision
import io.github.kdh949.beanflow.operations.internal.DecideOperationsSupportInvestigationCommand
import io.github.kdh949.beanflow.operations.internal.OperationsSupportInvestigationOutcome
import io.github.kdh949.beanflow.operations.internal.OperationsSupportInvestigationService
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.support.internal.domain.ProfileRiskClass
import io.github.kdh949.beanflow.support.internal.domain.SupportActionRequestState
import io.github.kdh949.beanflow.support.internal.domain.SupportApprovalDecision
import io.github.kdh949.beanflow.support.internal.domain.SupportProfileChangeState
import io.github.kdh949.beanflow.support.internal.domain.SupportProfileNotificationState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@Import(
    TestcontainersConfiguration::class,
    OwnerProfileChangeTestConfiguration::class,
    SupportProfileChangeFlowTestConfiguration::class,
)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest(
    properties = [
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
        "beanflow.support-case-idempotency.retention.initial-delay-ms=3600000",
    ],
)
internal class SupportProfileChangeIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val profiles: SupportProfileChangeApplicationService,
        private val profileTransactions: SupportProfileChangeTransactionService,
        private val actionRequests: SupportActionRequestApplicationService,
        private val operations: OperationsSupportInvestigationService,
        private val jdbcTemplate: JdbcTemplate,
        private val audits: ControllableProfileAuditOperations,
        private val notifications: ControllableProfileNotificationOperations,
    ) {
        private val requesterId = UUID.fromString("83000000-0000-0000-0000-000000000001")
        private val managerId = UUID.fromString("83000000-0000-0000-0000-000000000002")
        private val operationsId = UUID.fromString("83000000-0000-0000-0000-000000000003")
        private val replacementId = UUID.fromString("83000000-0000-0000-0000-000000000004")
        private val customerId = UUID.fromString("83000000-0000-0000-0000-000000000011")
        private lateinit var caseId: UUID
        private lateinit var sessionId: UUID

        @BeforeEach
        fun resetAndSeed() {
            jdbcTemplate.execute(
                "TRUNCATE TABLE notification_customer_preference, notification_inbox_item, notification_delivery CASCADE",
            )
            jdbcTemplate.execute("TRUNCATE TABLE identity_customer_support_profile CASCADE")
            jdbcTemplate.execute("TRUNCATE TABLE support_case CASCADE")
            jdbcTemplate.execute("TRUNCATE TABLE operations_audit_record, operations_operator_permission_grant CASCADE")
            audits.reset()
            notifications.reset()
            seedCustomer()
            seedSupportScope("REGISTERED_PHONE")
            listOf(
                "SUPPORT_CASE_READ",
                "SUPPORT_CASE_WRITE",
                "SUPPORT_ACTION_REQUEST",
                "SUPPORT_ACTION_EXECUTE",
                "SUPPORT_PROFILE_R1_CHANGE",
                "SUPPORT_PROFILE_R2_CHANGE",
                "SUPPORT_PROFILE_R3_REQUEST",
            ).forEach { grant(requesterId, it) }
            listOf(
                "SUPPORT_CASE_READ",
                "SUPPORT_ACTION_APPROVE",
                "SUPPORT_PROFILE_R3_APPROVE",
            ).forEach { grant(managerId, it) }
            grant(operationsId, "OPERATIONS_SUPPORT_INVESTIGATION")
        }

        @Test
        fun `typed R1 API is no-store masked and replays after owner version advances`() {
            val request =
                displayNameApi("profile-display-001", "김지원")
                    .andExpect(status().isCreated)
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.riskClass").value("R1"))
                    .andExpect(jsonPath("$.state").value("EXECUTED"))
                    .andExpect(jsonPath("$.notificationState").value("ACCEPTED"))
                    .andExpect(jsonPath("$.maskedAfter").value("김*원"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("김지원"))))
                    .andReturn()

            displayNameApi("profile-display-001", "김지원")
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.profileChangeId").value(resourceId(request.response.contentAsString).toString()))
            displayNameApi("profile-display-001", "다른이름")
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))

            assertThat(count("identity_customer_profile_change_history")).isOne()
            assertThat(currentCustomerVersion()).isEqualTo(1)
            assertThat(audits.commands.map { it.action }).contains("SUPPORT_PROFILE_CHANGE_EXECUTED")
        }

        @Test
        fun `new phone alone cannot replace a registered-channel verification`() {
            jdbcTemplate.update("DELETE FROM support_verification_challenge WHERE session_id = ?", sessionId)
            seedChallenge("IN_APP")

            assertThatThrownBy { profiles.submit(primaryPhoneCommand("phone-new-channel-only")) }
                .isInstanceOf(DomainFailure::class.java)
                .extracting("code")
                .isEqualTo(FailureCode.VERIFICATION_REQUIRED)
            assertThat(count("support_profile_change")).isZero()
            assertThat(currentCustomerVersion()).isZero()
        }

        @Test
        fun `composite typed APIs reject all-null and blank payloads as bad requests`() {
            val binding =
                """
                "binding": {
                  "subjectId": "$customerId",
                  "expectedProfileVersion": 0,
                  "verificationSessionId": "$sessionId",
                  "reason": "Profile correction requested",
                  "evidenceDigest": "$EVIDENCE_DIGEST"
                }
                """.trimIndent()
            listOf(
                "/store-public-profile-corrections" to
                    """{$binding,"displayName":null,"publicPhone":null,"description":null,"pickupInstructions":null}""",
                "/store-operations-contact-corrections" to """{$binding,"phone":null,"email":null}""",
                "/courier-relay-contact-corrections" to """{$binding,"phone":null,"email":null}""",
                "/store-public-profile-corrections" to
                    """{$binding,"displayName":"   ","publicPhone":null,"description":null,"pickupInstructions":null}""",
                "/store-operations-contact-corrections" to """{$binding,"phone":"   ","email":null}""",
                "/courier-relay-contact-corrections" to """{$binding,"phone":null,"email":"   "}""",
            ).forEachIndexed { index, (path, body) ->
                mockMvc
                    .perform(
                        post("/api/v1/support/cases/$caseId/profile-changes$path")
                            .with(jwt().jwt { it.subject(requesterId.toString()) })
                            .header("Idempotency-Key", "invalid-composite-$index")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body),
                    ).andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            }
        }

        @Test
        fun `missing customer store and courier owner profiles return not found contracts`() {
            val missing = UUID.randomUUID()
            val binding =
                """
                "binding": {
                  "subjectId": "$missing",
                  "expectedProfileVersion": 0,
                  "verificationSessionId": "$sessionId",
                  "reason": "Missing owner contract check",
                  "evidenceDigest": "$EVIDENCE_DIGEST"
                }
                """.trimIndent()
            listOf(
                "/customer-display-name-corrections" to """{$binding,"displayName":"valid name"}""",
                "/store-public-profile-corrections" to """{$binding,"displayName":"valid store"}""",
                "/courier-relay-contact-corrections" to """{$binding,"email":"valid@example.com"}""",
            ).forEachIndexed { index, (path, body) ->
                mockMvc
                    .perform(
                        post("/api/v1/support/cases/$caseId/profile-changes$path")
                            .with(jwt().jwt { it.subject(requesterId.toString()) })
                            .header("Idempotency-Key", "missing-owner-$index")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body),
                    ).andExpect(status().isNotFound)
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            }
        }

        @Test
        fun `R3 exact payload requires different manager and operations approvers before execution`() {
            val created = profiles.submit(primaryPhoneCommand("phone-dual-create"))
            assertThat(created.riskClass).isEqualTo(ProfileRiskClass.R3)
            assertThat(created.state).isEqualTo(SupportProfileChangeState.AWAITING_APPROVAL)

            grant(requesterId, "SUPPORT_ACTION_APPROVE")
            grant(requesterId, "SUPPORT_PROFILE_R3_APPROVE")
            assertThatThrownBy {
                decideManager(requireNotNull(created.actionRequestId), requesterId, "phone-self-approval")
            }.isInstanceOf(DomainFailure::class.java)
                .extracting("code")
                .isEqualTo(FailureCode.SUPPORT_APPROVER_MUST_DIFFER)

            val managerApproved =
                decideManager(requireNotNull(created.actionRequestId), managerId, "phone-manager-approval")
                    as SupportActionCommandOutcome.Succeeded
            assertThat(managerApproved.resource.state).isEqualTo(SupportActionRequestState.AWAITING_OPERATIONS)
            val operationsApproved = approveOperations(requireNotNull(created.actionRequestId))
            val approvedRequest = operationsApproved.resource
            assertThat(approvedRequest.supportRequestState).isEqualTo("READY_FOR_EXECUTION")

            val executed =
                profiles.execute(
                    ExecuteSupportProfileChangeCommand(
                        requesterId,
                        created.profileChangeId,
                        1,
                        approvedRequest.supportRequestVersion,
                        created.version,
                        0,
                        "phone-dual-execute",
                        SupportProfileChangePayload.CustomerPrimaryPhone("010-5555-7777"),
                    ),
                )
            assertThat(executed.state).isEqualTo(SupportProfileChangeState.EXECUTED)
            assertThat(executed.notificationState).isEqualTo(SupportProfileNotificationState.ACCEPTED)
            assertThat(executed.currentProfileVersion).isEqualTo(1)
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT terminal_profile_change_id FROM support_action_request WHERE id = ?",
                    UUID::class.java,
                    created.actionRequestId,
                ),
            ).isEqualTo(created.profileChangeId)
        }

        @Test
        fun `approved execution fails closed after subject link is removed`() {
            val (created, approved) = approvePrimaryPhone("phone-link-revoked")
            jdbcTemplate.update(
                "UPDATE support_case_subject_link SET unlinked_at = now(), unlinked_by_actor_id = ?, " +
                    "unlink_reason = 'REVIEW_TEST_UNLINK', unlink_case_version = 1 WHERE support_case_id = ? AND subject_id = ?",
                requesterId,
                caseId,
                customerId,
            )

            assertExecutionDenied(created, approved, FailureCode.ACCESS_DENIED)
            assertThat(currentCustomerVersion()).isZero()
        }

        @Test
        fun `approved execution fails closed after requester permission is revoked`() {
            val (created, approved) = approvePrimaryPhone("phone-permission-revoked")
            jdbcTemplate.update(
                "UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now() " +
                    "WHERE actor_id = ? AND permission = 'SUPPORT_PROFILE_R3_REQUEST'",
                requesterId,
            )

            assertExecutionDenied(created, approved, FailureCode.ACCESS_DENIED)
            assertThat(currentCustomerVersion()).isZero()
        }

        @Test
        fun `approved primary-phone execution fails after registered-channel challenge is invalidated`() {
            val (created, approved) = approvePrimaryPhone("phone-challenge-invalidated")
            jdbcTemplate.update(
                "UPDATE support_verification_challenge SET state = 'EXPIRED' WHERE session_id = ?",
                sessionId,
            )

            assertExecutionDenied(created, approved, FailureCode.VERIFICATION_REQUIRED)
            assertThat(currentCustomerVersion()).isZero()
        }

        @Test
        fun `approved request becomes stale when the owner profile version changes`() {
            val created = profiles.submit(primaryPhoneCommand("phone-stale-create"))
            profiles.submit(displayNameCommand("display-version-advance", "버전변경"))

            val outcome = decideManager(requireNotNull(created.actionRequestId), managerId, "phone-stale-manager")
            assertThat(outcome).isInstanceOf(SupportActionCommandOutcome.Failed::class.java)
            assertThat((outcome as SupportActionCommandOutcome.Failed).code)
                .isEqualTo(FailureCode.SUPPORT_ACTION_REQUEST_STALE)
            assertThat(currentCustomerVersion()).isEqualTo(1)
            assertThat(count("identity_customer_profile_change_history")).isOne()
        }

        @Test
        fun `inactive original executor requires explicit case and profile reassignment`() {
            val created = profiles.submit(primaryPhoneCommand("phone-reassign-create"))
            decideManager(requireNotNull(created.actionRequestId), managerId, "phone-reassign-manager")
            val approved = approveOperations(requireNotNull(created.actionRequestId)).resource
            jdbcTemplate.update(
                "UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now() " +
                    "WHERE actor_id = ? AND permission = 'SUPPORT_ACTION_EXECUTE'",
                requesterId,
            )

            val reassignmentRequired = actionRequests.get(managerId, requireNotNull(created.actionRequestId))
            assertThat(reassignmentRequired.state).isEqualTo(SupportActionRequestState.REASSIGNMENT_REQUIRED)
            grant(managerId, "SUPPORT_CASE_ASSIGN")
            listOf(
                "SUPPORT_CASE_READ",
                "SUPPORT_CASE_WRITE",
                "SUPPORT_ACTION_EXECUTE",
                "SUPPORT_PROFILE_R3_REQUEST",
            ).forEach { grant(replacementId, it) }

            val reassigned =
                actionRequests.reassign(
                    ReassignSupportActionRequestCommand(
                        managerId,
                        requireNotNull(created.actionRequestId),
                        1,
                        reassignmentRequired.requestVersion,
                        0,
                        replacementId,
                        "Original executor permission was revoked",
                        "phone-reassign-command",
                    ),
                )
            assertThat(reassigned.executorActorId).isEqualTo(replacementId)
            assertThat(reassigned.state).isEqualTo(SupportActionRequestState.READY_FOR_EXECUTION)
            assertThat(profiles.get(replacementId, created.profileChangeId).executorActorId).isEqualTo(replacementId)
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT current_assignee_id FROM support_case WHERE id = ?",
                    UUID::class.java,
                    caseId,
                ),
            ).isEqualTo(replacementId)
            assertThat(reassigned.requestVersion).isGreaterThan(approved.supportRequestVersion)
        }

        @Test
        fun `audit failure rolls back owner and support while notification failure remains retryable`() {
            audits.failNext()
            assertThatThrownBy { profiles.submit(displayNameCommand("audit-rollback", "감사실패")) }
                .isInstanceOf(DomainFailure::class.java)
                .extracting("code")
                .isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
            assertThat(currentCustomerVersion()).isZero()
            assertThat(count("support_profile_change")).isZero()
            assertThat(count("identity_customer_profile_change_history")).isZero()

            notifications.fail = true
            val failed = profiles.submit(displayNameCommand("notification-failure", "알림실패"))
            assertThat(failed.state).isEqualTo(SupportProfileChangeState.EXECUTED)
            assertThat(failed.notificationState).isEqualTo(SupportProfileNotificationState.RETRY_SCHEDULED)
            assertThat(currentCustomerVersion()).isEqualTo(1)

            notifications.fail = false
            val retried =
                profiles.retryNotifications(
                    RetrySupportProfileNotificationCommand(
                        requesterId,
                        failed.profileChangeId,
                        failed.version,
                        "notification-retry-001",
                    ),
                )
            assertThat(retried.notificationState).isEqualTo(SupportProfileNotificationState.ACCEPTED)
            val replayed =
                profiles.retryNotifications(
                    RetrySupportProfileNotificationCommand(
                        requesterId,
                        failed.profileChangeId,
                        failed.version,
                        "notification-retry-001",
                    ),
                )
            assertThat(replayed.notificationState).isEqualTo(SupportProfileNotificationState.ACCEPTED)
            assertThat(count("support_profile_change_idempotency", "operation = 'RETRY_PROFILE_NOTIFICATIONS'"))
                .isOne()
        }

        @Test
        fun `expired notification claim rejoins committed delivery without repeating owner write`() {
            val command = displayNameCommand("delivery-accept-gap", "복구대상")
            val executed = profiles.submit(command)
            val firstDispatch = notifications.commands.single()
            val deliveryCountAfterFirstDispatch = count("notification_delivery")
            val staleClaim = UUID.randomUUID()
            jdbcTemplate.update(
                """
                UPDATE support_profile_change_notification
                   SET delivery_id = NULL, state = 'PROCESSING', failure_code = NULL,
                       claim_id = ?, claim_expires_at = now() - interval '1 second', updated_at = now()
                 WHERE profile_change_id = ?
                """.trimIndent(),
                staleClaim,
                executed.profileChangeId,
            )
            jdbcTemplate.update(
                """
                UPDATE support_profile_change
                   SET notification_state = 'PENDING', notification_failure_code = NULL,
                       updated_at = now(), version = version + 1
                 WHERE id = ?
                """.trimIndent(),
                executed.profileChangeId,
            )

            assertThat(profiles.recoverNotifications()).isOne()
            val recovered = profiles.submit(command)

            assertThat(recovered.notificationState).isEqualTo(SupportProfileNotificationState.ACCEPTED)
            assertThat(count("identity_customer_profile_change_history")).isOne()
            assertThat(count("notification_delivery")).isEqualTo(deliveryCountAfterFirstDispatch)
            assertThat(notifications.commands).hasSize(2)
            assertThat(notifications.commands.last().occurredAt).isEqualTo(firstDispatch.occurredAt)
            assertThat(notifications.commands.last().correlationId).isEqualTo(firstDispatch.correlationId)
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT delivery_id FROM support_profile_change_notification WHERE profile_change_id = ?",
                    UUID::class.java,
                    executed.profileChangeId,
                ),
            ).isNotNull()
        }

        @Test
        fun `notification lease admits one claimant and ignores a stale claimant result`() {
            val executed = profiles.submit(displayNameCommand("notification-lease", "임대검증"))
            val lineId =
                requireNotNull(
                    jdbcTemplate.queryForObject(
                        "SELECT id FROM support_profile_change_notification WHERE profile_change_id = ?",
                        UUID::class.java,
                        executed.profileChangeId,
                    ),
                )
            val deliveryId =
                requireNotNull(
                    jdbcTemplate.queryForObject(
                        "SELECT delivery_id FROM support_profile_change_notification WHERE id = ?",
                        UUID::class.java,
                        lineId,
                    ),
                )
            jdbcTemplate.update(
                """
                UPDATE support_profile_change_notification
                   SET delivery_id = NULL, state = 'RETRY_SCHEDULED', failure_code = 'DEPENDENCY_UNAVAILABLE',
                       claim_id = NULL, claim_expires_at = NULL, updated_at = now()
                 WHERE id = ?
                """.trimIndent(),
                lineId,
            )
            val firstClaim = UUID.randomUUID()
            val staleClaim = UUID.randomUUID()

            val claimed = profileTransactions.claimNotifications(executed.profileChangeId, firstClaim, true)
            val concurrentlyClaimed = profileTransactions.claimNotifications(executed.profileChangeId, staleClaim, true)
            profileTransactions.acceptNotification(lineId, firstClaim, deliveryId)
            profileTransactions.failNotification(lineId, staleClaim, "LATE_STALE_RESULT")

            assertThat(claimed).hasSize(1)
            assertThat(concurrentlyClaimed).isEmpty()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT state FROM support_profile_change_notification WHERE id = ?",
                    String::class.java,
                    lineId,
                ),
            ).isEqualTo("ACCEPTED")
        }

        private fun displayNameApi(
            key: String,
            name: String,
        ) = mockMvc.perform(
            post("/api/v1/support/cases/$caseId/profile-changes/customer-display-name-corrections")
                .with(jwt().jwt { it.subject(requesterId.toString()) })
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "binding": {
                        "subjectId": "$customerId",
                        "expectedProfileVersion": 0,
                        "verificationSessionId": "$sessionId",
                        "reason": "Customer requested a display-name correction",
                        "evidenceDigest": "$EVIDENCE_DIGEST"
                      },
                      "displayName": "$name"
                    }
                    """.trimIndent(),
                ),
        )

        private fun displayNameCommand(
            key: String,
            name: String,
        ) = SubmitSupportProfileChangeCommand(
            requesterId,
            caseId,
            customerId,
            currentCustomerVersion(),
            sessionId,
            "Customer requested a display-name correction",
            EVIDENCE_DIGEST,
            key,
            SupportProfileChangePayload.CustomerDisplayName(name),
        )

        private fun primaryPhoneCommand(key: String) =
            SubmitSupportProfileChangeCommand(
                requesterId,
                caseId,
                customerId,
                0,
                sessionId,
                "Customer requested a primary-phone change",
                EVIDENCE_DIGEST,
                key,
                SupportProfileChangePayload.CustomerPrimaryPhone("010-5555-7777"),
            )

        private fun approvePrimaryPhone(key: String): Pair<SupportProfileChangeResource, Long> {
            val created = profiles.submit(primaryPhoneCommand("$key-create"))
            decideManager(requireNotNull(created.actionRequestId), managerId, "$key-manager")
            val approved = approveOperations(requireNotNull(created.actionRequestId)).resource
            return created to approved.supportRequestVersion
        }

        private fun assertExecutionDenied(
            created: SupportProfileChangeResource,
            approvedRequestVersion: Long,
            expected: FailureCode,
        ) {
            assertThatThrownBy {
                profiles.execute(
                    ExecuteSupportProfileChangeCommand(
                        requesterId,
                        created.profileChangeId,
                        1,
                        approvedRequestVersion,
                        created.version,
                        0,
                        "execute-denied-${created.profileChangeId}",
                        SupportProfileChangePayload.CustomerPrimaryPhone("010-5555-7777"),
                    ),
                )
            }.isInstanceOf(DomainFailure::class.java)
                .extracting("code")
                .isEqualTo(expected)
        }

        private fun decideManager(
            requestId: UUID,
            actorId: UUID,
            key: String,
        ) = actionRequests.decideSupportManager(
            DecideSupportManagerApprovalCommand(
                actorId,
                requestId,
                1,
                0,
                SupportApprovalDecision.APPROVE,
                "Exact profile payload and verification binding reviewed",
                key,
            ),
        )

        private fun approveOperations(requestId: UUID): OperationsSupportInvestigationOutcome.Succeeded {
            val investigationId =
                requireNotNull(
                    jdbcTemplate.queryForObject(
                        "SELECT id FROM operations_support_investigation_case WHERE support_action_request_id = ?",
                        UUID::class.java,
                        requestId,
                    ),
                )
            return operations.decide(
                DecideOperationsSupportInvestigationCommand(
                    operationsId,
                    investigationId,
                    0,
                    OperationsSupportInvestigationDecision.APPROVE,
                    "Cross-organization profile risk review approved",
                    EVIDENCE_DIGEST,
                    "operations-$requestId",
                    Instant.now(),
                ),
            ) as OperationsSupportInvestigationOutcome.Succeeded
        }

        private fun seedCustomer() {
            val createdAt = Timestamp.from(Instant.now().minusSeconds(120))
            jdbcTemplate.update(
                """
                INSERT INTO identity_customer_support_profile (
                    customer_id, display_name_ciphertext, display_name_key_version, display_name_aad_version,
                    masked_display_name, primary_phone_ciphertext, primary_phone_key_version,
                    primary_phone_aad_version, masked_primary_phone, created_at, updated_at, version
                ) VALUES (?, 'vault:v7:name', 7, 1, '기*값', 'vault:v7:old-phone', 7, 1,
                          '***-****-1111', ?, ?, 0)
                """.trimIndent(),
                customerId,
                createdAt,
                createdAt,
            )
        }

        private fun seedSupportScope(challengeChannel: String) {
            val now = Instant.now().minusSeconds(30)
            caseId = UUID.randomUUID()
            sessionId = UUID.randomUUID()
            val linkId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO support_case (
                    id, requester_type, requester_reference, category, priority, reason, state,
                    current_assignee_id, opened_at, last_changed_at, version, retention_policy_version_id
                ) VALUES (?, 'CUSTOMER', 'masked-reference', 'OTHER', 'NORMAL', 'PROFILE_CHANGE', 'OPEN',
                          ?, ?, ?, 0, 7)
                """.trimIndent(),
                caseId,
                requesterId,
                Timestamp.from(now),
                Timestamp.from(now),
            )
            jdbcTemplate.update(
                """
                INSERT INTO support_case_assignment_history (
                    id, support_case_id, sequence, previous_assignee_id, current_assignee_id,
                    actor_id, case_version, occurred_at
                ) VALUES (?, ?, 0, NULL, ?, ?, 0, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                caseId,
                requesterId,
                requesterId,
                Timestamp.from(now),
            )
            jdbcTemplate.update(
                """
                INSERT INTO support_case_subject_link (
                    id, support_case_id, subject_type, subject_id, relationship,
                    linked_by_actor_id, reason, linked_at
                ) VALUES (?, ?, 'CUSTOMER', ?, 'REQUESTER', ?, 'PROFILE_SUBJECT', ?)
                """.trimIndent(),
                linkId,
                caseId,
                customerId,
                requesterId,
                Timestamp.from(now),
            )
            jdbcTemplate.update(
                """
                INSERT INTO support_verification_session (
                    id, support_case_id, subject_link_id, subject_type, subject_id, actor_id, purpose,
                    action_scope, requested_level, state, invalid_attempts, started_at, expires_at,
                    verified_at, version
                ) VALUES (?, ?, ?, 'CUSTOMER', ?, ?, 'CASE_RESOLUTION', 'SUPPORT_ACTION',
                          'ENHANCED', 'VERIFIED', 0, ?, ?, ?, 1)
                """.trimIndent(),
                sessionId,
                caseId,
                linkId,
                customerId,
                requesterId,
                Timestamp.from(now),
                Timestamp.from(now.plusSeconds(900)),
                Timestamp.from(now.plusSeconds(1)),
            )
            seedChallenge(challengeChannel)
        }

        private fun seedChallenge(channel: String) {
            val requestedAt = Instant.now().minusSeconds(30)
            jdbcTemplate.update(
                """
                INSERT INTO support_verification_challenge (
                    id, session_id, channel, state, opaque_provider_reference,
                    requested_at, expires_at, completed_at, version
                ) VALUES (?, ?, ?, 'VERIFIED', 'provider-reference', ?, ?, ?, 1)
                """.trimIndent(),
                UUID.randomUUID(),
                sessionId,
                channel,
                Timestamp.from(requestedAt),
                Timestamp.from(requestedAt.plusSeconds(300)),
                Timestamp.from(requestedAt.plusSeconds(1)),
            )
        }

        private fun grant(
            actorId: UUID,
            permission: String,
        ) {
            jdbcTemplate.update(
                """
                INSERT INTO operations_operator_permission_grant (
                    actor_id, permission, state, granted_at, version, audit_source_reference
                ) VALUES (?, ?, 'ACTIVE', now(), 1, ?)
                ON CONFLICT (actor_id, permission) DO UPDATE
                    SET state = 'ACTIVE', revoked_at = NULL,
                        version = operations_operator_permission_grant.version + 1,
                        audit_source_reference = EXCLUDED.audit_source_reference
                """.trimIndent(),
                actorId,
                permission,
                "profile-flow:$permission:$actorId:${UUID.randomUUID()}",
            )
        }

        private fun currentCustomerVersion(): Long =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT version FROM identity_customer_support_profile WHERE customer_id = ?",
                    Long::class.java,
                    customerId,
                ),
            )

        private fun count(
            table: String,
            predicate: String? = null,
        ): Int =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM $table" + if (predicate == null) "" else " WHERE $predicate",
                    Int::class.java,
                ),
            )

        private fun resourceId(body: String): UUID =
            UUID.fromString(requireNotNull(Regex("\\\"profileChangeId\\\":\\\"([^\\\"]+)\\\"").find(body)).groupValues[1])

        private companion object {
            const val EVIDENCE_DIGEST = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        }
    }

@TestConfiguration(proxyBeanMethods = false)
internal class SupportProfileChangeFlowTestConfiguration {
    @Bean
    @Primary
    fun controllableProfileAudits(): ControllableProfileAuditOperations = ControllableProfileAuditOperations()

    @Bean
    @Primary
    fun controllableProfileNotifications(jdbcTemplate: JdbcTemplate): ControllableProfileNotificationOperations =
        ControllableProfileNotificationOperations(jdbcTemplate)
}

internal class ControllableProfileAuditOperations : AuditRecordOperations {
    val commands = CopyOnWriteArrayList<AppendAuditRecordCommand>()
    private val shouldFail = AtomicBoolean()

    fun reset() {
        commands.clear()
        shouldFail.set(false)
    }

    fun failNext() {
        shouldFail.set(true)
    }

    override fun appendAll(commands: List<AppendAuditRecordCommand>): List<UUID> {
        if (shouldFail.compareAndSet(true, false)) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Audit persistence failed")
        }
        this.commands += commands
        return commands.map { UUID.nameUUIDFromBytes(it.sourceReference.toByteArray(StandardCharsets.UTF_8)) }
    }
}

internal class ControllableProfileNotificationOperations(
    private val jdbcTemplate: JdbcTemplate,
) : ProfileChangeNotificationOperations {
    val commands = CopyOnWriteArrayList<RequestProfileChangeNotificationCommand>()

    @Volatile var fail: Boolean = false

    fun reset() {
        commands.clear()
        fail = false
    }

    override fun requestProfileChange(command: RequestProfileChangeNotificationCommand): AcceptedProfileChangeNotification {
        commands += command
        if (fail) throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Notification persistence failed")
        val logicalSource = "profile-test:${command.profileChangeId}:${command.ownerTargetId}"
        val deliveryId = UUID.nameUUIDFromBytes(logicalSource.toByteArray(StandardCharsets.UTF_8))
        val now = Timestamp.from(Instant.now())
        jdbcTemplate.update(
            """
            INSERT INTO notification_delivery (
                id, event_id, event_type, logical_source, order_id, classification, recipient_type, recipient_id,
                logical_channel, template, payload_json, state, attempt_count, next_attempt_at,
                provider_idempotency_key, correlation_id, created_at, updated_at, version
            ) VALUES (?, ?, 'SupportProfileChangeTestV1', ?, ?, 'TRANSACTIONAL', 'PROFILE_TARGET', ?, ?,
                      'SUPPORT_PROFILE_CHANGED', '{}', 'PENDING', 0, ?, ?, ?, ?, ?, 0)
            ON CONFLICT (logical_source) DO NOTHING
            """.trimIndent(),
            deliveryId,
            command.profileChangeId,
            logicalSource,
            command.profileChangeId,
            command.ownerTargetId,
            "PROFILE_${command.targetKind.name}",
            now,
            "notification:$logicalSource",
            command.correlationId,
            now,
            now,
        )
        return AcceptedProfileChangeNotification(
            deliveryId,
            "PENDING",
        )
    }
}

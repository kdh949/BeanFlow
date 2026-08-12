package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.operations.api.OperationsSupportInvestigationDecision
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.internal.DecideOperationsSupportInvestigationCommand
import io.github.kdh949.beanflow.operations.internal.OperationsSupportInvestigationOutcome
import io.github.kdh949.beanflow.operations.internal.OperationsSupportInvestigationService
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.internal.OrderCreationDatabaseFixture
import io.github.kdh949.beanflow.ordering.internal.OrderCreationFixture
import io.github.kdh949.beanflow.promotion.api.CouponPricingLine
import io.github.kdh949.beanflow.promotion.api.CouponReservationOperations
import io.github.kdh949.beanflow.promotion.api.ReserveCouponCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.support.internal.domain.SupportApprovalDecision
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationBenefitType
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationEvidenceBasis
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationRequestState
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationResponsibility
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
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class SupportCompensationIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val createOrder: CreateOrderUseCase,
        private val compensations: SupportCompensationApplicationService,
        private val actionRequests: SupportActionRequestApplicationService,
        private val operations: OperationsSupportInvestigationService,
        private val coupons: CouponReservationOperations,
        private val audits: AuditRecordOperations,
        private val objectMapper: ObjectMapper,
        private val transactionManager: PlatformTransactionManager,
    ) {
        private val requesterId = UUID.fromString("75000000-0000-0000-0000-000000000001")
        private val managerId = UUID.fromString("75000000-0000-0000-0000-000000000002")
        private val operationsId = UUID.fromString("75000000-0000-0000-0000-000000000003")
        private lateinit var fixture: OrderCreationFixture
        private lateinit var caseId: UUID
        private lateinit var sessionId: UUID
        private lateinit var orderId: UUID
        private var orderVersion: Long = 0

        @BeforeEach
        fun resetAndSeed() {
            OrderCreationDatabaseFixture.clean(jdbcTemplate)
            jdbcTemplate.execute("TRUNCATE TABLE support_case CASCADE")
            jdbcTemplate.execute("TRUNCATE TABLE operations_audit_record, operations_operator_permission_grant")
            fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            OrderCreationDatabaseFixture.insertPoints(jdbcTemplate, fixture.customerId, 0)
            val created = createOrder.create("support-goodwill-order", fixture.command())
            orderId = UUID.fromString(requireNotNull(Regex("\\\"orderId\\\":\\\"([^\\\"]+)\\\"").find(created.body)).groupValues[1])
            orderVersion =
                requireNotNull(
                    jdbcTemplate.queryForObject("SELECT version FROM ordering_order WHERE id = ?", Long::class.java, orderId),
                )
            seedSupportScope()
            listOf("SUPPORT_CASE_READ", "SUPPORT_COMPENSATION_REQUEST", "SUPPORT_COMPENSATION_EXECUTE").forEach {
                grant(requesterId, it)
            }
            listOf("SUPPORT_CASE_READ", "SUPPORT_COMPENSATION_APPROVE").forEach { grant(managerId, it) }
            grant(operationsId, "OPERATIONS_SUPPORT_INVESTIGATION")
        }

        @Test
        fun `low point API evaluates issues exactly once and returns no-store masked resource`() {
            val incidentId = UUID.randomUUID()
            evaluateApi(incidentId, 100)
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.band").value("LOW"))
                .andExpect(jsonPath("$.decision").value("ALLOWED"))
                .andExpect(jsonPath("$.approvalRoute").value("NONE"))

            val created =
                createApi(incidentId, 100, "goodwill-create-001")
                    .andExpect(status().isCreated)
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.state").value("READY_FOR_EXECUTION"))
                    .andExpect(jsonPath("$.actionRequestId").doesNotExist())
                    .andExpect(jsonPath("$.customerId").doesNotExist())
                    .andExpect(jsonPath("$.evidenceDigest").doesNotExist())
                    .andReturn()
            val resource = objectMapper.readValue(created.response.contentAsString, SupportCompensationResource::class.java)

            executeApi(resource, "goodwill-execute-001")
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("NOTIFICATION_ACCEPTED"))
                .andExpect(jsonPath("$.notificationState").value("PENDING"))
                .andExpect(jsonPath("$.terminalBenefitId").isNotEmpty)
            executeApi(resource, "goodwill-execute-001")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("NOTIFICATION_ACCEPTED"))

            assertThat(count("loyalty_goodwill_point_issuance", "compensation_request_id", resource.compensationRequestId)).isOne()
            assertThat(count("support_compensation_terminal_benefit", "request_id", resource.compensationRequestId)).isOne()
            assertThat(count("support_compensation_limit_consumption", "request_id", resource.compensationRequestId)).isEqualTo(5)
            assertThat(count("notification_delivery", "event_id", resource.compensationRequestId)).isOne()

            createApi(incidentId, 100, "goodwill-duplicate-incident")
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("SUPPORT_ACTION_POLICY_DENIED"))
        }

        @Test
        fun `medium request rejects self approval and consumes exact manager approval once`() {
            val created = compensations.create(command(UUID.randomUUID(), 3_001, "medium-create-001"))
            assertThat(created.actionRequestId).isNotNull()
            assertThat(created.state).isEqualTo(SupportCompensationRequestState.AWAITING_APPROVAL)

            grant(requesterId, "SUPPORT_COMPENSATION_APPROVE")
            assertThatThrownBy {
                approveManager(requireNotNull(created.actionRequestId), requesterId, "medium-self-approve")
            }.isInstanceOf(DomainFailure::class.java)
                .extracting("code")
                .isEqualTo(FailureCode.SUPPORT_APPROVER_MUST_DIFFER)

            val approved = approveManager(requireNotNull(created.actionRequestId), managerId, "medium-manager-approve")
            assertThat(approved).isInstanceOf(SupportActionCommandOutcome.Succeeded::class.java)
            val issued =
                compensations.execute(
                    ExecuteSupportCompensationCommand(
                        requesterId,
                        created.compensationRequestId,
                        created.version,
                        orderVersion,
                        created.payloadDigest,
                        "medium-execute-001",
                    ),
                )
            assertThat(issued.state).isEqualTo(SupportCompensationRequestState.NOTIFICATION_ACCEPTED)
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT terminal_compensation_id FROM support_action_request WHERE id = ?",
                    UUID::class.java,
                    created.actionRequestId,
                ),
            ).isEqualTo(created.compensationRequestId)
        }

        @Test
        fun `two approved high requests race under customer rolling lock and only one issues`() {
            val first = compensations.create(command(UUID.randomUUID(), 30_000, "high-create-001"))
            val second = compensations.create(command(UUID.randomUUID(), 30_000, "high-create-002"))
            approveOperations(first)
            approveOperations(second)

            val executor = Executors.newFixedThreadPool(2)
            try {
                val results =
                    executor.invokeAll(
                        listOf(
                            Callable { runCatching { execute(first, "high-execute-001") } },
                            Callable { runCatching { execute(second, "high-execute-002") } },
                        ),
                    ).map { it.get() }

                assertThat(results.count { it.isSuccess }).isOne()
                val failure = results.single { it.isFailure }.exceptionOrNull()
                assertThat(failure).isInstanceOf(DomainFailure::class.java)
                assertThat((failure as DomainFailure).code).isEqualTo(FailureCode.SUPPORT_ACTION_POLICY_DENIED)
                assertThat(
                    jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM support_compensation_terminal_benefit WHERE request_id IN (?, ?)",
                        Int::class.java,
                        first.compensationRequestId,
                        second.compensationRequestId,
                    ),
                ).isOne()
                assertThat(
                    jdbcTemplate.queryForObject(
                        "SELECT coalesce(sum(amount_krw), 0) FROM support_compensation_limit_consumption " +
                            "WHERE scope = 'CUSTOMER' AND scope_id = ?",
                        Long::class.java,
                        fixture.customerId,
                    ),
                ).isEqualTo(30_000)
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `audit failure rolls back owner issuance terminal limit and action consumption`() {
            val created = compensations.create(command(UUID.randomUUID(), 100, "audit-create-001"))
            TransactionTemplate(transactionManager).executeWithoutResult {
                audits.appendAll(
                    listOf(
                        AppendAuditRecordCommand(
                            requesterId.toString(),
                            AuditActorType.PLATFORM_OPERATOR,
                            AuditCategory.FINANCIAL_TRANSACTION,
                            "SUPPORT_COMPENSATION_BENEFIT_ISSUED",
                            "SUPPORT_COMPENSATION",
                            created.compensationRequestId,
                            Instant.now(),
                            "PRESEEDED",
                            correlationId = "audit-failure",
                            sourceReference =
                                "support-compensation:${created.compensationRequestId}:SUPPORT_COMPENSATION_BENEFIT_ISSUED",
                        ),
                    ),
                )
            }

            assertThatThrownBy { execute(created, "audit-execute-001") }
                .isInstanceOf(DomainFailure::class.java)
                .extracting("code")
                .isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
            assertThat(count("loyalty_goodwill_point_issuance", "compensation_request_id", created.compensationRequestId)).isZero()
            assertThat(count("support_compensation_terminal_benefit", "request_id", created.compensationRequestId)).isZero()
            assertThat(count("support_compensation_limit_consumption", "request_id", created.compensationRequestId)).isZero()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT state FROM support_compensation_request WHERE id = ?",
                    String::class.java,
                    created.compensationRequestId,
                ),
            ).isEqualTo("READY_FOR_EXECUTION")
        }

        @Test
        fun `shared goodwill coupon preserves exact cost legs through redemption quote`() {
            val created =
                compensations.create(
                    couponCommand(
                        incidentId = UUID.randomUUID(),
                        key = "shared-coupon-create-001",
                    ),
                )
            assertThat(created.band.name).isEqualTo("HIGH")
            approveOperations(created)

            val issued = execute(created, "shared-coupon-execute-001")
            assertThat(issued.state).isEqualTo(SupportCompensationRequestState.NOTIFICATION_ACCEPTED)
            val couponIssuanceId =
                requireNotNull(
                    jdbcTemplate.queryForObject(
                        "SELECT coupon_issuance_id FROM promotion_goodwill_coupon_issuance WHERE compensation_request_id = ?",
                        UUID::class.java,
                        created.compensationRequestId,
                    ),
                )

            val quote =
                TransactionTemplate(transactionManager).execute {
                    coupons.reserve(
                        ReserveCouponCommand(
                            orderId = UUID.randomUUID(),
                            customerId = fixture.customerId,
                            storeId = fixture.storeId,
                            couponIssuanceId = couponIssuanceId,
                            lines = listOf(CouponPricingLine(0, fixture.menuId, 5_000)),
                            reservationExpiresAt = Instant.now().plusSeconds(300),
                            sourceReference = "shared-goodwill-redemption-${created.compensationRequestId}",
                        ),
                    )
                }
            assertThat(quote).isNotNull()
            assertThat(quote?.discountKrw).isEqualTo(3_000)
            assertThat(quote?.platformShareBps).isEqualTo(3_333)
            assertThat(quote?.storeShareBps).isEqualTo(6_667)
            assertThat(quote?.platformCouponCostKrw).isEqualTo(1_000)
            assertThat(quote?.storeCouponCostKrw).isEqualTo(2_000)
        }

        @Test
        fun `notification failure remains retryable without rolling back terminal benefit`() {
            val created = compensations.create(command(UUID.randomUUID(), 100, "notification-failure-create-001"))
            insertConflictingNotification(created.compensationRequestId)

            val issued = execute(created, "notification-failure-execute-001")

            assertThat(issued.state).isEqualTo(SupportCompensationRequestState.NOTIFICATION_RETRY)
            assertThat(issued.notificationFailureCode).isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE.name)
            assertThat(count("loyalty_goodwill_point_issuance", "compensation_request_id", created.compensationRequestId)).isOne()
            assertThat(count("support_compensation_terminal_benefit", "request_id", created.compensationRequestId)).isOne()
            assertThat(count("support_compensation_limit_consumption", "request_id", created.compensationRequestId)).isEqualTo(5)
        }

        @Test
        fun `policy head change makes an unexecuted request stale without issuing`() {
            val created = compensations.create(command(UUID.randomUUID(), 100, "policy-boundary-create-001"))
            activateNextPolicyVersion()

            assertThatThrownBy { execute(created, "policy-boundary-execute-001") }
                .isInstanceOf(DomainFailure::class.java)
                .extracting("code")
                .isEqualTo(FailureCode.SUPPORT_ACTION_REQUEST_STALE)
            assertThat(count("loyalty_goodwill_point_issuance", "compensation_request_id", created.compensationRequestId)).isZero()
            assertThat(count("support_compensation_terminal_benefit", "request_id", created.compensationRequestId)).isZero()
        }

        private fun evaluateApi(
            incidentId: UUID,
            amount: Long,
        ) = mockMvc.perform(
            post("/api/v1/support/cases/$caseId/compensation-evaluations")
                .with(jwt().jwt { it.subject(requesterId.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload(incidentId, amount)),
        )

        private fun createApi(
            incidentId: UUID,
            amount: Long,
            key: String,
        ) = mockMvc.perform(
            post("/api/v1/support/cases/$caseId/compensations")
                .with(jwt().jwt { it.subject(requesterId.toString()) })
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload(incidentId, amount, includeEvidence = true)),
        )

        private fun executeApi(
            resource: SupportCompensationResource,
            key: String,
        ) = mockMvc.perform(
            post("/api/v1/support/compensations/${resource.compensationRequestId}/executions")
                .with(jwt().jwt { it.subject(requesterId.toString()) })
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"expectedRequestVersion":${resource.version},"expectedTargetVersion":$orderVersion,"expectedPayloadDigest":"${resource.payloadDigest}"}""",
                ),
        )

        private fun payload(
            incidentId: UUID,
            amount: Long,
            includeEvidence: Boolean = false,
        ): String =
            """
            {"incidentId":"$incidentId","orderId":"$orderId","expectedTargetVersion":$orderVersion,
             "benefitType":"POINT","amountKrw":$amount,"responsibility":"PLATFORM",
             "platformShareBps":10000,"storeShareBps":0,"verificationSessionId":"$sessionId"
             ${if (includeEvidence) ",\"evidenceDigest\":\"$EVIDENCE_DIGEST\"" else ""}}
            """.trimIndent()

        private fun command(
            incidentId: UUID,
            amount: Long,
            key: String,
        ) = CreateSupportCompensationCommand(
            requesterId,
            caseId,
            incidentId,
            orderId,
            orderVersion,
            SupportCompensationBenefitType.POINT,
            amount,
            null,
            SupportCompensationResponsibility.PLATFORM,
            null,
            null,
            10_000,
            0,
            sessionId,
            EVIDENCE_DIGEST,
            key,
        )

        private fun couponCommand(
            incidentId: UUID,
            key: String,
        ) = CreateSupportCompensationCommand(
            requesterId,
            caseId,
            incidentId,
            orderId,
            orderVersion,
            SupportCompensationBenefitType.COUPON,
            3_000,
            GOODWILL_COUPON_TEMPLATE_ID,
            SupportCompensationResponsibility.SHARED,
            SupportCompensationEvidenceBasis.OPERATIONS_FINDING,
            COST_EVIDENCE_DIGEST,
            3_333,
            6_667,
            sessionId,
            EVIDENCE_DIGEST,
            key,
        )

        private fun approveManager(
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
                "GOODWILL_POLICY_REVIEWED",
                key,
            ),
        )

        private fun approveOperations(resource: SupportCompensationResource) {
            val investigationId =
                requireNotNull(
                    jdbcTemplate.queryForObject(
                        "SELECT id FROM operations_support_investigation_case WHERE support_action_request_id = ?",
                        UUID::class.java,
                        resource.actionRequestId,
                    ),
                )
            val outcome =
                operations.decide(
                    DecideOperationsSupportInvestigationCommand(
                        operationsId,
                        investigationId,
                        0,
                        OperationsSupportInvestigationDecision.APPROVE,
                        "GOODWILL_INVESTIGATION_APPROVED",
                        EVIDENCE_DIGEST,
                        "operations-${resource.compensationRequestId}",
                        Instant.now(),
                    ),
                )
            assertThat(outcome).isInstanceOf(OperationsSupportInvestigationOutcome.Succeeded::class.java)
        }

        private fun execute(
            resource: SupportCompensationResource,
            key: String,
        ) = compensations.execute(
            ExecuteSupportCompensationCommand(
                requesterId,
                resource.compensationRequestId,
                resource.version,
                orderVersion,
                resource.payloadDigest,
                key,
            ),
        )

        private fun seedSupportScope() {
            val openedAt = Instant.now().minusSeconds(30)
            caseId = UUID.randomUUID()
            val customerLinkId = UUID.randomUUID()
            sessionId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO support_case (
                    id, requester_type, requester_reference, category, priority, reason, state,
                    current_assignee_id, opened_at, last_changed_at, version, retention_policy_version_id
                ) VALUES (?, 'CUSTOMER', 'masked-reference', 'OTHER', 'NORMAL', 'GOODWILL', 'OPEN',
                          ?, ?, ?, 0, 7)
                """.trimIndent(),
                caseId,
                requesterId,
                Timestamp.from(openedAt),
                Timestamp.from(openedAt),
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
                Timestamp.from(openedAt),
            )
            jdbcTemplate.update(
                """
                INSERT INTO support_case_subject_link (
                    id, support_case_id, subject_type, subject_id, relationship, linked_by_actor_id, reason, linked_at
                ) VALUES
                    (?, ?, 'CUSTOMER', ?, 'REQUESTER', ?, 'GOODWILL_SUBJECT', ?),
                    (?, ?, 'ORDER', ?, 'RELATED_ORDER', ?, 'GOODWILL_ORDER', ?)
                """.trimIndent(),
                customerLinkId,
                caseId,
                fixture.customerId,
                requesterId,
                Timestamp.from(openedAt),
                UUID.randomUUID(),
                caseId,
                orderId,
                requesterId,
                Timestamp.from(openedAt),
            )
            jdbcTemplate.update(
                """
                INSERT INTO support_verification_session (
                    id, support_case_id, subject_link_id, subject_type, subject_id, actor_id, purpose, action_scope,
                    requested_level, state, invalid_attempts, started_at, expires_at, verified_at, version
                ) VALUES (?, ?, ?, 'CUSTOMER', ?, ?, 'CASE_RESOLUTION', 'SUPPORT_ACTION',
                          'ENHANCED', 'VERIFIED', 0, ?, ?, ?, 1)
                """.trimIndent(),
                sessionId,
                caseId,
                customerLinkId,
                fixture.customerId,
                requesterId,
                Timestamp.from(openedAt),
                Timestamp.from(openedAt.plusSeconds(900)),
                Timestamp.from(openedAt.plusSeconds(1)),
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
                    SET state = 'ACTIVE', revoked_at = NULL, version = operations_operator_permission_grant.version + 1,
                        audit_source_reference = EXCLUDED.audit_source_reference
                """.trimIndent(),
                actorId,
                permission,
                "support-goodwill:$permission:$actorId:${UUID.randomUUID()}",
            )
        }

        private fun insertConflictingNotification(compensationRequestId: UUID) {
            val now = Timestamp.from(Instant.now())
            jdbcTemplate.update(
                """
                INSERT INTO notification_delivery (
                    id, event_id, event_type, logical_source, order_id, recipient_type, recipient_id,
                    logical_channel, template, payload_json, state, attempt_count, next_attempt_at,
                    provider_idempotency_key, correlation_id, created_at, updated_at, version
                ) VALUES (?, ?, 'ConflictingGoodwillEventV1', ?, ?, 'CUSTOMER', ?, 'CUSTOMER_APP',
                          'SUPPORT_GOODWILL_COMPENSATION_ISSUED', '{}', 'PENDING', 0, ?, ?, 'conflict', ?, ?, 0)
                """.trimIndent(),
                UUID.randomUUID(),
                compensationRequestId,
                "support-compensation:$compensationRequestId:customer-notification",
                orderId,
                fixture.customerId,
                now,
                "notification:test-conflict:$compensationRequestId",
                now,
                now,
            )
        }

        private fun activateNextPolicyVersion() {
            val policyVersionId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO support_compensation_policy_version (
                    id, code, effective_at, low_amount_maximum_krw, high_amount_maximum_krw,
                    supported_amount_maximum_krw, low_order_ratio_maximum_bps, created_at
                ) VALUES (?, ?, ?, 3000, 10000, 30000, 5000, ?)
                """.trimIndent(),
                policyVersionId,
                "GOODWILL_TEST_${policyVersionId.toString().replace("-", "").uppercase()}",
                Timestamp.from(Instant.now().minusSeconds(1)),
                Timestamp.from(Instant.now()),
            )
            listOf(
                Triple("CUSTOMER", 2_592_000L, 30_000L),
                Triple("ORDER", 2_592_000L, 30_000L),
                Triple("INCIDENT", 2_592_000L, 30_000L),
                Triple("ACTOR", 86_400L, 100_000L),
                Triple("STORE", 86_400L, 300_000L),
            ).forEach { (scope, windowSeconds, maximumKrw) ->
                jdbcTemplate.update(
                    """
                    INSERT INTO support_compensation_limit_rule (
                        id, policy_version_id, scope, window_seconds, maximum_krw
                    ) VALUES (?, ?, ?, ?, ?)
                    """.trimIndent(),
                    UUID.randomUUID(),
                    policyVersionId,
                    scope,
                    windowSeconds,
                    maximumKrw,
                )
            }
            jdbcTemplate.update(
                "UPDATE support_compensation_policy_head SET current_version_id = ?, updated_at = now(), " +
                    "version = version + 1 WHERE name = 'GOODWILL'",
                policyVersionId,
            )
        }

        private fun count(
            table: String,
            column: String,
            id: UUID,
        ): Int = requireNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM $table WHERE $column = ?", Int::class.java, id))

        private companion object {
            const val EVIDENCE_DIGEST = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
            const val COST_EVIDENCE_DIGEST = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
            val GOODWILL_COUPON_TEMPLATE_ID: UUID = UUID.fromString("91000000-0000-0000-0000-000000000003")
        }
    }

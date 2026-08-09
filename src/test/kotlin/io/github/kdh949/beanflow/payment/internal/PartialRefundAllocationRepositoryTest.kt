package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.loyalty.api.ExpiredPointRestorationMode
import io.github.kdh949.beanflow.loyalty.api.PartialRefundPointOperations
import io.github.kdh949.beanflow.loyalty.api.PartialRefundPointPolicyMode
import io.github.kdh949.beanflow.loyalty.api.PartialRefundPointSlice
import io.github.kdh949.beanflow.loyalty.api.PointIssuerType
import io.github.kdh949.beanflow.loyalty.api.PointReservationOperations
import io.github.kdh949.beanflow.loyalty.api.RestorePartialRefundPointsCommand
import io.github.kdh949.beanflow.loyalty.api.RestorePointsAfterTerminationCommand
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyOperations
import io.github.kdh949.beanflow.ordering.internal.OrderPointAccrualCalculator
import io.github.kdh949.beanflow.ordering.internal.OrderPointAccrualLineInput
import io.github.kdh949.beanflow.ordering.internal.OrderPointAccrualSnapshotService
import io.github.kdh949.beanflow.ordering.internal.PartialRefundActor
import io.github.kdh949.beanflow.ordering.internal.PartialRefundActorType
import io.github.kdh949.beanflow.ordering.internal.PartialRefundCommand
import io.github.kdh949.beanflow.ordering.internal.PartialRefundHttpResult
import io.github.kdh949.beanflow.ordering.internal.PartialRefundLineInput
import io.github.kdh949.beanflow.ordering.internal.PartialRefundRestorationService
import io.github.kdh949.beanflow.ordering.internal.PartialRefundRestorationWorker
import io.github.kdh949.beanflow.ordering.internal.PartialRefundService
import io.github.kdh949.beanflow.payment.api.PreparePointAccrualCompletionCommand
import io.github.kdh949.beanflow.payment.api.RefundPointAccrualSnapshotSource
import io.github.kdh949.beanflow.payment.api.RefundPointAccrualSourceState
import io.github.kdh949.beanflow.payment.api.RefundPointAccrualUnit
import io.github.kdh949.beanflow.payment.api.RefundPointRecoveryOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OrderTerminationTrigger
import io.github.kdh949.beanflow.shared.api.ReservationTransitionResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "beanflow.payment.refund.initial-delay-ms=3600000",
        "beanflow.payment.refund-restoration.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
    ],
)
internal class PartialRefundAllocationRepositoryTest
    @Autowired
    constructor(
        private val service: PartialRefundService,
        private val restorationWorker: PartialRefundRestorationWorker,
        private val restorationService: PartialRefundRestorationService,
        private val pointOperations: PartialRefundPointOperations,
        private val pointReservationOperations: PointReservationOperations,
        private val gateway: ScriptedTestPaymentGateway,
        private val jdbcTemplate: JdbcTemplate,
        private val objectMapper: ObjectMapper,
        private val mockMvc: MockMvc,
        private val pointAccrualPolicyOperations: OrdinaryPointAccrualPolicyOperations,
        private val pointAccrualSnapshotService: OrderPointAccrualSnapshotService,
        private val refundPointRecoveryOperations: RefundPointRecoveryOperations,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)
        private val pointAccrualCalculator = OrderPointAccrualCalculator()

        @BeforeEach
        fun cleanDatabase() {
            jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    event_publication,
                    payment_refund_point_recovery_work,
                    payment_order_point_accrual_outcome,
                    payment_refund_restoration_work,
                    payment_refund_point_allocation,
                    payment_refund_line_allocation,
                    payment_refund_point_request,
                    payment_refund_line_request,
                    loyalty_partial_refund_restoration,
                    loyalty_point_accrual_result,
                    loyalty_point_recovery_result,
                    loyalty_point_recovery_pending,
                    loyalty_point_transaction,
                    loyalty_point_reservation_allocation,
                    loyalty_point_reservation,
                    loyalty_point_lot,
                    loyalty_point_account,
                    payment_refund,
                    payment_reconciliation,
                    payment_idempotency_record,
                    payment_provider_request_snapshot,
                    payment_payment,
                    payment_method,
                    promotion_coupon_reservation,
                    promotion_coupon_issuance,
                    promotion_campaign_eligible_menu,
                    promotion_campaign,
                    ordering_order_line,
                    ordering_order,
                    identity_store_membership,
                    operations_audit_record
                CASCADE
                """.trimIndent(),
            )
            gateway.reset()
            jdbcTemplate.update(
                """
                UPDATE operations_expired_benefit_policy_head
                   SET policy_version = (
                       SELECT min(policy_version)
                         FROM operations_expired_benefit_policy_version
                        WHERE trigger = 'PARTIAL_REFUND'
                          AND benefit_type = 'POINTS'
                          AND mode = 'COMPENSATE_WITH_NEW_ISSUANCE'
                          AND compensation_validity_days = 30
                   ), version = version + 1
                 WHERE trigger = 'PARTIAL_REFUND' AND benefit_type = 'POINTS'
                """.trimIndent(),
            )
        }

        @Test
        fun `PaymentRefunded pre-completion payload replays once and points restore with issuer lineage`() {
            val fixture = fixture()
            gateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-refund-1"))
            val command = command(fixture, "refund-key-0001", fixture.firstLineId, 1)

            val first = service.create(command)

            assertThat(first.status).isEqualTo(201)
            val body = objectMapper.readTree(first.body)
            assertThat(body["state"].asText()).isEqualTo("SUCCEEDED")
            assertThat(body["cashRefundRequestedKrw"].asLong()).isEqualTo(666)
            assertThat(body["pointsRestorationRequestedKrw"].asLong()).isEqualTo(333)
            assertThat(body["pointsRestorationState"].asText()).isEqualTo("PROCESSING")
            assertThat(body["cashRefundedKrw"].asLong()).isEqualTo(666)
            assertThat(body.has("pointsRestoredKrw")).isFalse()
            assertThat(singleLong("select coupon_attribution_krw from payment_refund_line_allocation")).isEqualTo(1)
            assertThat(singleLong("select count(*) from promotion_coupon_reservation where state = 'USED'")).isEqualTo(1)

            assertThat(restorationWorker.runOnce()).isEqualTo(1)

            assertThat(singleString("select state from payment_refund_restoration_work")).isEqualTo("SUCCEEDED")
            assertThat(singleString("select state from loyalty_point_reservation")).isEqualTo("USED")
            assertThat(singleLong("select available_points_krw from loyalty_point_account")).isEqualTo(333)
            assertThat(singleString("select issuer_type from loyalty_point_lot where original_point_lot_id is not null"))
                .isEqualTo("STORE")
            assertThat(singleString("select issuer_reference from loyalty_point_lot where original_point_lot_id is not null"))
                .isEqualTo(fixture.storeId.toString())
            assertThat(singleString("select disposition from loyalty_partial_refund_restoration"))
                .isEqualTo("COMPENSATION_LOT")

            val terminationPolicyVersion =
                singleLong(
                    """
                    select policy_version from operations_expired_benefit_policy_head
                     where trigger = 'STORE_REJECTION' and benefit_type = 'POINTS'
                    """.trimIndent(),
                )
            val termination =
                RestorePointsAfterTerminationCommand(
                    orderId = fixture.orderId,
                    terminatedAt = NOW,
                    sourceReference = "order:${fixture.orderId}:store-rejection:points",
                    trigger = OrderTerminationTrigger.STORE_REJECTION,
                    policyVersionId = terminationPolicyVersion,
                    mode = ExpiredPointRestorationMode.COMPENSATE_WITH_NEW_ISSUANCE,
                    compensationValidityDays = 30,
                )
            val terminationResult = pointReservationOperations.restoreUsedAfterTermination(termination)
            val terminationReplay = pointReservationOperations.restoreUsedAfterTermination(termination)

            assertThat(terminationResult.result).isEqualTo(ReservationTransitionResult.APPLIED)
            assertThat(terminationReplay.result).isEqualTo(ReservationTransitionResult.ALREADY_APPLIED)
            assertThat(singleString("select state from loyalty_point_reservation")).isEqualTo("RESTORED")
            assertThat(singleLong("select available_points_krw from loyalty_point_account")).isEqualTo(3_000)
            assertThat(
                singleLong(
                    """
                    select coalesce(sum(amount_krw), 0) from loyalty_point_transaction
                     where restoration_trigger = 'STORE_REJECTION'
                    """.trimIndent(),
                ),
            ).isEqualTo(2_667)

            val replay = service.create(command)
            assertThat(replay).isEqualTo(first)
            assertThat(gateway.rejectionRefundCalls.get()).isEqualTo(1)
            assertThat(singleLong("select count(*) from payment_refund_line_allocation")).isEqualTo(1)
            assertThat(singleLong("select count(*) from loyalty_partial_refund_restoration")).isEqualTo(1)
            assertThat(
                singleLong(
                    "select count(*) from event_publication where event_type like '%PaymentRefundedV1'",
                ),
            ).isEqualTo(2)
            val refundEvent = paymentRefundedEvent()
            assertThat(refundEvent["completionDisposition"].asText()).isEqualTo("PRE_COMPLETION_ORDER")
            assertThat(refundEvent.has("orderCompletedAt")).isFalse()
            assertThat(refundEvent.has("settlementItemSource")).isFalse()
            assertThat(refundEvent["settlementRefundEffect"]["grossPaidDeltaKrw"].asLong()).isEqualTo(-1_000)
            assertThat(refundEvent["settlementRefundEffect"]["benefitCostDeltaKrw"].asLong()).isEqualTo(-334)
            assertThat(refundEvent["settlementRefundEffect"]["netSettlementDeltaKrw"].asLong()).isEqualTo(-666)
        }

        @Test
        fun `completion preparation excludes equal-time refunded units and rejects changed snapshot replay`() {
            val fixture = fixture()
            gateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-refund-exclusion"))
            service.create(command(fixture, "refund-recovery-key-0001", fixture.firstLineId, 1))
            val snapshot = requireNotNull(pointAccrualSnapshotService.read(fixture.orderId).snapshot)
            val refundSucceededAt =
                requireNotNull(
                    jdbcTemplate.queryForObject(
                        "select refund_succeeded_at from payment_refund_point_recovery_work where order_id = ?",
                        Instant::class.java,
                        fixture.orderId,
                    ),
                )
            val completion =
                PreparePointAccrualCompletionCommand(
                    orderId = fixture.orderId,
                    completedAt = refundSucceededAt,
                    completionSourceReference = "order:${fixture.orderId}:completed:1",
                    aggregateVersion = 1,
                    snapshotSchemaVersion = snapshot.snapshotSchemaVersion,
                    snapshotHash = snapshot.canonicalSnapshotHash,
                    units =
                        snapshot.units.map {
                            RefundPointAccrualUnit(it.orderLineId, it.unitPosition, it.accruedAmountKrw)
                        },
                    processedAt = NOW.plusSeconds(1),
                )

            val first = refundPointRecoveryOperations.prepareCompletion(completion)
            val replay = refundPointRecoveryOperations.prepareCompletion(completion)

            assertThat(first).isEqualTo(replay)
            assertThat(first.excludedUnits)
                .containsExactly(
                    io.github.kdh949.beanflow.payment.api
                        .RefundPointUnitKey(fixture.firstLineId, 0),
                )
            assertThat(singleString("select state from payment_refund_point_recovery_work"))
                .isEqualTo("EXCLUDED_BEFORE_ACCRUAL")
            assertThatThrownBy {
                refundPointRecoveryOperations.prepareCompletion(
                    completion.copy(snapshotHash = "b".repeat(64)),
                )
            }.isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.IDEMPOTENCY_KEY_REUSED)
            }
        }

        @Test
        fun `post-completion recovery work retries to manual review without losing its target`() {
            val fixture = fixture()
            gateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-refund-recovery"))
            service.create(command(fixture, "refund-recovery-key-0002", fixture.firstLineId, 1))
            val snapshot = requireNotNull(pointAccrualSnapshotService.read(fixture.orderId).snapshot)
            val completedAt = NOW.minusSeconds(1)
            val source =
                RefundPointAccrualSnapshotSource(
                    orderId = fixture.orderId,
                    orderState = "COMPLETED",
                    pointAccrualSourceState = RefundPointAccrualSourceState.SNAPSHOTTED,
                    outcomeAt = completedAt,
                    outcomeSourceReference = "order:${fixture.orderId}:completed:1",
                    aggregateVersion = 1,
                    snapshotSchemaVersion = snapshot.snapshotSchemaVersion,
                    snapshotHash = snapshot.canonicalSnapshotHash,
                    units =
                        snapshot.units.map {
                            RefundPointAccrualUnit(it.orderLineId, it.unitPosition, it.accruedAmountKrw)
                        },
                )
            refundPointRecoveryOperations.prepareCompletion(
                PreparePointAccrualCompletionCommand(
                    fixture.orderId,
                    completedAt,
                    requireNotNull(source.outcomeSourceReference),
                    requireNotNull(source.aggregateVersion),
                    snapshot.snapshotSchemaVersion,
                    snapshot.canonicalSnapshotHash,
                    source.units,
                    NOW.plusSeconds(1),
                ),
            )
            var due = NOW.plusSeconds(2)
            repeat(5) {
                val claim = refundPointRecoveryOperations.claimDue(due, 1).single()
                val prepared = refundPointRecoveryOperations.prepareRecovery(claim, source, due)
                assertThat(prepared?.targetAmountKrw)
                    .isEqualTo(
                        snapshot.units
                            .single { unit ->
                                unit.orderLineId == fixture.firstLineId && unit.unitPosition == 0
                            }.accruedAmountKrw,
                    )
                refundPointRecoveryOperations.recordFailure(
                    claim,
                    DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "simulated Loyalty recovery failure"),
                    due,
                )
                due = due.plusSeconds(10_000)
            }

            assertThat(singleString("select state from payment_refund_point_recovery_work"))
                .isEqualTo("MANUAL_REVIEW")
            assertThat(singleLong("select count(*) from payment_refund_point_recovery_work"))
                .isEqualTo(1)
        }

        @Test
        fun `store owner HTTP contract returns independent cash and points states`() {
            val fixture = fixture()
            gateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-refund-http"))

            mockMvc
                .perform(
                    post("/api/v1/payments/{paymentId}/refunds", fixture.paymentId)
                        .with(
                            jwt()
                                .jwt { it.subject(fixture.actorId.toString()).claim("roles", listOf("STORE_OWNER")) }
                                .authorities(SimpleGrantedAuthority("ROLE_STORE_OWNER")),
                        ).header("Idempotency-Key", "refund-http-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                              "lineItems": [{"orderLineId": "${fixture.firstLineId}", "quantity": 1}],
                              "reason": "CUSTOMER_REQUESTED_ITEM_ADJUSTMENT"
                            }
                            """.trimIndent(),
                        ),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.cashRefundRequestedKrw").value(666))
                .andExpect(jsonPath("$.cashRefundedKrw").value(666))
                .andExpect(jsonPath("$.pointsRestorationRequestedKrw").value(333))
                .andExpect(jsonPath("$.pointsRestorationState").value("PROCESSING"))
                .andExpect(jsonPath("$.pointsRestoredKrw").doesNotExist())
        }

        @Test
        fun `customer role cannot execute the store adjustment endpoint`() {
            val fixture = fixture()

            mockMvc
                .perform(
                    post("/api/v1/payments/{paymentId}/refunds", fixture.paymentId)
                        .with(
                            jwt()
                                .jwt { it.subject(fixture.customerId.toString()).claim("roles", listOf("CUSTOMER")) }
                                .authorities(SimpleGrantedAuthority("ROLE_CUSTOMER")),
                        ).header("Idempotency-Key", "refund-http-0002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"reason":"NOT_ALLOWED"}"""),
                ).andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            assertThat(gateway.rejectionRefundCalls.get()).isZero()
        }

        @Test
        fun `PaymentRefunded changed payload replay conflicts without another publication`() {
            val fixture = fixture()
            gateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-refund-replay"))
            service.create(command(fixture, "refund-key-0002", fixture.firstLineId, 1))

            assertThatThrownBy {
                service.create(command(fixture, "refund-key-0002", fixture.firstLineId, 2))
            }.isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.IDEMPOTENCY_KEY_REUSED)
            }
            assertThat(gateway.rejectionRefundCalls.get()).isEqualTo(1)
            assertThat(
                singleLong(
                    "select count(*) from event_publication where event_type like '%PaymentRefundedV1'",
                ),
            ).isEqualTo(2)
        }

        @Test
        fun `PaymentRefunded completed payload uses locked completion source and immutable terms`() {
            val fixture = fixture()
            jdbcTemplate.update(
                """
                update ordering_order
                   set state = 'COMPLETED', paid_at = ?, acceptance_warning_at = ?, acceptance_deadline_at = ?,
                       accepted_at = ?, preparing_at = ?, ready_at = ?, completed_at = ?, version = 5
                 where id = ?
                """.trimIndent(),
                Timestamp.from(NOW.minusSeconds(60)),
                Timestamp.from(NOW.plusSeconds(60)),
                Timestamp.from(NOW.plusSeconds(120)),
                Timestamp.from(NOW.minusSeconds(4)),
                Timestamp.from(NOW.minusSeconds(3)),
                Timestamp.from(NOW.minusSeconds(2)),
                Timestamp.from(NOW.minusSeconds(1)),
                fixture.orderId,
            )
            jdbcTemplate.update(
                """
                INSERT INTO merchant_store_settlement_terms (
                    terms_version_id, store_id, source_reference, fee_rate_bps,
                    effective_from, effective_to, created_at
                ) VALUES (?, ?, ?, 9999, ?, NULL, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                fixture.storeId,
                "test:changed-terms:${fixture.orderId}",
                Timestamp.from(NOW.plusSeconds(10)),
                Timestamp.from(NOW.plusSeconds(10)),
            )
            gateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-refund-completed"))

            service.create(command(fixture, "refund-completed-key", fixture.firstLineId, 1))

            val event = paymentRefundedEvent()
            assertThat(event["completionDisposition"].asText()).isEqualTo("COMPLETED_ORDER")
            assertThat(event["orderCompletedAt"].asText()).isEqualTo(NOW.minusSeconds(1).toString())
            assertThat(event["settlementItemSource"].asText())
                .isEqualTo("order:${fixture.orderId}:completed:5")
            assertThat(event["settlementRefundEffect"]["feeDeltaKrw"].asLong()).isZero()
        }

        @Test
        fun `PaymentRefunded outbox failure rolls back owner success and allocations`() {
            val fixture = fixture()
            jdbcTemplate.execute(
                """
                ALTER TABLE event_publication
                ADD CONSTRAINT test_block_payment_refunded
                CHECK (event_type <> 'io.github.kdh949.beanflow.eventing.api.PaymentRefundedV1')
                """.trimIndent(),
            )
            try {
                gateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-refund-outbox-failure"))

                assertThatThrownBy {
                    service.create(command(fixture, "refund-outbox-key", fixture.firstLineId, 1))
                }.isInstanceOf(DomainFailure::class.java)

                assertThat(singleString("select state from payment_refund")).isEqualTo("PROCESSING")
                assertThat(singleLong("select succeeded_refund_amount_krw from payment_payment")).isZero()
                assertThat(singleLong("select count(*) from payment_refund_line_allocation")).isZero()
                assertThat(singleLong("select count(*) from event_publication")).isZero()
            } finally {
                jdbcTemplate.execute("alter table event_publication drop constraint test_block_payment_refunded")
            }
        }

        @Test
        fun `PaymentRefunded missing success allocation rolls back owner result`() {
            val fixture = fixture()
            jdbcTemplate.execute(
                """
                CREATE FUNCTION test_skip_refund_line_allocation() RETURNS trigger
                LANGUAGE plpgsql AS 'BEGIN RETURN NULL; END'
                """.trimIndent(),
            )
            jdbcTemplate.execute(
                """
                CREATE TRIGGER test_skip_refund_line_allocation
                BEFORE INSERT ON payment_refund_line_allocation
                FOR EACH ROW EXECUTE FUNCTION test_skip_refund_line_allocation()
                """.trimIndent(),
            )
            try {
                gateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-refund-allocation-missing"))

                assertThatThrownBy {
                    service.create(command(fixture, "refund-allocation-missing", fixture.firstLineId, 1))
                }.isInstanceOf(DomainFailure::class.java)

                assertThat(singleString("select state from payment_refund")).isEqualTo("PROCESSING")
                assertThat(singleLong("select succeeded_refund_amount_krw from payment_payment")).isZero()
                assertThat(singleLong("select count(*) from payment_refund_line_allocation")).isZero()
                assertThat(singleLong("select count(*) from event_publication")).isZero()
            } finally {
                jdbcTemplate.execute(
                    "drop trigger test_skip_refund_line_allocation on payment_refund_line_allocation",
                )
                jdbcTemplate.execute("drop function test_skip_refund_line_allocation()")
            }
        }

        @Test
        fun `PaymentRefunded missing settlement snapshot fails before a success fact exists`() {
            assertThatThrownBy {
                fixture(includeSettlementSnapshot = false)
            }.isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
                .hasStackTraceContaining("Order requires exactly one settlement input snapshot")

            assertThat(singleLong("select count(*) from payment_refund")).isZero()
            assertThat(singleLong("select count(*) from event_publication")).isZero()
        }

        @Test
        fun `failed units remain refundable and later success consumes the same front range`() {
            val fixture = fixture()
            gateway.enqueueRejectionRefund(
                GatewayRefundResult.Failed("DECLINED"),
                GatewayRefundResult.Succeeded("provider-refund-2"),
            )

            val failed = service.create(command(fixture, "refund-key-0003", fixture.firstLineId, 1))
            val succeeded = service.create(command(fixture, "refund-key-0004", fixture.firstLineId, 1))

            assertThat(objectMapper.readTree(failed.body)["state"].asText()).isEqualTo("FAILED")
            assertThat(objectMapper.readTree(succeeded.body)["cashRefundRequestedKrw"].asLong()).isEqualTo(666)
            assertThat(singleLong("select first_unit_index from payment_refund_line_allocation")).isZero()
            assertThat(singleLong("select count(*) from payment_refund_line_request")).isEqualTo(2)
            assertThat(singleLong("select count(*) from payment_refund_line_allocation")).isEqualTo(1)
        }

        @Test
        fun `PaymentRefunded cumulative effects preserve deterministic allocation remainder`() {
            val fixture = fixture()
            gateway.enqueueRejectionRefund(
                GatewayRefundResult.Succeeded("provider-refund-3"),
                GatewayRefundResult.Succeeded("provider-refund-4"),
            )

            service.create(command(fixture, "refund-key-0005", fixture.firstLineId, 1))
            service.create(command(fixture, "refund-key-0006", fixture.firstLineId, 1))

            val rows =
                jdbcTemplate.query(
                    """
                    select first_unit_index, cash_refunded_krw, points_restored_krw, coupon_attribution_krw
                      from payment_refund_line_allocation order by first_unit_index
                    """.trimIndent(),
                    { rs, _ -> listOf(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) },
                )
            assertThat(rows).containsExactly(
                listOf(0, 666, 333, 1),
                listOf(1, 666, 334, 0),
            )
            assertThat(singleLong("select succeeded_refund_amount_krw from payment_payment")).isEqualTo(1_332)
            val effects =
                jdbcTemplate
                    .queryForList(
                        """
                        select serialized_event from event_publication
                         where listener_id = 'beanflow.settlement.payment-refunded-v1'
                        """.trimIndent(),
                        String::class.java,
                    ).map { objectMapper.readTree(it)["settlementRefundEffect"] }
            assertThat(effects.sumOf { it["grossPaidDeltaKrw"].asLong() }).isEqualTo(-2_000)
            assertThat(effects.sumOf { it["benefitCostDeltaKrw"].asLong() }).isEqualTo(-668)
            assertThat(effects.sumOf { it["netSettlementDeltaKrw"].asLong() }).isEqualTo(-1_332)
        }

        @Test
        fun `full refund consumes every remaining line and ties out to approved amount`() {
            val fixture = fixture()
            gateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-refund-full"))

            val result =
                service.create(
                    PartialRefundCommand(
                        paymentId = fixture.paymentId,
                        actor = PartialRefundActor(fixture.actorId, setOf(PartialRefundActorType.STORE_OWNER)),
                        idempotencyKey = "refund-key-full",
                        lines = null,
                        reason = "FULL_ORDER_ADJUSTMENT",
                    ),
                )

            val body = objectMapper.readTree(result.body)
            assertThat(body["cashRefundRequestedKrw"].asLong()).isEqualTo(5_000)
            assertThat(body["pointsRestorationRequestedKrw"].asLong()).isEqualTo(3_000)
            assertThat(singleLong("select sum(cash_refunded_krw) from payment_refund_line_allocation"))
                .isEqualTo(5_000)
            assertThat(singleLong("select sum(points_restored_krw) from payment_refund_line_allocation"))
                .isEqualTo(3_000)
            assertThat(singleLong("select sum(coupon_attribution_krw) from payment_refund_line_allocation"))
                .isEqualTo(2_000)
            assertThat(singleLong("select succeeded_refund_amount_krw from payment_payment")).isEqualTo(5_000)
        }

        @Test
        fun `provider call holds no database transaction and concurrent refund is rejected as unresolved`() {
            val fixture = fixture()
            val block = gateway.blockNextRefund()
            gateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-refund-concurrent"))
            val executor =
                java.util.concurrent.Executors
                    .newSingleThreadExecutor()
            val first =
                executor.submit<PartialRefundHttpResult> {
                    service.create(command(fixture, "refund-key-0008", fixture.firstLineId, 1))
                }
            assertThat(block.awaitStarted()).isTrue()

            assertThatThrownBy {
                service.create(command(fixture, "refund-key-0009", fixture.firstLineId, 1))
            }.isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.ORDER_STATE_CONFLICT)
            }

            block.release()
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS).status).isEqualTo(201)
            executor.shutdown()
            assertThat(gateway.rejectionRefundCalls.get()).isEqualTo(1)
        }

        @Test
        fun `loyalty write failures remain durable and exhaust to manual review`() {
            val fixture = fixture()
            gateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-refund-retry"))
            service.create(command(fixture, "refund-key-0010", fixture.firstLineId, 1))
            var due = Instant.parse("2099-01-01T00:00:00Z")

            repeat(5) {
                val claim = restorationService.claimDue(due, 1).single()
                restorationService.recordFailure(
                    claim,
                    DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "simulated Loyalty write failure"),
                    due,
                )
                due = due.plusSeconds(10_000)
            }

            assertThat(singleString("select state from payment_refund_restoration_work"))
                .isEqualTo("MANUAL_REVIEW")
            assertThat(singleLong("select attempt_count from payment_refund_restoration_work")).isEqualTo(5)
            assertThat(singleLong("select count(*) from loyalty_partial_refund_restoration")).isZero()
        }

        @Test
        fun `policy changes affect only later refund snapshots`() {
            val fixture = fixture()
            gateway.enqueueRejectionRefund(
                GatewayRefundResult.Failed("DECLINED"),
                GatewayRefundResult.Failed("DECLINED"),
            )
            service.create(command(fixture, "refund-key-0011", fixture.firstLineId, 1))
            val oldVersion =
                singleLong(
                    "select point_restoration_policy_version_id from payment_refund where idempotency_key = 'refund-key-0011'",
                )
            val newVersion =
                jdbcTemplate.queryForObject(
                    """
                    INSERT INTO operations_expired_benefit_policy_version (
                        trigger, benefit_type, mode, compensation_validity_days,
                        effective_at, updated_by, reason
                    ) VALUES ('PARTIAL_REFUND', 'POINTS', 'PRESERVE_ORIGINAL_EXPIRY', 7, ?, ?, 'TEST_POLICY_CHANGE')
                    RETURNING policy_version
                    """.trimIndent(),
                    Long::class.java,
                    Timestamp.from(NOW.plusSeconds(1)),
                    fixture.actorId,
                )!!
            jdbcTemplate.update(
                """
                UPDATE operations_expired_benefit_policy_head
                   SET policy_version = ?, version = version + 1
                 WHERE trigger = 'PARTIAL_REFUND' AND benefit_type = 'POINTS'
                """.trimIndent(),
                newVersion,
            )

            service.create(command(fixture, "refund-key-0012", fixture.firstLineId, 1))

            val snapshots =
                jdbcTemplate.query(
                    """
                    select point_restoration_policy_version_id, point_restoration_policy_mode,
                           point_restoration_policy_validity_days
                      from payment_refund order by created_at, id
                    """.trimIndent(),
                    { rs, _ -> Triple(rs.getLong(1), rs.getString(2), rs.getInt(3)) },
                )
            assertThat(snapshots).containsExactlyInAnyOrder(
                Triple(oldVersion, "COMPENSATE_WITH_NEW_ISSUANCE", 30),
                Triple(newVersion, "PRESERVE_ORIGINAL_EXPIRY", 7),
            )
        }

        @Test
        fun `PointsRestored expiry boundary publishes each immutable slice once`() {
            val fixture = boundaryFixture()
            val policyVersion =
                singleLong(
                    """
                    select policy_version from operations_expired_benefit_policy_head
                     where trigger = 'PARTIAL_REFUND' and benefit_type = 'POINTS'
                    """.trimIndent(),
                )
            val command =
                RestorePartialRefundPointsCommand(
                    refundId = UUID.randomUUID(),
                    orderId = fixture.orderId,
                    refundSucceededAt = NOW,
                    sourceReference = "boundary-restoration",
                    refundSourceReference = "partial-refund:boundary",
                    orderCompletedAt = null,
                    correlationId = "correlation:${fixture.orderId}",
                    policyVersionId = policyVersion,
                    policyMode = PartialRefundPointPolicyMode.COMPENSATE_WITH_NEW_ISSUANCE,
                    compensationValidityDays = 30,
                    slices = fixture.slices,
                )

            val first = pointOperations.restore(command)
            val replay = pointOperations.restore(command)

            assertThat(
                singleLong(
                    "select count(*) from event_publication where event_type like '%PointsRestoredV1'",
                ),
            ).isEqualTo(3)
            val restoredPayloads =
                jdbcTemplate.queryForList(
                    """
                    select serialized_event from event_publication
                     where event_type like '%PointsRestoredV1' order by serialized_event
                    """.trimIndent(),
                    String::class.java,
                )
            assertThat(restoredPayloads).allSatisfy {
                assertThat(it).contains("\"refundSource\":\"partial-refund:boundary\"")
                assertThat(it).contains("\"orderCompletedAt\":null")
            }

            assertThatThrownBy {
                pointOperations.restore(command.copy(compensationValidityDays = 31))
            }.isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.IDEMPOTENCY_KEY_REUSED)
            }
            assertThatThrownBy {
                pointOperations.restore(
                    command.copy(
                        slices =
                            command.slices.mapIndexed { index, slice ->
                                if (index == 0) slice.copy(issuerReference = "brand:changed") else slice
                            },
                    ),
                )
            }.isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.IDEMPOTENCY_KEY_REUSED)
            }

            assertThat(first.restoredAmountKrw).isEqualTo(3)
            assertThat(first.replayed).isFalse()
            assertThat(replay.replayed).isTrue()
            val dispositions =
                jdbcTemplate.queryForList(
                    "select disposition from loyalty_partial_refund_restoration order by order_line_id",
                    String::class.java,
                )
            assertThat(dispositions).containsExactlyInAnyOrder(
                "COMPENSATION_LOT",
                "COMPENSATION_LOT",
                "ORIGINAL_LOT",
            )
            assertThat(singleLong("select count(*) from loyalty_point_lot where original_point_lot_id is not null"))
                .isEqualTo(2)
            assertThat(singleLong("select count(*) from loyalty_partial_refund_restoration")).isEqualTo(3)
            assertThat(singleString("select state from loyalty_point_reservation")).isEqualTo("USED")
            assertThat(singleLong("select available_points_krw from loyalty_point_account")).isEqualTo(3)
            assertThat(
                jdbcTemplate
                    .queryForList(
                        "select expires_at from loyalty_point_lot where original_point_lot_id is not null",
                        Timestamp::class.java,
                    ).map { requireNotNull(it).toInstant() },
            ).allMatch { it == NOW.plusSeconds(30L * 86_400) }
        }

        @Test
        fun `PointsRestored outbox failure rolls back loyalty owner facts and schedules retry`() {
            val fixture = fixture()
            gateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-refund-points-outbox"))
            service.create(command(fixture, "refund-points-outbox", fixture.firstLineId, 1))
            jdbcTemplate.execute(
                """
                ALTER TABLE event_publication
                ADD CONSTRAINT test_block_points_restored
                CHECK (event_type <> 'io.github.kdh949.beanflow.eventing.api.PointsRestoredV1')
                """.trimIndent(),
            )
            try {
                assertThat(restorationWorker.runOnce()).isEqualTo(1)

                assertThat(singleString("select state from payment_refund_restoration_work"))
                    .isEqualTo("RETRY_SCHEDULED")
                assertThat(singleLong("select count(*) from loyalty_partial_refund_restoration")).isZero()
                assertThat(singleLong("select count(*) from loyalty_point_transaction where refund_id is not null"))
                    .isZero()
                assertThat(
                    singleLong(
                        "select count(*) from event_publication where event_type like '%PointsRestoredV1'",
                    ),
                ).isZero()
            } finally {
                jdbcTemplate.execute("alter table event_publication drop constraint test_block_points_restored")
            }
        }

        @Test
        fun `database rejects overlapping success allocation at commit`() {
            val fixture = fixture()
            gateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-refund-5"))
            service.create(command(fixture, "refund-key-0007", fixture.firstLineId, 1))
            val refundId = UUID.randomUUID()
            val lineRequestId = UUID.randomUUID()

            val failure =
                catchThrowable {
                    transactions.executeWithoutResult {
                        jdbcTemplate.update(
                            """
                            INSERT INTO payment_refund (
                                id, payment_id, order_id, requested_amount_krw, requested_points_krw,
                                reason, state, provider_idempotency_key, source_reference,
                                actor_id, idempotency_key, payload_hash, correlation_id,
                                point_restoration_policy_version_id, point_restoration_policy_trigger,
                                point_restoration_policy_benefit_type, point_restoration_policy_mode,
                                point_restoration_policy_validity_days, attempt_count,
                                request_attempt_count, lookup_attempt_count, next_action,
                                succeeded_amount_krw, provider_refund_reference,
                                created_at, updated_at
                            )
                            SELECT ?, id, order_id, 666, 333, 'PARTIAL_REFUND', 'SUCCEEDED', ?, ?,
                                   ?, ?, repeat('a', 64), 'test-correlation',
                                   (select policy_version from operations_expired_benefit_policy_head
                                     where trigger='PARTIAL_REFUND' and benefit_type='POINTS'),
                                   'PARTIAL_REFUND', 'POINTS', 'COMPENSATE_WITH_NEW_ISSUANCE', 30,
                                   1, 1, 0, 'REQUEST', 666, 'provider-overlap', ?, ?
                              FROM payment_payment
                            """.trimIndent(),
                            refundId,
                            "refund:partial:$refundId",
                            "overlap:$refundId",
                            fixture.actorId,
                            "overlap-key-0001",
                            Timestamp.from(NOW),
                            Timestamp.from(NOW),
                        )
                        jdbcTemplate.update(
                            """
                            INSERT INTO payment_refund_line_request (
                                id, refund_id, order_line_id, line_sequence, first_unit_index, quantity,
                                original_quantity, gross_krw, coupon_attribution_krw,
                                points_restoration_krw, cash_refund_krw, source_reference, created_at
                            ) VALUES (?, ?, ?, 0, 0, 1, 3, 1000, 1, 333, 666, ?, ?)
                            """.trimIndent(),
                            lineRequestId,
                            refundId,
                            fixture.firstLineId,
                            "overlap:$refundId:line",
                            Timestamp.from(NOW),
                        )
                        jdbcTemplate.update(
                            """
                            INSERT INTO payment_refund_point_request (
                                id, refund_id, refund_line_request_id, order_line_id,
                                point_reservation_allocation_id, original_point_lot_id,
                                issuer_type, issuer_reference, requested_amount_krw,
                                source_reference, created_at
                            )
                            SELECT ?, ?, ?, ?, id, point_lot_id, 'STORE', ?, 333, ?, ?
                              FROM loyalty_point_reservation_allocation order by point_lot_id limit 1
                            """.trimIndent(),
                            UUID.randomUUID(),
                            refundId,
                            lineRequestId,
                            fixture.firstLineId,
                            fixture.storeId.toString(),
                            "overlap:$refundId:point",
                            Timestamp.from(NOW),
                        )
                        jdbcTemplate.update(
                            """
                            INSERT INTO payment_refund_line_allocation (
                                id, refund_id, refund_line_request_id, order_line_id,
                                first_unit_index, quantity, gross_krw, coupon_attribution_krw,
                                points_restored_krw, cash_refunded_krw, source_reference, succeeded_at
                            ) VALUES (?, ?, ?, ?, 0, 1, 1000, 1, 333, 666, ?, ?)
                            """.trimIndent(),
                            UUID.randomUUID(),
                            refundId,
                            lineRequestId,
                            fixture.firstLineId,
                            "overlap:$refundId:success",
                            Timestamp.from(NOW),
                        )
                    }
                }
            assertThat(failure).isNotNull()
            assertThat(generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList())
                .anySatisfy { assertThat(it).contains("Successful Refund unit ranges overlap") }
        }

        private fun fixture(includeSettlementSnapshot: Boolean = true): Fixture {
            val fixture =
                Fixture(
                    actorId = UUID.randomUUID(),
                    storeId = UUID.randomUUID(),
                    customerId = UUID.randomUUID(),
                    orderId = UUID.randomUUID(),
                    paymentId = UUID.randomUUID(),
                    firstLineId = UUID.randomUUID(),
                    secondLineId = UUID.randomUUID(),
                )
            val methodId = UUID.randomUUID()
            val accountId = UUID.randomUUID()
            val expiredLotId = UUID.fromString("00000000-0000-0000-0000-000000000101")
            val validLotId = UUID.fromString("00000000-0000-0000-0000-000000000102")
            val reservationId = UUID.randomUUID()
            val campaignId = UUID.randomUUID()
            val issuanceId = UUID.randomUUID()
            val couponReservationId = UUID.randomUUID()
            jdbcTemplate.update(
                "insert into identity_store_membership values (?, ?, ?, 'OWNER', 'ACTIVE', ?, ?, 0)",
                UUID.randomUUID(),
                fixture.actorId,
                fixture.storeId,
                Timestamp.from(NOW),
                Timestamp.from(NOW),
            )
            transactions.executeWithoutResult {
                val settlementCreatedAt = NOW.minusSeconds(60)
                jdbcTemplate.update(
                    """
                    INSERT INTO ordering_order (
                        id, customer_id, store_id, pickup_slot_id, state, subtotal_krw,
                        coupon_discount_krw, points_applied_krw, payable_krw, currency,
                        reservation_expires_at, created_at, updated_at, paid_at,
                        acceptance_warning_at, acceptance_deadline_at, version
                    ) VALUES (?, ?, ?, ?, 'PAID', 10000, 2000, 3000, 5000, 'KRW',
                              NULL, ?, ?, ?, ?, ?, 0)
                    """.trimIndent(),
                    fixture.orderId,
                    fixture.customerId,
                    fixture.storeId,
                    UUID.randomUUID(),
                    Timestamp.from(NOW.minusSeconds(60)),
                    Timestamp.from(NOW),
                    Timestamp.from(NOW),
                    Timestamp.from(NOW.plusSeconds(120)),
                    Timestamp.from(NOW.plusSeconds(180)),
                )
                jdbcTemplate.update(
                    """
                    INSERT INTO ordering_order_line (
                        id, order_id, line_sequence, menu_id, menu_name,
                        option_names_json, sellable_requirements_json,
                        unit_price_krw, quantity, gross_krw,
                        coupon_discount_krw, points_applied_krw, cash_payable_krw,
                        option_selection_snapshot_state, normalized_option_ids_json
                    ) VALUES
                        (?, ?, 0, ?, 'line-1', '[]', '[]', 1000, 3, 3000, 1, 1000, 1999, 'LEGACY_UNAVAILABLE', NULL),
                        (?, ?, 1, ?, 'line-2', '[]', '[]', 3500, 2, 7000, 1999, 2000, 3001, 'LEGACY_UNAVAILABLE', NULL)
                    """.trimIndent(),
                    fixture.firstLineId,
                    fixture.orderId,
                    UUID.randomUUID(),
                    fixture.secondLineId,
                    fixture.orderId,
                    UUID.randomUUID(),
                )
                savePointAccrualSnapshot(
                    orderId = fixture.orderId,
                    storeId = fixture.storeId,
                    payableKrw = 5_000,
                    lines =
                        listOf(
                            OrderPointAccrualLineInput(fixture.firstLineId, 0, 1_000, 3, 3_000, 1, 1_000, 1_999),
                            OrderPointAccrualLineInput(fixture.secondLineId, 1, 3_500, 2, 7_000, 1_999, 2_000, 3_001),
                        ),
                )
                jdbcTemplate.update(
                    """
                    INSERT INTO loyalty_point_account (
                        id, customer_id, available_points_krw, reserved_points_krw, version
                    ) VALUES (?, ?, 0, 0, 0)
                    """.trimIndent(),
                    accountId,
                    fixture.customerId,
                )
                jdbcTemplate.update(
                    """
                    INSERT INTO loyalty_point_lot (
                        id, point_account_id, available_amount_krw, reserved_amount_krw,
                        expires_at, version, issuer_type, issuer_reference
                    ) VALUES
                        (?, ?, 0, 0, '2025-01-01T00:00:00Z', 0, 'STORE', ?),
                        (?, ?, 0, 0, '2035-01-01T00:00:00Z', 0, 'BRAND', 'brand:fixture')
                    """.trimIndent(),
                    expiredLotId,
                    accountId,
                    fixture.storeId.toString(),
                    validLotId,
                    accountId,
                )
                jdbcTemplate.update(
                    """
                    INSERT INTO loyalty_point_reservation (
                        id, order_id, point_account_id, amount_krw, state,
                        reservation_expires_at, source_reference, created_at, updated_at, version
                    ) VALUES (?, ?, ?, 3000, 'USED', ?, ?, ?, ?, 0)
                    """.trimIndent(),
                    reservationId,
                    fixture.orderId,
                    accountId,
                    Timestamp.from(NOW.plusSeconds(300)),
                    "points:${fixture.orderId}",
                    Timestamp.from(NOW),
                    Timestamp.from(NOW),
                )
                jdbcTemplate.update(
                    """
                    INSERT INTO loyalty_point_reservation_allocation VALUES
                        (?, ?, ?, 1500), (?, ?, ?, 1500)
                    """.trimIndent(),
                    UUID.randomUUID(),
                    reservationId,
                    expiredLotId,
                    UUID.randomUUID(),
                    reservationId,
                    validLotId,
                )
                jdbcTemplate.update(
                    """
                    INSERT INTO payment_method VALUES
                        (?, ?, 'SCRIPTED', 'token', 'test', 'TEST', '1234', 'ACTIVE', ?, ?, 0)
                    """.trimIndent(),
                    methodId,
                    fixture.customerId,
                    Timestamp.from(NOW),
                    Timestamp.from(NOW),
                )
                jdbcTemplate.update(
                    """
                    INSERT INTO payment_payment (
                        id, order_id, type, approval_state, approved_amount_krw, currency,
                        benefit_snapshot_reference, source_reference, correlation_id,
                        approved_at, updated_at, customer_id, payment_method_id,
                        requested_amount_krw, provider_transaction_reference,
                        created_at, version, succeeded_refund_amount_krw
                    ) VALUES (?, ?, 'EXTERNAL', 'APPROVED', 5000, 'KRW', NULL, ?, ?, ?, ?,
                              ?, ?, 5000, ?, ?, 0, 0)
                    """.trimIndent(),
                    fixture.paymentId,
                    fixture.orderId,
                    "payment:${fixture.paymentId}",
                    "correlation:${fixture.orderId}",
                    Timestamp.from(NOW),
                    Timestamp.from(NOW),
                    fixture.customerId,
                    methodId,
                    "provider-payment:${fixture.paymentId}",
                    Timestamp.from(NOW),
                )
                jdbcTemplate.update(
                    """
                    INSERT INTO payment_provider_request_snapshot (
                        payment_id, payment_method_id, provider, token_reference,
                        provider_customer_reference, created_at
                    ) VALUES (?, ?, 'SCRIPTED', 'token', NULL, ?)
                    """.trimIndent(),
                    fixture.paymentId,
                    methodId,
                    Timestamp.from(NOW),
                )
                jdbcTemplate.update(
                    """
                    INSERT INTO promotion_campaign (
                        id, store_id, active, discount_type, fixed_amount_krw, rate_bps,
                        minimum_eligible_subtotal_krw, maximum_discount_krw,
                        all_menus_eligible, cost_bearer, platform_share_bps,
                        store_share_bps, version
                    ) VALUES (?, ?, true, 'FIXED_KRW', 2000, NULL, 0, NULL, true,
                              'STORE', 0, 10000, 0)
                    """.trimIndent(),
                    campaignId,
                    fixture.storeId,
                )
                jdbcTemplate.update(
                    """
                    INSERT INTO promotion_coupon_issuance (
                        id, campaign_id, customer_id, state, coupon_expires_at,
                        reserved_order_id, version
                    ) VALUES (?, ?, ?, 'USED', '2035-01-01T00:00:00Z', ?, 0)
                    """.trimIndent(),
                    issuanceId,
                    campaignId,
                    fixture.customerId,
                    fixture.orderId,
                )
                jdbcTemplate.update(
                    """
                    INSERT INTO promotion_coupon_reservation (
                        id, order_id, coupon_issuance_id, state, discount_krw,
                        eligible_line_sequences, discount_type, fixed_amount_krw, rate_bps,
                        minimum_eligible_subtotal_krw, maximum_discount_krw,
                        campaign_id, campaign_version, store_id, all_menus_eligible,
                        eligible_menu_ids, cost_bearer, platform_share_bps,
                        store_share_bps, platform_coupon_cost_krw, store_coupon_cost_krw,
                        reservation_expires_at, source_reference, created_at, updated_at, version
                    ) VALUES (?, ?, ?, 'USED', 2000, '0,1', 'FIXED_KRW', 2000, NULL,
                              0, NULL, ?, 0, ?, true, '', 'STORE', 0, 10000, 0, 2000, ?, ?, ?, ?, 0)
                    """.trimIndent(),
                    couponReservationId,
                    fixture.orderId,
                    issuanceId,
                    campaignId,
                    fixture.storeId,
                    Timestamp.from(NOW.plusSeconds(300)),
                    "coupon:${fixture.orderId}",
                    Timestamp.from(NOW),
                    Timestamp.from(NOW),
                )
                val termsVersionId = UUID.randomUUID()
                jdbcTemplate.update(
                    "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled) VALUES (?, true, true)",
                    fixture.storeId,
                )
                jdbcTemplate.update(
                    """
                    INSERT INTO merchant_store_settlement_terms (
                        terms_version_id, store_id, source_reference, fee_rate_bps,
                        effective_from, effective_to, created_at
                    ) VALUES (?, ?, ?, 0, '2020-01-01T00:00:00Z', ?, ?)
                    """.trimIndent(),
                    termsVersionId,
                    fixture.storeId,
                    "test:partial-refund-terms:${fixture.orderId}",
                    Timestamp.from(NOW.plusSeconds(10)),
                    Timestamp.from(NOW),
                )
                if (includeSettlementSnapshot) {
                    jdbcTemplate.update(
                        """
                        INSERT INTO ordering_order_settlement_input_snapshot (
                            order_id, store_id, store_settlement_terms_version_id,
                            store_settlement_terms_source_reference,
                            coupon_reservation_id, coupon_campaign_id, coupon_campaign_version,
                            coupon_cost_bearer, coupon_platform_share_bps, coupon_store_share_bps,
                            coupon_discount_krw, platform_coupon_cost_krw, coupon_cost_krw,
                            point_reservation_id, point_allocation_hash, points_applied_krw, point_cost_krw,
                            gross_paid_krw, fee_base_krw, fee_rate_bps, fee_krw,
                            benefit_cost_krw, net_settlement_krw, currency,
                            snapshot_schema_version, canonical_snapshot_hash, created_at
                        ) VALUES (
                            ?, ?, ?, ?, ?, ?, 0, 'STORE', 0, 10000,
                            2000, 0, 2000, ?, ?, 3000, 1500,
                            10000, 5000, 0, 0, 3500, 6500, 'KRW', 1, ?, ?
                        )
                        """.trimIndent(),
                        fixture.orderId,
                        fixture.storeId,
                        termsVersionId,
                        "test:partial-refund-terms:${fixture.orderId}",
                        couponReservationId,
                        campaignId,
                        reservationId,
                        "a".repeat(64),
                        settlementSnapshotHash(
                            1,
                            fixture.orderId,
                            fixture.storeId,
                            termsVersionId,
                            "test:partial-refund-terms:${fixture.orderId}",
                            couponReservationId,
                            campaignId,
                            0L,
                            "STORE",
                            0,
                            10_000,
                            2_000L,
                            0L,
                            2_000L,
                            reservationId,
                            "a".repeat(64),
                            3_000L,
                            1_500L,
                            10_000L,
                            5_000L,
                            0,
                            0L,
                            3_500L,
                            6_500L,
                            "KRW",
                            settlementCreatedAt.epochSecond,
                            settlementCreatedAt.nano / 1_000,
                        ),
                        Timestamp.from(settlementCreatedAt),
                    )
                }
            }
            return fixture
        }

        private fun command(
            fixture: Fixture,
            key: String,
            lineId: UUID,
            quantity: Long,
        ) = PartialRefundCommand(
            paymentId = fixture.paymentId,
            actor = PartialRefundActor(fixture.actorId, setOf(PartialRefundActorType.STORE_OWNER)),
            idempotencyKey = key,
            lines = listOf(PartialRefundLineInput(lineId, quantity)),
            reason = "CUSTOMER_REQUESTED_ITEM_ADJUSTMENT",
        )

        private fun boundaryFixture(): BoundaryFixture {
            val orderId = UUID.randomUUID()
            val customerId = UUID.randomUUID()
            val storeId = UUID.randomUUID()
            val accountId = UUID.randomUUID()
            val reservationId = UUID.randomUUID()
            val lineIds = (1..3).map { UUID.randomUUID() }.sorted()
            val lotIds = (1..3).map { UUID.randomUUID() }.sorted()
            return requireNotNull(
                transactions.execute {
                    val settlementCreatedAt = NOW.minusSeconds(60)
                    jdbcTemplate.update(
                        """
                        INSERT INTO ordering_order (
                            id, customer_id, store_id, pickup_slot_id, state, subtotal_krw,
                            coupon_discount_krw, points_applied_krw, payable_krw, currency,
                            reservation_expires_at, created_at, updated_at, paid_at,
                            acceptance_warning_at, acceptance_deadline_at, version
                        ) VALUES (?, ?, ?, ?, 'PAID', 3, 0, 3, 0, 'KRW', NULL, ?, ?, ?, ?, ?, 0)
                        """.trimIndent(),
                        orderId,
                        customerId,
                        storeId,
                        UUID.randomUUID(),
                        Timestamp.from(NOW.minusSeconds(60)),
                        Timestamp.from(NOW),
                        Timestamp.from(NOW),
                        Timestamp.from(NOW.plusSeconds(120)),
                        Timestamp.from(NOW.plusSeconds(180)),
                    )
                    lineIds.forEachIndexed { index, lineId ->
                        jdbcTemplate.update(
                            """
                            INSERT INTO ordering_order_line (
                                id, order_id, line_sequence, menu_id, menu_name,
                                option_names_json, sellable_requirements_json,
                                unit_price_krw, quantity, gross_krw,
                                coupon_discount_krw, points_applied_krw, cash_payable_krw,
                                option_selection_snapshot_state, normalized_option_ids_json
                            ) VALUES
                                (?, ?, ?, ?, ?, '[]', '[]', 1, 1, 1, 0, 1, 0, 'LEGACY_UNAVAILABLE', NULL)
                            """.trimIndent(),
                            lineId,
                            orderId,
                            index,
                            UUID.randomUUID(),
                            "boundary-$index",
                        )
                    }
                    savePointAccrualSnapshot(
                        orderId = orderId,
                        storeId = storeId,
                        payableKrw = 0,
                        lines =
                            lineIds.mapIndexed { index, lineId ->
                                OrderPointAccrualLineInput(lineId, index, 1, 1, 1, 0, 1, 0)
                            },
                    )
                    jdbcTemplate.update(
                        """
                        INSERT INTO loyalty_point_account (
                            id, customer_id, available_points_krw, reserved_points_krw, version
                        ) VALUES (?, ?, 0, 0, 0)
                        """.trimIndent(),
                        accountId,
                        customerId,
                    )
                    val expiries = listOf(NOW.minusNanos(1_000), NOW, NOW.plusNanos(1_000))
                    lotIds.forEachIndexed { index, lotId ->
                        jdbcTemplate.update(
                            """
                            INSERT INTO loyalty_point_lot (
                                id, point_account_id, available_amount_krw, reserved_amount_krw,
                                expires_at, version, issuer_type, issuer_reference
                            ) VALUES (?, ?, 0, 0, ?, 0, 'BRAND', ?)
                            """.trimIndent(),
                            lotId,
                            accountId,
                            Timestamp.from(expiries[index]),
                            "brand:boundary-$index",
                        )
                    }
                    jdbcTemplate.update(
                        """
                        INSERT INTO loyalty_point_reservation (
                            id, order_id, point_account_id, amount_krw, state,
                            reservation_expires_at, source_reference, created_at, updated_at, version
                        ) VALUES (?, ?, ?, 3, 'USED', ?, ?, ?, ?, 0)
                        """.trimIndent(),
                        reservationId,
                        orderId,
                        accountId,
                        Timestamp.from(NOW.plusSeconds(300)),
                        "boundary:$orderId",
                        Timestamp.from(NOW),
                        Timestamp.from(NOW),
                    )
                    val slices =
                        lineIds.zip(lotIds).mapIndexed { index, (lineId, lotId) ->
                            val allocationId = UUID.randomUUID()
                            jdbcTemplate.update(
                                "insert into loyalty_point_reservation_allocation values (?, ?, ?, 1)",
                                allocationId,
                                reservationId,
                                lotId,
                            )
                            PartialRefundPointSlice(
                                orderLineId = lineId,
                                pointReservationAllocationId = allocationId,
                                originalPointLotId = lotId,
                                issuerType = PointIssuerType.BRAND,
                                issuerReference = "brand:boundary-$index",
                                amountKrw = 1,
                            )
                        }
                    val termsVersionId = UUID.randomUUID()
                    jdbcTemplate.update(
                        "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled) VALUES (?, true, true)",
                        storeId,
                    )
                    jdbcTemplate.update(
                        """
                        INSERT INTO merchant_store_settlement_terms (
                            terms_version_id, store_id, source_reference, fee_rate_bps,
                            effective_from, effective_to, created_at
                        ) VALUES (?, ?, ?, 0, '2020-01-01T00:00:00Z', NULL, ?)
                        """.trimIndent(),
                        termsVersionId,
                        storeId,
                        "test:boundary-terms:$orderId",
                        Timestamp.from(NOW),
                    )
                    jdbcTemplate.update(
                        """
                        INSERT INTO ordering_order_settlement_input_snapshot (
                            order_id, store_id, store_settlement_terms_version_id,
                            store_settlement_terms_source_reference,
                            coupon_discount_krw, platform_coupon_cost_krw, coupon_cost_krw,
                            point_reservation_id, point_allocation_hash, points_applied_krw, point_cost_krw,
                            gross_paid_krw, fee_base_krw, fee_rate_bps, fee_krw,
                            benefit_cost_krw, net_settlement_krw, currency,
                            snapshot_schema_version, canonical_snapshot_hash, created_at
                        ) VALUES (
                            ?, ?, ?, ?, 0, 0, 0, ?, ?, 3, 0,
                            3, 0, 0, 0, 0, 3, 'KRW', 1, ?, ?
                        )
                        """.trimIndent(),
                        orderId,
                        storeId,
                        termsVersionId,
                        "test:boundary-terms:$orderId",
                        reservationId,
                        "c".repeat(64),
                        settlementSnapshotHash(
                            1,
                            orderId,
                            storeId,
                            termsVersionId,
                            "test:boundary-terms:$orderId",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            0L,
                            0L,
                            0L,
                            reservationId,
                            "c".repeat(64),
                            3L,
                            0L,
                            3L,
                            0L,
                            0,
                            0L,
                            0L,
                            3L,
                            "KRW",
                            settlementCreatedAt.epochSecond,
                            settlementCreatedAt.nano / 1_000,
                        ),
                        Timestamp.from(settlementCreatedAt),
                    )
                    BoundaryFixture(orderId, slices)
                },
            )
        }

        private fun savePointAccrualSnapshot(
            orderId: UUID,
            storeId: UUID,
            payableKrw: Long,
            lines: List<OrderPointAccrualLineInput>,
        ) {
            val selected = pointAccrualPolicyOperations.selectForOrder(storeId)
            pointAccrualSnapshotService.save(
                orderId = orderId,
                orderPayableKrw = payableKrw,
                selected = selected,
                calculation = pointAccrualCalculator.calculate(selected.policy, lines),
                createdAt = NOW,
            )
        }

        private fun singleLong(sql: String): Long = requireNotNull(jdbcTemplate.queryForObject(sql, Long::class.java))

        private fun singleString(sql: String): String = requireNotNull(jdbcTemplate.queryForObject(sql, String::class.java))

        private fun paymentRefundedEvent() =
            objectMapper.readTree(
                requireNotNull(
                    jdbcTemplate.queryForObject(
                        """
                        select serialized_event from event_publication
                         where listener_id = 'beanflow.settlement.payment-refunded-v1'
                         order by publication_date desc limit 1
                        """.trimIndent(),
                        String::class.java,
                    ),
                ),
            )

        private fun settlementSnapshotHash(vararg fields: Any?): String {
            val canonical = StringBuilder()
            fields.forEach { field ->
                val value = field?.toString() ?: "<null>"
                canonical.append(value.length).append(':').append(value)
            }
            return HexFormat
                .of()
                .formatHex(
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest(canonical.toString().toByteArray(StandardCharsets.UTF_8)),
                )
        }

        private data class Fixture(
            val actorId: UUID,
            val storeId: UUID,
            val customerId: UUID,
            val orderId: UUID,
            val paymentId: UUID,
            val firstLineId: UUID,
            val secondLineId: UUID,
        )

        private data class BoundaryFixture(
            val orderId: UUID,
            val slices: List<PartialRefundPointSlice>,
        )

        private companion object {
            val NOW: Instant = Instant.parse("2026-08-01T00:00:00Z")
        }
    }

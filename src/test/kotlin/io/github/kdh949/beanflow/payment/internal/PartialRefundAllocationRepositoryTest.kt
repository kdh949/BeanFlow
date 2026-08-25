package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.MerchantAccountDatabaseFixture
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
import io.github.kdh949.beanflow.ordering.internal.OrderCreationDatabaseFixture
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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
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
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest(
    properties = [
        "beanflow.payment.refund.initial-delay-ms=3600000",
        "beanflow.payment.refund-restoration.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
    ],
)
internal class PartialRefundAllocationRepositoryTest : PartialRefundIntegrationTestSupport() {
    @Nested
    inner class AllocationAndReplay {
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
                    terminatedAt = now,
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
                    processedAt = now.plusSeconds(1),
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
                Timestamp.from(now.minusSeconds(60)),
                Timestamp.from(now.plusSeconds(60)),
                Timestamp.from(now.plusSeconds(120)),
                Timestamp.from(now.minusSeconds(4)),
                Timestamp.from(now.minusSeconds(3)),
                Timestamp.from(now.minusSeconds(2)),
                Timestamp.from(now.minusSeconds(1)),
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
                Timestamp.from(now.plusSeconds(10)),
                Timestamp.from(now.plusSeconds(10)),
            )
            gateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-refund-completed"))

            service.create(command(fixture, "refund-completed-key", fixture.firstLineId, 1))

            val event = paymentRefundedEvent()
            assertThat(event["completionDisposition"].asText()).isEqualTo("COMPLETED_ORDER")
            assertThat(event["orderCompletedAt"].asText()).isEqualTo(now.minusSeconds(1).toString())
            assertThat(event["settlementItemSource"].asText())
                .isEqualTo("order:${fixture.orderId}:completed:5")
            assertThat(event["settlementRefundEffect"]["feeDeltaKrw"].asLong()).isZero()
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
                    Timestamp.from(now.plusSeconds(1)),
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
    }

    @Nested
    inner class PointRestorationAndRecovery {
        @Test
        fun `post-completion recovery work retries to manual review without losing its target`() {
            val fixture = fixture()
            gateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-refund-recovery"))
            service.create(command(fixture, "refund-recovery-key-0002", fixture.firstLineId, 1))
            val snapshot = requireNotNull(pointAccrualSnapshotService.read(fixture.orderId).snapshot)
            val completedAt = now.minusSeconds(1)
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
                    now.plusSeconds(1),
                ),
            )
            var due = now.plusSeconds(2)
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
                    refundSucceededAt = now,
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
            ).allMatch { it == now.plusSeconds(30L * 86_400) }
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
    }

    @Nested
    inner class HttpAndAuthorization {
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
                                .authorities(SimpleGrantedAuthority("ROLE_MERCHANT")),
                        ).with(csrf())
                        .header("Idempotency-Key", "refund-http-0001")
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
    }

    @Nested
    inner class OutboxAndRollback {
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
    }

    @Nested
    inner class CommitConstraintAndConcurrency {
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
                assertThat(it.code).isEqualTo(FailureCode.REFUND_OUTCOME_UNRESOLVED)
            }

            block.release()
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS).status).isEqualTo(201)
            executor.shutdown()
            assertThat(gateway.rejectionRefundCalls.get()).isEqualTo(1)
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
                            Timestamp.from(now),
                            Timestamp.from(now),
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
                            Timestamp.from(now),
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
                            Timestamp.from(now),
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
                            Timestamp.from(now),
                        )
                    }
                }
            assertThat(failure).isNotNull()
            assertThat(generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList())
                .anySatisfy { assertThat(it).contains("Successful Refund unit ranges overlap") }
        }
    }
}

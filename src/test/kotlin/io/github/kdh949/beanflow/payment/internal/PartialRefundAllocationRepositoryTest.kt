package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.loyalty.api.PartialRefundPointOperations
import io.github.kdh949.beanflow.loyalty.api.PartialRefundPointPolicyMode
import io.github.kdh949.beanflow.loyalty.api.PartialRefundPointSlice
import io.github.kdh949.beanflow.loyalty.api.PointIssuerType
import io.github.kdh949.beanflow.loyalty.api.RestorePartialRefundPointsCommand
import io.github.kdh949.beanflow.ordering.internal.PartialRefundActor
import io.github.kdh949.beanflow.ordering.internal.PartialRefundActorType
import io.github.kdh949.beanflow.ordering.internal.PartialRefundCommand
import io.github.kdh949.beanflow.ordering.internal.PartialRefundHttpResult
import io.github.kdh949.beanflow.ordering.internal.PartialRefundLineInput
import io.github.kdh949.beanflow.ordering.internal.PartialRefundRestorationService
import io.github.kdh949.beanflow.ordering.internal.PartialRefundRestorationWorker
import io.github.kdh949.beanflow.ordering.internal.PartialRefundService
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
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
import java.sql.Timestamp
import java.time.Instant
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
        private val gateway: ScriptedTestPaymentGateway,
        private val jdbcTemplate: JdbcTemplate,
        private val objectMapper: ObjectMapper,
        private val mockMvc: MockMvc,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)

        @BeforeEach
        fun cleanDatabase() {
            jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    payment_refund_restoration_work,
                    payment_refund_point_allocation,
                    payment_refund_line_allocation,
                    payment_refund_point_request,
                    payment_refund_line_request,
                    loyalty_partial_refund_restoration,
                    loyalty_point_transaction,
                    loyalty_point_reservation_allocation,
                    loyalty_point_reservation,
                    loyalty_point_lot,
                    loyalty_point_account,
                    payment_refund,
                    payment_reconciliation,
                    payment_idempotency_record,
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
        fun `front unit rounding succeeds once and expired points restore with issuer lineage`() {
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
                .isEqualTo("store:fixture")
            assertThat(singleString("select disposition from loyalty_partial_refund_restoration"))
                .isEqualTo("COMPENSATION_LOT")

            val replay = service.create(command)
            assertThat(replay).isEqualTo(first)
            assertThat(gateway.rejectionRefundCalls.get()).isEqualTo(1)
            assertThat(singleLong("select count(*) from payment_refund_line_allocation")).isEqualTo(1)
            assertThat(singleLong("select count(*) from loyalty_partial_refund_restoration")).isEqualTo(1)
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
        fun `same idempotency key with another payload conflicts before provider`() {
            val fixture = fixture()
            gateway.enqueueRejectionRefund(GatewayRefundResult.Failed("DECLINED"))
            service.create(command(fixture, "refund-key-0002", fixture.firstLineId, 1))

            assertThatThrownBy {
                service.create(command(fixture, "refund-key-0002", fixture.firstLineId, 2))
            }.isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.IDEMPOTENCY_KEY_REUSED)
            }
            assertThat(gateway.rejectionRefundCalls.get()).isEqualTo(1)
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
        fun `successive partial refunds consume front units with deterministic point remainder`() {
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
        fun `point lot expiry boundary follows the PostgreSQL microsecond tick`() {
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
                    policyVersionId = policyVersion,
                    policyMode = PartialRefundPointPolicyMode.COMPENSATE_WITH_NEW_ISSUANCE,
                    compensationValidityDays = 30,
                    slices = fixture.slices,
                )

            val first = pointOperations.restore(command)
            val replay = pointOperations.restore(command)

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
                            SELECT ?, ?, ?, ?, id, point_lot_id, 'STORE', 'store:fixture', 333, ?, ?
                              FROM loyalty_point_reservation_allocation order by point_lot_id limit 1
                            """.trimIndent(),
                            UUID.randomUUID(),
                            refundId,
                            lineRequestId,
                            fixture.firstLineId,
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

        private fun fixture(): Fixture {
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
            jdbcTemplate.update(
                "insert into identity_store_membership values (?, ?, ?, 'OWNER', 'ACTIVE', ?, ?, 0)",
                UUID.randomUUID(),
                fixture.actorId,
                fixture.storeId,
                Timestamp.from(NOW),
                Timestamp.from(NOW),
            )
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
                INSERT INTO ordering_order_line VALUES
                    (?, ?, 0, ?, 'line-1', '[]', '[]', 1000, 3, 3000, 1, 1000, 1999),
                    (?, ?, 1, ?, 'line-2', '[]', '[]', 3500, 2, 7000, 1999, 2000, 3001)
                """.trimIndent(),
                fixture.firstLineId,
                fixture.orderId,
                UUID.randomUUID(),
                fixture.secondLineId,
                fixture.orderId,
                UUID.randomUUID(),
            )
            jdbcTemplate.update(
                "insert into loyalty_point_account values (?, ?, 0, 0, 0)",
                accountId,
                fixture.customerId,
            )
            jdbcTemplate.update(
                """
                INSERT INTO loyalty_point_lot (
                    id, point_account_id, available_amount_krw, reserved_amount_krw,
                    expires_at, version, issuer_type, issuer_reference
                ) VALUES
                    (?, ?, 0, 0, '2025-01-01T00:00:00Z', 0, 'STORE', 'store:fixture'),
                    (?, ?, 0, 0, '2035-01-01T00:00:00Z', 0, 'BRAND', 'brand:fixture')
                """.trimIndent(),
                expiredLotId,
                accountId,
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
                INSERT INTO promotion_campaign (
                    id, store_id, active, discount_type, fixed_amount_krw, rate_bps,
                    minimum_eligible_subtotal_krw, maximum_discount_krw,
                    all_menus_eligible, version
                ) VALUES (?, ?, true, 'FIXED_KRW', 2000, NULL, 0, NULL, true, 0)
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
                    reservation_expires_at, source_reference, created_at, updated_at, version
                ) VALUES (?, ?, ?, 'USED', 2000, '0,1', 'FIXED_KRW', 2000, NULL,
                          0, NULL, ?, ?, ?, ?, 0)
                """.trimIndent(),
                UUID.randomUUID(),
                fixture.orderId,
                issuanceId,
                Timestamp.from(NOW.plusSeconds(300)),
                "coupon:${fixture.orderId}",
                Timestamp.from(NOW),
                Timestamp.from(NOW),
            )
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
            val accountId = UUID.randomUUID()
            val reservationId = UUID.randomUUID()
            val lineIds = (1..3).map { UUID.randomUUID() }.sorted()
            val lotIds = (1..3).map { UUID.randomUUID() }.sorted()
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
                UUID.randomUUID(),
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
                    INSERT INTO ordering_order_line VALUES
                        (?, ?, ?, ?, ?, '[]', '[]', 1, 1, 1, 0, 1, 0)
                    """.trimIndent(),
                    lineId,
                    orderId,
                    index,
                    UUID.randomUUID(),
                    "boundary-$index",
                )
            }
            jdbcTemplate.update(
                "insert into loyalty_point_account values (?, ?, 0, 0, 0)",
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
            return BoundaryFixture(orderId, slices)
        }

        private fun singleLong(sql: String): Long = requireNotNull(jdbcTemplate.queryForObject(sql, Long::class.java))

        private fun singleString(sql: String): String = requireNotNull(jdbcTemplate.queryForObject(sql, String::class.java))

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

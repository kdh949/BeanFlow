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
import org.junit.jupiter.api.BeforeEach
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

internal abstract class PartialRefundIntegrationTestSupport {
    @Autowired
    protected lateinit var service: PartialRefundService

    @Autowired
    protected lateinit var restorationWorker: PartialRefundRestorationWorker

    @Autowired
    protected lateinit var restorationService: PartialRefundRestorationService

    @Autowired
    protected lateinit var pointOperations: PartialRefundPointOperations

    @Autowired
    protected lateinit var pointReservationOperations: PointReservationOperations

    @Autowired
    protected lateinit var gateway: ScriptedTestPaymentGateway

    @Autowired
    protected lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    protected lateinit var objectMapper: ObjectMapper

    @Autowired
    protected lateinit var mockMvc: MockMvc

    @Autowired
    protected lateinit var pointAccrualPolicyOperations: OrdinaryPointAccrualPolicyOperations

    @Autowired
    protected lateinit var pointAccrualSnapshotService: OrderPointAccrualSnapshotService

    @Autowired
    protected lateinit var refundPointRecoveryOperations: RefundPointRecoveryOperations

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    protected val transactions: TransactionTemplate by lazy { TransactionTemplate(transactionManager) }
    protected val pointAccrualCalculator = OrderPointAccrualCalculator()
    protected val now: Instant = Instant.parse("2026-08-01T00:00:00Z")

    @BeforeEach
    protected fun cleanDatabase() {
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
                identity_merchant_account,
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

    protected fun fixture(includeSettlementSnapshot: Boolean = true): Fixture {
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
        MerchantAccountDatabaseFixture.insertActive(jdbcTemplate, fixture.actorId)
        jdbcTemplate.update(
            "insert into identity_store_membership values (?, ?, ?, 'OWNER', 'ACTIVE', ?, ?, 0)",
            UUID.randomUUID(),
            fixture.actorId,
            fixture.storeId,
            Timestamp.from(now),
            Timestamp.from(now),
        )
        val publicReference =
            OrderCreationDatabaseFixture.registerPublicReference(jdbcTemplate, fixture.orderId)
        transactions.executeWithoutResult {
            val settlementCreatedAt = now.minusSeconds(60)
            jdbcTemplate.update(
                """
                INSERT INTO ordering_order (
                    id, customer_id, store_id, pickup_slot_id,
                    public_reference, pickup_business_date, pickup_sequence,
                    store_name_snapshot, pickup_window_start_snapshot, pickup_window_end_snapshot,
                    state, subtotal_krw,
                    coupon_discount_krw, points_applied_krw, payable_krw, currency,
                    reservation_expires_at, created_at, updated_at, paid_at,
                    acceptance_warning_at, acceptance_deadline_at, version
                ) VALUES (?, ?, ?, ?, ?, DATE '2026-08-03', ?,
                          'Test Store', '2026-08-03T00:00:00Z', '2026-08-03T00:10:00Z',
                          'PAID', 10000, 2000, 3000, 5000, 'KRW',
                          NULL, ?, ?, ?, ?, ?, 0)
                """.trimIndent(),
                fixture.orderId,
                fixture.customerId,
                fixture.storeId,
                UUID.randomUUID(),
                publicReference,
                OrderCreationDatabaseFixture.pickupSequence(fixture.orderId),
                Timestamp.from(now.minusSeconds(60)),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now.plusSeconds(120)),
                Timestamp.from(now.plusSeconds(180)),
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
                Timestamp.from(now.plusSeconds(300)),
                "points:${fixture.orderId}",
                Timestamp.from(now),
                Timestamp.from(now),
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
                Timestamp.from(now),
                Timestamp.from(now),
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
                Timestamp.from(now),
                Timestamp.from(now),
                fixture.customerId,
                methodId,
                "provider-payment:${fixture.paymentId}",
                Timestamp.from(now),
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
                Timestamp.from(now),
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
                Timestamp.from(now.plusSeconds(300)),
                "coupon:${fixture.orderId}",
                Timestamp.from(now),
                Timestamp.from(now),
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
                Timestamp.from(now.plusSeconds(10)),
                Timestamp.from(now),
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

    protected fun command(
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

    protected fun boundaryFixture(): BoundaryFixture {
        val orderId = UUID.randomUUID()
        val customerId = UUID.randomUUID()
        val storeId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        val reservationId = UUID.randomUUID()
        val lineIds = (1..3).map { UUID.randomUUID() }.sorted()
        val lotIds = (1..3).map { UUID.randomUUID() }.sorted()
        val publicReference = OrderCreationDatabaseFixture.registerPublicReference(jdbcTemplate, orderId)
        return requireNotNull(
            transactions.execute {
                val settlementCreatedAt = now.minusSeconds(60)
                jdbcTemplate.update(
                    """
                    INSERT INTO ordering_order (
                        id, customer_id, store_id, pickup_slot_id,
                        public_reference, pickup_business_date, pickup_sequence,
                        store_name_snapshot, pickup_window_start_snapshot, pickup_window_end_snapshot,
                        state, subtotal_krw,
                        coupon_discount_krw, points_applied_krw, payable_krw, currency,
                        reservation_expires_at, created_at, updated_at, paid_at,
                        acceptance_warning_at, acceptance_deadline_at, version
                    ) VALUES (?, ?, ?, ?, ?, DATE '2026-08-03', ?,
                              'Test Store', '2026-08-03T00:00:00Z', '2026-08-03T00:10:00Z',
                              'PAID', 3, 0, 3, 0, 'KRW', NULL, ?, ?, ?, ?, ?, 0)
                    """.trimIndent(),
                    orderId,
                    customerId,
                    storeId,
                    UUID.randomUUID(),
                    publicReference,
                    OrderCreationDatabaseFixture.pickupSequence(orderId),
                    Timestamp.from(now.minusSeconds(60)),
                    Timestamp.from(now),
                    Timestamp.from(now),
                    Timestamp.from(now.plusSeconds(120)),
                    Timestamp.from(now.plusSeconds(180)),
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
                val expiries = listOf(now.minusNanos(1_000), now, now.plusNanos(1_000))
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
                    Timestamp.from(now.plusSeconds(300)),
                    "boundary:$orderId",
                    Timestamp.from(now),
                    Timestamp.from(now),
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
                    Timestamp.from(now),
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

    protected fun savePointAccrualSnapshot(
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
            createdAt = now,
        )
    }

    protected fun singleLong(sql: String): Long = requireNotNull(jdbcTemplate.queryForObject(sql, Long::class.java))

    protected fun singleString(sql: String): String = requireNotNull(jdbcTemplate.queryForObject(sql, String::class.java))

    protected fun paymentRefundedEvent() =
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

    protected fun settlementSnapshotHash(vararg fields: Any?): String {
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

    protected data class Fixture(
        val actorId: UUID,
        val storeId: UUID,
        val customerId: UUID,
        val orderId: UUID,
        val paymentId: UUID,
        val firstLineId: UUID,
        val secondLineId: UUID,
    )

    protected data class BoundaryFixture(
        val orderId: UUID,
        val slices: List<PartialRefundPointSlice>,
    )
}

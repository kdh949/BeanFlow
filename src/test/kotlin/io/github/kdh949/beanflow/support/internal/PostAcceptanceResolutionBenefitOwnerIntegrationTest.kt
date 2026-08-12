package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.loyalty.api.PointIssuerType
import io.github.kdh949.beanflow.loyalty.api.PointReservationOperations
import io.github.kdh949.beanflow.loyalty.api.PostAcceptanceResolutionPointDisposition
import io.github.kdh949.beanflow.loyalty.api.PostAcceptanceResolutionPointOperations
import io.github.kdh949.beanflow.loyalty.api.ReservePointsCommand
import io.github.kdh949.beanflow.loyalty.api.RestorePostAcceptanceResolutionPointsCommand
import io.github.kdh949.beanflow.loyalty.internal.PointAccountEntity
import io.github.kdh949.beanflow.loyalty.internal.PointAccountJpaRepository
import io.github.kdh949.beanflow.loyalty.internal.PointLotEntity
import io.github.kdh949.beanflow.loyalty.internal.PointLotJpaRepository
import io.github.kdh949.beanflow.loyalty.internal.PointReservationJpaRepository
import io.github.kdh949.beanflow.promotion.api.CouponCostBearer
import io.github.kdh949.beanflow.promotion.api.CouponDiscountType
import io.github.kdh949.beanflow.promotion.api.CouponPricingLine
import io.github.kdh949.beanflow.promotion.api.CouponReservationOperations
import io.github.kdh949.beanflow.promotion.api.PostAcceptanceResolutionCouponDisposition
import io.github.kdh949.beanflow.promotion.api.PostAcceptanceResolutionCouponOperations
import io.github.kdh949.beanflow.promotion.api.ReserveCouponCommand
import io.github.kdh949.beanflow.promotion.api.RestorePostAcceptanceResolutionCouponCommand
import io.github.kdh949.beanflow.promotion.internal.CampaignEntity
import io.github.kdh949.beanflow.promotion.internal.CampaignJpaRepository
import io.github.kdh949.beanflow.promotion.internal.CouponIssuanceEntity
import io.github.kdh949.beanflow.promotion.internal.CouponIssuanceJpaRepository
import io.github.kdh949.beanflow.promotion.internal.CouponIssuanceState
import io.github.kdh949.beanflow.promotion.internal.CouponReservationJpaRepository
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@SpringBootTest
internal class PostAcceptanceResolutionBenefitOwnerIntegrationTest
    @Autowired
    constructor(
        private val pointOperations: PointReservationOperations,
        private val resolutionPoints: PostAcceptanceResolutionPointOperations,
        private val pointAccounts: PointAccountJpaRepository,
        private val pointLots: PointLotJpaRepository,
        private val pointReservations: PointReservationJpaRepository,
        private val couponOperations: CouponReservationOperations,
        private val resolutionCoupons: PostAcceptanceResolutionCouponOperations,
        private val campaigns: CampaignJpaRepository,
        private val issuances: CouponIssuanceJpaRepository,
        private val couponReservations: CouponReservationJpaRepository,
        private val jdbc: JdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)

        @BeforeEach
        fun cleanDatabase() {
            jdbc.execute(
                """
                TRUNCATE TABLE
                    loyalty_support_resolution_point_restoration,
                    promotion_support_resolution_coupon_restoration,
                    loyalty_point_transaction,
                    loyalty_partial_refund_restoration,
                    loyalty_point_reservation_allocation,
                    loyalty_point_reservation,
                    loyalty_point_lot,
                    loyalty_point_account,
                    promotion_compensation_coupon_eligible_menu,
                    promotion_compensation_coupon_terms_snapshot,
                    promotion_coupon_reservation,
                    promotion_coupon_issuance,
                    promotion_campaign_eligible_menu,
                    promotion_campaign,
                    support_post_acceptance_resolution_step,
                    support_post_acceptance_resolution,
                    merchant_store
                CASCADE
                """.trimIndent(),
            )
        }

        @Test
        fun `valid original benefits are restored once without goodwill issuance`() {
            val fixture = fixture(NOW.plusSeconds(60))
            val pointsCommand =
                RestorePostAcceptanceResolutionPointsCommand(
                    fixture.resolutionId,
                    fixture.orderId,
                    NOW,
                    "support-resolution:${fixture.resolutionId}:points",
                    DIGEST,
                )
            val couponCommand =
                RestorePostAcceptanceResolutionCouponCommand(
                    fixture.resolutionId,
                    fixture.orderId,
                    NOW,
                    "support-resolution:${fixture.resolutionId}:coupon",
                    DIGEST,
                )

            val points = resolutionPoints.restore(pointsCommand)
            val coupon = resolutionCoupons.restore(couponCommand)
            val pointsReplay = resolutionPoints.restore(pointsCommand)
            val couponReplay = resolutionCoupons.restore(couponCommand)

            assertThat(points.disposition).isEqualTo(PostAcceptanceResolutionPointDisposition.RESTORED)
            assertThat(points.restoredAmountKrw).isEqualTo(100)
            assertThat(coupon.disposition).isEqualTo(PostAcceptanceResolutionCouponDisposition.RESTORED)
            assertThat(pointsReplay.replayed).isTrue()
            assertThat(couponReplay.replayed).isTrue()
            assertThat(pointLots.count()).isEqualTo(1)
            assertThat(issuances.count()).isEqualTo(1)
            assertThat(pointReservations.findByOrderId(fixture.orderId)?.restorationTrigger)
                .isEqualTo("POST_ACCEPTANCE_RESOLUTION")
            assertThat(couponReservations.findByOrderId(fixture.orderId)?.restorationTrigger)
                .isEqualTo("POST_ACCEPTANCE_RESOLUTION")
            assertThatThrownBy { resolutionPoints.restore(pointsCommand.copy(payloadHash = OTHER_DIGEST)) }
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.IDEMPOTENCY_KEY_REUSED)
                }
        }

        @Test
        fun `expiry boundary is explicit and creates no replacement benefit`() {
            val fixture = fixture(NOW)

            val points =
                resolutionPoints.restore(
                    RestorePostAcceptanceResolutionPointsCommand(
                        fixture.resolutionId,
                        fixture.orderId,
                        NOW,
                        "support-resolution:${fixture.resolutionId}:points",
                        DIGEST,
                    ),
                )
            val coupon =
                resolutionCoupons.restore(
                    RestorePostAcceptanceResolutionCouponCommand(
                        fixture.resolutionId,
                        fixture.orderId,
                        NOW,
                        "support-resolution:${fixture.resolutionId}:coupon",
                        DIGEST,
                    ),
                )

            assertThat(points.disposition).isEqualTo(PostAcceptanceResolutionPointDisposition.SKIPPED_EXPIRED)
            assertThat(points.restoredAmountKrw).isZero()
            assertThat(coupon.disposition).isEqualTo(PostAcceptanceResolutionCouponDisposition.SKIPPED_EXPIRED)
            assertThat(pointLots.count()).isEqualTo(1)
            assertThat(issuances.count()).isEqualTo(1)
            assertThat(pointLots.findAll().single().availableAmountKrw).isZero()
            assertThat(issuances.findAll().single().state).isEqualTo(CouponIssuanceState.USED)
        }

        private fun fixture(expiresAt: Instant): Fixture {
            val orderId = UUID.randomUUID()
            val resolutionId = UUID.randomUUID()
            val customerId = UUID.randomUUID()
            val storeId = UUID.randomUUID()
            val menuId = UUID.randomUUID()
            val pointAccountId = UUID.randomUUID()
            val pointLotId = UUID.randomUUID()
            val campaignId = UUID.randomUUID()
            val issuanceId = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                storeId,
            )
            insertResolution(resolutionId, orderId)
            transactions.executeWithoutResult {
                pointAccounts.save(PointAccountEntity(pointAccountId, customerId, availablePointsKrw = 100))
                pointLots.save(
                    PointLotEntity(
                        pointLotId,
                        pointAccountId,
                        availableAmountKrw = 100,
                        expiresAt = expiresAt,
                        issuerType = PointIssuerType.PLATFORM,
                        issuerReference = "platform:resolution-test",
                    ),
                )
                pointOperations.reserve(
                    ReservePointsCommand(orderId, customerId, 100, NOW.plusSeconds(300), "order:$orderId:points"),
                )
                pointOperations.confirm(orderId, "order:$orderId:points")
                campaigns.save(
                    CampaignEntity(
                        id = campaignId,
                        storeId = storeId,
                        active = true,
                        discountType = CouponDiscountType.FIXED_KRW,
                        fixedAmountKrw = 100,
                        rateBps = null,
                        minimumEligibleSubtotalKrw = 0,
                        maximumDiscountKrw = null,
                        allMenusEligible = true,
                        costBearer = CouponCostBearer.PLATFORM,
                        platformShareBps = 10_000,
                        storeShareBps = 0,
                    ),
                )
                issuances.save(
                    CouponIssuanceEntity(
                        issuanceId,
                        campaignId,
                        customerId,
                        CouponIssuanceState.AVAILABLE,
                        expiresAt,
                    ),
                )
                couponOperations.reserve(
                    ReserveCouponCommand(
                        orderId,
                        customerId,
                        storeId,
                        issuanceId,
                        listOf(CouponPricingLine(0, menuId, 1_000)),
                        NOW.plusSeconds(300),
                        "order:$orderId:coupon",
                    ),
                )
                couponOperations.confirm(orderId, "order:$orderId:coupon")
            }
            return Fixture(orderId, resolutionId)
        }

        private fun insertResolution(
            resolutionId: UUID,
            orderId: UUID,
        ) {
            jdbc.execute("ALTER TABLE support_post_acceptance_resolution DISABLE TRIGGER ALL")
            try {
                jdbc.update(
                    """
                    INSERT INTO support_post_acceptance_resolution (
                        id, support_case_id, request_id, revision_id, revision_number, action,
                        action_payload_digest, order_id, trigger_order_state, trigger_order_version,
                        requester_actor_id, command_actor_id, executor_actor_id, outcome, responsibility, cash_refund_krw,
                        restore_points, restore_coupon, settlement_adjustment_krw, evidence_digest,
                        idempotency_key, payload_hash, state, created_at, updated_at,
                        retention_expires_at, version
                    ) VALUES (?, ?, ?, ?, 1, 'POST_ACCEPTANCE_RESOLUTION', ?, ?, 'PREPARING', 4,
                              ?, ?, ?, 'FULL_REFUND', 'PLATFORM', 1, true, true, NULL, ?,
                              ?, ?, 'PLANNED', ?, ?, ?, 0)
                    """.trimIndent(),
                    resolutionId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    DIGEST,
                    orderId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    DIGEST,
                    "resolution-$resolutionId",
                    DIGEST,
                    Timestamp.from(NOW),
                    Timestamp.from(NOW),
                    Timestamp.from(NOW.plusSeconds(90L * 24 * 60 * 60)),
                )
            } finally {
                jdbc.execute("ALTER TABLE support_post_acceptance_resolution ENABLE TRIGGER ALL")
            }
        }

        private data class Fixture(
            val orderId: UUID,
            val resolutionId: UUID,
        )

        private companion object {
            val NOW: Instant = Instant.parse("2030-08-12T03:00:00Z")
            const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            const val OTHER_DIGEST = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        }
    }

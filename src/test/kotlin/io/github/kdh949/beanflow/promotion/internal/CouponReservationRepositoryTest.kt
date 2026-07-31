package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.promotion.api.CouponDiscountType
import io.github.kdh949.beanflow.promotion.api.CouponPricingLine
import io.github.kdh949.beanflow.promotion.api.CouponReservationOperations
import io.github.kdh949.beanflow.promotion.api.ExpiredCouponRestorationMode
import io.github.kdh949.beanflow.promotion.api.ReserveCouponCommand
import io.github.kdh949.beanflow.promotion.api.RestoreCouponByRejectionCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.ReservationTransitionResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@SpringBootTest
internal class CouponReservationRepositoryTest
    @Autowired
    constructor(
        private val operations: CouponReservationOperations,
        private val campaignRepository: CampaignJpaRepository,
        private val eligibleMenuRepository: CampaignEligibleMenuJpaRepository,
        private val issuanceRepository: CouponIssuanceJpaRepository,
        private val reservationRepository: CouponReservationJpaRepository,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)

        @BeforeEach
        fun cleanDatabase() {
            transactions.executeWithoutResult {
                reservationRepository.deleteAllInBatch()
                issuanceRepository.deleteAllInBatch()
                eligibleMenuRepository.deleteAllInBatch()
                campaignRepository.deleteAllInBatch()
            }
        }

        @Test
        fun `one coupon issuance can be reserved by only one concurrent order`() {
            val fixture = insertFixedCoupon()
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)

            val results =
                (1..2)
                    .map { attempt ->
                        executor.submit<Result<Long>> {
                            barrier.await()
                            runCatching {
                                transactions.execute {
                                    operations
                                        .reserve(
                                            command(
                                                fixture = fixture,
                                                orderId = UUID.randomUUID(),
                                                sourceReference = "coupon-attempt-$attempt",
                                            ),
                                        ).discountKrw
                                }
                            }
                        }
                    }.map { it.get(10, TimeUnit.SECONDS) }
            executor.shutdown()

            assertThat(results.count(Result<Long>::isSuccess)).isEqualTo(1)
            val failure = results.single(Result<Long>::isFailure).exceptionOrNull()
            assertThat(failure).isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.COUPON_NOT_AVAILABLE)
            }
            transactions.executeWithoutResult {
                assertThat(issuanceRepository.findById(fixture.issuanceId).orElseThrow().state)
                    .isEqualTo(CouponIssuanceState.RESERVED)
                assertThat(reservationRepository.count()).isEqualTo(1)
            }
        }

        @Test
        fun `rate coupon uses only eligible line subtotal and stores the quote`() {
            val storeId = UUID.randomUUID()
            val customerId = UUID.randomUUID()
            val eligibleMenuId = UUID.randomUUID()
            val otherMenuId = UUID.randomUUID()
            val campaignId = UUID.randomUUID()
            val issuanceId = UUID.randomUUID()
            transactions.executeWithoutResult {
                campaignRepository.save(
                    CampaignEntity(
                        id = campaignId,
                        storeId = storeId,
                        active = true,
                        discountType = CouponDiscountType.RATE_BPS,
                        fixedAmountKrw = null,
                        rateBps = 5_000,
                        minimumEligibleSubtotalKrw = 100,
                        maximumDiscountKrw = null,
                        allMenusEligible = false,
                    ),
                )
                eligibleMenuRepository.save(CampaignEligibleMenuEntity(UUID.randomUUID(), campaignId, eligibleMenuId))
                issuanceRepository.save(
                    CouponIssuanceEntity(
                        id = issuanceId,
                        campaignId = campaignId,
                        customerId = customerId,
                        state = CouponIssuanceState.AVAILABLE,
                        couponExpiresAt = Instant.parse("2030-01-01T00:00:00Z"),
                    ),
                )
            }

            val quote =
                transactions.execute {
                    operations.reserve(
                        ReserveCouponCommand(
                            orderId = UUID.randomUUID(),
                            customerId = customerId,
                            storeId = storeId,
                            couponIssuanceId = issuanceId,
                            lines =
                                listOf(
                                    CouponPricingLine(0, eligibleMenuId, 101),
                                    CouponPricingLine(1, otherMenuId, 10_000),
                                ),
                            reservationExpiresAt = Instant.parse("2030-01-01T00:05:00Z"),
                            sourceReference = "rate-coupon",
                        ),
                    )
                }

            assertThat(quote.discountKrw).isEqualTo(50)
            assertThat(quote.eligibleLineSequences).containsExactly(0)
        }

        @Test
        fun `used unexpired coupon is restored once and can be reserved again`() {
            val fixture = insertFixedCoupon()
            val orderId = UUID.randomUUID()
            transactions.executeWithoutResult {
                operations.reserve(command(fixture, orderId, "coupon-order-$orderId"))
                operations.confirm(orderId, "coupon-order-$orderId")
            }
            val restore =
                RestoreCouponByRejectionCommand(
                    orderId,
                    Instant.parse("2029-01-01T00:00:00Z"),
                    "rejection-coupon-$orderId",
                    ExpiredCouponRestorationMode.COMPENSATE_WITH_NEW_ISSUANCE,
                    30,
                )

            val first = operations.restoreUsedByRejection(restore)
            val replay = operations.restoreUsedByRejection(restore)

            assertThat(first.result).isEqualTo(ReservationTransitionResult.APPLIED)
            assertThat(replay.result).isEqualTo(ReservationTransitionResult.ALREADY_APPLIED)
            transactions.executeWithoutResult {
                assertThat(issuanceRepository.findById(fixture.issuanceId).orElseThrow().state)
                    .isEqualTo(CouponIssuanceState.RESTORED)
                assertThat(reservationRepository.findByOrderId(orderId)?.state)
                    .isEqualTo(CouponReservationState.RESTORED)
                operations.reserve(command(fixture, UUID.randomUUID(), "coupon-reused-${fixture.issuanceId}"))
            }
        }

        private fun insertFixedCoupon(): CouponFixture {
            val fixture =
                CouponFixture(
                    storeId = UUID.randomUUID(),
                    customerId = UUID.randomUUID(),
                    menuId = UUID.randomUUID(),
                    issuanceId = UUID.randomUUID(),
                )
            val campaignId = UUID.randomUUID()
            transactions.executeWithoutResult {
                campaignRepository.save(
                    CampaignEntity(
                        id = campaignId,
                        storeId = fixture.storeId,
                        active = true,
                        discountType = CouponDiscountType.FIXED_KRW,
                        fixedAmountKrw = 100,
                        rateBps = null,
                        minimumEligibleSubtotalKrw = 0,
                        maximumDiscountKrw = null,
                        allMenusEligible = true,
                    ),
                )
                issuanceRepository.save(
                    CouponIssuanceEntity(
                        id = fixture.issuanceId,
                        campaignId = campaignId,
                        customerId = fixture.customerId,
                        state = CouponIssuanceState.AVAILABLE,
                        couponExpiresAt = Instant.parse("2030-01-01T00:00:00Z"),
                    ),
                )
            }
            return fixture
        }

        private fun command(
            fixture: CouponFixture,
            orderId: UUID,
            sourceReference: String,
        ) = ReserveCouponCommand(
            orderId = orderId,
            customerId = fixture.customerId,
            storeId = fixture.storeId,
            couponIssuanceId = fixture.issuanceId,
            lines = listOf(CouponPricingLine(0, fixture.menuId, 1_000)),
            reservationExpiresAt = Instant.parse("2030-01-01T00:05:00Z"),
            sourceReference = sourceReference,
        )

        private data class CouponFixture(
            val storeId: UUID,
            val customerId: UUID,
            val menuId: UUID,
            val issuanceId: UUID,
        )
    }

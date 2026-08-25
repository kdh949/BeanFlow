package io.github.kdh949.beanflow.ordering.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

internal class OrderSettlementInputSnapshotCanonicalizerTest {
    @Test
    fun `rounds created at to PostgreSQL microseconds before hashing`() {
        val canonical =
            OrderSettlementInputSnapshotCanonicalizer.canonicalize(
                snapshot(Instant.parse("2026-08-23T16:00:00.123456789Z")),
            )

        assertThat(canonical.createdAt).isEqualTo(Instant.parse("2026-08-23T16:00:00.123457Z"))
        assertThat(OrderSettlementInputSnapshotCanonicalizer.matches(canonical)).isTrue()
    }

    @Test
    fun `canonical hash rounds its timestamp exactly as PostgreSQL does`() {
        val belowHalfMicrosecond = snapshot(Instant.ofEpochSecond(100, 123_456_499))
        val acrossSecondBoundary = snapshot(Instant.ofEpochSecond(100, 999_999_500))

        val roundedDown = OrderSettlementInputSnapshotCanonicalizer.canonicalize(belowHalfMicrosecond)
        val roundedUp = OrderSettlementInputSnapshotCanonicalizer.canonicalize(acrossSecondBoundary)

        assertThat(roundedDown.createdAt).isEqualTo(Instant.ofEpochSecond(100, 123_456_000))
        assertThat(roundedUp.createdAt).isEqualTo(Instant.ofEpochSecond(101))
        assertThat(OrderSettlementInputSnapshotCanonicalizer.matches(roundedDown)).isTrue()
        assertThat(OrderSettlementInputSnapshotCanonicalizer.matches(roundedUp)).isTrue()
    }

    @Test
    fun `canonicalizing a database rounded timestamp keeps the same hash`() {
        val source = snapshot(Instant.ofEpochSecond(100, 123_456_789))

        val first = OrderSettlementInputSnapshotCanonicalizer.canonicalize(source)
        val replay = OrderSettlementInputSnapshotCanonicalizer.canonicalize(first)

        assertThat(replay.createdAt).isEqualTo(first.createdAt)
        assertThat(replay.canonicalSnapshotHash).isEqualTo(first.canonicalSnapshotHash)
    }

    private fun snapshot(createdAt: Instant) =
        OrderSettlementInputSnapshotEntity(
            orderId = UUID.fromString("10000000-0000-4000-8000-000000000001"),
            storeId = UUID.fromString("20000000-0000-4000-8000-000000000001"),
            storeSettlementTermsVersionId = UUID.fromString("30000000-0000-4000-8000-000000000001"),
            storeSettlementTermsSourceReference = "terms:v1",
            couponReservationId = null,
            couponCampaignId = null,
            couponCampaignVersion = null,
            couponCostBearer = null,
            couponPlatformShareBps = null,
            couponStoreShareBps = null,
            couponDiscountKrw = 0,
            platformCouponCostKrw = 0,
            couponCostKrw = 0,
            pointReservationId = null,
            pointAllocationHash = null,
            pointsAppliedKrw = 0,
            pointCostKrw = 0,
            grossPaidKrw = 10_000,
            feeBaseKrw = 10_000,
            feeRateBps = 300,
            feeKrw = 300,
            benefitCostKrw = 0,
            netSettlementKrw = 9_700,
            currency = "KRW",
            snapshotSchemaVersion = 1,
            canonicalSnapshotHash = "",
            createdAt = createdAt,
        )
}

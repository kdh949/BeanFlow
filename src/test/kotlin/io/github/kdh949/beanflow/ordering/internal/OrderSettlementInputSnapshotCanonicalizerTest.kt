package io.github.kdh949.beanflow.ordering.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

internal class OrderSettlementInputSnapshotCanonicalizerTest {
    @Test
    fun `rounds created at to PostgreSQL microseconds before hashing`() {
        val entity =
            OrderSettlementInputSnapshotEntity(
                orderId = UUID.fromString("aa000000-0000-4000-8000-000000000001"),
                storeId = UUID.fromString("aa000000-0000-4000-8000-000000000002"),
                storeSettlementTermsVersionId = UUID.fromString("aa000000-0000-4000-8000-000000000003"),
                storeSettlementTermsSourceReference = "test:settlement-terms:v1",
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
                createdAt = Instant.parse("2026-08-23T16:00:00.123456789Z"),
            )

        val canonical = OrderSettlementInputSnapshotCanonicalizer.canonicalize(entity)

        assertThat(canonical.createdAt).isEqualTo(Instant.parse("2026-08-23T16:00:00.123457Z"))
        assertThat(OrderSettlementInputSnapshotCanonicalizer.matches(canonical)).isTrue()
    }
}

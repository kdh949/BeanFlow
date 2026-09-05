package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.merchant.api.StorefrontImageStorageOperations
import io.github.kdh949.beanflow.merchant.api.StorefrontImageTarget
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal class LimitedCouponCampaignBannerOrphanSweepTest {
    private val storage = mock(StorefrontImageStorageOperations::class.java)
    private val campaigns = mock(LimitedCouponCampaignPersistence::class.java)
    private val metrics = SimpleMeterRegistry()
    private val now = Instant.parse("2026-09-02T00:00:00Z")
    private val sweep = LimitedCouponCampaignBannerOrphanSweep(storage, campaigns, metrics, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `campaign sweep rechecks current pointers and deletes only an old orphan`() {
        val referenced = "campaigns/id/hash/original.jpg"
        val orphan = "campaigns/id/hash/thumbnail.jpg"
        `when`(
            storage.listOrphanCandidates(
                setOf(StorefrontImageTarget.CAMPAIGN),
                now.minusSeconds(86_400),
                100,
            ),
        ).thenReturn(listOf(referenced, orphan))
        `when`(campaigns.isBannerReferenced(referenced)).thenReturn(true)
        `when`(campaigns.isBannerReferenced(orphan)).thenReturn(false)

        sweep.sweep()

        verify(storage, never()).deleteObject(referenced)
        verify(storage).deleteObject(orphan)
        assertThat(
            metrics
                .get("beanflow.media.orphan")
                .tag("target", "campaign_banner")
                .tag("outcome", "deleted")
                .counter()
                .count(),
        ).isEqualTo(1.0)
    }
}

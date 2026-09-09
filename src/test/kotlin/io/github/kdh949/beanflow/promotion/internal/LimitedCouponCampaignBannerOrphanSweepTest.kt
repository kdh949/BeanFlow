package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.merchant.api.StorefrontImageOrphanCandidatePage
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
    fun `campaign sweep advances past a full referenced page and eventually deletes an old orphan`() {
        val referenced = (1..100).map { "campaigns/id/hash/referenced-$it.jpg" }
        val orphan = "campaigns/id/hash/thumbnail.jpg"
        `when`(
            storage.listOrphanCandidatePage(
                StorefrontImageTarget.CAMPAIGN,
                now.minusSeconds(86_400),
                null,
                100,
            ),
        ).thenReturn(StorefrontImageOrphanCandidatePage(referenced, referenced.last()))
        `when`(
            storage.listOrphanCandidatePage(
                StorefrontImageTarget.CAMPAIGN,
                now.minusSeconds(86_400),
                referenced.last(),
                100,
            ),
        ).thenReturn(StorefrontImageOrphanCandidatePage(listOf(orphan), null))
        referenced.forEach { `when`(campaigns.isBannerReferenced(it)).thenReturn(true) }
        `when`(campaigns.isBannerReferenced(orphan)).thenReturn(false)

        sweep.sweep()
        sweep.sweep()

        referenced.forEach { verify(storage, never()).deleteObject(it) }
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

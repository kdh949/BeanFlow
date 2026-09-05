package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.merchant.api.StorefrontImageStorageOperations
import io.github.kdh949.beanflow.merchant.api.StorefrontImageTarget
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

@Component
internal class LimitedCouponCampaignBannerOrphanSweep(
    private val storage: StorefrontImageStorageOperations,
    private val campaigns: LimitedCouponCampaignPersistence,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {
    @Scheduled(
        initialDelayString = "\${beanflow.media.orphan-sweep.initial-delay-ms:600000}",
        fixedDelayString = "\${beanflow.media.orphan-sweep.fixed-delay-ms:3600000}",
    )
    fun sweep() {
        try {
            storage.listOrphanCandidates(TARGETS, clock.instant().minus(ORPHAN_GRACE), BATCH_SIZE).forEach { candidateKey ->
                if (!campaigns.isBannerReferenced(candidateKey)) {
                    storage.deleteObject(candidateKey)
                    record("deleted")
                }
            }
            record("succeeded")
        } catch (failure: RuntimeException) {
            record("failed")
            throw failure
        }
    }

    private fun record(outcome: String) {
        meterRegistry.counter("beanflow.media.orphan", "target", "campaign_banner", "outcome", outcome).increment()
    }

    private companion object {
        val TARGETS = setOf(StorefrontImageTarget.CAMPAIGN)
        val ORPHAN_GRACE: Duration = Duration.ofHours(24)
        const val BATCH_SIZE = 100
    }
}

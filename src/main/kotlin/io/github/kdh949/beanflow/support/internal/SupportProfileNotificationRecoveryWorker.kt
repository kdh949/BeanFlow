package io.github.kdh949.beanflow.support.internal

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
internal class SupportProfileNotificationRecoveryWorker(
    private val profiles: SupportProfileChangeApplicationService,
) {
    @Scheduled(
        initialDelayString = "\${beanflow.support-profile-notification-recovery.initial-delay-ms:60000}",
        fixedDelayString = "\${beanflow.support-profile-notification-recovery.fixed-delay-ms:10000}",
    )
    fun recoverExpiredClaims() {
        profiles.recoverNotifications()
    }
}

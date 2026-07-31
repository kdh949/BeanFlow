package io.github.kdh949.beanflow.notification.internal

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration(proxyBeanMethods = false)
@Profile("local & !prod")
internal class LocalNotificationProviderConfiguration {
    @Bean
    @ConditionalOnProperty(
        prefix = "beanflow.notification",
        name = ["provider"],
        havingValue = "scripted",
    )
    fun localScriptedNotificationProvider(
        @Value("\${beanflow.notification.local.outcome:acknowledged}")
        outcome: String,
    ): NotificationProvider =
        NotificationProvider { request ->
            when (outcome.trim().lowercase()) {
                "acknowledged" -> {
                    NotificationProviderResult.Acknowledged("local-delivery-${request.deliveryId}")
                }

                "failed" -> {
                    NotificationProviderResult.Failed("LOCAL_SCRIPTED_FAILURE")
                }

                "unknown" -> {
                    NotificationProviderResult.Unknown("LOCAL_SCRIPTED_UNKNOWN")
                }

                else -> {
                    throw IllegalStateException("Unsupported local notification outcome: $outcome")
                }
            }
        }
}

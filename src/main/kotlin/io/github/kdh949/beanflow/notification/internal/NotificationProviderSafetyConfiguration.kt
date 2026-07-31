package io.github.kdh949.beanflow.notification.internal

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

@Configuration(proxyBeanMethods = false)
internal class NotificationProviderSafetyConfiguration {
    @Bean
    fun notificationProviderSafetyGuard(
        environment: Environment,
        @Value("\${beanflow.notification.provider:unconfigured}")
        providerMode: String,
    ): SmartInitializingSingleton =
        SmartInitializingSingleton {
            if ("prod" in environment.activeProfiles && providerMode.equals("scripted", ignoreCase = true)) {
                error("Scripted notification provider cannot run in the prod profile")
            }
        }
}

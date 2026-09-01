package io.github.kdh949.beanflow.shared.internal

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import tools.jackson.databind.ObjectMapper

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VaultTransitPersonalDataProperties::class)
internal class VaultTransitPersonalDataConfiguration {
    @Bean
    fun vaultTransitPersonalDataAdapter(
        properties: VaultTransitPersonalDataProperties,
        objectMapper: ObjectMapper,
    ): VaultTransitPersonalDataAdapter = VaultTransitPersonalDataAdapter(properties, objectMapper)

    @Bean
    @Profile("vault-enforced")
    fun vaultTransitPersonalDataStartupValidator(
        properties: VaultTransitPersonalDataProperties,
        adapter: VaultTransitPersonalDataAdapter,
    ): SmartInitializingSingleton =
        SmartInitializingSingleton {
            try {
                properties.validated()
            } catch (exception: VaultTransitConfigurationException) {
                throw IllegalStateException("Vault Transit personal-data configuration is invalid", exception)
            }
            try {
                adapter.validateStartup()
            } catch (exception: RuntimeException) {
                throw IllegalStateException("Vault Transit personal-data startup validation failed", exception)
            }
        }
}

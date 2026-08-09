package io.github.kdh949.beanflow.operations.internal

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * Keeps the demo environment out of production.
 *
 * `local-demo` activates sandbox adapters, a deterministic fixture seed and an ephemeral local key
 * source. None of that may ever run against a production deployment, so the two profiles together
 * are a startup failure rather than a warning. `local-demo` also requires `local`, because the
 * sandbox payment and notification adapters are gated on `local & !prod`; without it the
 * application would start with no provider adapter at all.
 */
@Configuration(proxyBeanMethods = false)
internal class LocalDemoSafetyConfiguration {
    @Bean
    fun localDemoSafetyGuard(environment: Environment): SmartInitializingSingleton =
        SmartInitializingSingleton {
            val active = environment.activeProfiles.toSet()
            if (LOCAL_DEMO_PROFILE in active && PROD_PROFILE in active) {
                error("The local-demo profile cannot run together with the prod profile")
            }
            if (LOCAL_DEMO_PROFILE in active && LOCAL_PROFILE !in active) {
                error("The local-demo profile requires the local profile so sandbox adapters are active")
            }
        }

    private companion object {
        const val LOCAL_DEMO_PROFILE = "local-demo"
        const val LOCAL_PROFILE = "local"
        const val PROD_PROFILE = "prod"
    }
}

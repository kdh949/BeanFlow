package io.github.kdh949.beanflow.operations.internal

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/** Prevents the deterministic performance runtime from being activated as a production or portfolio runtime. */
@Configuration(proxyBeanMethods = false)
internal class PerformanceProfileSafetyConfiguration {
    @Bean
    fun performanceProfileSafetyGuard(environment: Environment): SmartInitializingSingleton =
        SmartInitializingSingleton {
            val active = environment.activeProfiles.toSet()
            if (PERF_PROFILE !in active) return@SmartInitializingSingleton
            if (active.any(FORBIDDEN_PROFILES::contains)) {
                error(
                    "The perf profile cannot run together with prod, portfolio, test, local-demo or toss-sandbox profiles",
                )
            }
            if (!active.containsAll(REQUIRED_PROFILES)) {
                error("The perf profile requires local, toss-perf and vault-enforced profiles")
            }
        }

    private companion object {
        const val PERF_PROFILE = "perf"
        val REQUIRED_PROFILES = setOf("local", "toss-perf", "vault-enforced")
        val FORBIDDEN_PROFILES = setOf("prod", "portfolio", "test", "local-demo", "toss-sandbox")
    }
}

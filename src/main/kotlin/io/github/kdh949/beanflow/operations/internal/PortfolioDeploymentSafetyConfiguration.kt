package io.github.kdh949.beanflow.operations.internal

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/** Keeps the intentionally sandboxed public portfolio runtime distinct from production and test fixtures. */
@Configuration(proxyBeanMethods = false)
internal class PortfolioDeploymentSafetyConfiguration {
    @Bean
    fun portfolioDeploymentSafetyGuard(environment: Environment): SmartInitializingSingleton =
        SmartInitializingSingleton {
            val active = environment.activeProfiles.toSet()
            if (PORTFOLIO_PROFILE !in active) return@SmartInitializingSingleton
            if (active.any(FORBIDDEN_PROFILES::contains)) {
                error("The portfolio profile cannot run together with prod, test or local-demo profiles")
            }
            if (!active.containsAll(REQUIRED_PROFILES)) {
                error("The portfolio profile requires local, toss-sandbox and vault-enforced profiles")
            }
        }

    private companion object {
        const val PORTFOLIO_PROFILE = "portfolio"
        val REQUIRED_PROFILES = setOf("local", "toss-sandbox", "vault-enforced")
        val FORBIDDEN_PROFILES = setOf("prod", "test", "local-demo")
    }
}

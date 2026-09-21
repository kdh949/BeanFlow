package io.github.kdh949.beanflow.demo.internal

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.stereotype.Component

@Component
internal class DemoSettings(
    @Value("\${beanflow.demo.enabled:false}") val enabled: Boolean,
    @Value("\${beanflow.demo.max-active:20}") val maxActive: Int,
    @Value("\${beanflow.demo.max-daily:200}") val maxDaily: Int,
    @Value("\${beanflow.demo.max-browser-daily:5}") val maxBrowserDaily: Int,
) {
    val lifetimeSeconds = 1800L
}

@Configuration(proxyBeanMethods = false)
internal class DemoConfiguration {
    @Bean
    fun demoSafety(
        settings: DemoSettings,
        environment: Environment,
    ) = SmartInitializingSingleton {
        if (settings.enabled) {
            val profiles = environment.activeProfiles.toSet()
            require(profiles.intersect(setOf("prod", "perf", "toss-perf")).isEmpty()) { "Visitor demo is forbidden in prod/perf" }
            require("local" in profiles && ("toss-sandbox" in profiles || "local-demo" in profiles)) {
                "Visitor demo requires an explicit local-demo or Toss sandbox environment"
            }
            require(settings.maxActive in 1..20 && settings.maxDaily in 1..200 && settings.maxBrowserDaily in 1..5) {
                "Visitor demo quotas must be within the published bounds"
            }
        }
    }

    @Bean
    @Order(-1)
    @ConditionalOnProperty(name = ["beanflow.demo.enabled"], havingValue = "true")
    fun demoSecurity(http: HttpSecurity): SecurityFilterChain {
        val csrf =
            CookieCsrfTokenRepository.withHttpOnlyFalse().apply {
                setCookieName("BEANFLOW_DEMO_XSRF")
                setHeaderName("X-BEANFLOW-CSRF")
                setCookiePath("/")
                setCookieCustomizer { it.secure(true).sameSite("Lax") }
            }
        return http
            .securityMatcher("/api/v1/demo/**")
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .requestCache { it.disable() }
            .csrf { it.csrfTokenRepository(csrf).csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler()) }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .build()
    }
}

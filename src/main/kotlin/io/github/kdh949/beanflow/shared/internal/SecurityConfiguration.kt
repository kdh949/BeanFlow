package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.shared.api.BrowserActorLoader
import io.github.kdh949.beanflow.shared.api.BrowserActorType
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.context.SecurityContextHolderFilter
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.csrf.InvalidCsrfTokenException
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
internal class SecurityConfiguration {
    @Bean
    @ConditionalOnMissingBean(JwtDecoder::class)
    fun jwtDecoder(
        @Value("\${beanflow.security.jwk-set-uri}") jwkSetUri: String,
    ): JwtDecoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build()

    @Bean
    @Order(0)
    fun publicSecurityFilterChain(
        http: HttpSecurity,
        registry: AuthenticationPathRegistry,
    ): SecurityFilterChain =
        http
            .securityMatcher(registry.requestMatcher(AuthenticationChain.PUBLIC))
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .build()

    @Bean
    @Order(1)
    fun operationsSecurityFilterChain(
        http: HttpSecurity,
        registry: AuthenticationPathRegistry,
        errorWriter: SecurityErrorResponseWriter,
    ): SecurityFilterChain {
        val converter =
            JwtAuthenticationConverter().apply {
                setJwtGrantedAuthoritiesConverter(
                    JwtGrantedAuthoritiesConverter().apply {
                        setAuthoritiesClaimName("roles")
                        setAuthorityPrefix("ROLE_")
                    },
                )
            }
        val handlers = securityErrorHandlers(errorWriter, AuthenticationChain.OPERATIONS)
        return http
            .securityMatcher(registry.requestMatcher(AuthenticationChain.OPERATIONS))
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .addFilterBefore(
                ActorCredentialIsolationFilter(AuthenticationChain.OPERATIONS, errorWriter),
                SecurityContextHolderFilter::class.java,
            ).authorizeHttpRequests { it.anyRequest().authenticated() }
            .exceptionHandling {
                it.authenticationEntryPoint(handlers.authenticationEntryPoint)
                it.accessDeniedHandler(handlers.accessDeniedHandler)
            }.oauth2ResourceServer {
                it.jwt { jwt -> jwt.jwtAuthenticationConverter(converter) }
                it.authenticationEntryPoint(handlers.authenticationEntryPoint)
                it.accessDeniedHandler(handlers.accessDeniedHandler)
            }.build()
    }

    @Bean
    @Order(2)
    fun merchantSecurityFilterChain(
        http: HttpSecurity,
        registry: AuthenticationPathRegistry,
        errorWriter: SecurityErrorResponseWriter,
        metrics: AuthenticationMetrics,
        loaders: List<BrowserActorLoader>,
        clock: Clock,
    ): SecurityFilterChain =
        browserSecurityFilterChain(
            http = http,
            registry = registry,
            chain = AuthenticationChain.MERCHANT,
            unauthenticatedEndpoints =
                setOf(
                    "/api/v1/auth/merchant/csrf",
                    "/api/v1/auth/merchant/sessions",
                ),
            csrfCookieName = "BEANFLOW_MERCHANT_XSRF",
            errorWriter = errorWriter,
            metrics = metrics,
            loaders = loaders,
            clock = clock,
        )

    @Bean
    @Order(3)
    fun customerSecurityFilterChain(
        http: HttpSecurity,
        registry: AuthenticationPathRegistry,
        errorWriter: SecurityErrorResponseWriter,
        metrics: AuthenticationMetrics,
        loaders: List<BrowserActorLoader>,
        clock: Clock,
    ): SecurityFilterChain =
        browserSecurityFilterChain(
            http = http,
            registry = registry,
            chain = AuthenticationChain.CUSTOMER,
            unauthenticatedEndpoints =
                setOf(
                    "/api/v1/auth/customer/csrf",
                    "/api/v1/auth/customer/registrations",
                    "/api/v1/auth/customer/sessions",
                ),
            csrfCookieName = "BEANFLOW_CUSTOMER_XSRF",
            errorWriter = errorWriter,
            metrics = metrics,
            loaders = loaders,
            clock = clock,
        )

    private fun browserSecurityFilterChain(
        http: HttpSecurity,
        registry: AuthenticationPathRegistry,
        chain: AuthenticationChain,
        unauthenticatedEndpoints: Set<String>,
        csrfCookieName: String,
        errorWriter: SecurityErrorResponseWriter,
        metrics: AuthenticationMetrics,
        loaders: List<BrowserActorLoader>,
        clock: Clock,
    ): SecurityFilterChain {
        val handlers = securityErrorHandlers(errorWriter, chain)
        val csrfRepository =
            CookieCsrfTokenRepository.withHttpOnlyFalse().apply {
                setCookieName(csrfCookieName)
                setHeaderName("X-BEANFLOW-CSRF")
                setCookiePath("/")
                setCookieCustomizer { cookie -> cookie.secure(true).sameSite("Lax") }
            }
        val csrfRequestHandler = CsrfTokenRequestAttributeHandler()
        val actorType =
            when (chain) {
                AuthenticationChain.CUSTOMER -> BrowserActorType.CUSTOMER
                AuthenticationChain.MERCHANT -> BrowserActorType.MERCHANT
                else -> error("Browser chain must be customer or merchant")
            }
        val browserAuthenticationFilter = BrowserSessionAuthenticationFilter(actorType, loaders, clock, errorWriter, metrics)
        http.addFilterAfter(browserAuthenticationFilter, SecurityContextHolderFilter::class.java)
        if (chain == AuthenticationChain.MERCHANT) {
            http.addFilterAfter(MerchantInitialPasswordGateFilter(errorWriter), BrowserSessionAuthenticationFilter::class.java)
        }
        return http
            .securityMatcher(registry.requestMatcher(chain))
            .sessionManagement {
                it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                // LoginSessionCoordinator already performs the required transactional rotation.
                // A second Spring Security fixation rotation on the first authenticated read would
                // make the just-issued response cookie stale.
                it.sessionFixation { fixation -> fixation.none() }
            }.requestCache { it.disable() }
            .addFilterBefore(
                ActorCredentialIsolationFilter(chain, errorWriter),
                SecurityContextHolderFilter::class.java,
            ).csrf {
                it.csrfTokenRepository(csrfRepository)
                it.csrfTokenRequestHandler(csrfRequestHandler)
            }.authorizeHttpRequests {
                it.requestMatchers(*unauthenticatedEndpoints.toTypedArray()).permitAll()
                it.anyRequest().authenticated()
            }.exceptionHandling {
                it.authenticationEntryPoint(handlers.authenticationEntryPoint)
                it.accessDeniedHandler(handlers.accessDeniedHandler)
            }.build()
    }

    private fun securityErrorHandlers(
        errorWriter: SecurityErrorResponseWriter,
        chain: AuthenticationChain,
    ): SecurityErrorHandlers =
        SecurityErrorHandlers(
            authenticationEntryPoint =
                AuthenticationEntryPoint { _, response, _ ->
                    errorWriter.write(
                        response,
                        chain,
                        HttpServletResponse.SC_UNAUTHORIZED,
                        "UNAUTHORIZED",
                        "Authentication is missing or invalid",
                        "missing_or_invalid_credential",
                    )
                },
            accessDeniedHandler =
                AccessDeniedHandler { _, response, failure ->
                    val invalidCsrfToken = failure is InvalidCsrfTokenException
                    errorWriter.write(
                        response,
                        chain,
                        HttpServletResponse.SC_FORBIDDEN,
                        if (invalidCsrfToken) "CSRF_TOKEN_INVALID" else "ACCESS_DENIED",
                        if (invalidCsrfToken) {
                            "Presented CSRF token is invalid"
                        } else {
                            "Required actor, CSRF token, role, or resource ownership is missing"
                        },
                        if (invalidCsrfToken) "csrf" else "authorization",
                    )
                },
        )

    private data class SecurityErrorHandlers(
        val authenticationEntryPoint: AuthenticationEntryPoint,
        val accessDeniedHandler: AccessDeniedHandler,
    )
}

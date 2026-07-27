package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.web.SecurityFilterChain
import tools.jackson.databind.ObjectMapper

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
internal class SecurityConfiguration {

	@Bean
	@ConditionalOnMissingBean(JwtDecoder::class)
	fun jwtDecoder(
		@Value("\${beanflow.security.jwk-set-uri}") jwkSetUri: String,
	): JwtDecoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build()

	@Bean
	fun securityFilterChain(
		http: HttpSecurity,
		objectMapper: ObjectMapper,
		correlationIdSource: CorrelationIdSource,
	): SecurityFilterChain {
		val authorities = JwtGrantedAuthoritiesConverter().apply {
			setAuthoritiesClaimName("roles")
			setAuthorityPrefix("ROLE_")
		}
		val converter = JwtAuthenticationConverter().apply {
			setJwtGrantedAuthoritiesConverter(authorities)
		}
		http
			.csrf { it.disable() }
			.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
			.authorizeHttpRequests {
				it.requestMatchers("/actuator/health").permitAll()
					.anyRequest().authenticated()
			}
			.oauth2ResourceServer {
				it.jwt { jwt -> jwt.jwtAuthenticationConverter(converter) }
				.authenticationEntryPoint { _, response, _ ->
					writeSecurityError(
						response,
						objectMapper,
						correlationIdSource,
						HttpServletResponse.SC_UNAUTHORIZED,
						"UNAUTHORIZED",
						"Authentication is missing or invalid",
					)
				}
				.accessDeniedHandler { _, response, _ ->
					writeSecurityError(
						response,
						objectMapper,
						correlationIdSource,
						HttpServletResponse.SC_FORBIDDEN,
						"ACCESS_DENIED",
						"Required role or resource ownership is missing",
					)
				}
			}
		return http.build()
	}

	private fun writeSecurityError(
		response: HttpServletResponse,
		objectMapper: ObjectMapper,
		correlationIdSource: CorrelationIdSource,
		status: Int,
		code: String,
		message: String,
	) {
		response.status = status
		response.contentType = MediaType.APPLICATION_JSON_VALUE
		response.characterEncoding = Charsets.UTF_8.name()
		response.writer.write(
			objectMapper.writeValueAsString(
				mapOf(
					"code" to code,
					"message" to message,
					"correlationId" to correlationIdSource.currentOrCreate(),
					"details" to emptyList<Any>(),
				),
			),
		)
	}
}

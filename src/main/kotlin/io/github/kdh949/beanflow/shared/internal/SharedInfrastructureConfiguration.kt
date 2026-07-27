package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.slf4j.MDC
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.UUID

@Configuration(proxyBeanMethods = false)
internal class SharedInfrastructureConfiguration {

	@Bean
	fun clock(): Clock = Clock.systemUTC()

	@Bean
	fun identifierSource(): IdentifierSource = IdentifierSource(UUID::randomUUID)

	@Bean
	fun correlationIdSource(identifierSource: IdentifierSource): CorrelationIdSource =
		CorrelationIdSource {
			MDC.get(CORRELATION_ID_MDC_KEY) ?: identifierSource.next().toString()
		}

	private companion object {
		const val CORRELATION_ID_MDC_KEY = "correlationId"
	}
}

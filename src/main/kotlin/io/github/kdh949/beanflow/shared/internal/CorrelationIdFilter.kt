package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.shared.api.IdentifierSource
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
internal class CorrelationIdFilter(
	private val identifierSource: IdentifierSource,
) : OncePerRequestFilter() {

	override fun doFilterInternal(
		request: HttpServletRequest,
		response: HttpServletResponse,
		filterChain: FilterChain,
	) {
		val requested = request.getHeader(HEADER)
		val correlationId = requested
			?.takeIf { it.length in 1..128 && it.all(::isSafeCharacter) }
			?: identifierSource.next().toString()
		MDC.put(MDC_KEY, correlationId)
		response.setHeader(HEADER, correlationId)
		try {
			filterChain.doFilter(request, response)
		} finally {
			MDC.remove(MDC_KEY)
		}
	}

	private fun isSafeCharacter(character: Char): Boolean =
		character.isLetterOrDigit() || character in "-_.:"

	private companion object {
		const val HEADER = "X-Correlation-Id"
		const val MDC_KEY = "correlationId"
	}
}

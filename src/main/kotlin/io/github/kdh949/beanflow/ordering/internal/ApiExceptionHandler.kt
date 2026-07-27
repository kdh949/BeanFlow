package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import jakarta.validation.ConstraintViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
internal class ApiExceptionHandler(
	private val correlationIdSource: CorrelationIdSource,
) {

	@ExceptionHandler(DomainFailure::class)
	fun domainFailure(failure: DomainFailure): ResponseEntity<ErrorResponse> {
		val headers = HttpHeaders()
		failure.retryAfterSeconds?.let { headers.set(HttpHeaders.RETRY_AFTER, it.toString()) }
		return ResponseEntity(
			ErrorResponse(
				code = failure.code.name,
				message = failure.message,
				correlationId = correlationIdSource.currentOrCreate(),
			),
			headers,
			statusOf(failure.code),
		)
	}

	@ExceptionHandler(
		MethodArgumentNotValidException::class,
		ConstraintViolationException::class,
		MissingRequestHeaderException::class,
	)
	fun invalidRequest(failure: Exception): ResponseEntity<ErrorResponse> =
		ResponseEntity.badRequest().body(
			ErrorResponse(
				code = FailureCode.INVALID_REQUEST.name,
				message = "Request validation failed",
				correlationId = correlationIdSource.currentOrCreate(),
				details = listOf(ErrorDetail(reason = failure.message ?: "Invalid request")),
			),
		)

	private fun statusOf(code: FailureCode): HttpStatus =
		when (code) {
			FailureCode.INVALID_REQUEST -> HttpStatus.BAD_REQUEST
			FailureCode.ACCESS_DENIED -> HttpStatus.FORBIDDEN
			FailureCode.RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND
			FailureCode.DEPENDENCY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE
			else -> HttpStatus.CONFLICT
		}
}

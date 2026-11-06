package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.core.convert.ConversionFailedException
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import java.sql.SQLException
import java.util.Collections
import java.util.IdentityHashMap

@RestControllerAdvice
internal class ApiExceptionHandler(
    private val correlationIdSource: CorrelationIdSource,
) {
    @ExceptionHandler(DomainFailure::class)
    fun domainFailure(failure: DomainFailure): ResponseEntity<ErrorResponse> {
        val correlationId = correlationIdSource.currentOrCreate()
        val status = statusOf(failure.code)
        if (status.is5xxServerError) logDependencyFailure(failure, failure.code, correlationId)
        val headers = HttpHeaders()
        if (failure.code == FailureCode.SUPPORT_VERIFICATION_RETIRED) headers.cacheControl = "no-store"
        failure.retryAfterSeconds?.let { headers.set(HttpHeaders.RETRY_AFTER, it.toString()) }
        return ResponseEntity(
            ErrorResponse(
                code = failure.code.name,
                message = failure.message,
                correlationId = correlationId,
                targetReference = failure.targetReference,
            ),
            headers,
            status,
        )
    }

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        ConstraintViolationException::class,
        MissingRequestHeaderException::class,
        MethodArgumentTypeMismatchException::class,
        HandlerMethodValidationException::class,
        HttpMessageNotReadableException::class,
        ConversionFailedException::class,
    )
    fun invalidRequest(failure: Exception): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(
            ErrorResponse(
                code = FailureCode.INVALID_REQUEST.name,
                message = "Request validation failed",
                correlationId = correlationIdSource.currentOrCreate(),
                details = safeValidationDetails(failure),
            ),
        )

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun oversizedImage(): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(
            ErrorResponse(
                code = FailureCode.INVALID_IMAGE.name,
                message = "Image validation failed",
                correlationId = correlationIdSource.currentOrCreate(),
            ),
        )

    private fun safeValidationDetails(failure: Exception): List<ErrorDetail> {
        val details =
            when (failure) {
                is MethodArgumentNotValidException -> {
                    failure.bindingResult.fieldErrors.map { ErrorDetail(field = it.field, reason = "INVALID_VALUE") }
                }

                is ConstraintViolationException -> {
                    failure.constraintViolations.map {
                        ErrorDetail(field = it.propertyPath.lastOrNull()?.name, reason = "INVALID_VALUE")
                    }
                }

                is MissingRequestHeaderException -> {
                    listOf(ErrorDetail(field = failure.headerName, reason = "MISSING_VALUE"))
                }

                is MethodArgumentTypeMismatchException -> {
                    listOf(ErrorDetail(field = failure.name, reason = "INVALID_FORMAT"))
                }

                is HandlerMethodValidationException -> {
                    failure.parameterValidationResults.map {
                        ErrorDetail(field = it.methodParameter.parameterName, reason = "INVALID_VALUE")
                    }
                }

                is ConversionFailedException -> {
                    listOf(ErrorDetail(reason = "INVALID_FORMAT"))
                }

                is HttpMessageNotReadableException -> {
                    listOf(ErrorDetail(reason = "MALFORMED_REQUEST"))
                }

                else -> {
                    listOf(ErrorDetail(reason = "INVALID_VALUE"))
                }
            }
        return details
            .distinct()
            .sortedWith(compareBy({ it.field ?: "" }, { it.reason }))
    }

    @ExceptionHandler(DataAccessException::class)
    fun persistenceFailure(failure: DataAccessException): ResponseEntity<ErrorResponse> {
        val correlationId = correlationIdSource.currentOrCreate()
        logDependencyFailure(failure, FailureCode.DEPENDENCY_UNAVAILABLE, correlationId)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
            ErrorResponse(
                code = FailureCode.DEPENDENCY_UNAVAILABLE.name,
                message = "A required persistence dependency is unavailable",
                correlationId = correlationId,
            ),
        )
    }

    private fun logDependencyFailure(
        failure: RuntimeException,
        code: FailureCode,
        correlationId: String,
    ) {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val causes = mutableListOf<Throwable>()
        var current: Throwable? = failure
        while (current != null && causes.size < 8 && seen.add(current)) {
            causes += current
            current = current.cause
        }
        // Throwable messages may contain SQL, credentials or customer input. Record only metadata.
        val types = causes.map { it.javaClass.name }
        val frames = causes.map { cause -> cause.stackTrace.take(12).map { it.toString() } }
        val sqlState = causes.filterIsInstance<SQLException>().firstNotNullOfOrNull { it.sqlState?.takeIf(SQL_STATE::matches) }
        val truncated = current != null
        logger
            .atError()
            .addKeyValue("correlationId", correlationId)
            .addKeyValue("error_code", code.name)
            .addKeyValue("exception_types", types)
            .addKeyValue("exception_frames", frames)
            .addKeyValue("exception_chain_truncated", truncated)
            .addKeyValue("sql_state", sqlState)
            .log(
                "api_dependency_failed correlationId={} error_code={} exception_types={} sql_state={} exception_chain_truncated={} exception_frames={}",
                correlationId,
                code.name,
                types,
                sqlState,
                truncated,
                frames,
            )
    }

    private companion object {
        val SQL_STATE = Regex("[A-Z0-9]{5}")
        val logger = LoggerFactory.getLogger(ApiExceptionHandler::class.java)
    }

    private fun statusOf(code: FailureCode): HttpStatus =
        when (code) {
            FailureCode.INVALID_REQUEST,
            FailureCode.INVALID_IMAGE,
            FailureCode.PASSWORD_POLICY_VIOLATION,
            -> HttpStatus.BAD_REQUEST

            FailureCode.SUPPORT_VERIFICATION_RETIRED -> HttpStatus.GONE

            FailureCode.AUTHENTICATION_FAILED -> HttpStatus.UNAUTHORIZED

            FailureCode.AUTHENTICATION_RATE_LIMITED,
            FailureCode.SUPPORT_SEARCH_RATE_LIMITED,
            FailureCode.VERIFICATION_LOCKED,
            -> HttpStatus.TOO_MANY_REQUESTS

            FailureCode.ACCESS_DENIED,
            FailureCode.INITIAL_PASSWORD_CHANGE_REQUIRED,
            -> HttpStatus.FORBIDDEN

            FailureCode.RESOURCE_NOT_FOUND,
            FailureCode.MERCHANT_ACCOUNT_NOT_FOUND,
            -> HttpStatus.NOT_FOUND

            FailureCode.VERIFICATION_REQUIRED,
            FailureCode.DATA_ACCESS_GRANT_REQUIRED,
            FailureCode.DATA_ACCESS_SCOPE_MISMATCH,
            FailureCode.SUPPORT_ORDER_CHANGE_AUTHORIZATION_REQUIRED,
            FailureCode.SUPPORT_ORDER_CHANGE_AUTHORIZATION_SCOPE_MISMATCH,
            -> HttpStatus.FORBIDDEN

            FailureCode.PAYMENT_DECLINED,
            FailureCode.PAYMENT_METHOD_REGISTRATION_REJECTED,
            FailureCode.ORDER_ACTION_NOT_ALLOWED,
            FailureCode.REFUND_QUANTITY_UNAVAILABLE,
            -> HttpStatus.UNPROCESSABLE_ENTITY

            FailureCode.COUPON_TERMS_INTEGRITY_FAILURE,
            FailureCode.SETTLEMENT_INPUT_UNAVAILABLE,
            FailureCode.DEPENDENCY_UNAVAILABLE,
            FailureCode.PAYMENT_METHOD_PROVIDER_UNAVAILABLE,
            FailureCode.ORDER_REFERENCE_EXHAUSTED,
            FailureCode.POINT_ACCOUNT_INTEGRITY_FAILURE,
            -> HttpStatus.SERVICE_UNAVAILABLE

            else -> HttpStatus.CONFLICT
        }
}

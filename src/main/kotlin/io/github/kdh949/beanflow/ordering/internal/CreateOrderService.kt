package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.CreateOrderCommand
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.api.StoredHttpResponse
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
internal class CreateOrderService(
	private val idempotencyService: OrderIdempotencyService,
	private val orderCreationTransaction: OrderCreationTransaction,
	private val identifierSource: IdentifierSource,
	private val correlationIdSource: CorrelationIdSource,
	private val objectMapper: ObjectMapper,
	private val meterRegistry: MeterRegistry,
	@Value("\${beanflow.idempotency.retry-after-seconds:2}")
	private val retryAfterSeconds: Long,
) : CreateOrderUseCase {

	override fun create(idempotencyKey: String, command: CreateOrderCommand): StoredHttpResponse {
		val sample = Timer.start(meterRegistry)
		return try {
			val response = execute(idempotencyKey, command)
			val outcome = when {
				response.replay -> "replay"
				response.status in 200..299 -> "success"
				else -> "failure"
			}
			meterRegistry.counter("beanflow.order.creation.attempts", "outcome", outcome).increment()
			if (response.replay) {
				meterRegistry.counter("beanflow.order.idempotency.events", "outcome", "replay").increment()
			}
			response
		} catch (failure: RuntimeException) {
			meterRegistry.counter("beanflow.order.creation.attempts", "outcome", "failure").increment()
			throw failure
		} finally {
			sample.stop(meterRegistry.timer("beanflow.order.creation.duration"))
		}
	}

	private fun execute(idempotencyKey: String, command: CreateOrderCommand): StoredHttpResponse {
		val correlationId = correlationIdSource.currentOrCreate()
		if (idempotencyKey.length !in 8..128) {
			return errorResponse(
				DomainFailure(FailureCode.INVALID_REQUEST, "Idempotency-Key length must be between 8 and 128"),
				correlationId,
			)
		}
		val payloadHash = try {
			CanonicalOrderPayload.hash(command)
		} catch (failure: RuntimeException) {
			return errorResponse(
				DomainFailure(FailureCode.INVALID_REQUEST, "Order payload cannot be canonicalized"),
				correlationId,
			)
		}
		val intendedOrderId = identifierSource.next()
		val registration = try {
			idempotencyService.register(
				actorId = command.customerId,
				idempotencyKey = idempotencyKey,
				payloadHash = payloadHash,
				intendedOrderId = intendedOrderId,
			)
		} catch (failure: DomainFailure) {
			return errorResponse(failure, correlationId)
		} catch (failure: DataAccessException) {
			return errorResponse(
				DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Idempotency dependency is unavailable"),
				correlationId,
			)
		}
		when (registration) {
			is IdempotencyRegistration.Replay -> return registration.response
			IdempotencyRegistration.InProgress -> return errorResponse(
				DomainFailure(
					FailureCode.IDEMPOTENCY_REQUEST_IN_PROGRESS,
					"An identical request is still processing",
					retryAfterSeconds = retryAfterSeconds,
				),
				correlationId,
			)
			is IdempotencyRegistration.Acquired -> {
				try {
					return orderCreationTransaction.create(
						idempotencyRecordId = registration.recordId,
						orderId = registration.intendedOrderId,
						command = command,
					)
				} catch (failure: DomainFailure) {
					val response = errorResponse(failure, correlationId)
					return persistFailureOrDependencyError(registration.recordId, response, correlationId)
				} catch (failure: DataAccessException) {
					val response = errorResponse(
						DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Order dependency is unavailable"),
						correlationId,
					)
					return persistFailureOrDependencyError(registration.recordId, response, correlationId)
				}
			}
		}
	}

	private fun persistFailureOrDependencyError(
		recordId: java.util.UUID,
		response: StoredHttpResponse,
		correlationId: String,
	): StoredHttpResponse =
		try {
			idempotencyService.fail(recordId, response)
			response
		} catch (_: DataAccessException) {
			errorResponse(
				DomainFailure(
					FailureCode.DEPENDENCY_UNAVAILABLE,
					"Order failed and its idempotency result could not be persisted",
				),
				correlationId,
			)
		}

	private fun errorResponse(failure: DomainFailure, correlationId: String): StoredHttpResponse {
		recordFailureMetric(failure.code)
		return StoredHttpResponse(
			status = statusOf(failure.code),
			body = objectMapper.writeValueAsString(
				ErrorResponse(
					code = failure.code.name,
					message = failure.message,
					correlationId = correlationId,
				),
			),
			retryAfterSeconds = failure.retryAfterSeconds,
		)
	}

	private fun recordFailureMetric(code: FailureCode) {
		val resource = when (code) {
			FailureCode.PICKUP_SLOT_FULL -> "pickup"
			FailureCode.STOCK_NOT_AVAILABLE -> "stock"
			FailureCode.COUPON_NOT_AVAILABLE -> "coupon"
			FailureCode.POINT_BALANCE_INSUFFICIENT -> "points"
			else -> null
		}
		resource?.let {
			meterRegistry.counter("beanflow.order.reservation.conflicts", "resource", it).increment()
		}
		val idempotencyOutcome = when (code) {
			FailureCode.IDEMPOTENCY_KEY_REUSED -> "key_reused"
			FailureCode.IDEMPOTENCY_REQUEST_IN_PROGRESS -> "in_progress"
			else -> null
		}
		idempotencyOutcome?.let {
			meterRegistry.counter("beanflow.order.idempotency.events", "outcome", it).increment()
		}
	}

	private fun statusOf(code: FailureCode): Int =
		when (code) {
			FailureCode.INVALID_REQUEST -> 400
			FailureCode.ACCESS_DENIED -> 403
			FailureCode.RESOURCE_NOT_FOUND -> 404
			FailureCode.PAYMENT_DECLINED -> 422
			FailureCode.DEPENDENCY_UNAVAILABLE -> 503
			else -> 409
		}
}

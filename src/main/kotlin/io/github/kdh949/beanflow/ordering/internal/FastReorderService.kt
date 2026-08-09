package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.ReorderOrderCommand
import io.github.kdh949.beanflow.ordering.api.ReorderOrderUseCase
import io.github.kdh949.beanflow.ordering.api.StoredHttpResponse
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Service
internal class FastReorderService(
    private val idempotencyService: OrderIdempotencyService,
    private val transaction: FastReorderTransaction,
    private val identifierSource: IdentifierSource,
    private val correlationIdSource: CorrelationIdSource,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    @Value("\${beanflow.idempotency.retry-after-seconds:2}")
    private val retryAfterSeconds: Long,
) : ReorderOrderUseCase {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun reorder(
        idempotencyKey: String,
        command: ReorderOrderCommand,
    ): StoredHttpResponse {
        val sample = Timer.start(meterRegistry)
        val correlationId = correlationIdSource.currentOrCreate()
        return try {
            execute(idempotencyKey, command, correlationId)
        } finally {
            sample.stop(meterRegistry.timer("beanflow.order.reorder.duration"))
        }
    }

    private fun execute(
        idempotencyKey: String,
        command: ReorderOrderCommand,
        correlationId: String,
    ): StoredHttpResponse {
        if (
            idempotencyKey.length !in 8..128 ||
            idempotencyKey.any(Char::isISOControl) ||
            command.pointsToUseKrw < 0
        ) {
            return failure(
                DomainFailure(FailureCode.INVALID_REQUEST, "Reorder request or Idempotency-Key is invalid"),
                correlationId,
            )
        }
        val payloadHash =
            try {
                CanonicalReorderPayload.hash(command)
            } catch (_: RuntimeException) {
                return failure(
                    DomainFailure(FailureCode.INVALID_REQUEST, "Reorder payload cannot be canonicalized"),
                    correlationId,
                )
            }
        val registration =
            try {
                idempotencyService.register(
                    actorId = command.customerId,
                    operation = OrderCreationOperation.REORDER,
                    idempotencyKey = idempotencyKey,
                    payloadHash = payloadHash,
                    intendedOrderId = identifierSource.next(),
                )
            } catch (failure: DomainFailure) {
                return failure(failure, correlationId)
            } catch (_: DataAccessException) {
                return failure(
                    DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Idempotency dependency is unavailable"),
                    correlationId,
                )
            }
        return when (registration) {
            is IdempotencyRegistration.Replay -> {
                meterRegistry.counter("beanflow.order.reorder.attempts", "outcome", "replay").increment()
                meterRegistry.counter("beanflow.order.idempotency.events", "outcome", "replay").increment()
                log("REPLAY", -1, -1)
                registration.response
            }

            IdempotencyRegistration.InProgress -> {
                failure(
                    DomainFailure(
                        FailureCode.IDEMPOTENCY_REQUEST_IN_PROGRESS,
                        "An identical reorder request is still processing",
                        retryAfterSeconds = retryAfterSeconds,
                    ),
                    correlationId,
                )
            }

            is IdempotencyRegistration.Acquired -> {
                executeAcquired(registration.recordId, registration.intendedOrderId, command, correlationId)
            }
        }
    }

    private fun executeAcquired(
        recordId: UUID,
        orderId: UUID,
        command: ReorderOrderCommand,
        correlationId: String,
    ): StoredHttpResponse =
        try {
            val execution = transaction.create(recordId, orderId, command)
            meterRegistry.counter("beanflow.order.reorder.attempts", "outcome", "success").increment()
            log("SUCCESS", execution.sourceLineCount, execution.changedPriceLineCount)
            execution.response
        } catch (failure: ReorderItemsUnavailableFailure) {
            failure.details.forEach { detail ->
                meterRegistry
                    .counter("beanflow.order.reorder.item_unavailable", "reason", detail.reason)
                    .increment()
            }
            persistFailure(
                recordId,
                unavailableResponse(failure, correlationId),
                correlationId,
                failure.sourceLineCount,
            )
        } catch (failure: DomainFailure) {
            persistFailure(recordId, errorResponse(failure, correlationId), correlationId)
        } catch (_: DataAccessException) {
            persistFailure(
                recordId,
                errorResponse(
                    DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Reorder dependency is unavailable"),
                    correlationId,
                ),
                correlationId,
            )
        }

    private fun persistFailure(
        recordId: UUID,
        response: StoredHttpResponse,
        correlationId: String,
        sourceLineCount: Int = 0,
    ): StoredHttpResponse =
        try {
            idempotencyService.fail(recordId, response)
            val code = objectMapper.readTree(response.body).path("code").stringValue() ?: "UNKNOWN"
            meterRegistry.counter("beanflow.order.reorder.attempts", "outcome", code).increment()
            log(code, sourceLineCount, 0)
            response
        } catch (_: DataAccessException) {
            failure(
                DomainFailure(
                    FailureCode.DEPENDENCY_UNAVAILABLE,
                    "Reorder failed and its idempotency result could not be persisted",
                ),
                correlationId,
            )
        }

    private fun unavailableResponse(
        failure: ReorderItemsUnavailableFailure,
        correlationId: String,
    ): StoredHttpResponse =
        StoredHttpResponse(
            status = 409,
            body =
                objectMapper.writeValueAsString(
                    ReorderItemsUnavailableErrorResponse(
                        code = FailureCode.REORDER_ITEMS_UNAVAILABLE.name,
                        message = requireNotNull(failure.message),
                        correlationId = correlationId,
                        details = failure.details,
                    ),
                ),
        )

    private fun failure(
        failure: DomainFailure,
        correlationId: String,
    ): StoredHttpResponse {
        val response = errorResponse(failure, correlationId)
        meterRegistry.counter("beanflow.order.reorder.attempts", "outcome", failure.code.name).increment()
        val idempotencyOutcome =
            when (failure.code) {
                FailureCode.IDEMPOTENCY_KEY_REUSED -> "key_reused"
                FailureCode.IDEMPOTENCY_REQUEST_IN_PROGRESS -> "in_progress"
                else -> null
            }
        idempotencyOutcome?.let {
            meterRegistry.counter("beanflow.order.idempotency.events", "outcome", it).increment()
        }
        log(failure.code.name, 0, 0)
        return response
    }

    private fun errorResponse(
        failure: DomainFailure,
        correlationId: String,
    ): StoredHttpResponse =
        StoredHttpResponse(
            status = statusOf(failure.code),
            body =
                objectMapper.writeValueAsString(
                    ErrorResponse(
                        code = failure.code.name,
                        message = failure.message,
                        correlationId = correlationId,
                    ),
                ),
            retryAfterSeconds = failure.retryAfterSeconds,
        )

    private fun statusOf(code: FailureCode): Int =
        when (code) {
            FailureCode.INVALID_REQUEST -> 400

            FailureCode.ACCESS_DENIED -> 403

            FailureCode.RESOURCE_NOT_FOUND -> 404

            FailureCode.SETTLEMENT_INPUT_UNAVAILABLE,
            FailureCode.DEPENDENCY_UNAVAILABLE,
            -> 503

            else -> 409
        }

    private fun log(
        outcome: String,
        sourceLineCount: Int,
        changedPriceLineCount: Int,
    ) {
        logger.info(
            "order_reorder operation=REORDER_ORDER_V1 outcome={} sourceLineCount={} changedPriceLineCount={}",
            outcome,
            sourceLineCount,
            changedPriceLineCount,
        )
    }
}

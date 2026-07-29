package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.StoredHttpResponse
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

internal sealed interface IdempotencyRegistration {
	data class Acquired(
		val recordId: UUID,
		val intendedOrderId: UUID,
	) : IdempotencyRegistration

	data class Replay(val response: StoredHttpResponse) : IdempotencyRegistration
	data object InProgress : IdempotencyRegistration
}

@Service
internal class OrderIdempotencyService(
	private val jdbcTemplate: JdbcTemplate,
	private val repository: IdempotencyRecordJpaRepository,
	private val identifierSource: IdentifierSource,
	private val clock: Clock,
) {

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	fun register(
		actorId: UUID,
		idempotencyKey: String,
		payloadHash: String,
		intendedOrderId: UUID,
	): IdempotencyRegistration {
		val recordId = identifierSource.next()
		val inserted = jdbcTemplate.update(
			"""
			INSERT INTO ordering_idempotency_record (
			    id, actor_id, operation, idempotency_key, payload_hash, status,
			    intended_order_id, started_at, version
			)
			VALUES (?, ?, ?, ?, ?, 'PROCESSING', ?, ?, 0)
			ON CONFLICT (actor_id, operation, idempotency_key) DO NOTHING
			""".trimIndent(),
			recordId,
			actorId,
			OPERATION,
			idempotencyKey,
			payloadHash,
			intendedOrderId,
			Timestamp.from(clock.instant()),
		)
		if (inserted == 1) {
			return IdempotencyRegistration.Acquired(recordId, intendedOrderId)
		}

		val existing = repository.findByActorIdAndOperationAndIdempotencyKey(
			actorId,
			OPERATION,
			idempotencyKey,
		) ?: throw DomainFailure(
			FailureCode.DEPENDENCY_UNAVAILABLE,
			"Idempotency record could not be loaded after scope conflict",
		)
		if (existing.payloadHash != payloadHash) {
			throw DomainFailure(
				FailureCode.IDEMPOTENCY_KEY_REUSED,
				"Idempotency-Key was already used with a different payload",
			)
		}
		return when (existing.status) {
			IdempotencyStatus.COMPLETED, IdempotencyStatus.FAILED ->
				IdempotencyRegistration.Replay(
					StoredHttpResponse(
						status = requireNotNull(existing.responseStatus),
						body = requireNotNull(existing.responseBody),
						replay = true,
					),
				)
			IdempotencyStatus.PROCESSING, IdempotencyStatus.MANUAL_REVIEW ->
				IdempotencyRegistration.InProgress
		}
	}

	@Transactional(propagation = Propagation.MANDATORY)
	fun complete(recordId: UUID, orderId: UUID, response: StoredHttpResponse) {
		val record = repository.findLockedById(recordId)
			?: throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Idempotency record is missing")
		check(record.status == IdempotencyStatus.PROCESSING)
		record.status = IdempotencyStatus.COMPLETED
		record.orderId = orderId
		record.responseStatus = response.status
		record.responseBody = response.body
		record.responseVersion = RESPONSE_VERSION
		record.completedAt = clock.instant()
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	fun fail(recordId: UUID, response: StoredHttpResponse) {
		val record = repository.findLockedById(recordId)
			?: throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Idempotency record is missing")
		if (record.status != IdempotencyStatus.PROCESSING) return
		record.status = IdempotencyStatus.FAILED
		record.responseStatus = response.status
		record.responseBody = response.body
		record.responseVersion = RESPONSE_VERSION
		record.completedAt = clock.instant()
	}

	private companion object {
		const val OPERATION = "CREATE_ORDER"
		const val RESPONSE_VERSION = 1
	}
}

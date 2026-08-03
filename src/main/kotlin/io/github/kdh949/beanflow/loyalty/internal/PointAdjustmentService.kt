package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.FinancialEventPublicationOperations
import io.github.kdh949.beanflow.eventing.api.PointsAdjustedV1
import io.github.kdh949.beanflow.loyalty.api.ApplyPointAdjustmentCommand
import io.github.kdh949.beanflow.loyalty.api.PointAccountView
import io.github.kdh949.beanflow.loyalty.api.PointAdjustmentIssuer
import io.github.kdh949.beanflow.loyalty.api.PointAdjustmentOperations
import io.github.kdh949.beanflow.loyalty.api.PointAdjustmentResult
import io.github.kdh949.beanflow.loyalty.api.PointIssuerType
import io.github.kdh949.beanflow.loyalty.api.PointTransactionView
import io.github.kdh949.beanflow.loyalty.api.PointTransactionViewType
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.UUID

internal enum class PointAdjustmentDirection {
    CREDIT,
    DEBIT,
    INVALID,
}

internal data class NormalizedPointAdjustmentCommand(
    val actorId: UUID,
    val pointAccountId: UUID,
    val idempotencyKey: String,
    val amountKrw: Long,
    val issuer: PointAdjustmentIssuer?,
    val expiresAt: java.time.Instant?,
    val reason: String,
    val evidenceReferences: List<String>,
    val correlationId: String,
    val now: java.time.Instant,
) {
    val direction: PointAdjustmentDirection =
        if (amountKrw > 0) PointAdjustmentDirection.CREDIT else PointAdjustmentDirection.DEBIT
}

internal data class PointAdjustmentExecution(
    val result: PointAdjustmentResult,
    val replayed: Boolean,
)

@Service
internal class PointAdjustmentService(
    private val transaction: PointAdjustmentTransaction,
    private val raceReader: PointAdjustmentRaceReader,
    private val meterRegistry: MeterRegistry,
) : PointAdjustmentOperations {
    override fun adjust(command: ApplyPointAdjustmentCommand): PointAdjustmentResult {
        val direction = direction(command.amountKrw)
        try {
            val normalized = normalize(command)
            val payloadHash = CanonicalPointAdjustmentPayload.hash(normalized)
            val execution =
                try {
                    transaction.execute(normalized, payloadHash)
                } catch (_: DataIntegrityViolationException) {
                    raceReader.resolve(normalized, payloadHash)
                }
            metric(direction, if (execution.replayed) "REPLAYED" else "APPLIED")
            if (!execution.replayed) {
                meterRegistry
                    .summary(
                        "beanflow.loyalty.point_adjustment.amount_krw",
                        "direction",
                        direction.name,
                    ).record(magnitude(command.amountKrw).toDouble())
            }
            return execution.result
        } catch (failure: DomainFailure) {
            metric(direction, outcome(failure.code))
            throw failure
        } catch (failure: DataAccessException) {
            metric(direction, "DEPENDENCY_UNAVAILABLE")
            throw failure
        } catch (failure: PersistenceException) {
            metric(direction, "DEPENDENCY_UNAVAILABLE")
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Point adjustment persistence failed",
            ).also { it.initCause(failure) }
        }
    }

    private fun normalize(command: ApplyPointAdjustmentCommand): NormalizedPointAdjustmentCommand {
        val key = command.idempotencyKey.trim()
        val reason = command.reason.trim()
        val evidence = command.evidenceReferences.map(String::trim)
        val correlationId = command.correlationId.trim()
        val issuer = command.issuer?.copy(issuerReference = command.issuer.issuerReference.trim())
        if (command.actorId == ZERO_UUID || command.pointAccountId == ZERO_UUID ||
            key.length !in 8..128 || key != command.idempotencyKey || key.hasControlCharacter() ||
            command.amountKrw == 0L || command.amountKrw == Long.MIN_VALUE ||
            reason.length !in 1..500 || evidence.isEmpty() || evidence.any { it.length !in 1..500 } ||
            correlationId.length !in 1..160 || correlationId.hasControlCharacter()
        ) {
            invalid("Point adjustment command fields are invalid")
        }
        if (command.amountKrw > 0) {
            if (issuer == null || issuer.issuerReference.length !in 1..200 ||
                command.expiresAt == null || !command.expiresAt.isAfter(command.now)
            ) {
                invalid("Credit adjustment requires an issuer and future expiry")
            }
        } else if (issuer != null || command.expiresAt != null) {
            invalid("Debit adjustment must not include issuer or expiry")
        }
        return NormalizedPointAdjustmentCommand(
            actorId = command.actorId,
            pointAccountId = command.pointAccountId,
            idempotencyKey = key,
            amountKrw = command.amountKrw,
            issuer = issuer,
            expiresAt = command.expiresAt,
            reason = reason,
            evidenceReferences = evidence,
            correlationId = correlationId,
            now = command.now,
        )
    }

    private fun metric(
        direction: PointAdjustmentDirection,
        outcome: String,
    ) {
        meterRegistry
            .counter(
                "beanflow.loyalty.point_adjustment.command.count",
                "direction",
                direction.name,
                "outcome",
                outcome,
            ).increment()
    }

    private fun direction(amountKrw: Long): PointAdjustmentDirection =
        when {
            amountKrw > 0 -> PointAdjustmentDirection.CREDIT
            amountKrw < 0 -> PointAdjustmentDirection.DEBIT
            else -> PointAdjustmentDirection.INVALID
        }

    private fun outcome(code: FailureCode): String =
        when (code) {
            FailureCode.INVALID_REQUEST -> "INVALID_REQUEST"
            FailureCode.ACCESS_DENIED -> "ACCESS_DENIED"
            FailureCode.RESOURCE_NOT_FOUND -> "NOT_FOUND"
            FailureCode.IDEMPOTENCY_KEY_REUSED -> "IDEMPOTENCY_CONFLICT"
            FailureCode.POINT_ADJUSTMENT_INSUFFICIENT_AVAILABLE -> "INSUFFICIENT_AVAILABLE"
            FailureCode.DEPENDENCY_UNAVAILABLE -> "DEPENDENCY_UNAVAILABLE"
            else -> "FAILED"
        }

    private fun magnitude(amountKrw: Long): Long = if (amountKrw > 0) amountKrw else Math.negateExact(amountKrw)

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    private companion object {
        val ZERO_UUID: UUID = UUID(0, 0)
    }
}

@Service
internal class PointAdjustmentTransaction(
    private val accounts: PointAccountJpaRepository,
    private val lots: PointLotJpaRepository,
    private val transactions: PointTransactionJpaRepository,
    private val idempotencies: PointAdjustmentIdempotencyJpaRepository,
    private val permissionAuthorization: OperatorPermissionAuthorization,
    private val audits: AuditRecordOperations,
    private val publications: FinancialEventPublicationOperations,
    private val identifierSource: IdentifierSource,
    private val objectMapper: ObjectMapper,
    private val entityManager: EntityManager,
) {
    @Transactional
    fun execute(
        command: NormalizedPointAdjustmentCommand,
        payloadHash: String,
    ): PointAdjustmentExecution {
        val account =
            accounts.findLockedById(command.pointAccountId)
                ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Point account was not found")
        permissionAuthorization.requireActive(command.actorId, OperatorPermission.POINT_ADJUSTMENT)
        idempotencies
            .findLockedByScope(command.actorId, OPERATION, command.idempotencyKey)
            ?.let { return replay(it, command, payloadHash, objectMapper) }

        verifyAvailableSummary(account)
        val adjustmentId = identifierSource.next()
        val before = AccountSummary.from(account)
        val affectedLots = mutableListOf<PointLotEntity>()
        val transactionRows =
            when (command.direction) {
                PointAdjustmentDirection.CREDIT -> credit(command, account, adjustmentId, affectedLots)
                PointAdjustmentDirection.DEBIT -> debit(command, account, adjustmentId, affectedLots)
                PointAdjustmentDirection.INVALID -> throw DomainFailure(FailureCode.INVALID_REQUEST, "Adjustment direction is invalid")
            }
        transactions.saveAll(transactionRows)
        entityManager.flush()

        val result = pointAdjustmentResult(account, transactionRows)
        val source = adjustmentId.toString()
        audits.appendAll(
            listOf(
                auditCommand(
                    command = command,
                    source = source,
                    before = before,
                    after = AccountSummary.from(account),
                    lots = affectedLots,
                    transactionRows = transactionRows,
                ),
            ),
        )
        publications.publish(
            PointsAdjustedV1(
                envelope =
                    EventEnvelope(
                        eventId = identifierSource.next(),
                        eventType = POINTS_ADJUSTED_EVENT_TYPE,
                        aggregateId = account.id,
                        aggregateVersion = account.version,
                        occurredAt = command.now,
                        payloadVersion = RESPONSE_VERSION,
                        correlationId = command.correlationId,
                        causationId = "point-adjustment:$source",
                    ),
                adjustmentSource = source,
                accountId = account.id,
                amountKrw = command.amountKrw,
                issuerType = command.issuer?.issuerType?.name,
            ),
        )
        val responseBody = objectMapper.writeValueAsString(result)
        idempotencies.saveAndFlush(
            PointAdjustmentIdempotencyEntity(
                id = adjustmentId,
                actorId = command.actorId,
                pointAccountId = account.id,
                operation = OPERATION,
                idempotencyKey = command.idempotencyKey,
                payloadHash = payloadHash,
                responseStatus = CREATED_STATUS,
                responseBody = responseBody,
                responseVersion = RESPONSE_VERSION,
                createdAt = command.now,
                retentionExpiresAt = command.now.plus(IDEMPOTENCY_RETENTION),
            ),
        )
        entityManager.flush()
        return PointAdjustmentExecution(result, replayed = false)
    }

    private fun credit(
        command: NormalizedPointAdjustmentCommand,
        account: PointAccountEntity,
        adjustmentId: UUID,
        affectedLots: MutableList<PointLotEntity>,
    ): List<PointTransactionEntity> {
        val issuer = requireNotNull(command.issuer)
        val lot =
            lots.save(
                PointLotEntity(
                    id = identifierSource.next(),
                    pointAccountId = account.id,
                    availableAmountKrw = command.amountKrw,
                    expiresAt = requireNotNull(command.expiresAt),
                    issuerType = issuer.issuerType,
                    issuerReference = issuer.issuerReference,
                ),
            )
        affectedLots += lot
        account.availablePointsKrw = exactAdd(account.availablePointsKrw, command.amountKrw)
        return listOf(adjustmentTransaction(adjustmentId, account.id, lot.id, command.amountKrw, PointBalanceEffect.CREDIT, command.now))
    }

    private fun debit(
        command: NormalizedPointAdjustmentCommand,
        account: PointAccountEntity,
        adjustmentId: UUID,
        affectedLots: MutableList<PointLotEntity>,
    ): List<PointTransactionEntity> {
        val requestedMagnitude = Math.negateExact(command.amountKrw)
        val candidates = lots.findAdjustmentDebitLotsLocked(account.id, command.now)
        if (exactSum(candidates.map(PointLotEntity::availableAmountKrw)) < requestedMagnitude) {
            throw DomainFailure(
                FailureCode.POINT_ADJUSTMENT_INSUFFICIENT_AVAILABLE,
                "Available unexpired points are insufficient for the adjustment",
            )
        }
        var remaining = requestedMagnitude
        val rows = mutableListOf<PointTransactionEntity>()
        candidates.forEach { lot ->
            if (remaining == 0L) return@forEach
            val amount = minOf(remaining, lot.availableAmountKrw)
            if (amount > 0) {
                lot.availableAmountKrw = exactSubtract(lot.availableAmountKrw, amount)
                remaining = exactSubtract(remaining, amount)
                affectedLots += lot
                rows += adjustmentTransaction(adjustmentId, account.id, lot.id, amount, PointBalanceEffect.DEBIT, command.now)
            }
        }
        if (remaining != 0L) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Point adjustment debit did not tie out")
        }
        account.availablePointsKrw = exactSubtract(account.availablePointsKrw, requestedMagnitude)
        return rows
    }

    private fun adjustmentTransaction(
        adjustmentId: UUID,
        accountId: UUID,
        lotId: UUID,
        amountKrw: Long,
        effect: PointBalanceEffect,
        occurredAt: java.time.Instant,
    ): PointTransactionEntity =
        PointTransactionEntity(
            id = identifierSource.next(),
            pointAccountId = accountId,
            pointLotId = lotId,
            amountKrw = amountKrw,
            type = PointTransactionType.ADJUSTMENT,
            balanceEffect = effect,
            sourceReference = "point-adjustment:$adjustmentId:lot:$lotId",
            occurredAt = occurredAt,
        )

    private fun verifyAvailableSummary(account: PointAccountEntity) {
        if (lots.sumAvailableAmountByAccountId(account.id) != account.availablePointsKrw) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Point account available summary does not tie out")
        }
    }

    private fun auditCommand(
        command: NormalizedPointAdjustmentCommand,
        source: String,
        before: AccountSummary,
        after: AccountSummary,
        lots: List<PointLotEntity>,
        transactionRows: List<PointTransactionEntity>,
    ): AppendAuditRecordCommand {
        val afterSummary =
            linkedMapOf(
                "availablePointsKrw" to after.availablePointsKrw.toString(),
                "reservedPointsKrw" to after.reservedPointsKrw.toString(),
                "recoveryPendingKrw" to after.recoveryPendingKrw.toString(),
                "requestedEffectKrw" to command.amountKrw.toString(),
                "affectedPointLotIds" to objectMapper.writeValueAsString(lots.map { it.id }),
                "pointTransactionIds" to objectMapper.writeValueAsString(transactionRows.map { it.id }),
                "evidenceReferences" to objectMapper.writeValueAsString(command.evidenceReferences),
            )
        command.issuer?.let { issuer ->
            afterSummary["issuerType"] = issuer.issuerType.name
            afterSummary["issuerReference"] = issuer.issuerReference
            afterSummary["expiresAt"] = requireNotNull(command.expiresAt).toString()
        }
        return AppendAuditRecordCommand(
            actorId = command.actorId.toString(),
            actorType = AuditActorType.PLATFORM_OPERATOR,
            action = "POINT_ADJUSTMENT_APPLIED",
            targetType = "POINT_ACCOUNT",
            targetId = command.pointAccountId,
            occurredAt = command.now,
            reason = command.reason,
            beforeSummary =
                mapOf(
                    "availablePointsKrw" to before.availablePointsKrw.toString(),
                    "reservedPointsKrw" to before.reservedPointsKrw.toString(),
                    "recoveryPendingKrw" to before.recoveryPendingKrw.toString(),
                ),
            afterSummary = afterSummary,
            correlationId = command.correlationId,
            sourceReference = source,
        )
    }

    private fun exactAdd(
        left: Long,
        right: Long,
    ): Long =
        try {
            Math.addExact(left, right)
        } catch (failure: ArithmeticException) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Point adjustment amount overflows the account balance").also {
                it.initCause(failure)
            }
        }

    private fun exactSubtract(
        left: Long,
        right: Long,
    ): Long =
        try {
            Math.subtractExact(left, right)
        } catch (failure: ArithmeticException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Point adjustment amount does not tie out").also {
                it.initCause(failure)
            }
        }

    private fun exactSum(values: Iterable<Long>): Long =
        try {
            values.fold(0L, Math::addExact)
        } catch (failure: ArithmeticException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Point lot balance sum overflowed").also {
                it.initCause(failure)
            }
        }

    private data class AccountSummary(
        val availablePointsKrw: Long,
        val reservedPointsKrw: Long,
        val recoveryPendingKrw: Long,
    ) {
        companion object {
            fun from(account: PointAccountEntity): AccountSummary =
                AccountSummary(account.availablePointsKrw, account.reservedPointsKrw, account.recoveryPendingKrw)
        }
    }

    private companion object {
        val OPERATION = PointAdjustmentOperation.POINT_ADJUSTMENT
        val IDEMPOTENCY_RETENTION: Duration = Duration.ofDays(90)
        const val CREATED_STATUS = 201
        const val RESPONSE_VERSION = 1
        const val POINTS_ADJUSTED_EVENT_TYPE = "PointsAdjustedV1"
    }
}

@Service
internal class PointAdjustmentRaceReader(
    private val idempotencies: PointAdjustmentIdempotencyJpaRepository,
    private val objectMapper: ObjectMapper,
) {
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    fun resolve(
        command: NormalizedPointAdjustmentCommand,
        payloadHash: String,
    ): PointAdjustmentExecution {
        val winner =
            idempotencies.findByActorIdAndOperationAndIdempotencyKey(
                command.actorId,
                PointAdjustmentOperation.POINT_ADJUSTMENT,
                command.idempotencyKey,
            ) ?: throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Point adjustment race winner could not be read",
            )
        return replay(winner, command, payloadHash, objectMapper)
    }
}

private fun replay(
    existing: PointAdjustmentIdempotencyEntity,
    command: NormalizedPointAdjustmentCommand,
    payloadHash: String,
    objectMapper: ObjectMapper,
): PointAdjustmentExecution {
    if (existing.pointAccountId != command.pointAccountId || existing.payloadHash != payloadHash) {
        throw DomainFailure(FailureCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was reused for another adjustment")
    }
    if (existing.responseStatus != 201 || existing.responseVersion != 1) {
        throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Stored point adjustment response is invalid")
    }
    val result =
        try {
            objectMapper.readValue(existing.responseBody, PointAdjustmentResult::class.java)
        } catch (failure: RuntimeException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Stored point adjustment response cannot be read").also {
                it.initCause(failure)
            }
        }
    val effect =
        try {
            result.transactions.fold(0L) { sum, transaction -> Math.addExact(sum, transaction.amountKrw) }
        } catch (failure: ArithmeticException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Stored point adjustment response does not tie out").also {
                it.initCause(failure)
            }
        }
    if (result.account.accountId != command.pointAccountId || result.account.availablePointsKrw < 0 ||
        result.account.recoveryPendingKrw < 0 || result.account.currency != "KRW" ||
        result.transactions.isEmpty() || result.transactions.any { it.type != PointTransactionViewType.ADJUSTMENT } ||
        effect != command.amountKrw
    ) {
        throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Stored point adjustment response is inconsistent")
    }
    return PointAdjustmentExecution(result, replayed = true)
}

private fun pointAdjustmentResult(
    account: PointAccountEntity,
    transactions: List<PointTransactionEntity>,
): PointAdjustmentResult =
    PointAdjustmentResult(
        account =
            PointAccountView(
                accountId = account.id,
                availablePointsKrw = account.availablePointsKrw,
                recoveryPendingKrw = account.recoveryPendingKrw,
            ),
        transactions = transactions.map(::pointTransactionView),
    )

internal fun pointTransactionView(transaction: PointTransactionEntity): PointTransactionView =
    PointTransactionView(
        transactionId = transaction.id,
        type = PointTransactionViewType.valueOf(transaction.type.name),
        amountKrw =
            when (transaction.balanceEffect) {
                PointBalanceEffect.CREDIT -> transaction.amountKrw
                PointBalanceEffect.DEBIT -> Math.negateExact(transaction.amountKrw)
                PointBalanceEffect.NONE -> 0L
            },
        occurredAt = transaction.occurredAt,
        sourceReference = transaction.sourceReference,
    )

internal object CanonicalPointAdjustmentPayload {
    fun hash(command: NormalizedPointAdjustmentCommand): String {
        val digest = MessageDigest.getInstance("SHA-256")
        add(digest, command.pointAccountId.toString())
        add(digest, command.amountKrw.toString())
        add(digest, command.issuer?.issuerType?.name)
        add(digest, command.issuer?.issuerReference)
        add(digest, command.expiresAt?.toString())
        add(digest, command.reason)
        add(digest, command.evidenceReferences.size.toString())
        command.evidenceReferences.forEach { add(digest, it) }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun add(
        digest: MessageDigest,
        value: String?,
    ) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(-1).array())
            return
        }
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
}

private fun String.hasControlCharacter(): Boolean = any { it.code < 0x20 || it.code == 0x7f }

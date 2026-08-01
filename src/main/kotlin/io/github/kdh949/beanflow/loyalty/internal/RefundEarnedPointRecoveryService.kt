package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.loyalty.api.AccrualUnitKey
import io.github.kdh949.beanflow.loyalty.api.AccrueCompletedOrderPointsCommand
import io.github.kdh949.beanflow.loyalty.api.AccrueCompletedOrderPointsResult
import io.github.kdh949.beanflow.loyalty.api.RecordLegacyCompletedOrderPointsCommand
import io.github.kdh949.beanflow.loyalty.api.RecoverRefundEarnedPointsCommand
import io.github.kdh949.beanflow.loyalty.api.RecoverRefundEarnedPointsResult
import io.github.kdh949.beanflow.loyalty.api.RefundEarnedPointRecoveryOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

@Service
internal class RefundEarnedPointRecoveryService(
    private val accountRepository: PointAccountJpaRepository,
    private val lotRepository: PointLotJpaRepository,
    private val transactionRepository: PointTransactionJpaRepository,
    private val pendingRepository: PointRecoveryPendingJpaRepository,
    private val recoveryResultRepository: PointRecoveryResultJpaRepository,
    private val accrualResultRepository: PointAccrualResultJpaRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val identifierSource: IdentifierSource,
    private val meterRegistry: MeterRegistry,
) : RefundEarnedPointRecoveryOperations {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun recover(command: RecoverRefundEarnedPointsCommand): RecoverRefundEarnedPointsResult {
        validateRecovery(command)
        val account = lockOrCreateAccount(command.customerId)
        recoveryResultRepository.findByRefundId(command.refundId)?.let { existing ->
            return replayRecovery(existing, account, command)
        }

        val lots = lotRepository.findRecoverableLotsLocked(account.id)
        verifyAvailableSummary(account, lots)
        var remaining = command.targetAmountKrw
        var recovered = 0L
        lots.forEach { lot ->
            if (remaining == 0L) return@forEach
            val amount = minOf(lot.availableAmountKrw, remaining)
            if (amount > 0) {
                lot.availableAmountKrw = Math.subtractExact(lot.availableAmountKrw, amount)
                remaining = Math.subtractExact(remaining, amount)
                recovered = Math.addExact(recovered, amount)
                transactionRepository.save(
                    PointTransactionEntity(
                        id = identifierSource.next(),
                        pointAccountId = account.id,
                        pointLotId = lot.id,
                        amountKrw = amount,
                        type = PointTransactionType.RECOVERY,
                        sourceReference = "refund:${command.refundId}:recovery:lot:${lot.id}",
                        occurredAt = command.refundSucceededAt,
                    ),
                )
            }
        }
        account.availablePointsKrw = Math.subtractExact(account.availablePointsKrw, recovered)
        if (remaining > 0) {
            pendingRepository.save(
                PointRecoveryPendingEntity(
                    id = identifierSource.next(),
                    pointAccountId = account.id,
                    refundSourceReference = command.refundSourceReference,
                    initialAmountKrw = remaining,
                    remainingAmountKrw = remaining,
                    state = PointRecoveryPendingState.PENDING,
                    createdAt = command.refundSucceededAt,
                ),
            )
            account.recoveryPendingKrw = Math.addExact(account.recoveryPendingKrw, remaining)
        }
        recoveryResultRepository.save(
            PointRecoveryResultEntity(
                id = identifierSource.next(),
                refundId = command.refundId,
                orderId = command.orderId,
                pointAccountId = account.id,
                refundSourceReference = command.refundSourceReference,
                completionSourceReference = command.completionSourceReference,
                completionAggregateVersion = command.completionAggregateVersion,
                snapshotSchemaVersion = command.snapshotSchemaVersion,
                snapshotHash = command.snapshotHash,
                targetAmountKrw = command.targetAmountKrw,
                recoveredAmountKrw = recovered,
                pendingAmountKrw = remaining,
                refundSucceededAt = command.refundSucceededAt,
                completedAt = command.completedAt,
                createdAt = command.processedAt,
            ),
        )
        metric("refund_recovery", if (remaining == 0L) "fully_recovered" else "pending_recorded")
        return RecoverRefundEarnedPointsResult(recovered, remaining, replayed = false)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun accrue(command: AccrueCompletedOrderPointsCommand): AccrueCompletedOrderPointsResult {
        validateAccrual(command)
        val excludedUnitsHash = excludedUnitsHash(command.excludedUnits)
        val unitAmounts = command.units.associateBy { AccrualUnitKey(it.orderLineId, it.unitPosition) }
        val excludedAmount = exactSum(command.excludedUnits.map { unitAmounts.getValue(it).accruedAmountKrw })
        val accruedAmount = Math.subtractExact(command.snapshotGrossAmountKrw, excludedAmount)
        val account = lockOrCreateAccount(command.customerId)
        accrualResultRepository.findByOrderId(command.orderId)?.let { existing ->
            return replayAccrual(existing, account, command, excludedUnitsHash, excludedAmount, accruedAmount)
        }
        if (accruedAmount == 0L) {
            accrualResultRepository.save(
                PointAccrualResultEntity(
                    id = identifierSource.next(),
                    orderId = command.orderId,
                    pointAccountId = account.id,
                    completionSourceReference = command.completionSourceReference,
                    completionAggregateVersion = command.completionAggregateVersion,
                    sourceState = PointAccrualResultState.NO_ACCRUAL,
                    snapshotSchemaVersion = command.snapshotSchemaVersion,
                    snapshotHash = command.snapshotHash,
                    excludedUnitsHash = excludedUnitsHash,
                    snapshotGrossAmountKrw = command.snapshotGrossAmountKrw,
                    excludedAmountKrw = excludedAmount,
                    accruedAmountKrw = 0,
                    offsetAmountKrw = 0,
                    availableAmountKrw = 0,
                    pointLotId = null,
                    completedAt = command.completedAt,
                    createdAt = command.processedAt,
                ),
            )
            metric("completion_accrual", "no_accrual")
            return AccrueCompletedOrderPointsResult(
                command.snapshotGrossAmountKrw,
                excludedAmount,
                0,
                0,
                0,
                replayed = false,
            )
        }

        val lot =
            lotRepository.save(
                PointLotEntity(
                    id = identifierSource.next(),
                    pointAccountId = account.id,
                    availableAmountKrw = accruedAmount,
                    expiresAt = command.expiresAt,
                    issuerType = command.issuerType,
                    issuerReference = command.issuerReference,
                    accrualOrderId = command.orderId,
                    accrualSourceReference = "order:${command.orderId}:completion-accrual",
                    accrualSnapshotHash = command.snapshotHash,
                ),
            )
        account.availablePointsKrw = Math.addExact(account.availablePointsKrw, accruedAmount)
        transactionRepository.save(
            PointTransactionEntity(
                id = identifierSource.next(),
                pointAccountId = account.id,
                pointLotId = lot.id,
                amountKrw = accruedAmount,
                type = PointTransactionType.ACCRUAL,
                sourceReference = "order:${command.orderId}:completion-accrual:transaction",
                occurredAt = command.completedAt,
            ),
        )

        val pendingRows = pendingRepository.findPendingLocked(account.id)
        verifyPendingSummary(account, pendingRows)
        var availableForOffset = accruedAmount
        var offset = 0L
        pendingRows.forEach { pending ->
            if (availableForOffset == 0L) return@forEach
            val amount = minOf(availableForOffset, pending.remainingAmountKrw)
            pending.remainingAmountKrw = Math.subtractExact(pending.remainingAmountKrw, amount)
            availableForOffset = Math.subtractExact(availableForOffset, amount)
            offset = Math.addExact(offset, amount)
            lot.availableAmountKrw = Math.subtractExact(lot.availableAmountKrw, amount)
            account.availablePointsKrw = Math.subtractExact(account.availablePointsKrw, amount)
            account.recoveryPendingKrw = Math.subtractExact(account.recoveryPendingKrw, amount)
            if (pending.remainingAmountKrw == 0L) {
                pending.state = PointRecoveryPendingState.SETTLED
                pending.settledAt = command.processedAt
            }
            transactionRepository.save(
                PointTransactionEntity(
                    id = identifierSource.next(),
                    pointAccountId = account.id,
                    pointLotId = lot.id,
                    amountKrw = amount,
                    type = PointTransactionType.RECOVERY,
                    sourceReference = "order:${command.orderId}:offset:pending:${pending.id}",
                    occurredAt = command.completedAt,
                    pointRecoveryPendingId = pending.id,
                ),
            )
        }
        accrualResultRepository.save(
            PointAccrualResultEntity(
                id = identifierSource.next(),
                orderId = command.orderId,
                pointAccountId = account.id,
                completionSourceReference = command.completionSourceReference,
                completionAggregateVersion = command.completionAggregateVersion,
                sourceState = PointAccrualResultState.APPLIED,
                snapshotSchemaVersion = command.snapshotSchemaVersion,
                snapshotHash = command.snapshotHash,
                excludedUnitsHash = excludedUnitsHash,
                snapshotGrossAmountKrw = command.snapshotGrossAmountKrw,
                excludedAmountKrw = excludedAmount,
                accruedAmountKrw = accruedAmount,
                offsetAmountKrw = offset,
                availableAmountKrw = availableForOffset,
                pointLotId = lot.id,
                completedAt = command.completedAt,
                createdAt = command.processedAt,
            ),
        )
        metric("completion_accrual", if (offset == 0L) "accrued" else "pending_offset")
        return AccrueCompletedOrderPointsResult(
            command.snapshotGrossAmountKrw,
            excludedAmount,
            accruedAmount,
            offset,
            availableForOffset,
            replayed = false,
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun recordLegacyNotApplicable(command: RecordLegacyCompletedOrderPointsCommand): Boolean {
        if (command.orderId == ZERO_UUID || command.completionSourceReference.isBlank() ||
            command.completionSourceReference.length > 240 || command.completionAggregateVersion < 0
        ) {
            fail(FailureCode.INVALID_REQUEST, "Legacy completed Order point source is invalid")
        }
        accrualResultRepository.findByOrderId(command.orderId)?.let { existing ->
            if (existing.sourceState != PointAccrualResultState.LEGACY_NOT_APPLICABLE ||
                existing.completionSourceReference != command.completionSourceReference ||
                existing.completionAggregateVersion != command.completionAggregateVersion ||
                existing.completedAt != command.completedAt
            ) {
                fail(FailureCode.IDEMPOTENCY_KEY_REUSED, "Legacy completed Order point source changed")
            }
            metric("completion_accrual", "legacy_replayed")
            return true
        }
        accrualResultRepository.save(
            PointAccrualResultEntity(
                id = identifierSource.next(),
                orderId = command.orderId,
                pointAccountId = null,
                completionSourceReference = command.completionSourceReference,
                completionAggregateVersion = command.completionAggregateVersion,
                sourceState = PointAccrualResultState.LEGACY_NOT_APPLICABLE,
                snapshotSchemaVersion = null,
                snapshotHash = null,
                excludedUnitsHash = null,
                snapshotGrossAmountKrw = null,
                excludedAmountKrw = null,
                accruedAmountKrw = null,
                offsetAmountKrw = null,
                availableAmountKrw = null,
                pointLotId = null,
                completedAt = command.completedAt,
                createdAt = command.processedAt,
            ),
        )
        metric("completion_accrual", "legacy_not_applicable")
        return false
    }

    private fun lockOrCreateAccount(customerId: UUID): PointAccountEntity {
        try {
            jdbcTemplate.update(
                """
                INSERT INTO loyalty_point_account (
                    id, customer_id, available_points_krw, reserved_points_krw,
                    recovery_pending_krw, version
                ) VALUES (?, ?, 0, 0, 0, 0)
                ON CONFLICT (customer_id) DO NOTHING
                """.trimIndent(),
                identifierSource.next(),
                customerId,
            )
        } catch (failure: DataIntegrityViolationException) {
            fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Point account creation failed", failure)
        }
        return accountRepository.findLockedByCustomerId(customerId)
            ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Point account is missing after creation")
    }

    private fun verifyAvailableSummary(
        account: PointAccountEntity,
        lots: List<PointLotEntity>,
    ) {
        if (exactSum(lots.map { it.availableAmountKrw }) != account.availablePointsKrw) {
            fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Point account available summary does not tie out")
        }
    }

    private fun verifyPendingSummary(
        account: PointAccountEntity,
        pendingRows: List<PointRecoveryPendingEntity>,
    ) {
        if (exactSum(pendingRows.map { it.remainingAmountKrw }) != account.recoveryPendingKrw) {
            fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Point account recovery pending summary does not tie out")
        }
    }

    private fun replayRecovery(
        existing: PointRecoveryResultEntity,
        account: PointAccountEntity,
        command: RecoverRefundEarnedPointsCommand,
    ): RecoverRefundEarnedPointsResult {
        if (existing.orderId != command.orderId ||
            existing.pointAccountId != account.id ||
            existing.refundSourceReference != command.refundSourceReference ||
            existing.completionSourceReference != command.completionSourceReference ||
            existing.completionAggregateVersion != command.completionAggregateVersion ||
            existing.snapshotSchemaVersion != command.snapshotSchemaVersion ||
            existing.snapshotHash != command.snapshotHash ||
            existing.targetAmountKrw != command.targetAmountKrw ||
            existing.refundSucceededAt != command.refundSucceededAt ||
            existing.completedAt != command.completedAt
        ) {
            fail(FailureCode.IDEMPOTENCY_KEY_REUSED, "Refund earned-point recovery source changed")
        }
        metric("refund_recovery", "replayed")
        return RecoverRefundEarnedPointsResult(
            existing.recoveredAmountKrw,
            existing.pendingAmountKrw,
            replayed = true,
        )
    }

    private fun replayAccrual(
        existing: PointAccrualResultEntity,
        account: PointAccountEntity,
        command: AccrueCompletedOrderPointsCommand,
        excludedUnitsHash: String,
        excludedAmount: Long,
        accruedAmount: Long,
    ): AccrueCompletedOrderPointsResult {
        if (existing.pointAccountId != account.id ||
            existing.completionSourceReference != command.completionSourceReference ||
            existing.completionAggregateVersion != command.completionAggregateVersion ||
            existing.snapshotSchemaVersion != command.snapshotSchemaVersion ||
            existing.snapshotHash != command.snapshotHash ||
            existing.excludedUnitsHash != excludedUnitsHash ||
            existing.snapshotGrossAmountKrw != command.snapshotGrossAmountKrw ||
            existing.excludedAmountKrw != excludedAmount ||
            existing.accruedAmountKrw != accruedAmount ||
            existing.completedAt != command.completedAt
        ) {
            fail(FailureCode.IDEMPOTENCY_KEY_REUSED, "Order point accrual source changed")
        }
        if (existing.sourceState == PointAccrualResultState.LEGACY_NOT_APPLICABLE) {
            fail(FailureCode.IDEMPOTENCY_KEY_REUSED, "Snapshotted Order was already marked legacy")
        }
        existing.pointLotId?.let { lotId ->
            val lot =
                lotRepository.findById(lotId).orElse(null)
                    ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Accrual result PointLot is missing")
            if (lot.issuerType != command.issuerType || lot.issuerReference != command.issuerReference ||
                lot.expiresAt != command.expiresAt || lot.accrualSnapshotHash != command.snapshotHash
            ) {
                fail(FailureCode.IDEMPOTENCY_KEY_REUSED, "Order point accrual lot source changed")
            }
        }
        metric("completion_accrual", "replayed")
        return AccrueCompletedOrderPointsResult(
            requireNotNull(existing.snapshotGrossAmountKrw),
            requireNotNull(existing.excludedAmountKrw),
            requireNotNull(existing.accruedAmountKrw),
            requireNotNull(existing.offsetAmountKrw),
            requireNotNull(existing.availableAmountKrw),
            replayed = true,
        )
    }

    private fun validateRecovery(command: RecoverRefundEarnedPointsCommand) {
        if (command.refundId == ZERO_UUID || command.orderId == ZERO_UUID || command.customerId == ZERO_UUID ||
            command.refundSourceReference.isBlank() || command.refundSourceReference.length > 240 ||
            command.completionSourceReference.isBlank() || command.completionSourceReference.length > 240 ||
            command.completionAggregateVersion < 0 || command.snapshotSchemaVersion < 1 ||
            !HASH_PATTERN.matches(command.snapshotHash) || command.targetAmountKrw <= 0 ||
            !command.refundSucceededAt.isAfter(command.completedAt)
        ) {
            fail(FailureCode.INVALID_REQUEST, "Refund earned-point recovery command is invalid")
        }
    }

    private fun validateAccrual(command: AccrueCompletedOrderPointsCommand) {
        val keys = command.units.map { AccrualUnitKey(it.orderLineId, it.unitPosition) }
        val issuer = command.issuerReference.trim()
        if (command.orderId == ZERO_UUID || command.customerId == ZERO_UUID ||
            command.completionSourceReference.isBlank() || command.completionSourceReference.length > 240 ||
            command.completionAggregateVersion < 0 || command.snapshotSchemaVersion < 1 ||
            !HASH_PATTERN.matches(command.snapshotHash) || command.snapshotGrossAmountKrw < 0 ||
            issuer != command.issuerReference || issuer.length !in 1..240 ||
            !command.expiresAt.isAfter(command.completedAt) || command.units.isEmpty() ||
            command.units.any { it.orderLineId == ZERO_UUID || it.unitPosition < 0 || it.accruedAmountKrw < 0 } ||
            keys.toSet().size != keys.size || !keys.containsAll(command.excludedUnits) ||
            exactSum(command.units.map { it.accruedAmountKrw }) != command.snapshotGrossAmountKrw
        ) {
            fail(FailureCode.INVALID_REQUEST, "Completed Order point accrual command is invalid")
        }
    }

    private fun excludedUnitsHash(units: Set<AccrualUnitKey>): String {
        val canonical = StringBuilder()
        units.sortedWith(compareBy({ it.orderLineId }, { it.unitPosition })).forEach { unit ->
            val lineId = unit.orderLineId.toString()
            canonical.append(lineId.length).append(':').append(lineId)
            canonical.append(unit.unitPosition.toString().length).append(':').append(unit.unitPosition)
        }
        return HexFormat
            .of()
            .formatHex(
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(canonical.toString().toByteArray(StandardCharsets.UTF_8)),
            )
    }

    private fun exactSum(amounts: Iterable<Long>): Long = amounts.fold(0L, Math::addExact)

    private fun metric(
        operation: String,
        outcome: String,
    ) {
        meterRegistry
            .counter(
                "beanflow.loyalty.point_recovery.count",
                "operation",
                operation,
                "outcome",
                outcome,
            ).increment()
    }

    private fun fail(
        code: FailureCode,
        message: String,
        cause: Throwable? = null,
    ): Nothing {
        val failure = DomainFailure(code, message)
        cause?.let(failure::initCause)
        throw failure
    }

    private companion object {
        val ZERO_UUID: UUID = UUID(0, 0)
        val HASH_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

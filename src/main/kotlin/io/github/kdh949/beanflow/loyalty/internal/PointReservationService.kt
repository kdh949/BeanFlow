package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.loyalty.api.ExpiredPointRestorationMode
import io.github.kdh949.beanflow.loyalty.api.PointReservationAllocation
import io.github.kdh949.beanflow.loyalty.api.PointReservationOperations
import io.github.kdh949.beanflow.loyalty.api.PointReservationResult
import io.github.kdh949.beanflow.loyalty.api.ReservePointsCommand
import io.github.kdh949.beanflow.loyalty.api.RestorePointsAfterTerminationCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.ReservationTransitionReport
import io.github.kdh949.beanflow.shared.api.ReservationTransitionResult
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
internal class PointReservationService(
    private val accountRepository: PointAccountJpaRepository,
    private val lotRepository: PointLotJpaRepository,
    private val reservationRepository: PointReservationJpaRepository,
    private val allocationRepository: PointReservationAllocationJpaRepository,
    private val transactionRepository: PointTransactionJpaRepository,
    private val partialRefundRestorationRepository: PartialRefundRestorationJpaRepository,
    private val identifierSource: IdentifierSource,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
) : PointReservationOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun reserve(command: ReservePointsCommand): PointReservationResult {
        if (command.amountKrw <= 0 || command.sourceReference.isBlank()) {
            fail(FailureCode.INVALID_REQUEST, "Positive point amount and source reference are required")
        }
        val account =
            accountRepository.findLockedByCustomerId(command.customerId)
                ?: fail(FailureCode.POINT_BALANCE_INSUFFICIENT, "Point account is not available")
        reservationRepository.findBySourceReference(command.sourceReference)?.let {
            if (it.orderId == command.orderId && it.amountKrw == command.amountKrw) {
                return resultOf(it)
            }
            fail(FailureCode.ORDER_STATE_CONFLICT, "Point source reference was reused")
        }
        if (account.availablePointsKrw < command.amountKrw) {
            fail(FailureCode.POINT_BALANCE_INSUFFICIENT, "Available point balance is insufficient")
        }
        val now = clock.instant()
        val lots = lotRepository.findReservableLotsLocked(account.id, now)
        var remaining = command.amountKrw
        val allocated = mutableListOf<Pair<PointLotEntity, Long>>()
        for (lot in lots) {
            if (remaining == 0L) break
            val amount = minOf(remaining, lot.availableAmountKrw)
            if (amount > 0) {
                allocated += lot to amount
                remaining -= amount
            }
        }
        if (remaining != 0L) {
            fail(FailureCode.POINT_BALANCE_INSUFFICIENT, "Unexpired point lots are insufficient")
        }

        val reservation =
            PointReservationEntity(
                id = identifierSource.next(),
                orderId = command.orderId,
                pointAccountId = account.id,
                amountKrw = command.amountKrw,
                state = PointReservationState.RESERVED,
                reservationExpiresAt = command.reservationExpiresAt,
                sourceReference = command.sourceReference,
                createdAt = now,
                updatedAt = now,
            )
        account.availablePointsKrw -= command.amountKrw
        account.reservedPointsKrw += command.amountKrw
        reservationRepository.save(reservation)
        allocated.forEach { (lot, amount) ->
            lot.availableAmountKrw -= amount
            lot.reservedAmountKrw += amount
            allocationRepository.save(
                PointReservationAllocationEntity(
                    id = identifierSource.next(),
                    pointReservationId = reservation.id,
                    pointLotId = lot.id,
                    amountKrw = amount,
                ),
            )
        }
        return resultOf(reservation)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun confirm(
        orderId: UUID,
        sourceReference: String,
    ): ReservationTransitionReport = transition(orderId, sourceReference, null, confirm = true)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun release(
        orderId: UUID,
        now: Instant,
        sourceReference: String,
    ): ReservationTransitionReport = transition(orderId, sourceReference, now, confirm = false)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun restoreUsedAfterTermination(command: RestorePointsAfterTerminationCommand): ReservationTransitionReport {
        if (command.sourceReference.isBlank() || command.policyVersionId < 1 || command.compensationValidityDays !in 1..365) {
            fail(FailureCode.INVALID_REQUEST, "Point restoration source and validity are invalid")
        }
        val reservation =
            reservationRepository.findLockedByOrderId(command.orderId)
                ?: return report(ReservationTransitionResult.NOT_ELIGIBLE)
        if (reservation.state == PointReservationState.RESTORED) {
            return if (reservation.restorationSourceReference == command.sourceReference &&
                reservation.restorationTrigger == command.trigger &&
                reservation.restorationPolicyVersionId == command.policyVersionId
            ) {
                report(ReservationTransitionResult.ALREADY_APPLIED, reservation.id)
            } else {
                sourceConflict(command, "Point restoration metadata conflicts")
            }
        }
        if (reservation.state != PointReservationState.USED) {
            return report(ReservationTransitionResult.NOT_ELIGIBLE, reservation.id)
        }
        val account =
            accountRepository.findById(reservation.pointAccountId).orElse(null)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Used point account is missing")
        val lockedAccount =
            accountRepository.findLockedByCustomerId(account.customerId)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Used point account is missing")
        val allocations =
            allocationRepository
                .findAllLockedByReservationId(reservation.id)
        val lots =
            lotRepository
                .findAllLockedByIds(allocations.map { it.pointLotId })
                .associateBy(PointLotEntity::id)
        allocations.forEach { allocation ->
            val alreadyRestored = partialRefundRestorationRepository.sumRestoredAmountByAllocationId(allocation.id)
            if (alreadyRestored !in 0..allocation.amountKrw) {
                fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Partial-refund point restoration does not tie out")
            }
            val remainingAmount = allocation.amountKrw - alreadyRestored
            if (remainingAmount == 0L) return@forEach
            val originalLot =
                lots[allocation.pointLotId]
                    ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Used point lot is missing")
            when {
                command.terminatedAt.isBefore(originalLot.expiresAt) -> {
                    originalLot.availableAmountKrw = Math.addExact(originalLot.availableAmountKrw, remainingAmount)
                    lockedAccount.availablePointsKrw = Math.addExact(lockedAccount.availablePointsKrw, remainingAmount)
                    transactionRepository.save(
                        restorationTransaction(
                            reservation,
                            allocation,
                            originalLot.id,
                            remainingAmount,
                            PointTransactionType.RESTORE,
                            PartialRefundRestorationDisposition.ORIGINAL_LOT,
                            command,
                        ),
                    )
                    recordRestorationMetric(command, PartialRefundRestorationDisposition.ORIGINAL_LOT, remainingAmount)
                }

                command.mode == ExpiredPointRestorationMode.COMPENSATE_WITH_NEW_ISSUANCE -> {
                    val compensationLot =
                        PointLotEntity(
                            id = identifierSource.next(),
                            pointAccountId = lockedAccount.id,
                            availableAmountKrw = remainingAmount,
                            expiresAt =
                                command.terminatedAt.plus(Duration.ofDays(command.compensationValidityDays.toLong())),
                            issuerType = originalLot.issuerType,
                            issuerReference = originalLot.issuerReference,
                            originalPointLotId = originalLot.id,
                            compensationSourceReference =
                                "${command.sourceReference}:${allocation.id}:lot",
                            restorationTrigger = command.trigger.name,
                            restorationPolicyVersionId = command.policyVersionId,
                        )
                    lotRepository.save(compensationLot)
                    lockedAccount.availablePointsKrw = Math.addExact(lockedAccount.availablePointsKrw, remainingAmount)
                    transactionRepository.save(
                        restorationTransaction(
                            reservation,
                            allocation,
                            compensationLot.id,
                            remainingAmount,
                            PointTransactionType.COMPENSATION,
                            PartialRefundRestorationDisposition.COMPENSATION_LOT,
                            command,
                        ),
                    )
                    recordRestorationMetric(command, PartialRefundRestorationDisposition.COMPENSATION_LOT, remainingAmount)
                }

                else -> {
                    transactionRepository.save(
                        restorationTransaction(
                            reservation,
                            allocation,
                            originalLot.id,
                            remainingAmount,
                            PointTransactionType.RESTORE_SKIPPED_EXPIRED,
                            PartialRefundRestorationDisposition.SKIPPED_EXPIRED,
                            command,
                        ),
                    )
                    recordRestorationMetric(command, PartialRefundRestorationDisposition.SKIPPED_EXPIRED, 0)
                }
            }
        }
        reservation.state = PointReservationState.RESTORED
        reservation.restorationSourceReference = command.sourceReference
        reservation.restorationTrigger = command.trigger
        reservation.restorationPolicyVersionId = command.policyVersionId
        reservation.updatedAt = command.terminatedAt
        return report(ReservationTransitionResult.APPLIED, reservation.id)
    }

    private fun transition(
        orderId: UUID,
        sourceReference: String,
        now: Instant?,
        confirm: Boolean,
    ): ReservationTransitionReport {
        val current =
            reservationRepository.findByOrderId(orderId)
                ?: return report(ReservationTransitionResult.NOT_ELIGIBLE)
        val account =
            accountRepository.findById(current.pointAccountId).orElse(null)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Reserved point account is missing")
        val lockedAccount =
            accountRepository.findLockedByCustomerId(account.customerId)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Reserved point account is missing")
        val reservation =
            reservationRepository.findLockedByOrderId(orderId)
                ?: return report(ReservationTransitionResult.NOT_ELIGIBLE)
        if (reservation.sourceReference != sourceReference) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Point transition source does not match")
        }
        val terminal = if (confirm) PointReservationState.USED else PointReservationState.RELEASED
        if (reservation.state == terminal) {
            return report(ReservationTransitionResult.ALREADY_APPLIED, reservation.id)
        }
        if (reservation.state != PointReservationState.RESERVED) {
            return report(ReservationTransitionResult.NOT_ELIGIBLE, reservation.id)
        }

        val allocations =
            allocationRepository
                .findAllByPointReservationIdOrderByPointLotId(reservation.id)
        val lots =
            lotRepository
                .findAllLockedByIds(allocations.map { it.pointLotId })
                .associateBy(PointLotEntity::id)
        lockedAccount.reservedPointsKrw -= reservation.amountKrw
        allocations.forEach { allocation ->
            val lot =
                lots[allocation.pointLotId]
                    ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Reserved point lot is missing")
            check(lot.reservedAmountKrw >= allocation.amountKrw)
            lot.reservedAmountKrw -= allocation.amountKrw
            if (confirm) {
                transactionRepository.save(
                    pointTransaction(reservation, allocation, PointTransactionType.USE, clock.instant()),
                )
            } else if (now!!.isBefore(lot.expiresAt)) {
                lot.availableAmountKrw += allocation.amountKrw
                lockedAccount.availablePointsKrw += allocation.amountKrw
            } else {
                transactionRepository.save(
                    pointTransaction(reservation, allocation, PointTransactionType.EXPIRATION, now),
                )
            }
        }
        reservation.state = terminal
        reservation.updatedAt = now ?: clock.instant()
        return report(ReservationTransitionResult.APPLIED, reservation.id)
    }

    private fun report(
        result: ReservationTransitionResult,
        vararg ids: UUID,
    ) = ReservationTransitionReport(result, ids.toList())

    private fun pointTransaction(
        reservation: PointReservationEntity,
        allocation: PointReservationAllocationEntity,
        type: PointTransactionType,
        occurredAt: Instant,
    ): PointTransactionEntity =
        PointTransactionEntity(
            id = identifierSource.next(),
            pointAccountId = reservation.pointAccountId,
            pointLotId = allocation.pointLotId,
            amountKrw = allocation.amountKrw,
            type = type,
            sourceReference = "${reservation.sourceReference}:${allocation.pointLotId}:$type",
            occurredAt = occurredAt,
        )

    private fun restorationTransaction(
        reservation: PointReservationEntity,
        allocation: PointReservationAllocationEntity,
        pointLotId: UUID,
        amountKrw: Long,
        type: PointTransactionType,
        disposition: PartialRefundRestorationDisposition,
        command: RestorePointsAfterTerminationCommand,
    ): PointTransactionEntity =
        PointTransactionEntity(
            id = identifierSource.next(),
            pointAccountId = reservation.pointAccountId,
            pointLotId = pointLotId,
            amountKrw = amountKrw,
            type = type,
            sourceReference = "${command.sourceReference}:${allocation.id}:$type",
            occurredAt = command.terminatedAt,
            pointReservationAllocationId = allocation.id,
            restorationTrigger = command.trigger.name,
            restorationPolicyVersionId = command.policyVersionId,
            restorationDisposition = disposition.name,
        )

    private fun recordRestorationMetric(
        command: RestorePointsAfterTerminationCommand,
        disposition: PartialRefundRestorationDisposition,
        restoredAmountKrw: Long,
    ) {
        afterCommit {
            val tags =
                arrayOf(
                    "benefit_type",
                    "points",
                    "trigger",
                    command.trigger.name.lowercase(),
                    "disposition",
                    disposition.name.lowercase(),
                )
            meterRegistry.counter("beanflow.benefit.restoration.count", *tags).increment()
            meterRegistry.summary("beanflow.benefit.restoration.amount", *tags).record(restoredAmountKrw.toDouble())
        }
    }

    private fun sourceConflict(
        command: RestorePointsAfterTerminationCommand,
        message: String,
    ): Nothing {
        meterRegistry
            .counter(
                "beanflow.benefit.restoration.source_conflict.count",
                "benefit_type",
                "points",
                "trigger",
                command.trigger.name.lowercase(),
            ).increment()
        fail(FailureCode.COMPENSATION_SOURCE_CONFLICT, message)
    }

    private fun afterCommit(action: () -> Unit) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = action()
            },
        )
    }

    private fun resultOf(reservation: PointReservationEntity): PointReservationResult =
        PointReservationResult(
            reservationId = reservation.id,
            allocations =
                allocationRepository
                    .findAllByPointReservationIdOrderByPointLotId(reservation.id)
                    .let { allocations ->
                        val lotsById = lotRepository.findAllById(allocations.map { it.pointLotId }).associateBy { it.id }
                        allocations.map { allocation ->
                            val lot =
                                lotsById[allocation.pointLotId]
                                    ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Allocated point lot is missing")
                            PointReservationAllocation(
                                pointLotId = lot.id,
                                issuerType = lot.issuerType,
                                issuerReference = lot.issuerReference,
                                finalAllocationKrw = allocation.amountKrw,
                            )
                        }
                    },
        )

    private fun fail(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)
}

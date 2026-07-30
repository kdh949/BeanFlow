package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.loyalty.api.ExpiredPointRestorationMode
import io.github.kdh949.beanflow.loyalty.api.PointAllocation
import io.github.kdh949.beanflow.loyalty.api.PointReservationOperations
import io.github.kdh949.beanflow.loyalty.api.PointReservationResult
import io.github.kdh949.beanflow.loyalty.api.ReservePointsCommand
import io.github.kdh949.beanflow.loyalty.api.RestorePointsByRejectionCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.ReservationTransitionReport
import io.github.kdh949.beanflow.shared.api.ReservationTransitionResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class PointReservationService(
    private val accountRepository: PointAccountJpaRepository,
    private val lotRepository: PointLotJpaRepository,
    private val reservationRepository: PointReservationJpaRepository,
    private val allocationRepository: PointReservationAllocationJpaRepository,
    private val transactionRepository: PointTransactionJpaRepository,
    private val identifierSource: IdentifierSource,
    private val clock: Clock,
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
    override fun restoreUsedByRejection(command: RestorePointsByRejectionCommand): ReservationTransitionReport {
        if (command.sourceReference.isBlank() || command.compensationValidityDays !in 1..365) {
            fail(FailureCode.INVALID_REQUEST, "Point restoration source and validity are invalid")
        }
        val current =
            reservationRepository.findByOrderId(command.orderId)
                ?: return report(ReservationTransitionResult.NOT_ELIGIBLE)
        val account =
            accountRepository.findById(current.pointAccountId).orElse(null)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Used point account is missing")
        val lockedAccount =
            accountRepository.findLockedByCustomerId(account.customerId)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Used point account is missing")
        val reservation =
            reservationRepository.findLockedByOrderId(command.orderId)
                ?: return report(ReservationTransitionResult.NOT_ELIGIBLE)
        if (reservation.state == PointReservationState.RESTORED) {
            return if (reservation.restorationSourceReference == command.sourceReference) {
                report(ReservationTransitionResult.ALREADY_APPLIED, reservation.id)
            } else {
                report(ReservationTransitionResult.NOT_ELIGIBLE, reservation.id)
            }
        }
        if (reservation.state != PointReservationState.USED) {
            return report(ReservationTransitionResult.NOT_ELIGIBLE, reservation.id)
        }
        val allocations =
            allocationRepository
                .findAllByPointReservationIdOrderByPointLotId(reservation.id)
        val lots =
            lotRepository
                .findAllLockedByIds(allocations.map { it.pointLotId })
                .associateBy(PointLotEntity::id)
        allocations.forEach { allocation ->
            val originalLot =
                lots[allocation.pointLotId]
                    ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Used point lot is missing")
            when {
                command.rejectedAt.isBefore(originalLot.expiresAt) -> {
                    originalLot.availableAmountKrw += allocation.amountKrw
                    lockedAccount.availablePointsKrw += allocation.amountKrw
                    transactionRepository.save(
                        restorationTransaction(
                            reservation,
                            allocation,
                            originalLot.id,
                            PointTransactionType.RESTORE,
                            command,
                        ),
                    )
                }

                command.mode == ExpiredPointRestorationMode.COMPENSATE_WITH_NEW_ISSUANCE -> {
                    val compensationLot =
                        PointLotEntity(
                            id = identifierSource.next(),
                            pointAccountId = lockedAccount.id,
                            availableAmountKrw = allocation.amountKrw,
                            expiresAt =
                                command.rejectedAt.plusSeconds(
                                    command.compensationValidityDays.toLong() * 86_400,
                                ),
                            originalPointLotId = originalLot.id,
                            compensationSourceReference =
                                "${command.sourceReference}:${allocation.id}:lot",
                        )
                    lotRepository.save(compensationLot)
                    lockedAccount.availablePointsKrw += allocation.amountKrw
                    transactionRepository.save(
                        restorationTransaction(
                            reservation,
                            allocation,
                            compensationLot.id,
                            PointTransactionType.COMPENSATION,
                            command,
                        ),
                    )
                }

                else -> {
                    transactionRepository.save(
                        restorationTransaction(
                            reservation,
                            allocation,
                            originalLot.id,
                            PointTransactionType.RESTORE_SKIPPED_EXPIRED,
                            command,
                        ),
                    )
                }
            }
        }
        reservation.state = PointReservationState.RESTORED
        reservation.restorationSourceReference = command.sourceReference
        reservation.updatedAt = command.rejectedAt
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
        type: PointTransactionType,
        command: RestorePointsByRejectionCommand,
    ): PointTransactionEntity =
        PointTransactionEntity(
            id = identifierSource.next(),
            pointAccountId = reservation.pointAccountId,
            pointLotId = pointLotId,
            amountKrw = allocation.amountKrw,
            type = type,
            sourceReference = "${command.sourceReference}:${allocation.id}:$type",
            occurredAt = command.rejectedAt,
        )

    private fun resultOf(reservation: PointReservationEntity): PointReservationResult =
        PointReservationResult(
            reservationId = reservation.id,
            allocations =
                allocationRepository
                    .findAllByPointReservationIdOrderByPointLotId(reservation.id)
                    .map { PointAllocation(it.pointLotId, it.amountKrw) },
        )

    private fun fail(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)
}

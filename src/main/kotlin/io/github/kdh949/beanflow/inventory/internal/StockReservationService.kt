package io.github.kdh949.beanflow.inventory.internal

import io.github.kdh949.beanflow.inventory.api.ReserveStockCommand
import io.github.kdh949.beanflow.inventory.api.RestoreStockAfterTerminationCommand
import io.github.kdh949.beanflow.inventory.api.StockRequirement
import io.github.kdh949.beanflow.inventory.api.StockReservationOperations
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
internal class StockReservationService(
    private val stockRepository: SellableStockJpaRepository,
    private val reservationRepository: StockReservationJpaRepository,
    private val identifierSource: IdentifierSource,
    private val clock: Clock,
) : StockReservationOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun reserve(command: ReserveStockCommand): List<UUID> {
        if (command.sourceReference.isBlank() || command.requirements.isEmpty()) {
            fail(FailureCode.INVALID_REQUEST, "Stock reservation source and requirements are required")
        }
        val requirements = aggregate(command.requirements)
        val ids = mutableListOf<UUID>()
        for (requirement in requirements) {
            val stock =
                stockRepository.findLockedById(requirement.sellableUnitId)
                    ?: fail(FailureCode.RESOURCE_NOT_FOUND, "Sellable stock was not found")
            if (stock.storeId != command.storeId) {
                fail(FailureCode.INVALID_REQUEST, "Sellable stock belongs to another store")
            }
            val existing =
                reservationRepository.findBySourceReferenceAndSellableUnitId(
                    command.sourceReference,
                    requirement.sellableUnitId,
                )
            if (existing != null) {
                if (existing.orderId == command.orderId && existing.quantity == requirement.quantity &&
                    existing.state == StockReservationState.RESERVED
                ) {
                    ids += existing.id
                    continue
                }
                fail(FailureCode.ORDER_STATE_CONFLICT, "Stock source reference is already terminal or reused")
            }
            if (stock.availableQuantity < requirement.quantity) {
                fail(FailureCode.STOCK_NOT_AVAILABLE, "Sellable stock is insufficient")
            }
            stock.reserve(requirement.quantity)
            val now = clock.instant()
            val reservation =
                StockReservationEntity(
                    id = identifierSource.next(),
                    orderId = command.orderId,
                    sellableUnitId = requirement.sellableUnitId,
                    quantity = requirement.quantity,
                    state = StockReservationState.RESERVED,
                    expiresAt = command.expiresAt,
                    sourceReference = command.sourceReference,
                    createdAt = now,
                    updatedAt = now,
                )
            reservationRepository.save(reservation)
            ids += reservation.id
        }
        return ids
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun confirm(
        orderId: UUID,
        sourceReference: String,
    ): ReservationTransitionReport = transition(orderId, sourceReference, null, StockTransition.CONFIRM)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun release(
        orderId: UUID,
        now: Instant,
        sourceReference: String,
    ): ReservationTransitionReport = transition(orderId, sourceReference, now, StockTransition.RELEASE)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun expire(
        orderId: UUID,
        now: Instant,
        sourceReference: String,
    ): ReservationTransitionReport = transition(orderId, sourceReference, now, StockTransition.EXPIRE)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun restoreConfirmedAfterTermination(command: RestoreStockAfterTerminationCommand): ReservationTransitionReport {
        val current = reservationRepository.findByOrderIdOrderBySellableUnitId(command.orderId)
        if (current.isEmpty()) return report(ReservationTransitionResult.NOT_ELIGIBLE)
        val stocks =
            current.sortedBy(StockReservationEntity::sellableUnitId).associate { reservation ->
                reservation.sellableUnitId to (
                    stockRepository.findLockedById(reservation.sellableUnitId)
                        ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Confirmed sellable stock is missing")
                )
            }
        val reservations = reservationRepository.findLockedByOrderId(command.orderId)
        if (reservations.all {
                it.state == StockReservationState.RELEASED_AFTER_TERMINATION &&
                    it.restorationSourceReference == command.sourceReference &&
                    it.restorationTrigger == command.trigger
            }
        ) {
            return report(ReservationTransitionResult.ALREADY_APPLIED, reservations)
        }
        if (reservations.any { it.state != StockReservationState.CONFIRMED }) {
            fail(FailureCode.COMPENSATION_SOURCE_CONFLICT, "Stock termination release metadata conflicts")
        }
        reservations.forEach { reservation ->
            stocks.getValue(reservation.sellableUnitId).restoreConfirmed(reservation.quantity)
            reservation.state = StockReservationState.RELEASED_AFTER_TERMINATION
            reservation.restorationSourceReference = command.sourceReference
            reservation.restorationTrigger = command.trigger
            reservation.updatedAt = command.terminatedAt
        }
        return report(ReservationTransitionResult.APPLIED, reservations)
    }

    private fun transition(
        orderId: UUID,
        sourceReference: String,
        now: Instant?,
        transition: StockTransition,
    ): ReservationTransitionReport {
        val current = reservationRepository.findByOrderIdOrderBySellableUnitId(orderId)
        if (current.isEmpty()) return report(ReservationTransitionResult.NOT_ELIGIBLE)
        val stocks =
            current.sortedBy(StockReservationEntity::sellableUnitId).associate { reservation ->
                reservation.sellableUnitId to (
                    stockRepository.findLockedById(reservation.sellableUnitId)
                        ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Reserved sellable stock is missing")
                )
            }
        val reservations = reservationRepository.findLockedByOrderId(orderId)
        if (reservations.any { it.sourceReference != sourceReference }) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Stock transition source does not match")
        }
        if (transition == StockTransition.EXPIRE && reservations.any { now!!.isBefore(it.expiresAt) }) {
            return report(ReservationTransitionResult.NOT_ELIGIBLE, reservations)
        }
        val terminal =
            when (transition) {
                StockTransition.CONFIRM -> StockReservationState.CONFIRMED
                StockTransition.EXPIRE -> StockReservationState.EXPIRED
                StockTransition.RELEASE -> StockReservationState.RELEASED
            }
        if (reservations.all { it.state == terminal }) {
            return report(ReservationTransitionResult.ALREADY_APPLIED, reservations)
        }
        if (reservations.any { it.state != StockReservationState.RESERVED }) {
            return report(ReservationTransitionResult.NOT_ELIGIBLE, reservations)
        }
        reservations.forEach { reservation ->
            val stock = stocks.getValue(reservation.sellableUnitId)
            if (transition == StockTransition.CONFIRM) {
                stock.confirm(reservation.quantity)
            } else {
                stock.release(reservation.quantity)
            }
            reservation.state = terminal
            reservation.updatedAt = now ?: clock.instant()
        }
        return report(ReservationTransitionResult.APPLIED, reservations)
    }

    private fun report(
        result: ReservationTransitionResult,
        reservations: List<StockReservationEntity> = emptyList(),
    ) = ReservationTransitionReport(result, reservations.map(StockReservationEntity::id))

    private fun aggregate(requirements: List<StockRequirement>): List<StockRequirement> =
        requirements
            .groupBy(StockRequirement::sellableUnitId)
            .map { (id, values) ->
                val quantity =
                    values.fold(0L) { total, requirement ->
                        if (requirement.quantity < 1) {
                            fail(FailureCode.INVALID_REQUEST, "Stock requirement quantity must be positive")
                        }
                        try {
                            Math.addExact(total, requirement.quantity)
                        } catch (_: ArithmeticException) {
                            fail(FailureCode.INVALID_REQUEST, "Stock requirement quantity exceeds supported range")
                        }
                    }
                StockRequirement(id, quantity)
            }.sortedBy(StockRequirement::sellableUnitId)

    private fun fail(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)

    private enum class StockTransition {
        CONFIRM,
        EXPIRE,
        RELEASE,
    }
}

package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.FinancialEventPublicationOperations
import io.github.kdh949.beanflow.eventing.api.PointRestorationDisposition
import io.github.kdh949.beanflow.eventing.api.PointsRestoredV1
import io.github.kdh949.beanflow.loyalty.api.PartialRefundPointOperations
import io.github.kdh949.beanflow.loyalty.api.PartialRefundPointPolicyMode
import io.github.kdh949.beanflow.loyalty.api.PartialRefundPointRestorationResult
import io.github.kdh949.beanflow.loyalty.api.PartialRefundPointSourceAllocation
import io.github.kdh949.beanflow.loyalty.api.PartialRefundPointSourceSnapshot
import io.github.kdh949.beanflow.loyalty.api.RestorePartialRefundPointsCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

internal fun isPointLotValidAt(
    expiresAt: java.time.Instant,
    refundSucceededAt: java.time.Instant,
): Boolean = refundSucceededAt.isBefore(expiresAt)

@Service
internal class PartialRefundPointService(
    private val accountRepository: PointAccountJpaRepository,
    private val lotRepository: PointLotJpaRepository,
    private val reservationRepository: PointReservationJpaRepository,
    private val allocationRepository: PointReservationAllocationJpaRepository,
    private val transactionRepository: PointTransactionJpaRepository,
    private val restorationRepository: PartialRefundRestorationJpaRepository,
    private val identifierSource: IdentifierSource,
    private val publications: FinancialEventPublicationOperations,
    private val meterRegistry: MeterRegistry,
) : PartialRefundPointOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun lockSourceSnapshot(orderId: UUID): PartialRefundPointSourceSnapshot {
        val current =
            reservationRepository.findByOrderId(orderId)
                ?: return PartialRefundPointSourceSnapshot(null, emptyList())
        val reservation =
            reservationRepository.findLockedByOrderId(orderId)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Point reservation disappeared while locking")
        if (reservation.state != PointReservationState.USED) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Only a USED point reservation can fund a partial refund")
        }
        val allocations = allocationRepository.findAllLockedByReservationId(reservation.id)
        val lots = lotRepository.findAllLockedByIds(allocations.map { it.pointLotId }).associateBy { it.id }
        if (allocations.sumOf { it.amountKrw } != reservation.amountKrw) {
            fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Point reservation allocation does not tie out")
        }
        return PartialRefundPointSourceSnapshot(
            pointReservationId = current.id,
            allocations =
                allocations
                    .map { allocation ->
                        val lot =
                            lots[allocation.pointLotId]
                                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Original point lot is missing")
                        PartialRefundPointSourceAllocation(
                            pointReservationAllocationId = allocation.id,
                            pointLotId = lot.id,
                            amountKrw = allocation.amountKrw,
                            expiresAt = lot.expiresAt,
                            issuerType = lot.issuerType,
                            issuerReference = lot.issuerReference,
                        )
                    }.sortedWith(compareBy({ it.expiresAt }, { it.pointLotId })),
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun restore(command: RestorePartialRefundPointsCommand): PartialRefundPointRestorationResult {
        validate(command)
        val canonicalSlices =
            command.slices.sortedWith(
                compareBy({ it.orderLineId }, { it.pointReservationAllocationId }),
            )
        val requestedAmount = canonicalSlices.sumOf { it.amountKrw }
        restorationRepository
            .findAllByRefundIdOrderByOrderLineIdAscPointReservationAllocationIdAsc(command.refundId)
            .takeIf { it.isNotEmpty() }
            ?.let { existing ->
                if (!samePayload(existing, canonicalSlices, command)) {
                    fail(FailureCode.IDEMPOTENCY_KEY_REUSED, "Refund restoration source was reused with another payload")
                }
                return PartialRefundPointRestorationResult(requestedAmount, replayed = true)
            }

        val reservation =
            reservationRepository.findLockedByOrderId(command.orderId)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "USED point reservation is missing")
        if (reservation.state != PointReservationState.USED) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Partial refund must preserve a USED point reservation")
        }
        val account =
            accountRepository.findById(reservation.pointAccountId).orElse(null)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Point account is missing")
        val lockedAccount =
            accountRepository.findLockedByCustomerId(account.customerId)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Point account is missing")
        val allocations = allocationRepository.findAllLockedByReservationId(reservation.id).associateBy { it.id }
        val lots =
            lotRepository
                .findAllLockedByIds(canonicalSlices.map { it.originalPointLotId }.distinct())
                .associateBy { it.id }

        canonicalSlices.forEach { slice ->
            val allocation =
                allocations[slice.pointReservationAllocationId]
                    ?: fail(FailureCode.ORDER_STATE_CONFLICT, "Refund points do not belong to this order reservation")
            if (allocation.pointLotId != slice.originalPointLotId) {
                fail(FailureCode.IDEMPOTENCY_KEY_REUSED, "Refund point source lot does not match its allocation")
            }
            val originalLot =
                lots[slice.originalPointLotId]
                    ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Original point lot is missing")
            if (originalLot.issuerType != slice.issuerType || originalLot.issuerReference != slice.issuerReference) {
                fail(FailureCode.IDEMPOTENCY_KEY_REUSED, "Refund point issuer snapshot changed")
            }

            val (restoredLot, disposition, transactionType) =
                when {
                    isPointLotValidAt(originalLot.expiresAt, command.refundSucceededAt) -> {
                        originalLot.availableAmountKrw = Math.addExact(originalLot.availableAmountKrw, slice.amountKrw)
                        lockedAccount.availablePointsKrw = Math.addExact(lockedAccount.availablePointsKrw, slice.amountKrw)
                        Triple(
                            originalLot,
                            PartialRefundRestorationDisposition.ORIGINAL_LOT,
                            PointTransactionType.RESTORE,
                        )
                    }

                    command.policyMode == PartialRefundPointPolicyMode.COMPENSATE_WITH_NEW_ISSUANCE -> {
                        val compensationLot =
                            PointLotEntity(
                                id = identifierSource.next(),
                                pointAccountId = lockedAccount.id,
                                availableAmountKrw = slice.amountKrw,
                                expiresAt =
                                    command.refundSucceededAt.plus(
                                        Duration.ofDays(command.compensationValidityDays.toLong()),
                                    ),
                                issuerType = originalLot.issuerType,
                                issuerReference = originalLot.issuerReference,
                                originalPointLotId = originalLot.id,
                                compensationSourceReference = compensationLotSource(command, slice.orderLineId, allocation.id),
                                restorationTrigger = PARTIAL_REFUND,
                                restorationPolicyVersionId = command.policyVersionId,
                                restorationRefundId = command.refundId,
                            )
                        lotRepository.save(compensationLot)
                        lockedAccount.availablePointsKrw = Math.addExact(lockedAccount.availablePointsKrw, slice.amountKrw)
                        Triple(
                            compensationLot,
                            PartialRefundRestorationDisposition.COMPENSATION_LOT,
                            PointTransactionType.COMPENSATION,
                        )
                    }

                    else -> {
                        Triple(
                            originalLot,
                            PartialRefundRestorationDisposition.SKIPPED_EXPIRED,
                            PointTransactionType.RESTORE_SKIPPED_EXPIRED,
                        )
                    }
                }
            val source = sliceSource(command, slice.orderLineId, allocation.id)
            val pointTransaction =
                transactionRepository.save(
                    PointTransactionEntity(
                        id = identifierSource.next(),
                        pointAccountId = lockedAccount.id,
                        pointLotId = restoredLot.id,
                        amountKrw = slice.amountKrw,
                        type = transactionType,
                        sourceReference = "$source:transaction",
                        occurredAt = command.refundSucceededAt,
                        refundId = command.refundId,
                        orderLineId = slice.orderLineId,
                        pointReservationAllocationId = allocation.id,
                        restorationTrigger = PARTIAL_REFUND,
                        restorationPolicyVersionId = command.policyVersionId,
                        restorationDisposition = disposition.name,
                    ),
                )
            restorationRepository.save(
                PartialRefundRestorationEntity(
                    id = identifierSource.next(),
                    refundId = command.refundId,
                    orderId = command.orderId,
                    orderLineId = slice.orderLineId,
                    pointReservationId = reservation.id,
                    pointReservationAllocationId = allocation.id,
                    originalPointLotId = originalLot.id,
                    restoredPointLotId = restoredLot.id,
                    issuerType = originalLot.issuerType,
                    issuerReference = originalLot.issuerReference,
                    amountKrw = slice.amountKrw,
                    disposition = disposition,
                    policyVersionId = command.policyVersionId,
                    policyMode = command.policyMode.name,
                    policyValidityDays = command.compensationValidityDays,
                    sourceReference = source,
                    restoredAt = command.refundSucceededAt,
                ),
            )
            publications.publish(
                PointsRestoredV1(
                    envelope =
                        EventEnvelope(
                            eventId = identifierSource.next(),
                            eventType = POINTS_RESTORED_EVENT_TYPE,
                            aggregateId = lockedAccount.id,
                            aggregateVersion = Math.addExact(lockedAccount.version, 1),
                            occurredAt = command.refundSucceededAt,
                            payloadVersion = 1,
                            correlationId = command.correlationId,
                            causationId = "point-transaction:${pointTransaction.sourceReference}",
                        ),
                    pointTransactionSource = pointTransaction.sourceReference,
                    refundSource = command.refundSourceReference,
                    orderId = command.orderId,
                    refundSucceededAt = command.refundSucceededAt,
                    orderCompletedAt = command.orderCompletedAt,
                    amountKrw = slice.amountKrw,
                    currency = "KRW",
                    restorationDisposition = disposition.toEventDisposition(),
                ),
            )
            meterRegistry
                .counter(
                    "beanflow.loyalty.partial_refund_restoration.count",
                    "disposition",
                    disposition.name.lowercase(),
                    "policy_mode",
                    command.policyMode.name.lowercase(),
                    "outcome",
                    "succeeded",
                ).increment()
        }
        return PartialRefundPointRestorationResult(requestedAmount, replayed = false)
    }

    private fun validate(command: RestorePartialRefundPointsCommand) {
        if (command.sourceReference.isBlank() || command.refundSourceReference.isBlank() ||
            command.correlationId.isBlank() || command.policyVersionId < 1 ||
            command.compensationValidityDays !in 1..365 || command.slices.isEmpty() ||
            command.slices.any { it.amountKrw <= 0 || it.issuerReference.isBlank() }
        ) {
            fail(FailureCode.INVALID_REQUEST, "Partial-refund restoration command is invalid")
        }
        if (command.slices
                .map { it.orderLineId to it.pointReservationAllocationId }
                .toSet()
                .size !=
            command.slices.size
        ) {
            fail(FailureCode.INVALID_REQUEST, "Partial-refund point slices must be unique")
        }
    }

    private fun samePayload(
        existing: List<PartialRefundRestorationEntity>,
        slices: List<io.github.kdh949.beanflow.loyalty.api.PartialRefundPointSlice>,
        command: RestorePartialRefundPointsCommand,
    ): Boolean {
        val expected = slices.associateBy { it.orderLineId to it.pointReservationAllocationId }
        return existing.size == expected.size &&
            existing.all { row ->
                val slice = expected[row.orderLineId to row.pointReservationAllocationId] ?: return@all false
                row.orderId == command.orderId &&
                    row.originalPointLotId == slice.originalPointLotId &&
                    row.issuerType == slice.issuerType &&
                    row.issuerReference == slice.issuerReference &&
                    row.amountKrw == slice.amountKrw &&
                    row.policyVersionId == command.policyVersionId &&
                    row.policyMode == command.policyMode.name &&
                    row.policyValidityDays == command.compensationValidityDays &&
                    row.restoredAt == command.refundSucceededAt &&
                    row.sourceReference == sliceSource(command, slice.orderLineId, slice.pointReservationAllocationId)
            }
    }

    private fun sliceSource(
        command: RestorePartialRefundPointsCommand,
        lineId: UUID,
        allocationId: UUID,
    ) = "${command.sourceReference}:line:$lineId:allocation:$allocationId"

    private fun compensationLotSource(
        command: RestorePartialRefundPointsCommand,
        lineId: UUID,
        allocationId: UUID,
    ) = "${sliceSource(command, lineId, allocationId)}:lot"

    private fun PartialRefundRestorationDisposition.toEventDisposition(): PointRestorationDisposition =
        when (this) {
            PartialRefundRestorationDisposition.ORIGINAL_LOT -> PointRestorationDisposition.RESTORE
            PartialRefundRestorationDisposition.COMPENSATION_LOT -> PointRestorationDisposition.COMPENSATION
            PartialRefundRestorationDisposition.SKIPPED_EXPIRED -> PointRestorationDisposition.SKIPPED
        }

    private fun fail(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)

    private companion object {
        const val POINTS_RESTORED_EVENT_TYPE = "PointsRestoredV1"
        const val PARTIAL_REFUND = "PARTIAL_REFUND"
    }
}

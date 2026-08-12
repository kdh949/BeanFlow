package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.loyalty.api.PostAcceptanceResolutionPointDisposition
import io.github.kdh949.beanflow.loyalty.api.PostAcceptanceResolutionPointOperations
import io.github.kdh949.beanflow.loyalty.api.PostAcceptanceResolutionPointResult
import io.github.kdh949.beanflow.loyalty.api.RestorePostAcceptanceResolutionPointsCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
internal class PostAcceptanceResolutionPointService(
    private val accounts: PointAccountJpaRepository,
    private val lots: PointLotJpaRepository,
    private val reservations: PointReservationJpaRepository,
    private val allocations: PointReservationAllocationJpaRepository,
    private val transactions: PointTransactionJpaRepository,
    private val partialRestorations: PartialRefundRestorationJpaRepository,
    private val results: SupportResolutionPointRestorationJpaRepository,
    private val identifiers: IdentifierSource,
) : PostAcceptanceResolutionPointOperations {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun restore(command: RestorePostAcceptanceResolutionPointsCommand): PostAcceptanceResolutionPointResult {
        validate(command)
        results.findBySourceReference(command.sourceReference)?.let { return it.exactReplay(command) }
        val reservation = reservations.findLockedByOrderId(command.orderId)
        if (reservation == null) {
            return saveResult(command, null, PostAcceptanceResolutionPointDisposition.NOT_ELIGIBLE, 0)
        }
        if (reservation.state != PointReservationState.USED) {
            fail(FailureCode.COMPENSATION_SOURCE_CONFLICT, "Point reservation is not eligible for Resolution restoration")
        }
        val account =
            accounts.findById(reservation.pointAccountId).orElse(null)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Point account is missing")
        val lockedAccount =
            accounts.findLockedByCustomerId(account.customerId)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Point account is missing")
        val reservationAllocations = allocations.findAllLockedByReservationId(reservation.id)
        val originalLots = lots.findAllLockedByIds(reservationAllocations.map { it.pointLotId }).associateBy { it.id }
        var remainingTotal = 0L
        var restoredTotal = 0L
        reservationAllocations.forEach { allocation ->
            val alreadyRestored = partialRestorations.sumRestoredAmountByAllocationId(allocation.id)
            if (alreadyRestored !in 0..allocation.amountKrw) {
                fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Point restoration history does not tie out")
            }
            val remaining = allocation.amountKrw - alreadyRestored
            if (remaining == 0L) return@forEach
            remainingTotal = Math.addExact(remainingTotal, remaining)
            val lot =
                originalLots[allocation.pointLotId]
                    ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Original point lot is missing")
            val restored = command.restoredAt.isBefore(lot.expiresAt)
            if (restored) {
                lot.availableAmountKrw = Math.addExact(lot.availableAmountKrw, remaining)
                lockedAccount.availablePointsKrw = Math.addExact(lockedAccount.availablePointsKrw, remaining)
                restoredTotal = Math.addExact(restoredTotal, remaining)
            }
            transactions.save(
                PointTransactionEntity(
                    id = identifiers.next(),
                    pointAccountId = lockedAccount.id,
                    pointLotId = lot.id,
                    amountKrw = remaining,
                    type = if (restored) PointTransactionType.RESTORE else PointTransactionType.RESTORE_SKIPPED_EXPIRED,
                    sourceReference = "${command.sourceReference}:${allocation.id}",
                    occurredAt = command.restoredAt,
                    pointReservationAllocationId = allocation.id,
                    restorationTrigger = TRIGGER,
                    restorationDisposition =
                        if (restored) {
                            PartialRefundRestorationDisposition.ORIGINAL_LOT.name
                        } else {
                            PartialRefundRestorationDisposition.SKIPPED_EXPIRED.name
                        },
                ),
            )
        }
        reservation.state = PointReservationState.RESTORED
        reservation.restorationSourceReference = command.sourceReference
        reservation.restorationTrigger = TRIGGER
        reservation.restorationPolicyVersionId = null
        reservation.updatedAt = command.restoredAt
        val disposition =
            when {
                remainingTotal == 0L -> PostAcceptanceResolutionPointDisposition.NOT_ELIGIBLE
                restoredTotal == 0L -> PostAcceptanceResolutionPointDisposition.SKIPPED_EXPIRED
                restoredTotal == remainingTotal -> PostAcceptanceResolutionPointDisposition.RESTORED
                else -> PostAcceptanceResolutionPointDisposition.PARTIALLY_RESTORED
            }
        return saveResult(command, reservation.id, disposition, restoredTotal)
    }

    private fun saveResult(
        command: RestorePostAcceptanceResolutionPointsCommand,
        reservationId: java.util.UUID?,
        disposition: PostAcceptanceResolutionPointDisposition,
        amountKrw: Long,
    ): PostAcceptanceResolutionPointResult =
        results
            .saveAndFlush(
                SupportResolutionPointRestorationEntity(
                    id = identifiers.next(),
                    resolutionId = command.resolutionId,
                    orderId = command.orderId,
                    pointReservationId = reservationId,
                    sourceReference = command.sourceReference,
                    payloadHash = command.payloadHash,
                    disposition = disposition.name,
                    restoredAmountKrw = amountKrw,
                    restoredAt = command.restoredAt,
                ),
            ).toResult(false)

    private fun SupportResolutionPointRestorationEntity.exactReplay(
        command: RestorePostAcceptanceResolutionPointsCommand,
    ): PostAcceptanceResolutionPointResult {
        if (resolutionId != command.resolutionId || orderId != command.orderId ||
            payloadHash != command.payloadHash || restoredAt != command.restoredAt
        ) {
            fail(FailureCode.IDEMPOTENCY_KEY_REUSED, "Point restoration source was reused with another payload")
        }
        return toResult(true)
    }

    private fun SupportResolutionPointRestorationEntity.toResult(replayed: Boolean) =
        PostAcceptanceResolutionPointResult(
            id,
            sourceReference,
            PostAcceptanceResolutionPointDisposition.valueOf(disposition),
            restoredAmountKrw,
            replayed,
        )

    private fun validate(command: RestorePostAcceptanceResolutionPointsCommand) {
        if (command.sourceReference.isBlank() || command.sourceReference != command.sourceReference.trim() ||
            command.sourceReference.length > 240 || !command.payloadHash.matches(SHA_256)
        ) {
            fail(FailureCode.INVALID_REQUEST, "Resolution point restoration command is invalid")
        }
    }

    private fun fail(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)

    private companion object {
        const val TRIGGER = "POST_ACCEPTANCE_RESOLUTION"
        val SHA_256 = Regex("^[0-9a-f]{64}$")
    }
}

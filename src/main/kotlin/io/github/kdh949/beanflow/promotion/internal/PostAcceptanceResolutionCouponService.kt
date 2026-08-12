package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.promotion.api.PostAcceptanceResolutionCouponDisposition
import io.github.kdh949.beanflow.promotion.api.PostAcceptanceResolutionCouponOperations
import io.github.kdh949.beanflow.promotion.api.PostAcceptanceResolutionCouponResult
import io.github.kdh949.beanflow.promotion.api.RestorePostAcceptanceResolutionCouponCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
internal class PostAcceptanceResolutionCouponService(
    private val issuances: CouponIssuanceJpaRepository,
    private val reservations: CouponReservationJpaRepository,
    private val results: SupportResolutionCouponRestorationJpaRepository,
    private val identifiers: IdentifierSource,
) : PostAcceptanceResolutionCouponOperations {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun restore(command: RestorePostAcceptanceResolutionCouponCommand): PostAcceptanceResolutionCouponResult {
        validate(command)
        results.findBySourceReference(command.sourceReference)?.let { return it.exactReplay(command) }
        val current = reservations.findByOrderId(command.orderId)
        if (current == null) {
            return saveResult(command, null, PostAcceptanceResolutionCouponDisposition.NOT_ELIGIBLE)
        }
        val issuance =
            issuances.findLockedById(current.couponIssuanceId)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Coupon issuance is missing")
        val reservation =
            reservations.findLockedByOrderId(command.orderId)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Coupon reservation disappeared while locking")
        if (reservation.state != CouponReservationState.USED || issuance.state != CouponIssuanceState.USED) {
            fail(FailureCode.COMPENSATION_SOURCE_CONFLICT, "Coupon is not eligible for Resolution restoration")
        }
        val disposition =
            if (command.restoredAt.isBefore(issuance.couponExpiresAt)) {
                issuance.state = CouponIssuanceState.RESTORED
                issuance.reservedOrderId = null
                CouponRestorationDisposition.ORIGINAL_RESTORED
            } else {
                CouponRestorationDisposition.SKIPPED_EXPIRED
            }
        reservation.state = CouponReservationState.RESTORED
        reservation.restorationSourceReference = command.sourceReference
        reservation.restorationTrigger = TRIGGER
        reservation.restorationPolicyVersionId = null
        reservation.restorationDisposition = disposition
        reservation.updatedAt = command.restoredAt
        return saveResult(
            command,
            reservation.id,
            if (disposition == CouponRestorationDisposition.ORIGINAL_RESTORED) {
                PostAcceptanceResolutionCouponDisposition.RESTORED
            } else {
                PostAcceptanceResolutionCouponDisposition.SKIPPED_EXPIRED
            },
        )
    }

    private fun saveResult(
        command: RestorePostAcceptanceResolutionCouponCommand,
        reservationId: java.util.UUID?,
        disposition: PostAcceptanceResolutionCouponDisposition,
    ): PostAcceptanceResolutionCouponResult =
        results
            .saveAndFlush(
                SupportResolutionCouponRestorationEntity(
                    id = identifiers.next(),
                    resolutionId = command.resolutionId,
                    orderId = command.orderId,
                    couponReservationId = reservationId,
                    sourceReference = command.sourceReference,
                    payloadHash = command.payloadHash,
                    disposition = disposition.name,
                    restoredAt = command.restoredAt,
                ),
            ).toResult(false)

    private fun SupportResolutionCouponRestorationEntity.exactReplay(
        command: RestorePostAcceptanceResolutionCouponCommand,
    ): PostAcceptanceResolutionCouponResult {
        if (resolutionId != command.resolutionId || orderId != command.orderId ||
            payloadHash != command.payloadHash || restoredAt != command.restoredAt
        ) {
            fail(FailureCode.IDEMPOTENCY_KEY_REUSED, "Coupon restoration source was reused with another payload")
        }
        return toResult(true)
    }

    private fun SupportResolutionCouponRestorationEntity.toResult(replayed: Boolean) =
        PostAcceptanceResolutionCouponResult(
            id,
            sourceReference,
            PostAcceptanceResolutionCouponDisposition.valueOf(disposition),
            replayed,
        )

    private fun validate(command: RestorePostAcceptanceResolutionCouponCommand) {
        if (command.sourceReference.isBlank() || command.sourceReference != command.sourceReference.trim() ||
            command.sourceReference.length > 240 || !command.payloadHash.matches(SHA_256)
        ) {
            fail(FailureCode.INVALID_REQUEST, "Resolution coupon restoration command is invalid")
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

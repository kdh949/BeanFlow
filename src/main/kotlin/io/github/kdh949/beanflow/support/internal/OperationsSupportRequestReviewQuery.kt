package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.ordering.api.GoodwillCompensationOrderOperations
import io.github.kdh949.beanflow.promotion.api.GoodwillCouponOperations
import io.github.kdh949.beanflow.promotion.api.GoodwillCouponTemplateView
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OperatorActor
import io.github.kdh949.beanflow.support.internal.domain.ProfileChangePurpose
import io.github.kdh949.beanflow.support.internal.domain.SupportActionApprovalRoute
import io.github.kdh949.beanflow.support.internal.domain.SupportActionRequestState
import io.github.kdh949.beanflow.support.internal.domain.SupportActionType
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationBenefitType
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

internal data class OperationsSupportActionReview(
    val requestId: UUID,
    val caseId: UUID,
    val action: SupportActionType,
    val targetId: UUID,
    val revisionNumber: Int,
    val requestVersion: Long,
    val state: SupportActionRequestState,
    val actionPayloadDigest: String,
    val targetVersion: Long,
    val evidenceDigest: String,
)

internal data class OperationsSupportProfileReview(
    val subjectId: UUID,
    val purpose: ProfileChangePurpose,
    val expectedProfileVersion: Long,
    val currentProfileVersion: Long,
    val payloadDigest: String,
)

internal data class OperationsSupportCompensationReview(
    val incidentId: UUID,
    val orderId: UUID?,
    val benefitType: SupportCompensationBenefitType,
    val amountKrw: Long,
    val payloadDigest: String,
    val terms: SupportCompensationTermsResource,
    val currentTargetVersion: Long?,
    val couponTemplate: GoodwillCouponTemplateView?,
)

internal data class OperationsSupportRequestReviewResource(
    val request: OperationsSupportActionReview,
    val profile: OperationsSupportProfileReview?,
    val compensation: OperationsSupportCompensationReview?,
)

/** Read-only review metadata for the explicit Operations grant, without Support case access or raw PII. */
@Service
internal class OperationsSupportRequestReviewQuery(
    private val permissions: OperatorPermissionAuthorization,
    private val requests: SupportActionRequestJpaRepository,
    private val revisions: SupportActionRevisionJpaRepository,
    private val profiles: SupportProfileChangeJpaRepository,
    private val compensations: SupportCompensationRequestJpaRepository,
    private val owners: SupportProfileChangeOwnerHandler,
    private val orders: GoodwillCompensationOrderOperations,
    private val coupons: GoodwillCouponOperations,
) {
    @Transactional
    fun get(
        actorId: UUID,
        requestId: UUID,
    ): OperationsSupportRequestReviewResource {
        permissions.requireActive(actorId, OperatorPermission.OPERATIONS_SUPPORT_INVESTIGATION)
        val request = requests.findById(requestId).orElse(null) ?: missing()
        if (request.approvalRoute !in
            setOf(SupportActionApprovalRoute.OPERATIONS, SupportActionApprovalRoute.SUPPORT_MANAGER_THEN_OPERATIONS)
        ) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Request does not have an Operations review route")
        }
        val revision = revisions.findByRequestIdAndRevisionNumber(requestId, request.currentRevisionNumber) ?: missing()
        val profile =
            if (request.action == SupportActionType.PROFILE_CHANGE) {
                val entity = profiles.findById(request.targetId).orElse(null) ?: missing()
                if (entity.actionRequestId != request.id || entity.supportCaseId != request.supportCaseId ||
                    entity.payloadDigest != revision.actionPayloadDigest || entity.expectedProfileVersion != revision.targetVersion
                ) {
                    stale()
                }
                OperationsSupportProfileReview(
                    entity.subjectId,
                    entity.purpose,
                    entity.expectedProfileVersion,
                    owners.currentVersion(entity.purpose, entity.subjectId),
                    entity.payloadDigest,
                )
            } else {
                null
            }
        val compensation =
            if (request.action == SupportActionType.GOODWILL_COMPENSATION) {
                val entity = compensations.findById(request.targetId).orElse(null) ?: missing()
                if (entity.actionRequestId != request.id || entity.supportCaseId != request.supportCaseId ||
                    entity.payloadDigest != revision.actionPayloadDigest || entity.targetVersion != revision.targetVersion
                ) {
                    stale()
                }
                val orderId = entity.orderId
                OperationsSupportCompensationReview(
                    entity.incidentId,
                    entity.orderId,
                    entity.benefitType,
                    entity.amountKrw,
                    entity.payloadDigest,
                    SupportCompensationTermsResource(
                        entity.responsibility,
                        entity.evidenceBasis,
                        entity.costEvidenceDigest,
                        entity.platformShareBps,
                        entity.storeShareBps,
                        entity.evidenceDigest,
                        entity.targetVersion,
                    ),
                    if (orderId == null) 0L else orders.find(orderId)?.version,
                    entity.couponTemplateId?.let {
                        coupons.findTemplate(it)
                            ?: throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Compensation coupon template is missing")
                    },
                )
            } else {
                null
            }
        return OperationsSupportRequestReviewResource(
            OperationsSupportActionReview(
                request.id,
                request.supportCaseId,
                request.action,
                request.targetId,
                request.currentRevisionNumber,
                request.version,
                request.state,
                revision.actionPayloadDigest,
                revision.targetVersion,
                revision.evidenceDigest,
            ),
            profile,
            compensation,
        )
    }

    private fun missing(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Operations review target was not found")

    private fun stale(): Nothing = throw DomainFailure(FailureCode.SUPPORT_ACTION_REQUEST_STALE, "Operations review binding is stale")
}

@RestController
internal class OperationsSupportRequestReviewController(
    private val query: OperationsSupportRequestReviewQuery,
) {
    @GetMapping("/api/v1/operations/support-action-requests/{requestId}/review")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun get(
        actor: OperatorActor,
        @PathVariable requestId: UUID,
    ): ResponseEntity<OperationsSupportRequestReviewResource> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.get(actor.actorId, requestId))
}

package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.notification.api.GoodwillCompensationNotificationOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.ordering.api.GoodwillCompensationOrderOperations
import io.github.kdh949.beanflow.promotion.api.GoodwillCouponOperations
import io.github.kdh949.beanflow.promotion.api.GoodwillCouponTemplateView
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OperatorActor
import io.github.kdh949.beanflow.support.internal.domain.SupportActionRequestState
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationEvidenceBasis
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationResponsibility
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

internal enum class SupportCompensationWorkflowAction { EXECUTE, RETRY_NOTIFICATION, DECIDE_SUPPORT_MANAGER, REASSIGN }

internal data class SupportCompensationTermsResource(
    val responsibility: SupportCompensationResponsibility,
    val evidenceBasis: SupportCompensationEvidenceBasis?,
    val costEvidenceDigest: String?,
    val platformShareBps: Int,
    val storeShareBps: Int,
    val evidenceDigest: String,
    val targetVersion: Long,
)

internal data class SupportCompensationApprovalResource(
    val requestId: UUID,
    val revisionNumber: Int,
    val requestVersion: Long,
    val state: SupportActionRequestState,
    val caseVersion: Long,
    val executorActorId: UUID,
)

internal data class SupportCompensationWorkflowResource(
    val request: SupportCompensationResource,
    val terms: SupportCompensationTermsResource,
    val approval: SupportCompensationApprovalResource?,
    val currentTargetVersion: Long?,
    val verificationExpiresAt: Instant,
    val allowedActions: List<SupportCompensationWorkflowAction>,
    val couponTemplate: GoodwillCouponTemplateView? = null,
)

internal data class SupportCompensationCouponTemplatePage(
    val items: List<GoodwillCouponTemplateView>,
    val nextCursor: UUID?,
)

@Service
internal class SupportCompensationWorkflowService(
    private val transactions: SupportCompensationTransactionService,
    private val ordering: GoodwillCompensationOrderOperations,
    private val notifications: GoodwillCompensationNotificationOperations,
    private val permissions: OperatorPermissionAuthorization,
    private val coupons: GoodwillCouponOperations,
) {
    @Transactional
    fun get(
        actorId: UUID,
        compensationRequestId: UUID,
    ): SupportCompensationWorkflowResource {
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
        val binding = transactions.executionOrderBinding(compensationRequestId)
        val workflow = transactions.workflow(actorId, compensationRequestId, binding.orderId?.let(ordering::find))
        val resource = workflow.request
        val notificationState =
            if (resource.state.name == "NOTIFICATION_SKIPPED") {
                "NOTIFICATION_SKIPPED"
            } else {
                resource.notificationDeliveryId?.let(notifications::findGoodwill)?.state
            }
        return workflow.copy(
            request = resource.copy(notificationState = notificationState),
            couponTemplate =
                resource.couponTemplateId?.let {
                    coupons.findTemplate(it)
                        ?: throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Compensation coupon template is missing")
                },
        )
    }

    @Transactional
    fun templates(
        actorId: UUID,
        cursor: UUID?,
        limit: Int,
    ): SupportCompensationCouponTemplatePage {
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_COMPENSATION_REQUEST)
        val rows = coupons.listTemplates(cursor, limit + 1)
        val page = rows.take(limit)
        return SupportCompensationCouponTemplatePage(page, if (rows.size > limit) page.last().templateId else null)
    }
}

@Validated
@RestController
internal class SupportCompensationWorkflowController(
    private val service: SupportCompensationWorkflowService,
) {
    @GetMapping("/api/v1/support/compensations/{compensationRequestId}/workflow")
    @PreAuthorize("isAuthenticated()")
    fun get(
        actor: OperatorActor,
        @PathVariable compensationRequestId: UUID,
    ): ResponseEntity<SupportCompensationWorkflowResource> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(actor.actorId, compensationRequestId))

    @GetMapping("/api/v1/support/compensation-coupon-templates")
    @PreAuthorize("isAuthenticated()")
    fun templates(
        actor: OperatorActor,
        @RequestParam(required = false) cursor: UUID?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) limit: Int,
    ): ResponseEntity<SupportCompensationCouponTemplatePage> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.templates(actor.actorId, cursor, limit))
}

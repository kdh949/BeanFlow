package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.fulfillment.api.PickupSlotQueryOperations
import io.github.kdh949.beanflow.fulfillment.api.PickupSlotView
import io.github.kdh949.beanflow.identity.api.StoreAccessOperations
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.ordering.api.OrderingSupportTimelineOperations
import io.github.kdh949.beanflow.ordering.api.SupportOrderState
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.MerchantActor
import io.github.kdh949.beanflow.shared.api.OperatorActor
import io.github.kdh949.beanflow.support.internal.domain.SupportActionPolicy
import io.github.kdh949.beanflow.support.internal.domain.SupportActionRequestState
import io.github.kdh949.beanflow.support.internal.domain.SupportActionType
import io.github.kdh949.beanflow.support.internal.domain.SupportCaseState
import io.github.kdh949.beanflow.support.internal.domain.SupportOrderChangeAuthorization
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class SupportOrderContextResource(
    val orderId: UUID,
    val storeId: UUID,
    val state: SupportOrderState,
    val version: Long,
)

internal data class SupportPickupSlotList(
    val items: List<PickupSlotView>,
)

internal enum class SupportOrderWorkflowAction { REVISE, DECIDE_SUPPORT_MANAGER, REASSIGN, EXECUTE }

internal data class SupportOrderWorkflowResource(
    val request: SupportActionRequestResource,
    val caseVersion: Long,
    val order: SupportOrderContextResource?,
    val allowedActions: List<SupportOrderWorkflowAction>,
)

internal data class StoreSupportOrderChangeRequestResource(
    val requestId: UUID,
    val orderId: UUID,
    val action: SupportActionType,
    val revisionNumber: Int,
    val requestVersion: Long,
    val targetVersion: Long,
    val actionPayloadDigest: String,
    val expiresAt: Instant,
    val policyVersion: String,
)

@Service
internal class SupportOrderWorkflowQuery(
    private val authorization: SupportActionEvaluationAuthorization,
    private val ordering: OrderingSupportTimelineOperations,
    private val transactions: SupportActionRequestTransactionService,
    private val cases: SupportCaseJpaRepository,
    private val requests: SupportActionRequestJpaRepository,
    private val revisions: SupportActionRevisionJpaRepository,
    private val permissions: OperatorPermissionAuthorization,
    private val storeAccess: StoreAccessOperations,
    private val clock: Clock,
    private val pickupSlots: PickupSlotQueryOperations,
) {
    @Transactional
    fun order(
        actorId: UUID,
        caseId: UUID,
        orderId: UUID,
    ): SupportOrderContextResource {
        authorization.authorizeTarget(actorId, caseId, orderId)
        val order = ordering.findOrderSnapshots(setOf(orderId)).singleOrNull() ?: missing()
        return SupportOrderContextResource(order.orderId, order.storeId, order.state, order.version)
    }

    @Transactional
    fun orderPickupSlots(
        actorId: UUID,
        caseId: UUID,
        orderId: UUID,
    ): SupportPickupSlotList {
        val current = order(actorId, caseId, orderId)
        return SupportPickupSlotList(pickupSlots.listOpenSlots(current.storeId, clock.instant()))
    }

    @Transactional
    fun requestPickupSlots(
        actorId: UUID,
        requestId: UUID,
    ): SupportPickupSlotList {
        val current = workflow(actorId, requestId).order ?: denied()
        return SupportPickupSlotList(pickupSlots.listOpenSlots(current.storeId, clock.instant()))
    }

    @Transactional
    fun storePickupSlots(
        actorId: UUID,
        storeId: UUID,
        requestId: UUID,
    ): SupportPickupSlotList {
        storeRequest(actorId, storeId, requestId)
        return SupportPickupSlotList(pickupSlots.listOpenSlots(storeId, clock.instant()))
    }

    @Transactional
    fun workflow(
        actorId: UUID,
        requestId: UUID,
    ): SupportOrderWorkflowResource {
        val request = transactions.get(actorId, requestId)
        val supportCase = cases.findLockedById(request.caseId) ?: missing()
        val actions = mutableListOf<SupportOrderWorkflowAction>()
        val current = clock.instant().isBefore(request.expiresAt) && supportCase.state in ACTIVE_CASE_STATES
        val direct = request.action in DIRECT_ACTIONS

        fun has(permission: OperatorPermission) = permissions.hasActive(actorId, permission)
        if (direct && current) {
            if (actorId == request.requesterActorId && actorId == supportCase.currentAssigneeId &&
                request.state in REVISION_STATES && has(OperatorPermission.SUPPORT_ACTION_REQUEST) &&
                has(request.action.capabilityPermission()) && has(OperatorPermission.SUPPORT_ORDER_READ)
            ) {
                actions += SupportOrderWorkflowAction.REVISE
            }
            if (request.state == SupportActionRequestState.AWAITING_SUPPORT_MANAGER &&
                actorId != request.requesterActorId && actorId != request.executorActorId &&
                has(OperatorPermission.SUPPORT_ACTION_APPROVE) && has(OperatorPermission.SUPPORT_ORDER_READ)
            ) {
                actions += SupportOrderWorkflowAction.DECIDE_SUPPORT_MANAGER
            }
            if (request.state in EXECUTOR_STATES && has(OperatorPermission.SUPPORT_CASE_ASSIGN)) {
                actions += SupportOrderWorkflowAction.REASSIGN
            }
            if (request.state == SupportActionRequestState.READY_FOR_EXECUTION &&
                actorId == request.executorActorId && actorId == supportCase.currentAssigneeId &&
                has(OperatorPermission.SUPPORT_ACTION_EXECUTE) && has(request.action.executionCapabilityPermission()) &&
                has(OperatorPermission.SUPPORT_ORDER_READ)
            ) {
                actions += SupportOrderWorkflowAction.EXECUTE
            }
        }
        val order =
            if (direct && has(OperatorPermission.SUPPORT_ORDER_READ)) {
                val snapshot = ordering.findOrderSnapshots(setOf(request.targetId)).singleOrNull() ?: missing()
                SupportOrderContextResource(snapshot.orderId, snapshot.storeId, snapshot.state, snapshot.version)
            } else {
                null
            }
        return SupportOrderWorkflowResource(request, supportCase.version, order, actions)
    }

    @Transactional
    fun storeRequest(
        actorId: UUID,
        storeId: UUID,
        requestId: UUID,
    ): StoreSupportOrderChangeRequestResource {
        storeAccess.requireOrderManagementAccess(actorId, storeId, setOf(StoreActorRole.OWNER, StoreActorRole.STAFF))
        val request = requests.findById(requestId).orElseThrow { missing() }
        val order = ordering.findOrderSnapshots(setOf(request.targetId)).singleOrNull() ?: missing()
        if (order.storeId != storeId) denied()
        val revision = revisions.findByRequestIdAndRevisionNumber(requestId, request.currentRevisionNumber) ?: missing()
        if (request.action !in DIRECT_ACTIONS || order.state != SupportOrderState.ACCEPTED ||
            request.state !in
            (EXECUTOR_STATES + SupportActionRequestState.AWAITING_SUPPORT_MANAGER + SupportActionRequestState.AWAITING_OPERATIONS) ||
            !clock.instant().isBefore(revision.expiresAt) || order.version != revision.targetVersion ||
            revision.policyVersion != SupportActionPolicy.POLICY_VERSION
        ) {
            throw DomainFailure(FailureCode.RESOURCE_STATE_CONFLICT, "Current request cannot receive store confirmation")
        }
        return StoreSupportOrderChangeRequestResource(
            request.id,
            order.orderId,
            request.action,
            revision.revisionNumber,
            request.version,
            revision.targetVersion,
            revision.actionPayloadDigest,
            revision.expiresAt,
            SupportOrderChangeAuthorization.INITIAL_POLICY_VERSION,
        )
    }

    private fun missing(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Support workflow resource was not found")

    private fun denied(): Nothing = throw DomainFailure(FailureCode.ACCESS_DENIED, "Store order scope is required")

    private companion object {
        val DIRECT_ACTIONS = setOf(SupportActionType.ORDER_CANCELLATION, SupportActionType.PICKUP_RESCHEDULE)
        val ACTIVE_CASE_STATES = setOf(SupportCaseState.OPEN, SupportCaseState.IN_PROGRESS, SupportCaseState.WAITING)
        val REVISION_STATES =
            setOf(
                SupportActionRequestState.AWAITING_SUPPORT_MANAGER,
                SupportActionRequestState.AWAITING_OPERATIONS,
                SupportActionRequestState.REVISION_REQUIRED,
            )
        val EXECUTOR_STATES = setOf(SupportActionRequestState.READY_FOR_EXECUTION, SupportActionRequestState.REASSIGNMENT_REQUIRED)
    }
}

@RestController
internal class SupportOrderWorkflowController(
    private val query: SupportOrderWorkflowQuery,
) {
    @GetMapping("/api/v1/support/cases/{caseId}/orders/{orderId}")
    @PreAuthorize("isAuthenticated()")
    fun order(
        actor: OperatorActor,
        @PathVariable caseId: UUID,
        @PathVariable orderId: UUID,
    ): ResponseEntity<SupportOrderContextResource> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.order(actor.actorId, caseId, orderId))

    @GetMapping("/api/v1/support/cases/{caseId}/orders/{orderId}/pickup-slots")
    @PreAuthorize("isAuthenticated()")
    fun orderPickupSlots(
        actor: OperatorActor,
        @PathVariable caseId: UUID,
        @PathVariable orderId: UUID,
    ): ResponseEntity<SupportPickupSlotList> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.orderPickupSlots(actor.actorId, caseId, orderId))

    @GetMapping("/api/v1/support/action-requests/{requestId}/pickup-slots")
    @PreAuthorize("isAuthenticated()")
    fun requestPickupSlots(
        actor: OperatorActor,
        @PathVariable requestId: UUID,
    ): ResponseEntity<SupportPickupSlotList> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.requestPickupSlots(actor.actorId, requestId))

    @GetMapping("/api/v1/stores/{storeId}/support-order-change-requests/{requestId}/pickup-slots")
    @PreAuthorize("isAuthenticated()")
    fun storePickupSlots(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @PathVariable requestId: UUID,
    ): ResponseEntity<SupportPickupSlotList> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.storePickupSlots(actor.actorId, storeId, requestId))

    @GetMapping("/api/v1/support/action-requests/{requestId}/workflow")
    @PreAuthorize("isAuthenticated()")
    fun workflow(
        actor: OperatorActor,
        @PathVariable requestId: UUID,
    ): ResponseEntity<SupportOrderWorkflowResource> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.workflow(actor.actorId, requestId))

    @GetMapping("/api/v1/stores/{storeId}/support-order-change-requests/{requestId}")
    @PreAuthorize("isAuthenticated()")
    fun storeRequest(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @PathVariable requestId: UUID,
    ): ResponseEntity<StoreSupportOrderChangeRequestResource> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.storeRequest(actor.actorId, storeId, requestId))
}

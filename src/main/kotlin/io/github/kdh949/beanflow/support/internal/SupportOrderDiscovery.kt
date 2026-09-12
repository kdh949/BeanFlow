package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.identity.api.StoreAccessOperations
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.ordering.api.OrderingSupportTimelineOperations
import io.github.kdh949.beanflow.ordering.api.SupportOrderState
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.MerchantActor
import io.github.kdh949.beanflow.shared.api.OperatorActor
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import io.github.kdh949.beanflow.support.internal.domain.SupportActionType
import io.github.kdh949.beanflow.support.internal.domain.SupportOrderChangeAuthorization
import io.github.kdh949.beanflow.support.internal.domain.SupportOrderChangeAuthorizationType
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

internal data class StoreOrderChangeCandidate(
    val requestId: UUID,
    val orderReference: String,
    val action: SupportActionType,
    val revisionNumber: Int,
    val createdAt: Instant,
    val expiresAt: Instant,
)

internal data class StoreOrderChangeCandidatePage(
    val items: List<StoreOrderChangeCandidate>,
    val nextCursor: String?,
)

internal data class SupportOrderConsentCandidate(
    val authorizationId: UUID,
    val authorizationType: SupportOrderChangeAuthorizationType,
    val authorizedAt: Instant,
    val expiresAt: Instant,
    val remainingUses: Int,
)

internal data class SupportOrderConsentPage(
    val items: List<SupportOrderConsentCandidate>,
    val nextCursor: String?,
)

@Service
@Transactional
internal class SupportOrderDiscovery(
    private val storeAccess: StoreAccessOperations,
    private val ordering: OrderingSupportTimelineOperations,
    private val workflow: SupportOrderWorkflowQuery,
    private val requests: SupportActionRequestJpaRepository,
    private val jdbc: JdbcTemplate,
    private val cursors: SignedCursorCodec,
    private val clock: Clock,
) {
    fun storeRequests(
        actorId: UUID,
        storeId: UUID,
        cursor: String?,
        limit: Int,
    ): StoreOrderChangeCandidatePage {
        storeAccess.requireOrderManagementAccess(actorId, storeId, setOf(StoreActorRole.OWNER, StoreActorRole.STAFF))
        requireLimit(limit)
        val scope = scope("store-support-order-requests", "$actorId|$storeId")
        val after = cursor?.let { cursors.verify(it, scope).sort }
        val args = mutableListOf<Any>(Timestamp.from(clock.instant()))
        val afterClause = if (after == null) "" else " AND request.id > ?".also { args += after }
        args += limit + 1
        val rows =
            jdbc.query(
                """
                SELECT request.id, request.target_id, request.created_at, revision.target_version
                FROM support_action_request request
                JOIN support_action_revision revision ON revision.request_id = request.id AND revision.revision_number = request.current_revision_number
                JOIN support_case ON support_case.id = request.support_case_id
                WHERE request.action IN ('ORDER_CANCELLATION', 'PICKUP_RESCHEDULE')
                  AND request.state IN ('AWAITING_SUPPORT_MANAGER', 'AWAITING_OPERATIONS', 'READY_FOR_EXECUTION', 'REASSIGNMENT_REQUIRED')
                  AND support_case.state IN ('OPEN', 'IN_PROGRESS', 'WAITING') AND revision.expires_at > ?
                  $afterClause ORDER BY request.id LIMIT ?
                """.trimIndent(),
                {
                    rs,
                    _,
                    ->
                    StoreCandidateRow(
                        rs.getObject("id", UUID::class.java),
                        rs.getObject("target_id", UUID::class.java),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getLong("target_version"),
                    )
                },
                *args.toTypedArray(),
            )
        val page = rows.take(limit)
        if (page.isEmpty()) return StoreOrderChangeCandidatePage(emptyList(), null)
        val orders = ordering.findOrderSnapshots(page.map { it.orderId }.toSet()).associateBy { it.orderId }
        val eligible =
            page.filter { row ->
                val order =
                    orders[row.orderId]
                        ?: throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Support request order is missing")
                order.storeId == storeId && order.state == SupportOrderState.ACCEPTED && order.version == row.targetVersion
            }
        val displays = if (eligible.isEmpty()) emptyMap() else ordering.findOrderDisplays(eligible.map { it.orderId }.toSet())
        val items =
            eligible.map { row ->
                val request = workflow.storeRequest(actorId, storeId, row.id)
                val display =
                    displays[row.orderId]
                        ?: throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Order display is missing")
                StoreOrderChangeCandidate(
                    row.id,
                    display.publicReference,
                    request.action,
                    request.revisionNumber,
                    row.createdAt,
                    request.expiresAt,
                )
            }
        return StoreOrderChangeCandidatePage(items, next(scope, rows.size > limit, page.lastOrNull()?.id))
    }

    fun consents(
        actorId: UUID,
        requestId: UUID,
        cursor: String?,
        limit: Int,
    ): SupportOrderConsentPage {
        requireLimit(limit)
        val value = workflow.workflow(actorId, requestId)
        if (SupportOrderWorkflowAction.EXECUTE !in
            value.allowedActions
        ) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Current execution permission is required")
        }
        val order =
            value.order
                ?: throw DomainFailure(FailureCode.ACCESS_DENIED, "Current order permission is required")
        if (order.state != SupportOrderState.ACCEPTED || order.version != value.request.targetVersion ||
            value.request.action !in setOf(SupportActionType.ORDER_CANCELLATION, SupportActionType.PICKUP_RESCHEDULE)
        ) {
            throw DomainFailure(FailureCode.RESOURCE_STATE_CONFLICT, "Current request does not require store consent")
        }
        val request =
            requests
                .findById(
                    requestId,
                ).orElseThrow { DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Support request is missing") }
        val scope = scope("support-order-consents", "$actorId|$requestId|${value.request.revisionNumber}|${value.request.requestVersion}")
        val after = cursor?.let { cursors.verify(it, scope).sort }
        val excluded =
            setOfNotNull(
                request.requesterActorId,
                request.executorActorId,
                request.supportApproverActorId,
                request.operationsApproverActorId,
            )
        val args =
            mutableListOf<Any>(
                order.storeId,
                request.action.name,
                Timestamp.from(clock.instant()),
                requestId,
                value.request.revisionNumber,
                value.request.actionPayloadDigest,
                value.request.targetVersion,
                SupportOrderChangeAuthorization.INITIAL_POLICY_VERSION,
            )
        args.addAll(excluded)
        val afterClause = if (after == null) "" else " AND consent.id > ?".also { args += after }
        args += limit + 1
        val rows =
            jdbc.query(
                """
                SELECT consent.id, consent.authorization_type, consent.authorized_at, consent.expires_at,
                       consent.max_successful_uses - (SELECT count(*) FROM support_order_change_authorization_use used WHERE used.authorization_id = consent.id) AS remaining_uses
                FROM support_order_change_authorization consent
                WHERE consent.store_id = ? AND consent.action = ? AND consent.revoked_at IS NULL AND consent.expires_at > ?
                  AND ((consent.authorization_type = 'CONFIRMATION' AND consent.request_id = ? AND consent.revision_number = ? AND consent.action_payload_digest = ? AND consent.target_version = ?)
                       OR (consent.authorization_type = 'DELEGATION' AND consent.policy_version = ?))
                  AND consent.authorized_by_actor_id NOT IN (${excluded.joinToString(",") { "?" }})
                  AND (SELECT count(*) FROM support_order_change_authorization_use used WHERE used.authorization_id = consent.id) < consent.max_successful_uses
                  $afterClause ORDER BY consent.id LIMIT ?
                """.trimIndent(),
                {
                    rs,
                    _,
                    ->
                    SupportOrderConsentCandidate(
                        rs.getObject("id", UUID::class.java),
                        SupportOrderChangeAuthorizationType.valueOf(rs.getString("authorization_type")),
                        rs.getTimestamp("authorized_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getInt("remaining_uses"),
                    )
                },
                *args.toTypedArray(),
            )
        val page = rows.take(limit)
        return SupportOrderConsentPage(page, next(scope, rows.size > limit, page.lastOrNull()?.authorizationId))
    }

    private fun requireLimit(limit: Int) {
        if (limit !in 1..100) throw DomainFailure(FailureCode.INVALID_REQUEST, "Discovery limit must be between 1 and 100")
    }

    private fun scope(
        name: String,
        binding: String,
    ) = SignedCursorScope(name, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binding.toByteArray())), SORT)

    private fun next(
        scope: SignedCursorScope<UUID>,
        more: Boolean,
        last: UUID?,
    ) = if (more &&
        last != null
    ) {
        cursors.issue(scope, last, clock.instant().plus(Duration.ofMinutes(15)))
    } else {
        null
    }

    private data class StoreCandidateRow(
        val id: UUID,
        val orderId: UUID,
        val createdAt: Instant,
        val targetVersion: Long,
    )

    private companion object {
        val SORT =
            object : CursorSortAdapter<UUID> {
                override fun encode(sort: UUID) = listOf(sort.toString())

                override fun decode(values: List<String>) = UUID.fromString(values.single())
            }
    }
}

@Validated
@RestController
internal class SupportOrderDiscoveryController(
    private val service: SupportOrderDiscovery,
) {
    @GetMapping("/api/v1/stores/{storeId}/support-order-change-requests")
    @PreAuthorize("isAuthenticated()")
    fun storeRequests(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @RequestParam(required = false) @Size(max = 2048) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ): ResponseEntity<StoreOrderChangeCandidatePage> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.storeRequests(actor.actorId, storeId, cursor, limit))

    @GetMapping("/api/v1/support/action-requests/{requestId}/store-consents")
    @PreAuthorize("isAuthenticated()")
    fun consents(
        actor: OperatorActor,
        @PathVariable requestId: UUID,
        @RequestParam(required = false) @Size(max = 2048) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ): ResponseEntity<SupportOrderConsentPage> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.consents(actor.actorId, requestId, cursor, limit))
}

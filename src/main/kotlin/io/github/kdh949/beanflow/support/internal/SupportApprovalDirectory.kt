package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.operations.api.OperatorDirectoryOperations
import io.github.kdh949.beanflow.operations.api.OperatorDisplay
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OperatorActor
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import io.github.kdh949.beanflow.support.internal.domain.SupportInquiryCategory
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
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

internal enum class SupportApprovalKind { DATA_ACCESS, BREAK_GLASS, ORDER_ACTION, COMPENSATION, PROFILE_CHANGE }

internal enum class SupportApprovalView { REVIEW, VISIBLE }

internal enum class SupportApprovalReviewAction { DECIDE, REVIEW }

internal data class SupportApprovalItem(
    val kind: SupportApprovalKind,
    val requestId: UUID,
    val caseId: UUID,
    val caseCategory: SupportInquiryCategory,
    val caseOpenedAt: Instant,
    val purpose: String,
    val state: String,
    val createdAt: Instant,
    val reviewAction: SupportApprovalReviewAction?,
)

internal data class SupportApprovalPage(
    val items: List<SupportApprovalItem>,
    val nextCursor: String?,
)

internal data class SupportApprovalCandidate(
    val kind: SupportApprovalKind,
    val id: UUID,
    val caseId: UUID,
    val category: SupportInquiryCategory,
    val openedAt: Instant,
    val createdAt: Instant,
)

internal data class SupportApprovalSort(
    val createdAt: Instant,
    val id: UUID,
    val kind: SupportApprovalKind,
)

internal data class SupportApprovalHistorySort(
    val occurredAt: Instant,
    val id: UUID,
)

internal data class SupportApprovalDecisionRow(
    val id: UUID,
    val step: String,
    val state: String,
    val actorId: UUID?,
    val revision: Int?,
    val occurredAt: Instant,
)

internal data class SupportApprovalDecisionItem(
    val eventId: UUID,
    val step: String,
    val state: String,
    val actorDisplay: OperatorDisplay?,
    val revisionNumber: Int?,
    val occurredAt: Instant,
)

internal data class SupportApprovalHistoryPage(
    val items: List<SupportApprovalDecisionItem>,
    val nextCursor: String?,
)

internal data class SupportApprovalInspection(
    val item: SupportApprovalItem,
    val actionRequestId: UUID?,
)

@Service
internal class SupportApprovalDirectoryQuery(
    private val jdbc: JdbcTemplate,
    private val permissions: OperatorPermissionAuthorization,
) {
    @Transactional
    fun availableKinds(actorId: UUID): Set<SupportApprovalKind> {
        fun has(permission: OperatorPermission) = permissions.hasActive(actorId, permission)
        val caseRead = has(OperatorPermission.SUPPORT_CASE_READ)
        return SupportApprovalKind.entries
            .filter { kind ->
                when (kind) {
                    SupportApprovalKind.DATA_ACCESS -> {
                        has(OperatorPermission.SUPPORT_PII_REVEAL_APPROVE)
                    }

                    SupportApprovalKind.BREAK_GLASS -> {
                        has(OperatorPermission.SUPPORT_PII_REVEAL_APPROVE) ||
                            has(OperatorPermission.PRIVACY_BREAK_GLASS_REVIEW)
                    }

                    SupportApprovalKind.ORDER_ACTION -> {
                        caseRead && has(OperatorPermission.SUPPORT_ACTION_APPROVE) &&
                            has(OperatorPermission.SUPPORT_ORDER_READ)
                    }

                    SupportApprovalKind.COMPENSATION -> {
                        caseRead && has(OperatorPermission.SUPPORT_COMPENSATION_APPROVE)
                    }

                    SupportApprovalKind.PROFILE_CHANGE -> {
                        caseRead && has(OperatorPermission.SUPPORT_PROFILE_R3_APPROVE) &&
                            has(OperatorPermission.SUPPORT_ACTION_APPROVE)
                    }
                }
            }.toSet()
    }

    fun scan(
        kinds: Set<SupportApprovalKind>,
        after: SupportApprovalSort?,
        limit: Int,
    ): List<SupportApprovalCandidate> {
        val args = mutableListOf<Any>()
        args.addAll(kinds.map { it.name }.sorted())
        val filter =
            if (after ==
                null
            ) {
                ""
            } else {
                args.addAll(listOf(Timestamp.from(after.createdAt), after.id, after.kind.name))
                "AND (r.created_at, r.id, r.kind) < (?, ?, ?)"
            }
        args += limit
        return jdbc.query(
            "$CANDIDATES WHERE r.kind IN (${kinds.joinToString(
                ",",
            ) { "?" }}) $filter ORDER BY r.created_at DESC, r.id DESC, r.kind DESC LIMIT ?",
            ::candidate,
            *args.toTypedArray(),
        )
    }

    fun find(
        kind: SupportApprovalKind,
        id: UUID,
    ): SupportApprovalCandidate? = jdbc.query("$CANDIDATES WHERE r.kind = ? AND r.id = ?", ::candidate, kind.name, id).singleOrNull()

    fun history(
        kind: SupportApprovalKind,
        id: UUID,
        actionRequestId: UUID?,
        after: SupportApprovalHistorySort?,
        limit: Int,
    ): List<SupportApprovalDecisionRow> {
        val source =
            when (kind) {
                SupportApprovalKind.DATA_ACCESS -> {
                    "SELECT id, 'DECISION' AS step, decision AS state, actor_id, NULL::integer AS revision, decided_at FROM support_data_access_grant_decision WHERE grant_id = ?"
                }

                SupportApprovalKind.BREAK_GLASS -> {
                    "SELECT id, decision_type AS step, decision AS state, actor_id, NULL::integer AS revision, decided_at FROM support_break_glass_decision WHERE request_id = ?"
                }

                else -> {
                    if (actionRequestId == null) return emptyList() // A request that never required approval has no approval history.
                    "SELECT id, step_type AS step, state, decided_by_actor_id AS actor_id, revision_number AS revision, decided_at FROM support_action_approval_step WHERE request_id = ?"
                }
            }
        val args =
            mutableListOf<Any>(
                if (kind in
                    setOf(SupportApprovalKind.DATA_ACCESS, SupportApprovalKind.BREAK_GLASS)
                ) {
                    id
                } else {
                    requireNotNull(actionRequestId)
                },
            )
        val filter =
            if (after ==
                null
            ) {
                ""
            } else {
                args.addAll(listOf(Timestamp.from(after.occurredAt), after.id))
                "WHERE (decided_at, id) < (?, ?)"
            }
        args += limit
        return jdbc.query(
            "SELECT * FROM ($source) history $filter ORDER BY decided_at DESC, id DESC LIMIT ?",
            { rs, _ ->
                SupportApprovalDecisionRow(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("step"),
                    rs.getString("state"),
                    rs.getObject("actor_id", UUID::class.java),
                    rs.getObject("revision", Int::class.javaObjectType),
                    rs.getTimestamp("decided_at").toInstant(),
                )
            },
            *args.toTypedArray(),
        )
    }

    private fun candidate(
        rs: ResultSet,
        row: Int,
    ) = SupportApprovalCandidate(
        SupportApprovalKind.valueOf(rs.getString("kind")),
        rs.getObject("id", UUID::class.java),
        rs.getObject("support_case_id", UUID::class.java),
        SupportInquiryCategory.valueOf(rs.getString("category")),
        rs.getTimestamp("opened_at").toInstant(),
        rs.getTimestamp("created_at").toInstant(),
    )

    private companion object {
        const val CANDIDATES = """WITH requests AS (
            SELECT 'DATA_ACCESS' AS kind, id, support_case_id, requested_at AS created_at FROM support_data_access_grant
            UNION ALL SELECT 'BREAK_GLASS', id, support_case_id, requested_at FROM support_break_glass_request
            UNION ALL SELECT 'ORDER_ACTION', id, support_case_id, created_at FROM support_action_request WHERE action IN ('ORDER_CANCELLATION', 'PICKUP_RESCHEDULE', 'POST_ACCEPTANCE_RESOLUTION')
            UNION ALL SELECT 'COMPENSATION', id, support_case_id, created_at FROM support_compensation_request
            UNION ALL SELECT 'PROFILE_CHANGE', id, support_case_id, created_at FROM support_profile_change
        ) SELECT r.*, c.category, c.opened_at FROM requests r JOIN support_case c ON c.id = r.support_case_id"""
    }
}

/** Reuses the current workflow inspection, including its existing expiration/authority convergence. */
@Service
internal class SupportApprovalInspectionService(
    private val grants: DataAccessGrantApplicationService,
    private val emergencies: BreakGlassApplicationService,
    private val orders: SupportOrderWorkflowQuery,
    private val compensations: SupportCompensationWorkflowService,
    private val profiles: SupportProfileWorkflowQuery,
) {
    fun inspect(
        actorId: UUID,
        candidate: SupportApprovalCandidate,
    ): SupportApprovalInspection {
        fun result(
            caseId: UUID,
            purpose: String,
            state: String,
            review: SupportApprovalReviewAction?,
            approvalId: UUID? = null,
        ): SupportApprovalInspection {
            if (caseId != candidate.caseId) throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Approval case binding is invalid")
            return SupportApprovalInspection(
                SupportApprovalItem(
                    candidate.kind,
                    candidate.id,
                    caseId,
                    candidate.category,
                    candidate.openedAt,
                    purpose,
                    state,
                    candidate.createdAt,
                    review,
                ),
                approvalId,
            )
        }
        return when (candidate.kind) {
            SupportApprovalKind.DATA_ACCESS -> {
                grants.get(actorId, candidate.id).let {
                    result(
                        it.grant.caseId,
                        it.grant.purpose.name,
                        it.grant.state.name,
                        SupportApprovalReviewAction.DECIDE.takeIf { _ ->
                            it.viewerRole ==
                                DataAccessGrantViewerRole.APPROVER &&
                                it.grant.state.name == "APPROVAL_PENDING"
                        },
                    )
                }
            }

            SupportApprovalKind.BREAK_GLASS -> {
                emergencies.workflow(actorId, candidate.id).let {
                    result(
                        it.request.caseId,
                        it.request.purpose.name,
                        it.request.state.name,
                        when {
                            BreakGlassWorkflowAction.DECIDE in
                                it.allowedActions -> SupportApprovalReviewAction.DECIDE

                            BreakGlassWorkflowAction.REVIEW in it.allowedActions -> SupportApprovalReviewAction.REVIEW

                            else -> null
                        },
                    )
                }
            }

            SupportApprovalKind.ORDER_ACTION -> {
                orders.workflow(actorId, candidate.id).let {
                    result(
                        it.request.caseId,
                        it.request.action.name,
                        it.request.state.name,
                        SupportApprovalReviewAction.DECIDE.takeIf { _ ->
                            SupportOrderWorkflowAction.DECIDE_SUPPORT_MANAGER in
                                it.allowedActions
                        },
                        it.request.requestId,
                    )
                }
            }

            SupportApprovalKind.COMPENSATION -> {
                compensations.get(actorId, candidate.id).let {
                    result(
                        it.request.supportCaseId,
                        it.request.benefitType.name,
                        it.request.state.name,
                        SupportApprovalReviewAction.DECIDE.takeIf { _ ->
                            SupportCompensationWorkflowAction.DECIDE_SUPPORT_MANAGER in
                                it.allowedActions
                        },
                        it.approval?.requestId,
                    )
                }
            }

            SupportApprovalKind.PROFILE_CHANGE -> {
                profiles.workflow(actorId, candidate.id).let {
                    result(
                        it.profileChange.caseId,
                        it.profileChange.purpose.name,
                        it.profileChange.state.name,
                        SupportApprovalReviewAction.DECIDE.takeIf { _ ->
                            SupportProfileWorkflowAction.DECIDE_SUPPORT_MANAGER in
                                it.allowedActions
                        },
                        it.approval?.requestId,
                    )
                }
            }
        }
    }
}

@Service
internal class SupportApprovalDirectoryService(
    private val query: SupportApprovalDirectoryQuery,
    private val inspections: SupportApprovalInspectionService,
    private val operators: OperatorDirectoryOperations,
    private val cursors: SignedCursorCodec,
    private val clock: Clock,
) {
    fun list(
        actorId: UUID,
        kind: SupportApprovalKind?,
        view: SupportApprovalView,
        cursor: String?,
        limit: Int,
    ): SupportApprovalPage {
        requireLimit(limit)
        val kinds = authorize(actorId, kind)
        val scope = SignedCursorScope("support-approvals", binding("$actorId|$kind|$view|${kinds.sortedBy { it.name }}"), LIST_SORT)
        val after = cursor?.let { cursors.verify(it, scope).sort }
        val rows = query.scan(kinds, after, limit + 1)
        val page = rows.take(limit)
        val items =
            page.mapNotNull { candidate ->
                try {
                    inspections.inspect(actorId, candidate).item.takeIf { view == SupportApprovalView.VISIBLE || it.reviewAction != null }
                } catch (
                    failure: DomainFailure,
                ) {
                    if (failure.code == FailureCode.ACCESS_DENIED) null else throw failure
                }
            }
        if (authorize(actorId, kind) != kinds) denied()
        return SupportApprovalPage(
            items,
            if (rows.size >
                limit
            ) {
                page.last().let { cursors.issue(scope, SupportApprovalSort(it.createdAt, it.id, it.kind), expiry()) }
            } else {
                null
            },
        )
    }

    fun history(
        actorId: UUID,
        kind: SupportApprovalKind,
        id: UUID,
        cursor: String?,
        limit: Int,
    ): SupportApprovalHistoryPage {
        requireLimit(limit)
        authorize(actorId, kind)
        val candidate = query.find(kind, id) ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Approval request was not found")
        val inspected = inspections.inspect(actorId, candidate)
        val scope = SignedCursorScope("support-approval-history", binding("$actorId|$kind|$id|${inspected.actionRequestId}"), HISTORY_SORT)
        val after = cursor?.let { cursors.verify(it, scope).sort }
        val rows = query.history(kind, id, inspected.actionRequestId, after, limit + 1)
        val page = rows.take(limit)
        val displays = operators.displays(page.mapNotNull { it.actorId }.toSet())
        authorize(actorId, kind)
        val rechecked = inspections.inspect(actorId, candidate)
        if (rechecked.actionRequestId !=
            inspected.actionRequestId
        ) {
            throw DomainFailure(FailureCode.RESOURCE_STATE_CONFLICT, "Approval request binding changed")
        }
        val items =
            page.map {
                SupportApprovalDecisionItem(it.id, it.step, it.state, it.actorId?.let(displays::getValue), it.revision, it.occurredAt)
            }
        val next =
            if (rows.size >
                limit
            ) {
                page.last().let { cursors.issue(scope, SupportApprovalHistorySort(it.occurredAt, it.id), expiry()) }
            } else {
                null
            }
        return SupportApprovalHistoryPage(items, next)
    }

    private fun authorize(
        actorId: UUID,
        kind: SupportApprovalKind?,
    ): Set<SupportApprovalKind> {
        val available = query.availableKinds(actorId)
        if (available.isEmpty() || (kind != null && kind !in available)) denied()
        return kind?.let(::setOf) ?: available
    }

    private fun requireLimit(limit: Int) {
        if (limit !in
            1..100
        ) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Approval limit must be between 1 and 100")
        }
    }

    private fun denied(): Nothing = throw DomainFailure(FailureCode.ACCESS_DENIED, "Approval directory permission is required")

    private fun binding(value: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    private fun expiry() = clock.instant().plus(Duration.ofMinutes(15))

    private companion object {
        val LIST_SORT =
            object : CursorSortAdapter<SupportApprovalSort> {
                override fun encode(sort: SupportApprovalSort) = listOf(sort.createdAt.toString(), sort.id.toString(), sort.kind.name)

                override fun decode(values: List<String>) =
                    SupportApprovalSort(Instant.parse(values[0]), UUID.fromString(values[1]), SupportApprovalKind.valueOf(values[2])).also {
                        require(values.size == 3)
                    }
            }
        val HISTORY_SORT =
            object : CursorSortAdapter<SupportApprovalHistorySort> {
                override fun encode(sort: SupportApprovalHistorySort) = listOf(sort.occurredAt.toString(), sort.id.toString())

                override fun decode(values: List<String>) =
                    SupportApprovalHistorySort(Instant.parse(values[0]), UUID.fromString(values[1])).also {
                        require(values.size == 2)
                    }
            }
    }
}

@Validated
@RestController
internal class SupportApprovalDirectoryController(
    private val service: SupportApprovalDirectoryService,
) {
    @GetMapping("/api/v1/support/approval-tasks")
    @PreAuthorize("isAuthenticated()")
    fun list(
        actor: OperatorActor,
        @RequestParam(required = false) kind: SupportApprovalKind?,
        @RequestParam(defaultValue = "REVIEW") view: SupportApprovalView,
        @RequestParam(required = false) @Size(max = 2048) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ): ResponseEntity<SupportApprovalPage> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(actor.actorId, kind, view, cursor, limit))

    @GetMapping("/api/v1/support/approval-tasks/{kind}/{requestId}/history")
    @PreAuthorize("isAuthenticated()")
    fun history(
        actor: OperatorActor,
        @PathVariable kind: SupportApprovalKind,
        @PathVariable requestId: UUID,
        @RequestParam(required = false) @Size(max = 2048) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ): ResponseEntity<SupportApprovalHistoryPage> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.history(actor.actorId, kind, requestId, cursor, limit))
}

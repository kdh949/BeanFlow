package io.github.kdh949.beanflow.support.internal

import com.fasterxml.jackson.annotation.JsonInclude
import io.github.kdh949.beanflow.identity.api.CustomerSupportProfileQueryOperations
import io.github.kdh949.beanflow.merchant.api.StoreSupportProfileQueryOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.ordering.api.OrderingSupportTimelineOperations
import io.github.kdh949.beanflow.ordering.api.SupportOrderOverviewSnapshot
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OperatorActor
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import io.github.kdh949.beanflow.support.internal.domain.SupportCasePriority
import io.github.kdh949.beanflow.support.internal.domain.SupportCaseState
import io.github.kdh949.beanflow.support.internal.domain.SupportInquiryCategory
import jakarta.persistence.PersistenceException
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionException
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal enum class SupportCaseQueueScope { MINE, ALL }
internal enum class SupportApprovalTaskScope { MINE, ALL }
internal enum class SupportApprovalTaskType { DATA_ACCESS_GRANT, BREAK_GLASS, SUPPORT_ACTION, COMPENSATION, PROFILE_CHANGE }
internal enum class SupportApprovalAction { APPROVE, DENY, RETURN_FOR_REVISION, REASSIGN, EXECUTE, REVIEW }

internal data class SupportCaseQueueSummaryResource(
    val active: Long,
    val open: Long,
    val inProgress: Long,
    val waiting: Long,
    val urgent: Long,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class SupportMaskedSubjectResource(
    val subjectType: SupportSubjectType,
    val subjectId: UUID,
    val maskedDisplayName: String?,
    val maskedMatchedValue: String?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class SupportCaseQueueItemResource(
    val caseId: UUID,
    val state: SupportCaseState,
    val priority: SupportCasePriority,
    val category: SupportInquiryCategory,
    val assigneeId: UUID,
    val version: Long,
    val openedAt: Instant,
    val latestChangedAt: Instant,
    val latestChannel: SupportInteractionChannel?,
    val primarySubject: SupportMaskedSubjectResource?,
)

internal data class SupportCaseQueuePageResource(
    val items: List<SupportCaseQueueItemResource>,
    val nextCursor: String?,
)

internal data class SupportCaseOverviewResource(
    val case: SupportCaseQueueItemResource,
    val subjects: List<SupportMaskedSubjectResource>,
    val orders: List<SupportOrderOverviewResource>,
    val availableSections: List<String>,
)

internal data class SupportOrderLineOverviewResource(
    val sequence: Int,
    val menuName: String,
    val quantity: Long,
    val amountKrw: Long,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class SupportOrderOverviewResource(
    val orderId: UUID,
    val publicReference: String,
    val state: String,
    val version: Long,
    val orderedAt: Instant,
    val pickupWindowStart: Instant,
    val pickupWindowEnd: Instant,
    val storeName: String,
    val subtotalKrw: Long,
    val couponDiscountKrw: Long,
    val pointsAppliedKrw: Long,
    val payableKrw: Long,
    val currency: String,
    val paymentState: String,
    val paidAt: Instant?,
    val customer: SupportMaskedSubjectResource?,
    val store: SupportMaskedSubjectResource?,
    val lines: List<SupportOrderLineOverviewResource>,
)

internal data class SupportApprovalTaskResource(
    val taskType: SupportApprovalTaskType,
    val resourceId: UUID,
    val caseId: UUID,
    val state: String,
    val version: Long,
    val revision: Int?,
    val requesterActorId: UUID,
    val executorActorId: UUID?,
    val updatedAt: Instant,
    val allowedActions: List<SupportApprovalAction>,
)

internal data class SupportApprovalTaskPageResource(
    val items: List<SupportApprovalTaskResource>,
    val nextCursor: String?,
)

internal data class SupportApprovalTaskDetailResource(
    val task: SupportApprovalTaskResource,
    val lineage: List<SupportApprovalLineageResource>,
)

internal data class SupportApprovalLineageResource(
    val step: String,
    val state: String,
    val actorId: UUID?,
    val occurredAt: Instant,
)

internal data class SupportApprovalTimelineItemResource(
    val eventId: UUID,
    val eventType: String,
    val state: String,
    val actorId: UUID?,
    val occurredAt: Instant,
)

internal data class SupportApprovalTimelinePageResource(
    val items: List<SupportApprovalTimelineItemResource>,
    val nextCursor: String?,
)

internal data class SupportCompensationListItemResource(
    val requestId: UUID,
    val caseId: UUID,
    val benefitType: String,
    val amountKrw: Long,
    val band: String,
    val state: String,
    val notificationState: String,
    val version: Long,
    val updatedAt: Instant,
)

internal data class SupportCompensationPageResource(
    val items: List<SupportCompensationListItemResource>,
    val nextCursor: String?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class SupportProfileChangeListItemResource(
    val profileChangeId: UUID,
    val caseId: UUID,
    val subjectType: String,
    val purpose: String,
    val riskClass: String,
    val state: String,
    val notificationState: String,
    val maskedBefore: String?,
    val maskedAfter: String?,
    val version: Long,
    val updatedAt: Instant,
)

internal data class SupportProfileChangePageResource(
    val items: List<SupportProfileChangeListItemResource>,
    val nextCursor: String?,
)

internal data class ConsoleSort(val changedAt: Instant, val id: UUID)

internal data class QueueProjection(
    val caseId: UUID,
    val state: SupportCaseState,
    val priority: SupportCasePriority,
    val category: SupportInquiryCategory,
    val assigneeId: UUID,
    val version: Long,
    val openedAt: Instant,
    val latestChangedAt: Instant,
    val latestChannel: SupportInteractionChannel?,
    val subjectType: SupportSubjectType?,
    val subjectId: UUID?,
)

internal data class ApprovalProjection(
    val taskType: SupportApprovalTaskType,
    val resourceId: UUID,
    val caseId: UUID,
    val state: String,
    val version: Long,
    val revision: Int?,
    val requesterActorId: UUID,
    val executorActorId: UUID?,
    val updatedAt: Instant,
)

@Repository
internal class SupportConsoleQueryRepository(private val jdbc: JdbcTemplate) {
    fun summary(actorId: UUID): SupportCaseQueueSummaryResource =
        jdbc.queryForObject(
            """
            SELECT count(*) FILTER (WHERE state <> 'CLOSED') AS active,
                   count(*) FILTER (WHERE state = 'OPEN') AS open,
                   count(*) FILTER (WHERE state = 'IN_PROGRESS') AS in_progress,
                   count(*) FILTER (WHERE state = 'WAITING') AS waiting,
                   count(*) FILTER (WHERE state <> 'CLOSED' AND priority = 'URGENT') AS urgent
              FROM support_case
             WHERE current_assignee_id = ?
            """.trimIndent(),
            { rs, _ -> SupportCaseQueueSummaryResource(rs.getLong("active"), rs.getLong("open"), rs.getLong("in_progress"), rs.getLong("waiting"), rs.getLong("urgent")) },
            actorId,
        )

    fun queue(
        actorId: UUID?,
        state: SupportCaseState?,
        priority: SupportCasePriority?,
        category: SupportInquiryCategory?,
        after: ConsoleSort?,
        limit: Int,
    ): List<QueueProjection> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any>()
        actorId?.let { clauses += "c.current_assignee_id = ?"; args += it }
        state?.let { clauses += "c.state = ?"; args += it.name }
        priority?.let { clauses += "c.priority = ?"; args += it.name }
        category?.let { clauses += "c.category = ?"; args += it.name }
        after?.let {
            clauses += "(c.last_changed_at < ? OR (c.last_changed_at = ? AND c.id < ?))"
            args += Timestamp.from(it.changedAt); args += Timestamp.from(it.changedAt); args += it.id
        }
        args += limit
        val where = if (clauses.isEmpty()) "" else " WHERE ${clauses.joinToString(" AND ")}"
        return jdbc.query(
            """
            SELECT c.id, c.state, c.priority, c.category, c.current_assignee_id, c.version,
                   c.opened_at, c.last_changed_at, latest.channel AS latest_channel,
                   subject.subject_type, subject.subject_id
              FROM support_case c
              LEFT JOIN LATERAL (
                    SELECT i.channel FROM support_case_interaction i
                     WHERE i.support_case_id = c.id ORDER BY i.occurred_at DESC, i.id DESC LIMIT 1
              ) latest ON true
              LEFT JOIN LATERAL (
                    SELECT l.subject_type, l.subject_id FROM support_case_subject_link l
                     WHERE l.support_case_id = c.id AND l.unlinked_at IS NULL
                     ORDER BY CASE l.relationship WHEN 'REQUESTER' THEN 0 ELSE 1 END, l.linked_at, l.id LIMIT 1
              ) subject ON true
              $where
             ORDER BY c.last_changed_at DESC, c.id DESC
             LIMIT ?
            """.trimIndent(),
            ::queueProjection,
            *args.toTypedArray(),
        )
    }

    fun case(caseId: UUID): QueueProjection? =
        jdbc.query(
            """
            SELECT c.id, c.state, c.priority, c.category, c.current_assignee_id, c.version,
                   c.opened_at, c.last_changed_at, latest.channel AS latest_channel,
                   subject.subject_type, subject.subject_id
              FROM support_case c
              LEFT JOIN LATERAL (
                    SELECT i.channel FROM support_case_interaction i
                     WHERE i.support_case_id = c.id ORDER BY i.occurred_at DESC, i.id DESC LIMIT 1
              ) latest ON true
              LEFT JOIN LATERAL (
                    SELECT l.subject_type, l.subject_id FROM support_case_subject_link l
                     WHERE l.support_case_id = c.id AND l.unlinked_at IS NULL
                     ORDER BY CASE l.relationship WHEN 'REQUESTER' THEN 0 ELSE 1 END, l.linked_at, l.id LIMIT 1
              ) subject ON true
             WHERE c.id = ?
            """.trimIndent(),
            ::queueProjection,
            caseId,
        ).singleOrNull()

    fun subjects(caseId: UUID): List<Pair<SupportSubjectType, UUID>> =
        jdbc.query(
            """SELECT subject_type, subject_id FROM support_case_subject_link
                WHERE support_case_id = ? AND unlinked_at IS NULL ORDER BY linked_at, id""",
            { rs, _ -> SupportSubjectType.valueOf(rs.getString("subject_type")) to rs.getObject("subject_id", UUID::class.java) },
            caseId,
        )

    fun hasActiveOrderLink(caseId: UUID, orderId: UUID): Boolean =
        jdbc.queryForObject(
            """SELECT EXISTS(SELECT 1 FROM support_case_subject_link
                 WHERE support_case_id = ? AND subject_type = 'ORDER' AND subject_id = ? AND unlinked_at IS NULL)""",
            Boolean::class.java,
            caseId,
            orderId,
        ) == true

    fun approvalTasks(
        types: Set<SupportApprovalTaskType>,
        assigneeId: UUID?,
        state: String?,
        after: ConsoleSort?,
        limit: Int,
    ): List<ApprovalProjection> {
        if (types.isEmpty()) return emptyList()
        val typeNames = types.map { it.name }.sorted()
        val args = mutableListOf<Any>()
        val typePlaceholders = typeNames.joinToString(",") { "?" }
        args.addAll(typeNames)
        val clauses = mutableListOf("task_type IN ($typePlaceholders)")
        state?.let { clauses += "task_state = ?"; args += it }
        assigneeId?.let { clauses += "case_assignee_id = ?"; args += it }
        after?.let {
            clauses += "(updated_at < ? OR (updated_at = ? AND resource_id < ?))"
            args += Timestamp.from(it.changedAt); args += Timestamp.from(it.changedAt); args += it.id
        }
        args += limit
        return jdbc.query(
            """
            WITH tasks AS (
              SELECT 'DATA_ACCESS_GRANT' task_type, g.id resource_id, g.support_case_id, g.state task_state,
                     g.version, NULL::integer revision, g.requester_id requester_actor_id,
                     NULL::uuid executor_actor_id, g.requested_at updated_at
                FROM support_data_access_grant g
              UNION ALL
              SELECT 'BREAK_GLASS', b.id, b.support_case_id, b.state, b.version, NULL::integer,
                     b.requester_id, NULL::uuid, b.requested_at
                FROM support_break_glass_request b
              UNION ALL
              SELECT CASE r.target_type WHEN 'COMPENSATION_REQUEST' THEN 'COMPENSATION'
                        WHEN 'PROFILE_CHANGE_REQUEST' THEN 'PROFILE_CHANGE' ELSE 'SUPPORT_ACTION' END,
                     r.target_id, r.support_case_id, r.state, r.version, r.current_revision_number,
                     r.requester_actor_id, r.executor_actor_id, r.updated_at
                FROM support_action_request r
            )
            SELECT tasks.*, c.current_assignee_id case_assignee_id
              FROM tasks JOIN support_case c ON c.id = tasks.support_case_id
             WHERE ${clauses.joinToString(" AND ")}
             ORDER BY updated_at DESC, resource_id DESC
             LIMIT ?
            """.trimIndent(),
            ::approvalProjection,
            *args.toTypedArray(),
        )
    }

    fun approvalTask(type: SupportApprovalTaskType, resourceId: UUID): ApprovalProjection? =
        jdbc.query(
            """
            WITH tasks AS (
              SELECT 'DATA_ACCESS_GRANT' task_type, g.id resource_id, g.support_case_id, g.state task_state,
                     g.version, NULL::integer revision, g.requester_id requester_actor_id,
                     NULL::uuid executor_actor_id, g.requested_at updated_at
                FROM support_data_access_grant g
              UNION ALL
              SELECT 'BREAK_GLASS', b.id, b.support_case_id, b.state, b.version, NULL::integer,
                     b.requester_id, NULL::uuid, b.requested_at
                FROM support_break_glass_request b
              UNION ALL
              SELECT CASE r.target_type WHEN 'COMPENSATION_REQUEST' THEN 'COMPENSATION'
                        WHEN 'PROFILE_CHANGE_REQUEST' THEN 'PROFILE_CHANGE' ELSE 'SUPPORT_ACTION' END,
                     r.target_id, r.support_case_id, r.state, r.version, r.current_revision_number,
                     r.requester_actor_id, r.executor_actor_id, r.updated_at
                FROM support_action_request r
            )
            SELECT tasks.*, c.current_assignee_id case_assignee_id
              FROM tasks JOIN support_case c ON c.id = tasks.support_case_id
             WHERE task_type = ? AND resource_id = ?
            """.trimIndent(),
            ::approvalProjection,
            type.name,
            resourceId,
        ).singleOrNull()

    fun approvalTimeline(type: SupportApprovalTaskType, resourceId: UUID): List<SupportApprovalTimelineItemResource> {
        val task = approvalTask(type, resourceId) ?: return emptyList()
        return when (type) {
            SupportApprovalTaskType.DATA_ACCESS_GRANT -> jdbc.query(
                """SELECT id, 'DECISION' event_type, decision state, actor_id, decided_at occurred_at
                     FROM support_data_access_grant_decision WHERE grant_id = ? ORDER BY decided_at, id""",
                ::timelineItem, resourceId,
            )
            SupportApprovalTaskType.BREAK_GLASS -> jdbc.query(
                """SELECT id, decision_type event_type, decision state, actor_id, decided_at occurred_at
                     FROM support_break_glass_decision WHERE request_id = ? ORDER BY decided_at, id""",
                ::timelineItem, resourceId,
            )
            else -> jdbc.query(
                """SELECT s.id, s.step_type event_type, s.state, s.decided_by_actor_id actor_id, s.decided_at occurred_at
                     FROM support_action_approval_step s JOIN support_action_request r ON r.id = s.request_id
                    WHERE r.support_case_id = ? AND r.target_id = ? ORDER BY s.decided_at, s.id""",
                ::timelineItem, task.caseId, resourceId,
            )
        }
    }

    fun compensationPage(caseId: UUID?, state: String?, after: ConsoleSort?, limit: Int): List<SupportCompensationListItemResource> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any>()
        caseId?.let { clauses += "support_case_id = ?"; args += it }
        state?.let { clauses += "state = ?"; args += it }
        after?.let { clauses += "(updated_at < ? OR (updated_at = ? AND id < ?))"; args += Timestamp.from(it.changedAt); args += Timestamp.from(it.changedAt); args += it.id }
        args += limit
        val where = if (clauses.isEmpty()) "" else " WHERE ${clauses.joinToString(" AND ")}"
        return jdbc.query(
            """SELECT id, support_case_id, benefit_type, amount_krw, band, state,
                       CASE WHEN notification_failure_code IS NOT NULL THEN 'MANUAL_REVIEW'
                            WHEN notification_delivery_id IS NOT NULL THEN 'ACCEPTED' ELSE 'NOT_REQUESTED' END notification_state,
                       version, updated_at
                  FROM support_compensation_request$where ORDER BY updated_at DESC, id DESC LIMIT ?""",
            { rs, _ -> SupportCompensationListItemResource(rs.uuid("id"), rs.uuid("support_case_id"), rs.getString("benefit_type"), rs.getLong("amount_krw"), rs.getString("band"), rs.getString("state"), rs.getString("notification_state"), rs.getLong("version"), rs.instant("updated_at")) },
            *args.toTypedArray(),
        )
    }

    fun profileChangePage(caseId: UUID?, state: String?, after: ConsoleSort?, limit: Int): List<SupportProfileChangeListItemResource> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any>()
        caseId?.let { clauses += "support_case_id = ?"; args += it }
        state?.let { clauses += "state = ?"; args += it }
        after?.let { clauses += "(updated_at < ? OR (updated_at = ? AND id < ?))"; args += Timestamp.from(it.changedAt); args += Timestamp.from(it.changedAt); args += it.id }
        args += limit
        val where = if (clauses.isEmpty()) "" else " WHERE ${clauses.joinToString(" AND ")}"
        return jdbc.query(
            """SELECT id, support_case_id, subject_type, purpose, risk_class, state, notification_state,
                       masked_before, masked_after, version, updated_at
                  FROM support_profile_change$where ORDER BY updated_at DESC, id DESC LIMIT ?""",
            { rs, _ -> SupportProfileChangeListItemResource(rs.uuid("id"), rs.uuid("support_case_id"), rs.getString("subject_type"), rs.getString("purpose"), rs.getString("risk_class"), rs.getString("state"), rs.getString("notification_state"), rs.getString("masked_before"), rs.getString("masked_after"), rs.getLong("version"), rs.instant("updated_at")) },
            *args.toTypedArray(),
        )
    }

    private fun queueProjection(rs: ResultSet, ignored: Int) = QueueProjection(
        rs.uuid("id"), SupportCaseState.valueOf(rs.getString("state")), SupportCasePriority.valueOf(rs.getString("priority")),
        SupportInquiryCategory.valueOf(rs.getString("category")), rs.uuid("current_assignee_id"), rs.getLong("version"),
        rs.instant("opened_at"), rs.instant("last_changed_at"), rs.getString("latest_channel")?.let(SupportInteractionChannel::valueOf),
        rs.getString("subject_type")?.let(SupportSubjectType::valueOf), rs.getObject("subject_id", UUID::class.java),
    )

    private fun approvalProjection(rs: ResultSet, ignored: Int) = ApprovalProjection(
        SupportApprovalTaskType.valueOf(rs.getString("task_type")), rs.uuid("resource_id"), rs.uuid("support_case_id"),
        rs.getString("task_state"), rs.getLong("version"), rs.getObject("revision", Int::class.javaObjectType),
        rs.uuid("requester_actor_id"), rs.getObject("executor_actor_id", UUID::class.java), rs.instant("updated_at"),
    )

    private fun timelineItem(rs: ResultSet, ignored: Int) = SupportApprovalTimelineItemResource(
        rs.uuid("id"), rs.getString("event_type"), rs.getString("state"), rs.getObject("actor_id", UUID::class.java), rs.instant("occurred_at"),
    )

    private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)
    private fun ResultSet.instant(column: String): Instant = getTimestamp(column).toInstant()
}

@Service
internal class SupportConsoleQueryService(
    private val repository: SupportConsoleQueryRepository,
    private val permissions: OperatorPermissionAuthorization,
    private val customerProfiles: CustomerSupportProfileQueryOperations,
    private val storeProfiles: StoreSupportProfileQueryOperations,
    private val orders: OrderingSupportTimelineOperations,
    private val cursors: SignedCursorCodec,
    private val clock: Clock,
) {
    @Transactional
    fun queueSummary(actorId: UUID): SupportCaseQueueSummaryResource = boundary {
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
        repository.summary(actorId)
    }

    @Transactional
    fun queue(actorId: UUID, scope: SupportCaseQueueScope, state: SupportCaseState?, priority: SupportCasePriority?, category: SupportInquiryCategory?, cursor: String?, limit: Int?): SupportCaseQueuePageResource = boundary {
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
        if (scope == SupportCaseQueueScope.ALL) permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_ASSIGN)
        val size = normalizedLimit(limit)
        val cursorScope = cursorScope("support/case-queue", "scope=$scope|state=$state|priority=$priority|category=$category")
        val after = cursor?.let { cursors.verify(it, cursorScope).sort }
        val fetched = repository.queue(actorId.takeIf { scope == SupportCaseQueueScope.MINE }, state, priority, category, after, size + 1)
        page(fetched, size, cursorScope) { it.latestChangedAt to it.caseId }.let { (items, next) ->
            SupportCaseQueuePageResource(items.map(::queueResource), next)
        }
    }

    @Transactional
    fun caseOverview(actorId: UUID, caseId: UUID): SupportCaseOverviewResource = boundary {
        val case = visibleCase(actorId, caseId)
        val subjects = repository.subjects(caseId)
        val masked = subjects.mapNotNull { (type, id) -> maskedSubject(type, id) }
        val orderIds = subjects.filter { it.first == SupportSubjectType.ORDER }.map { it.second }.toSet()
        val orderResources = if (orderIds.isEmpty()) emptyList() else orders.findOrderOverviews(orderIds).map(::orderResource)
        SupportCaseOverviewResource(queueResource(case), masked, orderResources, listOf("DETAIL", "VERIFICATION", "ORDER_ACTION", "COMPENSATION", "PROFILE_CHANGE", "AUDIT"))
    }

    @Transactional
    fun orderOverview(actorId: UUID, caseId: UUID, orderId: UUID): SupportOrderOverviewResource = boundary {
        visibleCase(actorId, caseId)
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_ORDER_READ)
        if (!repository.hasActiveOrderLink(caseId, orderId)) denied("Order is not actively linked to this SupportCase")
        val snapshot = orders.findOrderOverviews(setOf(orderId)).singleOrNull() ?: notFound("Order")
        orderResource(snapshot)
    }

    @Transactional
    fun approvalTasks(actorId: UUID, scope: SupportApprovalTaskScope, type: SupportApprovalTaskType?, state: String?, cursor: String?, limit: Int?): SupportApprovalTaskPageResource = boundary {
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
        val allowedTypes = type?.let { setOf(it).also { requireApprovalPermission(actorId, type) } } ?: visibleApprovalTypes(actorId)
        if (scope == SupportApprovalTaskScope.ALL) permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_ASSIGN)
        val size = normalizedLimit(limit)
        val cursorScope = cursorScope("support/approval-tasks", "scope=$scope|type=$type|state=${state.orEmpty()}")
        val after = cursor?.let { cursors.verify(it, cursorScope).sort }
        val fetched = repository.approvalTasks(allowedTypes, actorId.takeIf { scope == SupportApprovalTaskScope.MINE }, state?.trim()?.takeIf(String::isNotEmpty), after, size + 1)
        page(fetched, size, cursorScope) { it.updatedAt to it.resourceId }.let { (items, next) ->
            SupportApprovalTaskPageResource(items.map { approvalResource(actorId, it) }, next)
        }
    }

    @Transactional
    fun approvalTask(actorId: UUID, type: SupportApprovalTaskType, resourceId: UUID): SupportApprovalTaskDetailResource = boundary {
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
        requireApprovalPermission(actorId, type)
        val projection = repository.approvalTask(type, resourceId) ?: notFound("Approval task")
        visibleCase(actorId, projection.caseId, allowApprover = true)
        val timeline = repository.approvalTimeline(type, resourceId)
        SupportApprovalTaskDetailResource(approvalResource(actorId, projection), timeline.map { SupportApprovalLineageResource(it.eventType, it.state, it.actorId, it.occurredAt) })
    }

    @Transactional
    fun approvalTimeline(actorId: UUID, type: SupportApprovalTaskType, resourceId: UUID, limit: Int?): SupportApprovalTimelinePageResource = boundary {
        approvalTask(actorId, type, resourceId)
        SupportApprovalTimelinePageResource(repository.approvalTimeline(type, resourceId).take(normalizedLimit(limit)), null)
    }

    @Transactional
    fun compensations(actorId: UUID, caseId: UUID?, state: String?, cursor: String?, limit: Int?): SupportCompensationPageResource = boundary {
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
        caseId?.let { visibleCase(actorId, it, allowApprover = true) }
        val size = normalizedLimit(limit)
        val cursorScope = cursorScope("support/compensations", "caseId=${caseId ?: ""}|state=${state.orEmpty()}")
        val after = cursor?.let { cursors.verify(it, cursorScope).sort }
        val fetched = repository.compensationPage(caseId, state?.trim()?.takeIf(String::isNotEmpty), after, size + 1)
        page(fetched, size, cursorScope) { it.updatedAt to it.requestId }.let { (items, next) -> SupportCompensationPageResource(items, next) }
    }

    @Transactional(readOnly = true)
    fun profileChanges(actorId: UUID, caseId: UUID?, state: String?, cursor: String?, limit: Int?): SupportProfileChangePageResource = boundary {
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
        caseId?.let { visibleCase(actorId, it, allowApprover = true) }
        val size = normalizedLimit(limit)
        val cursorScope = cursorScope("support/profile-changes", "caseId=${caseId ?: ""}|state=${state.orEmpty()}")
        val after = cursor?.let { cursors.verify(it, cursorScope).sort }
        val fetched = repository.profileChangePage(caseId, state?.trim()?.takeIf(String::isNotEmpty), after, size + 1)
        page(fetched, size, cursorScope) { it.updatedAt to it.profileChangeId }.let { (items, next) -> SupportProfileChangePageResource(items, next) }
    }

    private fun visibleCase(actorId: UUID, caseId: UUID, allowApprover: Boolean = false): QueueProjection {
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
        val case = repository.case(caseId) ?: notFound("SupportCase")
        val broad = permissions.hasActive(actorId, OperatorPermission.SUPPORT_CASE_ASSIGN)
        if (case.assigneeId != actorId && !broad && !allowApprover) denied("SupportCase is outside the actor scope")
        return case
    }

    private fun queueResource(item: QueueProjection) = SupportCaseQueueItemResource(
        item.caseId, item.state, item.priority, item.category, item.assigneeId, item.version, item.openedAt,
        item.latestChangedAt, item.latestChannel,
        if (item.subjectType != null && item.subjectId != null) maskedSubject(item.subjectType, item.subjectId) else null,
    )

    private fun maskedSubject(type: SupportSubjectType, id: UUID): SupportMaskedSubjectResource? = when (type) {
        SupportSubjectType.CUSTOMER -> customerProfiles.findMaskedById(id)?.let { SupportMaskedSubjectResource(type, id, it.maskedDisplayName, it.maskedMatchedValue) }
        SupportSubjectType.STORE -> storeProfiles.findMaskedById(id)?.let { SupportMaskedSubjectResource(type, id, it.maskedDisplayName, it.maskedMatchedValue) }
        SupportSubjectType.ORDER, SupportSubjectType.DELIVERY -> SupportMaskedSubjectResource(type, id, null, null)
    }

    private fun orderResource(order: SupportOrderOverviewSnapshot) = SupportOrderOverviewResource(
        order.orderId, order.publicReference, order.state.name, order.version, order.orderedAt, order.pickupWindowStart,
        order.pickupWindowEnd, order.storeName, order.subtotalKrw, order.couponDiscountKrw, order.pointsAppliedKrw,
        order.payableKrw, order.currency, if (order.paidAt == null) "NOT_PAID" else "PAID", order.paidAt,
        customerProfiles.findMaskedById(order.customerId)?.let { SupportMaskedSubjectResource(SupportSubjectType.CUSTOMER, order.customerId, it.maskedDisplayName, it.maskedMatchedValue) },
        storeProfiles.findMaskedById(order.storeId)?.let { SupportMaskedSubjectResource(SupportSubjectType.STORE, order.storeId, it.maskedDisplayName, it.maskedMatchedValue) },
        order.lines.map { SupportOrderLineOverviewResource(it.sequence, it.menuName, it.quantity, it.amountKrw) },
    )

    private fun approvalResource(actorId: UUID, projection: ApprovalProjection) = SupportApprovalTaskResource(
        projection.taskType, projection.resourceId, projection.caseId, projection.state, projection.version,
        projection.revision, projection.requesterActorId, projection.executorActorId, projection.updatedAt,
        allowedActions(actorId, projection),
    )

    private fun allowedActions(actorId: UUID, task: ApprovalProjection): List<SupportApprovalAction> = buildList {
        when (task.taskType) {
            SupportApprovalTaskType.DATA_ACCESS_GRANT -> if (task.state == "APPROVAL_PENDING" && permissions.hasActive(actorId, OperatorPermission.SUPPORT_PII_REVEAL_APPROVE)) addAll(listOf(SupportApprovalAction.APPROVE, SupportApprovalAction.DENY))
            SupportApprovalTaskType.BREAK_GLASS -> if (task.state == "APPROVAL_PENDING" && permissions.hasActive(actorId, OperatorPermission.SUPPORT_PII_REVEAL_APPROVE)) addAll(listOf(SupportApprovalAction.APPROVE, SupportApprovalAction.DENY)) else if (task.state == "REVIEW_PENDING" && permissions.hasActive(actorId, OperatorPermission.PRIVACY_BREAK_GLASS_REVIEW)) add(SupportApprovalAction.REVIEW)
            SupportApprovalTaskType.SUPPORT_ACTION -> actionRequestActions(actorId, task.state)
            SupportApprovalTaskType.COMPENSATION -> actionRequestActions(actorId, task.state)
            SupportApprovalTaskType.PROFILE_CHANGE -> actionRequestActions(actorId, task.state)
        }
    }

    private fun MutableList<SupportApprovalAction>.actionRequestActions(actorId: UUID, state: String) {
        if (state == "AWAITING_SUPPORT_MANAGER" && permissions.hasActive(actorId, OperatorPermission.SUPPORT_ACTION_APPROVE)) addAll(listOf(SupportApprovalAction.APPROVE, SupportApprovalAction.DENY, SupportApprovalAction.RETURN_FOR_REVISION))
        if (state == "READY_FOR_EXECUTION") add(SupportApprovalAction.EXECUTE)
        if (state == "REASSIGNMENT_REQUIRED" && permissions.hasActive(actorId, OperatorPermission.SUPPORT_CASE_ASSIGN)) add(SupportApprovalAction.REASSIGN)
    }

    private fun visibleApprovalTypes(actorId: UUID): Set<SupportApprovalTaskType> = buildSet {
        if (permissions.hasActive(actorId, OperatorPermission.SUPPORT_PII_REVEAL_APPROVE)) { add(SupportApprovalTaskType.DATA_ACCESS_GRANT); add(SupportApprovalTaskType.BREAK_GLASS) }
        if (permissions.hasActive(actorId, OperatorPermission.SUPPORT_ACTION_APPROVE)) add(SupportApprovalTaskType.SUPPORT_ACTION)
        if (permissions.hasActive(actorId, OperatorPermission.SUPPORT_COMPENSATION_APPROVE)) add(SupportApprovalTaskType.COMPENSATION)
        if (permissions.hasActive(actorId, OperatorPermission.SUPPORT_PROFILE_R3_APPROVE)) add(SupportApprovalTaskType.PROFILE_CHANGE)
    }

    private fun requireApprovalPermission(actorId: UUID, type: SupportApprovalTaskType) = when (type) {
        SupportApprovalTaskType.DATA_ACCESS_GRANT, SupportApprovalTaskType.BREAK_GLASS -> permissions.requireActive(actorId, OperatorPermission.SUPPORT_PII_REVEAL_APPROVE)
        SupportApprovalTaskType.SUPPORT_ACTION -> permissions.requireActive(actorId, OperatorPermission.SUPPORT_ACTION_APPROVE)
        SupportApprovalTaskType.COMPENSATION -> permissions.requireActive(actorId, OperatorPermission.SUPPORT_COMPENSATION_APPROVE)
        SupportApprovalTaskType.PROFILE_CHANGE -> permissions.requireActive(actorId, OperatorPermission.SUPPORT_PROFILE_R3_APPROVE)
    }

    private fun normalizedLimit(limit: Int?): Int = (limit ?: 20).also { if (it !in 1..100) invalid("limit must be between 1 and 100") }
    private fun cursorScope(endpoint: String, filter: String) = SignedCursorScope(endpoint, SupportSha256.utf8("$endpoint|$filter"), SORT_ADAPTER)

    private fun <T> page(fetched: List<T>, size: Int, scope: SignedCursorScope<ConsoleSort>, boundary: (T) -> Pair<Instant, UUID>): Pair<List<T>, String?> {
        val items = fetched.take(size)
        val next = if (fetched.size > size) boundary(items.last()).let { cursors.issue(scope, ConsoleSort(it.first, it.second), clock.instant().plus(CURSOR_TTL)) } else null
        return items to next
    }

    private fun <T> boundary(block: () -> T): T = try { block() } catch (failure: DomainFailure) { throw failure } catch (failure: DataAccessException) { throw dependency("Support console query is unavailable", failure) } catch (failure: PersistenceException) { throw dependency("Support console query is unavailable", failure) } catch (failure: TransactionException) { throw dependency("Support console query is unavailable", failure) }

    private companion object {
        val CURSOR_TTL: Duration = Duration.ofMinutes(15)
        val SORT_ADAPTER = object : CursorSortAdapter<ConsoleSort> {
            override fun encode(sort: ConsoleSort) = listOf(sort.changedAt.toString(), sort.id.toString())
            override fun decode(values: List<String>): ConsoleSort? = if (values.size != 2) null else try { ConsoleSort(Instant.parse(values[0]), UUID.fromString(values[1])) } catch (_: IllegalArgumentException) { null }
        }
    }
}

@Validated
@RestController
@RequestMapping("/api/v1/support")
internal class SupportConsoleQueryController(private val service: SupportConsoleQueryService) {
    @GetMapping("/case-queue/summary") @PreAuthorize("isAuthenticated()")
    fun queueSummary(actor: OperatorActor) = noStore(service.queueSummary(actor.actorId))

    @GetMapping("/case-queue") @PreAuthorize("isAuthenticated()")
    fun queue(actor: OperatorActor, @RequestParam(defaultValue = "MINE") scope: SupportCaseQueueScope, @RequestParam(required = false) state: SupportCaseState?, @RequestParam(required = false) priority: SupportCasePriority?, @RequestParam(required = false) category: SupportInquiryCategory?, @RequestParam(required = false) cursor: String?, @RequestParam(required = false) @Min(1) @Max(100) limit: Int?) = noStore(service.queue(actor.actorId, scope, state, priority, category, cursor, limit))

    @GetMapping("/cases/{caseId}/overview") @PreAuthorize("isAuthenticated()")
    fun caseOverview(actor: OperatorActor, @PathVariable caseId: UUID) = noStore(service.caseOverview(actor.actorId, caseId))

    @GetMapping("/orders/{orderId}/overview") @PreAuthorize("isAuthenticated()")
    fun orderOverview(actor: OperatorActor, @PathVariable orderId: UUID, @RequestParam caseId: UUID) = noStore(service.orderOverview(actor.actorId, caseId, orderId))

    @GetMapping("/approval-tasks") @PreAuthorize("isAuthenticated()")
    fun approvalTasks(actor: OperatorActor, @RequestParam(defaultValue = "MINE") scope: SupportApprovalTaskScope, @RequestParam(required = false) taskType: SupportApprovalTaskType?, @RequestParam(required = false) state: String?, @RequestParam(required = false) cursor: String?, @RequestParam(required = false) @Min(1) @Max(100) limit: Int?) = noStore(service.approvalTasks(actor.actorId, scope, taskType, state, cursor, limit))

    @GetMapping("/approval-tasks/{taskType}/{resourceId}") @PreAuthorize("isAuthenticated()")
    fun approvalTask(actor: OperatorActor, @PathVariable taskType: SupportApprovalTaskType, @PathVariable resourceId: UUID) = noStore(service.approvalTask(actor.actorId, taskType, resourceId))

    @GetMapping("/approval-tasks/{taskType}/{resourceId}/timeline") @PreAuthorize("isAuthenticated()")
    fun approvalTimeline(actor: OperatorActor, @PathVariable taskType: SupportApprovalTaskType, @PathVariable resourceId: UUID, @RequestParam(required = false) @Min(1) @Max(100) limit: Int?) = noStore(service.approvalTimeline(actor.actorId, taskType, resourceId, limit))

    @GetMapping("/compensations") @PreAuthorize("isAuthenticated()")
    fun compensations(actor: OperatorActor, @RequestParam(required = false) caseId: UUID?, @RequestParam(required = false) state: String?, @RequestParam(required = false) cursor: String?, @RequestParam(required = false) @Min(1) @Max(100) limit: Int?) = noStore(service.compensations(actor.actorId, caseId, state, cursor, limit))

    @GetMapping("/profile-changes") @PreAuthorize("isAuthenticated()")
    fun profileChanges(actor: OperatorActor, @RequestParam(required = false) caseId: UUID?, @RequestParam(required = false) state: String?, @RequestParam(required = false) cursor: String?, @RequestParam(required = false) @Min(1) @Max(100) limit: Int?) = noStore(service.profileChanges(actor.actorId, caseId, state, cursor, limit))

    private fun <T : Any> noStore(body: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
}

private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)
private fun denied(message: String): Nothing = throw DomainFailure(FailureCode.ACCESS_DENIED, message)
private fun notFound(resource: String): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "$resource was not found")
private fun dependency(message: String, cause: RuntimeException? = null): DomainFailure = DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message).also { if (cause != null) it.initCause(cause) }

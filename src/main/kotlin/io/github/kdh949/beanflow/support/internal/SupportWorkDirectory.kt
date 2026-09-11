package io.github.kdh949.beanflow.support.internal

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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

internal enum class SupportWorkKind { VERIFICATION, DATA_ACCESS, ORDER_ACTION, COMPENSATION, PROFILE_CHANGE, BREAK_GLASS }

internal data class SupportWorkItem(
    val requestId: UUID,
    val kind: SupportWorkKind,
    val caseId: UUID,
    val caseCategory: SupportInquiryCategory,
    val caseOpenedAt: Instant,
    val purpose: String,
    val state: String,
    val createdAt: Instant,
    val expiresAt: Instant?,
)

internal data class SupportWorkPage(
    val items: List<SupportWorkItem>,
    val nextCursor: String?,
)

internal data class SupportWorkCandidate(
    val id: UUID,
    val caseId: UUID,
    val category: SupportInquiryCategory,
    val openedAt: Instant,
    val createdAt: Instant,
)

@Service
internal class SupportWorkCandidateQuery(
    private val jdbc: JdbcTemplate,
    private val permissions: OperatorPermissionAuthorization,
) {
    @Transactional
    fun authorize(
        actorId: UUID,
        kind: SupportWorkKind,
    ) {
        when (kind) {
            SupportWorkKind.VERIFICATION -> {
                permissions.requireActive(actorId, OperatorPermission.SUPPORT_VERIFICATION_MANAGE)
            }

            SupportWorkKind.DATA_ACCESS -> {
                requireAny(
                    actorId,
                    OperatorPermission.SUPPORT_PII_REVEAL_REQUEST,
                    OperatorPermission.SUPPORT_PII_REVEAL_APPROVE,
                )
            }

            SupportWorkKind.BREAK_GLASS -> {
                requireAny(
                    actorId,
                    OperatorPermission.SUPPORT_BREAK_GLASS_REQUEST,
                    OperatorPermission.SUPPORT_PII_REVEAL_APPROVE,
                    OperatorPermission.PRIVACY_BREAK_GLASS_REVIEW,
                )
            }

            else -> {
                permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
            }
        }
    }

    @Transactional
    fun scan(
        actorId: UUID,
        kind: SupportWorkKind,
        caseId: UUID?,
        after: UUID?,
        limit: Int,
    ): List<SupportWorkCandidate> {
        authorize(actorId, kind)
        val table =
            when (kind) {
                SupportWorkKind.VERIFICATION -> "support_verification_session"
                SupportWorkKind.DATA_ACCESS -> "support_data_access_grant"
                SupportWorkKind.ORDER_ACTION -> "support_action_request"
                SupportWorkKind.COMPENSATION -> "support_compensation_request"
                SupportWorkKind.PROFILE_CHANGE -> "support_profile_change"
                SupportWorkKind.BREAK_GLASS -> "support_break_glass_request"
            }
        val createdColumn =
            when (kind) {
                SupportWorkKind.VERIFICATION -> "started_at"
                SupportWorkKind.DATA_ACCESS, SupportWorkKind.BREAK_GLASS -> "requested_at"
                else -> "created_at"
            }
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (caseId != null) {
            conditions += "request.support_case_id = ?"
            args += caseId
        }
        if (after != null) {
            conditions += "request.id > ?"
            args += after
        }
        if (kind in setOf(SupportWorkKind.VERIFICATION, SupportWorkKind.DATA_ACCESS)) {
            conditions += "support_case.state IN ('OPEN', 'IN_PROGRESS', 'WAITING')"
        }
        if (kind == SupportWorkKind.DATA_ACCESS) {
            conditions +=
                "EXISTS (SELECT 1 FROM support_case_subject_link link WHERE link.id = request.subject_link_id AND link.support_case_id = request.support_case_id AND link.unlinked_at IS NULL)"
        }
        if (kind == SupportWorkKind.ORDER_ACTION) {
            conditions += "request.action IN ('ORDER_CANCELLATION', 'PICKUP_RESCHEDULE', 'POST_ACCEPTANCE_RESOLUTION')"
        }
        if (kind == SupportWorkKind.VERIFICATION) {
            conditions += "request.actor_id = ?"
            args += actorId
        }
        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        args += limit
        return jdbc.query(
            """
            SELECT request.id, request.support_case_id, support_case.category, support_case.opened_at, request.$createdColumn AS created_at
            FROM $table request JOIN support_case ON support_case.id = request.support_case_id
            $where ORDER BY request.id ASC LIMIT ?
            """.trimIndent(),
            {
                rs,
                _,
                ->
                SupportWorkCandidate(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("support_case_id", UUID::class.java),
                    SupportInquiryCategory.valueOf(rs.getString("category")),
                    rs.getTimestamp("opened_at").toInstant(),
                    rs.getTimestamp("created_at").toInstant(),
                )
            },
            *args.toTypedArray(),
        )
    }

    private fun requireAny(
        actorId: UUID,
        vararg values: OperatorPermission,
    ) {
        if (values.none {
                permissions.hasActive(actorId, it)
            }
        ) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Support work directory permission is required")
        }
    }
}

/** Reuses existing short inspection transactions; it never holds locks across a page. */
@Service
internal class SupportWorkDirectoryService(
    private val candidates: SupportWorkCandidateQuery,
    private val verification: SupportVerificationApplicationService,
    private val grants: DataAccessGrantApplicationService,
    private val actions: SupportActionRequestTransactionService,
    private val compensations: SupportCompensationWorkflowService,
    private val profiles: SupportProfileChangeApplicationService,
    private val emergencies: BreakGlassApplicationService,
    private val cursors: SignedCursorCodec,
    private val clock: Clock,
) {
    fun list(
        actorId: UUID,
        kind: SupportWorkKind,
        caseId: UUID?,
        cursor: String?,
        limit: Int,
    ): SupportWorkPage {
        if (limit !in 1..100) throw DomainFailure(FailureCode.INVALID_REQUEST, "Support work limit must be between 1 and 100")
        candidates.authorize(actorId, kind)
        val binding = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("$actorId|$kind|$caseId".toByteArray()))
        val scope = SignedCursorScope("support-work-directory", binding, SORT)
        val after = cursor?.let { cursors.verify(it, scope).sort }
        val rows = candidates.scan(actorId, kind, caseId, after, limit + 1)
        val page = rows.take(limit)
        val items =
            page.mapNotNull { candidate ->
                try {
                    inspect(actorId, kind, candidate)
                } catch (failure: DomainFailure) {
                    // ADR-128: per-object visibility filtering is distinct from a failed directory permission check.
                    if (failure.code == FailureCode.ACCESS_DENIED) null else throw failure
                }
            }
        candidates.authorize(actorId, kind)
        val next = if (rows.size > limit) cursors.issue(scope, page.last().id, clock.instant().plus(Duration.ofMinutes(15))) else null
        return SupportWorkPage(items, next)
    }

    private fun inspect(
        actorId: UUID,
        kind: SupportWorkKind,
        candidate: SupportWorkCandidate,
    ): SupportWorkItem {
        fun item(
            caseId: UUID,
            purpose: String,
            state: String,
            createdAt: Instant,
            expiresAt: Instant? = null,
        ): SupportWorkItem {
            if (caseId != candidate.caseId) throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Support work binding is invalid")
            return SupportWorkItem(candidate.id, kind, caseId, candidate.category, candidate.openedAt, purpose, state, createdAt, expiresAt)
        }
        return when (kind) {
            SupportWorkKind.VERIFICATION -> {
                verification.get(actorId, candidate.id).let {
                    item(it.caseId, it.purpose.name, it.state.name, it.startedAt, it.expiresAt)
                }
            }

            SupportWorkKind.DATA_ACCESS -> {
                grants.get(actorId, candidate.id).grant.let {
                    item(it.caseId, it.purpose.name, it.state.name, it.requestedAt, it.expiresAt)
                }
            }

            SupportWorkKind.ORDER_ACTION -> {
                actions.get(actorId, candidate.id).let {
                    item(it.caseId, it.action.name, it.state.name, candidate.createdAt, it.expiresAt)
                }
            }

            SupportWorkKind.COMPENSATION -> {
                compensations.get(actorId, candidate.id).let {
                    item(
                        it.request.supportCaseId,
                        it.request.benefitType.name,
                        it.request.state.name,
                        it.request.createdAt,
                        it.verificationExpiresAt,
                    )
                }
            }

            SupportWorkKind.PROFILE_CHANGE -> {
                profiles
                    .get(
                        actorId,
                        candidate.id,
                    ).let { item(it.caseId, it.purpose.name, it.state.name, it.createdAt) }
            }

            SupportWorkKind.BREAK_GLASS -> {
                emergencies.workflow(actorId, candidate.id).request.let {
                    item(it.caseId, it.purpose.name, it.state.name, it.requestedAt, it.expiresAt)
                }
            }
        }
    }

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
internal class SupportWorkDirectoryController(
    private val service: SupportWorkDirectoryService,
) {
    @GetMapping("/api/v1/support/work-items")
    @PreAuthorize("isAuthenticated()")
    fun list(
        actor: OperatorActor,
        @RequestParam kind: SupportWorkKind,
        @RequestParam(required = false) caseId: UUID?,
        @RequestParam(required = false) @Size(max = 2048) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ): ResponseEntity<SupportWorkPage> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(actor.actorId, kind, caseId, cursor, limit))
}

package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.OperationsSupportInvestigationState
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.operations.api.SupportInvestigationDisplay
import io.github.kdh949.beanflow.operations.api.SupportInvestigationDisplayOperations
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OperatorActor
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
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
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

internal data class OperationsInvestigationCandidate(
    val investigationId: UUID,
    val request: SupportInvestigationDisplay,
    val state: OperationsSupportInvestigationState,
    val openedAt: Instant,
    val expiresAt: Instant,
    val canDecide: Boolean,
)

internal data class OperationsInvestigationPage(
    val items: List<OperationsInvestigationCandidate>,
    val nextCursor: String?,
)

@Service
internal class OperationsSupportInvestigationDirectory(
    private val permissions: OperatorPermissionAuthorization,
    private val displays: SupportInvestigationDisplayOperations,
    private val jdbc: JdbcTemplate,
    private val workflows: OperationsSupportInvestigationService,
    private val cursors: SignedCursorCodec,
    private val clock: Clock,
) {
    @Transactional
    fun list(
        actorId: UUID,
        state: OperationsSupportInvestigationState?,
        cursor: String?,
        limit: Int,
    ): OperationsInvestigationPage {
        permissions.requireActive(actorId, OperatorPermission.OPERATIONS_SUPPORT_INVESTIGATION)
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
        if (limit !in 1..100) throw DomainFailure(FailureCode.INVALID_REQUEST, "Investigation limit must be between 1 and 100")
        val scope =
            SignedCursorScope(
                "operations-investigation-directory",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("$actorId|$state".toByteArray())),
                SORT,
            )
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (state != null) {
            conditions += "state = ?"
            args += state.name
        }
        if (state == OperationsSupportInvestigationState.OPEN) {
            conditions += "expires_at > ?"
            args += Timestamp.from(clock.instant())
        }
        if (cursor != null) {
            conditions += "id > ?"
            args += cursors.verify(cursor, scope).sort
        }
        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        args += limit + 1
        val rows =
            jdbc.query(
                "SELECT id, support_action_request_id, revision_number FROM operations_support_investigation_case $where ORDER BY id LIMIT ?",
                {
                    rs,
                    _,
                    ->
                    Row(
                        rs.getObject("id", UUID::class.java),
                        rs.getObject("support_action_request_id", UUID::class.java),
                        rs.getInt("revision_number"),
                    )
                },
                *args.toTypedArray(),
            )
        val page = rows.take(limit)
        val labels = displays.findDisplays(page.map { it.requestId }.toSet())
        val items =
            page.mapNotNull { row ->
                val display =
                    labels[row.requestId]
                        ?: throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Investigation request display is missing")
                if (display.revisionNumber != row.revision) return@mapNotNull null
                val workflow = workflows.workflow(actorId, row.requestId, row.revision, clock.instant())
                val investigation = workflow.investigation
                OperationsInvestigationCandidate(
                    row.id,
                    display,
                    investigation.state,
                    investigation.openedAt,
                    investigation.expiresAt,
                    workflow.canDecide,
                )
            }
        return OperationsInvestigationPage(
            items,
            if (rows.size >
                limit
            ) {
                cursors.issue(scope, page.last().id, clock.instant().plus(Duration.ofMinutes(15)))
            } else {
                null
            },
        )
    }

    private data class Row(
        val id: UUID,
        val requestId: UUID,
        val revision: Int,
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
internal class OperationsSupportInvestigationDirectoryController(
    private val service: OperationsSupportInvestigationDirectory,
) {
    @GetMapping("/api/v1/operations/investigation-queue")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun list(
        actor: OperatorActor,
        @RequestParam(required = false) state: OperationsSupportInvestigationState?,
        @RequestParam(required = false) @Size(max = 2048) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ): ResponseEntity<OperationsInvestigationPage> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(actor.actorId, state, cursor, limit))
}

package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.operations.api.OrderInvestigationOperations
import io.github.kdh949.beanflow.operations.api.OrderInvestigationState
import io.github.kdh949.beanflow.operations.api.OrderInvestigationTarget
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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

internal data class RepairOrderDisplay(
    val publicReference: String,
    val storeName: String,
    val state: OrderInvestigationState,
    val createdAt: Instant,
)

internal data class SetupRecoveryCaseItem(
    val caseId: UUID,
    val status: ReprocessingCaseStatus,
    val reason: String,
    val updatedAt: Instant,
    val order: RepairOrderDisplay,
)

internal data class SetupRepairProposalItem(
    val proposal: RepairProposal,
    val order: RepairOrderDisplay,
)

internal data class SetupRecoveryCasePage(
    val items: List<SetupRecoveryCaseItem>,
    val nextCursor: String?,
)

internal data class SetupRepairProposalPage(
    val items: List<SetupRepairProposalItem>,
    val nextCursor: String?,
)

@Service
@Transactional
internal class PaymentSetupRecoveryDirectory(
    private val grants: OperatorPermissionAuthorization,
    private val orders: OrderInvestigationOperations,
    private val jdbc: JdbcTemplate,
    private val cursors: SignedCursorCodec,
    private val clock: Clock,
) {
    fun cases(
        actorId: UUID,
        status: ReprocessingCaseStatus?,
        cursor: String?,
        limit: Int,
    ): SetupRecoveryCasePage {
        grants.requireActive(
            actorId,
            OperatorPermission.PAYMENT_CANCELLATION_SETUP_REPAIR,
        )
        requireLimit(limit)
        val scope =
            scope(
                "setup-recovery-cases",
                actorId,
                status?.name,
                null,
            )
        val args = mutableListOf<Any>()
        var where = "case_type = 'PAYMENT_CANCELLATION_SETUP'"
        if (status != null) {
            where += " AND status = ?"
            args += status.name
        }
        if (cursor != null) {
            where += " AND id > ?"
            args +=
                cursors
                    .verify(
                        cursor,
                        scope,
                    ).sort
        }
        args += limit + 1
        val rows =
            jdbc.query(
                "SELECT id, owner_reference, status, reason, updated_at " +
                    "FROM operations_reprocessing_case WHERE $where ORDER BY id LIMIT ?",
                {
                    rs,
                    _,
                    ->
                    val owner = OWNER.matchEntire(rs.getString("owner_reference")) ?: unavailable("Setup case owner is invalid")
                    val orderId =
                        runCatching { UUID.fromString(owner.groupValues[1]) }.getOrNull()
                            ?: unavailable("Setup case order is invalid")
                    CaseRow(
                        rs.getObject(
                            "id",
                            UUID::class.java,
                        ),
                        orderId,
                        ReprocessingCaseStatus.valueOf(rs.getString("status")),
                        rs.getString("reason"),
                        rs.getTimestamp("updated_at").toInstant(),
                    )
                },
                *args.toTypedArray(),
            )
        val page = rows.take(limit)
        val targets = orders.findTargets(page.map { it.orderId }.toSet())
        return SetupRecoveryCasePage(
            page.map { row ->
                SetupRecoveryCaseItem(
                    row.id,
                    row.status,
                    row.reason,
                    row.updatedAt,
                    display(targets[row.orderId]),
                )
            },
            next(
                scope,
                rows.size > limit,
                page.lastOrNull()?.id,
            ),
        )
    }

    fun proposals(
        actorId: UUID,
        caseId: UUID?,
        state: PaymentSetupRepairProposalState?,
        cursor: String?,
        limit: Int,
    ): SetupRepairProposalPage {
        grants.requireActive(
            actorId,
            OperatorPermission.PAYMENT_CANCELLATION_SETUP_REPAIR,
        )
        requireLimit(limit)
        val scope =
            scope(
                "setup-repair-proposals",
                actorId,
                state?.name,
                caseId,
            )
        val args = mutableListOf<Any>()
        val conditions = mutableListOf<String>()
        if (caseId != null) {
            conditions += "case_id = ?"
            args += caseId
        }
        if (state != null) {
            conditions += "state = ?"
            args += state.name
        }
        if (cursor != null) {
            conditions += "id > ?"
            args +=
                cursors
                    .verify(
                        cursor,
                        scope,
                    ).sort
        }
        args += limit + 1
        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        val rows =
            jdbc.query(
                "SELECT id, case_id, order_id, action, state, proposed_by, decided_by, " +
                    "created_at, expires_at, decided_at, correlation_id " +
                    "FROM operations_payment_setup_repair_proposal $where ORDER BY id LIMIT ?",
                {
                    rs,
                    _,
                    ->
                    ProposalRow(
                        rs.getObject(
                            "order_id",
                            UUID::class.java,
                        ),
                        RepairProposal(
                            rs.getObject(
                                "id",
                                UUID::class.java,
                            ),
                            rs.getObject(
                                "case_id",
                                UUID::class.java,
                            ),
                            PaymentSetupRepairAction.valueOf(rs.getString("action")),
                            PaymentSetupRepairProposalState.valueOf(rs.getString("state")),
                            rs.getObject(
                                "proposed_by",
                                UUID::class.java,
                            ),
                            rs.getObject(
                                "decided_by",
                                UUID::class.java,
                            ),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("expires_at").toInstant(),
                            rs.getTimestamp("decided_at")?.toInstant(),
                            rs.getString("correlation_id"),
                        ),
                    )
                },
                *args.toTypedArray(),
            )
        val page = rows.take(limit)
        val targets = orders.findTargets(page.map { it.orderId }.toSet())
        return SetupRepairProposalPage(
            page.map {
                SetupRepairProposalItem(
                    it.proposal,
                    display(targets[it.orderId]),
                )
            },
            next(
                scope,
                rows.size > limit,
                page.lastOrNull()?.proposal?.proposalId,
            ),
        )
    }

    private fun display(target: OrderInvestigationTarget?): RepairOrderDisplay {
        if (target == null) unavailable("Recovery order display is missing")
        return RepairOrderDisplay(
            target.publicReference,
            target.storeName,
            target.state,
            target.createdAt,
        )
    }

    private fun next(
        scope: SignedCursorScope<UUID>,
        more: Boolean,
        id: UUID?,
    ): String? =
        if (more && id != null) {
            cursors.issue(
                scope,
                id,
                clock.instant().plus(Duration.ofMinutes(15)),
            )
        } else {
            null
        }

    private fun requireLimit(limit: Int) {
        if (limit !in 1..100) {
            throw DomainFailure(
                FailureCode.INVALID_REQUEST,
                "Recovery list limit is invalid",
            )
        }
    }

    private fun scope(
        endpoint: String,
        actorId: UUID,
        state: String?,
        caseId: UUID?,
    ): SignedCursorScope<UUID> {
        val binding = "$actorId|$state|$caseId".toByteArray()
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binding))
        return SignedCursorScope(
            endpoint,
            hash,
            object : CursorSortAdapter<UUID> {
                override fun encode(sort: UUID) = listOf(sort.toString())

                override fun decode(values: List<String>): UUID? {
                    val value = values.singleOrNull() ?: return null
                    return runCatching { UUID.fromString(value) }.getOrNull()
                }
            },
        )
    }

    private fun unavailable(message: String): Nothing =
        throw DomainFailure(
            FailureCode.DEPENDENCY_UNAVAILABLE,
            message,
        )

    private data class CaseRow(
        val id: UUID,
        val orderId: UUID,
        val status: ReprocessingCaseStatus,
        val reason: String,
        val updatedAt: Instant,
    )

    private data class ProposalRow(
        val orderId: UUID,
        val proposal: RepairProposal,
    )

    private companion object {
        val OWNER = Regex("^order:([0-9a-fA-F-]{36}):customer-cancellation:([0-9]+):payment-setup$")
    }
}

@Validated
@RestController
@RequestMapping("/api/v1/operations")
@PreAuthorize("hasRole('PLATFORM_OPERATOR')")
internal class PaymentSetupRecoveryDirectoryController(
    private val directory: PaymentSetupRecoveryDirectory,
) {
    @GetMapping("/payment-setup-recovery-cases")
    fun cases(
        actor: OperatorActor,
        @RequestParam(required = false) status: ReprocessingCaseStatus?,
        @RequestParam(required = false) @Size(
            min = 1,
            max = 2048,
        ) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
        directory.cases(
            actor.actorId,
            status,
            cursor,
            limit,
        ),
    )

    @GetMapping("/reprocessing-repair-proposals")
    fun proposals(
        actor: OperatorActor,
        @RequestParam(required = false) caseId: UUID?,
        @RequestParam(required = false) state: PaymentSetupRepairProposalState?,
        @RequestParam(required = false) @Size(
            min = 1,
            max = 2048,
        ) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
        directory.proposals(
            actor.actorId,
            caseId,
            state,
            cursor,
            limit,
        ),
    )
}

package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.ManualRecoveryCaseOperations
import io.github.kdh949.beanflow.operations.api.ManualRecoveryCasePage
import io.github.kdh949.beanflow.operations.api.ManualRecoveryCaseView
import io.github.kdh949.beanflow.operations.api.ManualRecoveryKind
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

@Service
@Transactional(propagation = Propagation.MANDATORY)
internal class ManualRecoveryCaseService(
    private val repository: ReprocessingCaseJpaRepository,
    private val jdbc: JdbcTemplate,
    private val cursors: SignedCursorCodec,
    private val clock: Clock,
) : ManualRecoveryCaseOperations {
    override fun recordPublicationOutcome(
        targetId: UUID,
        expectedVersion: Long,
        outcome: String,
        now: Instant,
    ): ManualRecoveryCaseView? {
        require(outcome in setOf("SUCCEEDED", "FAILED", "UNKNOWN"))
        val kind = ManualRecoveryKind.EVENT_PUBLICATION
        val row = repository.findLockedByCaseTypeAndOwnerReference(type(kind), reference(kind, targetId)) ?: return null
        if (row.version != expectedVersion ||
            row.status !in setOf(ReprocessingCaseStatus.RUNNING, ReprocessingCaseStatus.MANUAL_REVIEW)
        ) {
            return null
        }
        row.status = if (outcome == "SUCCEEDED") ReprocessingCaseStatus.RESOLVED else ReprocessingCaseStatus.MANUAL_REVIEW
        row.resolution = if (outcome == "SUCCEEDED") "OWNER_EXECUTION_COMPLETED" else null
        row.reason =
            when (outcome) {
                "UNKNOWN" -> "EXECUTION_OUTCOME_UNKNOWN"
                "FAILED" -> "OWNER_EXECUTION_FAILED"
                else -> row.reason
            }
        row.updatedAt = now
        repository.flush()
        return row.view(kind, targetId)
    }

    override fun find(
        kind: ManualRecoveryKind,
        targetId: UUID,
    ): ManualRecoveryCaseView? =
        repository
            .findByCaseTypeAndOwnerReference(
                type(kind),
                reference(
                    kind,
                    targetId,
                ),
            )?.view(
                kind,
                targetId,
            )

    override fun list(
        kind: ManualRecoveryKind,
        actorId: UUID,
        cursor: String?,
        limit: Int,
    ): ManualRecoveryCasePage {
        if (limit !in 1..100) {
            throw DomainFailure(
                FailureCode.INVALID_REQUEST,
                "Recovery list limit is invalid",
            )
        }
        val scope =
            SignedCursorScope(
                "manual-recovery-cases",
                HexFormat.of().formatHex(
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest("$kind:$actorId".toByteArray()),
                ),
                object : CursorSortAdapter<UUID> {
                    override fun encode(sort: UUID) = listOf(sort.toString())

                    override fun decode(values: List<String>): UUID? =
                        values.singleOrNull()?.let {
                            runCatching {
                                UUID.fromString(it)
                            }.getOrNull()
                        }
                },
            )
        val after =
            cursor?.let {
                cursors
                    .verify(
                        it,
                        scope,
                    ).sort
            }
        val args = mutableListOf<Any>(kind.name)
        val where =
            if (after != null) {
                args += after
                " AND id > ?"
            } else {
                ""
            }
        args += limit + 1
        val rows =
            jdbc.query(
                "SELECT id, owner_reference, status, version, reason, updated_at, resolution " +
                    "FROM operations_reprocessing_case WHERE case_type = ?$where ORDER BY id LIMIT ?",
                {
                    rs,
                    _,
                    ->
                    ManualRecoveryCaseView(
                        rs.getObject(
                            "id",
                            UUID::class.java,
                        ),
                        kind,
                        UUID.fromString(rs.getString("owner_reference").removePrefix(kind.prefix)),
                        rs.getString("status"),
                        rs.getLong("version"),
                        rs.getString("reason"),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getString("resolution"),
                    )
                },
                *args.toTypedArray(),
            )
        val items = rows.take(limit)
        return ManualRecoveryCasePage(
            items,
            if (rows.size > limit) {
                cursors.issue(
                    scope,
                    items.last().caseId,
                    clock.instant().plus(Duration.ofMinutes(15)),
                )
            } else {
                null
            },
        )
    }

    override fun begin(
        kind: ManualRecoveryKind,
        targetId: UUID,
        expectedVersion: Long,
        now: Instant,
    ): ManualRecoveryCaseView {
        val row =
            repository.findLockedByCaseTypeAndOwnerReference(
                type(kind),
                reference(
                    kind,
                    targetId,
                ),
            )
                ?: throw DomainFailure(
                    FailureCode.RESOURCE_NOT_FOUND,
                    "Manual recovery case was not found",
                )
        if (row.status != ReprocessingCaseStatus.MANUAL_REVIEW ||
            row.version != expectedVersion
        ) {
            throw DomainFailure(
                FailureCode.RESOURCE_STATE_CONFLICT,
                "Manual recovery case state or version changed",
            )
        }
        row.status = ReprocessingCaseStatus.RUNNING
        row.resolution = null
        row.updatedAt = now
        repository.flush()
        return row.view(
            kind,
            targetId,
        )
    }

    override fun finish(
        kind: ManualRecoveryKind,
        targetId: UUID,
        resolved: Boolean,
        now: Instant,
    ) {
        val row =
            repository.findLockedByCaseTypeAndOwnerReference(
                type(kind),
                reference(
                    kind,
                    targetId,
                ),
            ) ?: return
        if (row.status != ReprocessingCaseStatus.RUNNING) return
        row.status = if (resolved) ReprocessingCaseStatus.RESOLVED else ReprocessingCaseStatus.MANUAL_REVIEW
        row.resolution = if (resolved) "OWNER_EXECUTION_COMPLETED" else null
        row.updatedAt = now
        repository.flush()
    }

    private fun type(kind: ManualRecoveryKind) = ReprocessingCaseType.valueOf(kind.name)

    private fun reference(
        kind: ManualRecoveryKind,
        id: UUID,
    ) = kind.prefix + id

    private fun ReprocessingCaseEntity.view(
        kind: ManualRecoveryKind,
        id: UUID,
    ) = ManualRecoveryCaseView(
        this.id,
        kind,
        id,
        status.name,
        version,
        reason,
        updatedAt,
        resolution,
    )
}

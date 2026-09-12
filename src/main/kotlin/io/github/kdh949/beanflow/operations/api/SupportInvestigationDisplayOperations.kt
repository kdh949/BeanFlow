package io.github.kdh949.beanflow.operations.api

import java.time.Instant
import java.util.UUID

/** Minimal support-owned metadata for an already-authorized operations investigation list. */
data class SupportInvestigationDisplay(
    val requestId: UUID,
    val action: String,
    val caseCategory: String,
    val caseOpenedAt: Instant,
    val revisionNumber: Int,
)

interface SupportInvestigationDisplayOperations {
    fun findDisplays(requestIds: Set<UUID>): Map<UUID, SupportInvestigationDisplay>
}

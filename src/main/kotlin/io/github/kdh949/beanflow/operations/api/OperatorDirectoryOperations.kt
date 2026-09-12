package io.github.kdh949.beanflow.operations.api

import java.time.Instant
import java.util.UUID

enum class OperatorDisplayState { AVAILABLE, MISSING_PROFILE }

data class OperatorDisplay(
    val state: OperatorDisplayState,
    val loginName: String? = null,
    val observedAt: Instant? = null,
)

/** Read-only display metadata. Callers retain their own object authorization. */
interface OperatorDirectoryOperations {
    fun displays(actorIds: Set<UUID>): Map<UUID, OperatorDisplay>
}

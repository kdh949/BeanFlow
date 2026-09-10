package io.github.kdh949.beanflow.operations.api

import java.time.Instant
import java.util.UUID

enum class ManualRecoveryKind(
    val prefix: String,
) {
    NOTIFICATION_DELIVERY("notification:"),

    EVENT_PUBLICATION("event-publication:"),
}

data class ManualRecoveryCaseView(
    val caseId: UUID,
    val kind: ManualRecoveryKind,
    val targetId: UUID,
    val status: String,
    val version: Long,
    val reason: String,
    val updatedAt: Instant,
    val resolution: String?,
)

data class ManualRecoveryAcceptance(
    val commandId: UUID,
    val recoveryCase: ManualRecoveryCaseView,
    val acceptedAt: Instant,
)

data class ManualRecoveryCasePage(
    val items: List<ManualRecoveryCaseView>,
    val nextCursor: String?,
)

/** Only notification/publication recovery cases; owner commands and worker results share their local transaction. */
interface ManualRecoveryCaseOperations {
    /** Publication 결과는 요청이 보유한 Case version에만 반영한다. 늦은 이전 시도는 새 Case를 덮지 않는다. */
    fun recordPublicationOutcome(
        targetId: UUID,
        expectedVersion: Long,
        outcome: String,
        now: Instant,
    ): ManualRecoveryCaseView?

    fun find(
        kind: ManualRecoveryKind,
        targetId: UUID,
    ): ManualRecoveryCaseView?

    fun list(
        kind: ManualRecoveryKind,
        actorId: UUID,
        cursor: String?,
        limit: Int,
    ): ManualRecoveryCasePage

    fun begin(
        kind: ManualRecoveryKind,
        targetId: UUID,
        expectedVersion: Long,
        now: Instant,
    ): ManualRecoveryCaseView

    fun finish(
        kind: ManualRecoveryKind,
        targetId: UUID,
        resolved: Boolean,
        now: Instant,
    )
}

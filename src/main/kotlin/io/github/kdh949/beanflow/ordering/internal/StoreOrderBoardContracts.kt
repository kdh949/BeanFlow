package io.github.kdh949.beanflow.ordering.internal

import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate

internal enum class StoreOrderBoardLane {
    PENDING_ACCEPTANCE,
    ACCEPTED,
    PREPARING,
    READY,
}

internal enum class StoreOrderAcceptancePhase {
    OPEN,
    WARNING,
    TIMEOUT_PENDING,
}

internal enum class StoreOrderAction {
    ACCEPT,
    REJECT,
    START_PREPARING,
    MARK_READY,
    COMPLETE,
}

internal enum class StoreOrderExpectedStatus {
    PAID,
    ACCEPTED,
    PREPARING,
    READY,
}

internal data class StoreOrderActionRequest(
    val action: StoreOrderAction,
    val expectedStatus: StoreOrderExpectedStatus,
    @field:Size(max = 500)
    val reason: String?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class StoreOrderBoardLifecycleResponse(
    val paidAt: Instant?,
    val acceptedAt: Instant?,
    val preparingAt: Instant?,
    val readyAt: Instant?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class StoreOrderBoardItemResponse(
    val orderReference: String,
    val pickupNumber: String,
    val pickupBusinessDate: LocalDate,
    val lane: StoreOrderBoardLane?,
    val status: String,
    val pickupWindowStart: Instant,
    val pickupWindowEnd: Instant,
    val itemSummary: String,
    val acceptanceDeadlineAt: Instant?,
    val acceptancePhase: StoreOrderAcceptancePhase?,
    val allowedActions: List<StoreOrderAction>,
    val lifecycle: StoreOrderBoardLifecycleResponse? = null,
    val compensationRecovery: StoreCompensationSummary? = null,
)

internal data class StoreOrderBoardDateGroupResponse(
    val pickupBusinessDate: LocalDate,
    val items: List<StoreOrderBoardItemResponse>,
)

internal data class StoreOrderBoardResponse(
    val groups: List<StoreOrderBoardDateGroupResponse>,
    val overflow: List<StoreOrderBoardOverflowResponse> = emptyList(),
)

internal data class StoreOrderBoardOverflowResponse(
    val lane: StoreOrderBoardLane,
    val overflowCount: Long,
    val nextCursor: String,
)

internal data class StoreOrderBoardOverflowPageResponse(
    val lane: StoreOrderBoardLane,
    val items: List<StoreOrderBoardItemResponse>,
    val nextCursor: String?,
)

internal data class StoreOrderBoardSnapshot(
    val body: StoreOrderBoardResponse,
    val etag: String,
)

internal data class StoreOrderBoardPresentation(
    val lane: StoreOrderBoardLane?,
    val acceptancePhase: StoreOrderAcceptancePhase?,
    val allowedActions: List<StoreOrderAction>,
)

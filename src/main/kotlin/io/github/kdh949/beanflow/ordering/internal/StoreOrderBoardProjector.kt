package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Component
import java.time.Instant

@Component
internal class StoreOrderBoardProjector {
    fun board(
        rows: StoreOrderBoardRows,
        now: Instant,
        overflow: List<StoreOrderBoardOverflowResponse>,
    ): StoreOrderBoardResponse {
        val items = items(rows, now)
        return StoreOrderBoardResponse(
            groups =
                items
                    .groupBy { it.pickupBusinessDate }
                    .toSortedMap()
                    .map { (date, grouped) -> StoreOrderBoardDateGroupResponse(date, grouped) },
            overflow = overflow,
        )
    }

    fun overflowPage(
        rows: StoreOrderBoardRows,
        now: Instant,
    ): List<StoreOrderBoardItemResponse> = items(rows, now)

    fun detail(
        rows: StoreOrderBoardRows,
        compensationRecovery: StoreCompensationSummary?,
        now: Instant,
    ): StoreOrderBoardItemResponse {
        val order = rows.orders.singleOrNull() ?: dependency("Store order detail projection is not singular")
        return item(order, rows.linesByOrderId[order.orderId].orEmpty(), compensationRecovery, now)
    }

    fun transitioned(
        result: StoreOrderResult,
        now: Instant,
    ): StoreOrderBoardItemResponse {
        val order = result.order
        val state = parseState(order.state)
        val lifecycle =
            PersistedOrderLifecycle(order.paidAt, order.acceptedAt, order.preparingAt, order.readyAt, order.completedAt)
        OrderLifecycleProjection.validate(state, lifecycle)
        val presentation =
            StoreOrderBoardPresentationPolicy.present(
                state,
                order.acceptanceWarningAt,
                order.acceptanceDeadlineAt,
                now,
            )
        return StoreOrderBoardItemResponse(
            orderReference = order.publicReference,
            pickupNumber = order.pickupNumber,
            pickupBusinessDate = order.pickupBusinessDate,
            lane = presentation.lane,
            status = state.name,
            pickupWindowStart = order.pickupWindowStart,
            pickupWindowEnd = order.pickupWindowEnd,
            itemSummary = itemSummary(order.lines.map { DisplayLine(it.menuName, it.quantity) }),
            acceptanceDeadlineAt = order.acceptanceDeadlineAt,
            acceptancePhase = presentation.acceptancePhase,
            allowedActions = presentation.allowedActions,
            lifecycle = lifecycle.toBoardResponse(),
            compensationRecovery = result.compensationRecovery,
        )
    }

    private fun item(
        order: StoreOrderBoardOrderProjection,
        lines: List<StoreOrderBoardLineProjection>,
        compensationRecovery: StoreCompensationSummary?,
        now: Instant,
    ): StoreOrderBoardItemResponse {
        val state = parseState(order.state)
        val lifecycle = order.lifecycle()
        OrderLifecycleProjection.validate(state, lifecycle)
        if (order.pickupSequence <= 0 || !order.pickupWindowEnd.isAfter(order.pickupWindowStart)) {
            dependency("Store order board projection is invalid")
        }
        PublicOrderReference.parse(order.publicReference)
        val presentation =
            StoreOrderBoardPresentationPolicy.present(
                state,
                order.acceptanceWarningAt,
                order.acceptanceDeadlineAt,
                now,
            )
        return StoreOrderBoardItemResponse(
            orderReference = order.publicReference,
            pickupNumber = "A-${order.pickupSequence}",
            pickupBusinessDate = order.pickupBusinessDate,
            lane = presentation.lane,
            status = state.name,
            pickupWindowStart = order.pickupWindowStart,
            pickupWindowEnd = order.pickupWindowEnd,
            itemSummary = itemSummary(lines.sortedBy { it.lineSequence }.map { DisplayLine(it.menuName, it.quantity) }),
            acceptanceDeadlineAt = order.acceptanceDeadlineAt,
            acceptancePhase = presentation.acceptancePhase,
            allowedActions = presentation.allowedActions,
            lifecycle = lifecycle.toBoardResponse(),
            compensationRecovery = compensationRecovery,
        )
    }

    private fun items(
        rows: StoreOrderBoardRows,
        now: Instant,
    ): List<StoreOrderBoardItemResponse> =
        rows.orders.map { order ->
            item(order, rows.linesByOrderId[order.orderId].orEmpty(), null, now)
        }

    private fun itemSummary(lines: List<DisplayLine>): String {
        if (lines.isEmpty() || lines.any { it.menuName.isBlank() || it.quantity <= 0 }) {
            dependency("Store order has no displayable line")
        }
        val first = "${lines.first().menuName} × ${lines.first().quantity}"
        return if (lines.size == 1) first else "$first 외 ${lines.size - 1}건"
    }

    private fun parseState(raw: String): OrderState =
        try {
            OrderState.valueOf(raw)
        } catch (_: IllegalArgumentException) {
            dependency("Store order state is unsupported")
        }

    private fun StoreOrderBoardOrderProjection.lifecycle(): PersistedOrderLifecycle =
        PersistedOrderLifecycle(paidAt, acceptedAt, preparingAt, readyAt, completedAt)

    private fun PersistedOrderLifecycle.toBoardResponse(): StoreOrderBoardLifecycleResponse? =
        takeIf(PersistedOrderLifecycle::hasOccurredEvent)?.let {
            StoreOrderBoardLifecycleResponse(it.paidAt, it.acceptedAt, it.preparingAt, it.readyAt)
        }

    private fun dependency(message: String): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)

    private data class DisplayLine(
        val menuName: String,
        val quantity: Long,
    )
}

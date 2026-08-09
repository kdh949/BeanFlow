package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.merchant.api.CurrentMenuLineQuoteResult
import io.github.kdh949.beanflow.merchant.api.MenuItemUnavailability
import io.github.kdh949.beanflow.merchant.api.MenuLineQuote
import io.github.kdh949.beanflow.merchant.api.MenuQuoteUseCase
import io.github.kdh949.beanflow.merchant.api.QuoteOrderLine
import io.github.kdh949.beanflow.ordering.api.CreateOrderCommand
import io.github.kdh949.beanflow.ordering.api.CreateOrderLineCommand
import io.github.kdh949.beanflow.ordering.api.ReorderOrderCommand
import io.github.kdh949.beanflow.ordering.api.StoredHttpResponse
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

internal data class FastReorderExecution(
    val response: StoredHttpResponse,
    val sourceLineCount: Int,
    val changedPriceLineCount: Int,
)

@Service
internal class FastReorderTransaction(
    private val orderRepository: OrderJpaRepository,
    private val orderLineRepository: OrderLineJpaRepository,
    private val menuQuoteUseCase: MenuQuoteUseCase,
    private val workflow: OrderCreationWorkflow,
    private val responseFactory: FastReorderResponseFactory,
    private val idempotencyService: OrderIdempotencyService,
) {
    @Transactional
    fun create(
        idempotencyRecordId: UUID,
        orderId: UUID,
        command: ReorderOrderCommand,
    ): FastReorderExecution {
        val source =
            orderRepository.findLockedById(command.sourceOrderId)
                ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Source order was not found")
        if (source.customerId != command.customerId) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Source order belongs to another customer")
        }
        if (!FastReorderSourcePolicy.isAllowed(source.state)) {
            throw DomainFailure(FailureCode.REORDER_SOURCE_STATE_INVALID, "Source order state does not allow reorder")
        }
        val sourceLines = orderLineRepository.findAllByOrderIdOrderByLineSequence(source.id)
        if (sourceLines.isEmpty()) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Source order has no immutable lines")
        }

        val failures = mutableListOf<ReorderItemFailureDetail>()
        val currentCandidates = mutableListOf<Pair<OrderLineEntity, QuoteOrderLine>>()
        sourceLines.forEach { line ->
            val optionIds = line.normalizedOptionIds
            if (line.optionSelectionSnapshotState != OptionSelectionSnapshotState.SNAPSHOTTED || optionIds == null) {
                failures +=
                    ReorderItemFailureDetail(
                        sourceOrderLineId = line.id,
                        lineSequence = line.lineSequence,
                        menuId = line.menuId,
                        optionId = null,
                        reason = SOURCE_OPTION_SELECTION_UNAVAILABLE,
                    )
            } else {
                currentCandidates += line to QuoteOrderLine(line.menuId, optionIds, line.quantity)
            }
        }

        val availableQuotes = mutableMapOf<UUID, MenuLineQuote>()
        if (currentCandidates.isNotEmpty()) {
            val results =
                menuQuoteUseCase.quoteCurrentBatch(
                    source.storeId,
                    currentCandidates.map(Pair<OrderLineEntity, QuoteOrderLine>::second),
                )
            if (results.size != currentCandidates.size) {
                throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Merchant batch result count is invalid")
            }
            currentCandidates.zip(results).forEach { (candidate, result) ->
                val line = candidate.first
                when (result) {
                    is CurrentMenuLineQuoteResult.Available -> {
                        availableQuotes[line.id] = result.quote
                    }

                    is CurrentMenuLineQuoteResult.Unavailable -> {
                        failures += result.failures.map { it.toDetail(line) }
                    }
                }
            }
        }
        if (failures.isNotEmpty()) {
            throw ReorderItemsUnavailableFailure(sourceLines.size, failures.sortedWith(FAILURE_ORDER))
        }

        val createCommand =
            CreateOrderCommand(
                customerId = command.customerId,
                storeId = source.storeId,
                pickupSlotId = command.pickupSlotId,
                lines =
                    sourceLines.map { line ->
                        CreateOrderLineCommand(
                            menuId = line.menuId,
                            optionIds = requireNotNull(line.normalizedOptionIds),
                            quantity = line.quantity,
                        )
                    },
                couponIssuanceId = command.couponIssuanceId,
                pointsToUseKrw = command.pointsToUseKrw,
            )
        val quotes = sourceLines.map { line -> requireNotNull(availableQuotes[line.id]) }
        val outcome = workflow.create(orderId, createCommand, quotes)
        val comparison = FastReorderPriceComparison.calculate(sourceLines, outcome.order)
        val response = responseFactory.create(outcome, comparison)
        idempotencyService.complete(idempotencyRecordId, outcome.order.id, response)
        return FastReorderExecution(response, sourceLines.size, comparison.items.size)
    }

    private fun MenuItemUnavailability.toDetail(line: OrderLineEntity): ReorderItemFailureDetail =
        ReorderItemFailureDetail(
            sourceOrderLineId = line.id,
            lineSequence = line.lineSequence,
            menuId = line.menuId,
            optionId = optionId,
            reason = reason.name,
        )

    private companion object {
        const val SOURCE_OPTION_SELECTION_UNAVAILABLE = "SOURCE_OPTION_SELECTION_UNAVAILABLE"
        val REASON_ORDER =
            listOf(
                SOURCE_OPTION_SELECTION_UNAVAILABLE,
                "MENU_REMOVED",
                "MENU_NOT_AVAILABLE",
                "OPTION_REMOVED",
                "OPTION_NOT_AVAILABLE",
                "MENU_CONFIGURATION_NOT_AVAILABLE",
            ).withIndex().associate { it.value to it.index }
        val FAILURE_ORDER =
            compareBy<ReorderItemFailureDetail>(
                ReorderItemFailureDetail::lineSequence,
                { REASON_ORDER.getValue(it.reason) },
                { it.optionId },
            )
    }
}

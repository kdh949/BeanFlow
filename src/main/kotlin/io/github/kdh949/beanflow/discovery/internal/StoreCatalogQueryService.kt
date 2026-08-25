package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.discovery.api.StoreCatalogQueryOperations
import io.github.kdh949.beanflow.discovery.api.StoreMenuItemOptionView
import io.github.kdh949.beanflow.discovery.api.StoreMenuItemView
import io.github.kdh949.beanflow.discovery.api.StorePickupSlotView
import io.github.kdh949.beanflow.fulfillment.api.PickupSlotQueryOperations
import io.github.kdh949.beanflow.fulfillment.api.PickupSlotView
import io.github.kdh949.beanflow.merchant.api.StoreMenuOptionView
import io.github.kdh949.beanflow.merchant.api.StoreMenuQueryOperations
import io.github.kdh949.beanflow.merchant.api.StoreMenuView
import io.github.kdh949.beanflow.merchant.api.StorePolicyScopeOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionException
import java.time.Instant
import java.util.UUID

internal enum class StoreCatalogOperation {
    MENUS,
    PICKUP_SLOTS,
}

internal enum class StoreCatalogOutcome {
    SUCCEEDED,
    NOT_FOUND,
    DEPENDENCY_UNAVAILABLE,
}

/**
 * Public store catalogue reads.
 *
 * Discovery calls Merchant and Fulfillment public Query APIs only. Store existence is answered by
 * Merchant, the catalogue owner, so a missing store is `404` while a persistence failure stays
 * `503` and is never turned into `404` or an empty list.
 */
@Service
internal class StoreCatalogQueryService(
    private val storeScope: StorePolicyScopeOperations,
    private val menus: StoreMenuQueryOperations,
    private val pickupSlots: PickupSlotQueryOperations,
    private val metrics: StoreCatalogMetrics,
    private val imageViews: StorefrontImageViewResolver,
) : StoreCatalogQueryOperations {
    override fun listMenus(storeId: UUID): List<StoreMenuItemView> =
        observed(StoreCatalogOperation.MENUS) {
            menus.listMenus(storeId).map { it.toCatalogView(imageViews) }
        }

    override fun listPickupSlots(
        storeId: UUID,
        now: Instant,
    ): List<StorePickupSlotView> =
        observed(StoreCatalogOperation.PICKUP_SLOTS) {
            // Fulfillment owns slots but not store identity, so the catalogue owner answers 404.
            // A store that is not accepting orders, or has pickup disabled, has no reservable slot
            // at all: order creation rejects every one of them. Listing none keeps ADR-076's
            // contract that a listed slot is a slot that can be reserved right now.
            if (storeScope.pickupOrderingAvailable(storeId)) {
                pickupSlots.listOpenSlots(storeId, now).map(PickupSlotView::toCatalogView)
            } else {
                emptyList()
            }
        }

    private fun <T> observed(
        operation: StoreCatalogOperation,
        block: () -> T,
    ): T =
        try {
            block().also { metrics.record(operation, StoreCatalogOutcome.SUCCEEDED) }
        } catch (failure: DomainFailure) {
            metrics.record(operation, failure.toOutcome())
            throw failure
        } catch (failure: TransactionException) {
            metrics.record(operation, StoreCatalogOutcome.DEPENDENCY_UNAVAILABLE)
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Store catalogue read transaction could not complete",
            ).also { it.initCause(failure) }
        }
}

private fun StoreMenuView.toCatalogView(imageViews: StorefrontImageViewResolver) =
    StoreMenuItemView(
        menuId = menuId,
        name = name,
        basePriceKrw = basePriceKrw,
        currency = KRW,
        available = available,
        options = options.map(StoreMenuOptionView::toCatalogView),
        image = imageViews.resolve(imageThumbnailKey),
    )

private fun StoreMenuOptionView.toCatalogView() =
    StoreMenuItemOptionView(
        optionId = optionId,
        name = name,
        additionalPriceKrw = additionalPriceKrw,
        available = available,
    )

private fun PickupSlotView.toCatalogView() =
    StorePickupSlotView(
        pickupSlotId = pickupSlotId,
        startsAt = startsAt,
        endsAt = endsAt,
        remainingCapacity = remainingCapacity,
    )

private const val KRW = "KRW"

private fun DomainFailure.toOutcome(): StoreCatalogOutcome =
    when (code) {
        FailureCode.RESOURCE_NOT_FOUND -> StoreCatalogOutcome.NOT_FOUND
        else -> StoreCatalogOutcome.DEPENDENCY_UNAVAILABLE
    }

/** Closed operation and outcome vocabularies only. Store IDs are never used as metric tags. */
@Component
internal class StoreCatalogMetrics(
    private val meterRegistry: MeterRegistry,
) {
    fun record(
        operation: StoreCatalogOperation,
        outcome: StoreCatalogOutcome,
    ) {
        meterRegistry
            .counter(
                "beanflow.discovery.store_catalog.read.count",
                "operation",
                operation.name,
                "outcome",
                outcome.name,
            ).increment()
    }
}

package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.StoreMenuOptionView
import io.github.kdh949.beanflow.merchant.api.StoreMenuQueryOperations
import io.github.kdh949.beanflow.merchant.api.StoreMenuView
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class StoreMenuQueryService(
    private val storeRepository: StoreJpaRepository,
    private val repository: StoreMenuQueryRepository,
) : StoreMenuQueryOperations {
    @Transactional(readOnly = true)
    override fun listMenus(storeId: UUID): List<StoreMenuView> {
        val storeExists =
            try {
                storeRepository.existsById(storeId)
            } catch (failure: DataAccessException) {
                unavailable(failure)
            }
        // A store with no menus is a legitimate empty list; a store that does not exist is 404.
        if (!storeExists) throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Store was not found")

        val menus =
            try {
                repository.findMenus(storeId)
            } catch (failure: DataAccessException) {
                unavailable(failure)
            }
        if (menus.isEmpty()) return emptyList()
        requireWithinBound(menus.size, MAX_STORE_MENUS, "menus")

        val options =
            try {
                repository.findOptions(storeId)
            } catch (failure: DataAccessException) {
                unavailable(failure)
            }
        requireWithinBound(options.size, MAX_STORE_MENU_OPTIONS, "menu options")
        val optionsByMenu = options.groupBy(StoreMenuOptionProjection::menuId)
        return menus.map { menu ->
            StoreMenuView(
                menuId = menu.menuId,
                name = menu.name,
                basePriceKrw = menu.basePriceKrw,
                available = menu.available,
                imageThumbnailKey = menu.imageThumbnailKey,
                options =
                    optionsByMenu[menu.menuId].orEmpty().map { option ->
                        StoreMenuOptionView(
                            optionId = option.optionId,
                            name = option.name,
                            additionalPriceKrw = option.additionalPriceKrw,
                            available = option.available,
                        )
                    },
            )
        }
    }

    /**
     * The repository asks for one row past the bound, so [actual] exceeding [bound] means the store
     * really is over the published limit. The read fails instead of returning the truncated rows,
     * because a client cannot tell a truncated catalogue from a complete one.
     */
    private fun requireWithinBound(
        actual: Int,
        bound: Int,
        what: String,
    ) {
        if (actual > bound) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Store catalogue exceeds the published bound of $bound $what",
            )
        }
    }

    private fun unavailable(cause: Throwable): Nothing =
        throw DomainFailure(
            FailureCode.DEPENDENCY_UNAVAILABLE,
            "Store menu catalogue is unavailable",
        ).also { it.initCause(cause) }
}

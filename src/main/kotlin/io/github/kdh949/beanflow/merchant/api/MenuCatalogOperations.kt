package io.github.kdh949.beanflow.merchant.api

import java.time.Instant
import java.util.UUID

enum class MenuCatalogLifecycle {
    ACTIVE,
    ARCHIVED,
}

data class MenuOptionTradeContent(
    val optionId: UUID,
    val name: String,
    val additionalPriceKrw: Long,
    val available: Boolean,
)

data class MenuSellableRequirement(
    val sellableUnitId: UUID,
    val quantityPerLineUnit: Long,
)

data class MenuConfigurationTradeContent(
    val configurationId: UUID,
    val selectedOptionIds: List<UUID>,
    val available: Boolean,
    val requirements: List<MenuSellableRequirement>,
)

data class MenuTradeContent(
    val menuId: UUID,
    val name: String,
    val basePriceKrw: Long,
    val available: Boolean,
    val lifecycle: MenuCatalogLifecycle,
    val options: List<MenuOptionTradeContent>,
    val configurations: List<MenuConfigurationTradeContent>,
    val version: Long,
    val updatedAt: Instant,
)

data class MenuCatalogSummary(
    val menuId: UUID,
    val name: String,
    val basePriceKrw: Long,
    val available: Boolean,
    val lifecycle: MenuCatalogLifecycle,
    val optionCount: Int,
    val configurationCount: Int,
    val version: Long,
    val updatedAt: Instant,
)

data class MenuCatalogPage(
    val items: List<MenuCatalogSummary>,
    val nextName: String?,
    val nextMenuId: UUID?,
)

data class MenuTradeDefinition(
    val menuId: UUID,
    val name: String,
    val basePriceKrw: Long,
    val available: Boolean,
    val options: List<MenuOptionTradeContent>,
    val configurations: List<MenuConfigurationTradeContent>,
)

data class CreateMenuCatalogCommand(
    val actorId: UUID,
    val idempotencyKey: String,
    val storeId: UUID,
    val definition: MenuTradeDefinition,
    val now: Instant,
)

data class ReplaceMenuTradeContentCommand(
    val actorId: UUID,
    val idempotencyKey: String,
    val storeId: UUID,
    val menuId: UUID,
    val expectedVersion: Long,
    val definition: MenuTradeDefinition,
    val now: Instant,
)

data class ArchiveMenuCatalogCommand(
    val actorId: UUID,
    val idempotencyKey: String,
    val storeId: UUID,
    val menuId: UUID,
    val expectedVersion: Long,
    val now: Instant,
)

data class MenuCatalogMutation(
    val content: MenuTradeContent,
    val previous: MenuTradeContent?,
    val changed: Boolean,
    val replayed: Boolean,
)

/** Menu Aggregate owner port. Every call joins the caller's membership-lock transaction. */
interface MenuCatalogOperations {
    fun list(
        storeId: UUID,
        lifecycle: MenuCatalogLifecycle,
        afterName: String?,
        afterMenuId: UUID?,
        limit: Int,
    ): MenuCatalogPage

    fun find(
        storeId: UUID,
        menuId: UUID,
    ): MenuTradeContent

    fun create(command: CreateMenuCatalogCommand): MenuCatalogMutation

    fun replace(command: ReplaceMenuTradeContentCommand): MenuCatalogMutation

    fun archive(command: ArchiveMenuCatalogCommand): MenuCatalogMutation
}

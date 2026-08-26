package io.github.kdh949.beanflow.merchant.api

import java.util.UUID

data class MenuDisplayContentSnapshot(
    val storeId: UUID,
    val menuId: UUID,
    val displayCategory: String?,
    val description: String?,
    val version: Long,
)

data class ReplaceMenuDisplayContentCommand(
    val storeId: UUID,
    val menuId: UUID,
    val expectedVersion: Long,
    val displayCategory: String?,
    val description: String?,
)

data class MenuDisplayContentChange(
    val previous: MenuDisplayContentSnapshot,
    val current: MenuDisplayContentSnapshot,
    val changed: Boolean,
)

interface MenuDisplayContentOperations {
    fun find(
        storeId: UUID,
        menuId: UUID,
    ): MenuDisplayContentSnapshot

    fun replace(command: ReplaceMenuDisplayContentCommand): MenuDisplayContentChange
}

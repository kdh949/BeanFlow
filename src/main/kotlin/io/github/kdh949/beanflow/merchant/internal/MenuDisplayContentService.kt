package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.MenuDisplayContentChange
import io.github.kdh949.beanflow.merchant.api.MenuDisplayContentOperations
import io.github.kdh949.beanflow.merchant.api.MenuDisplayContentSnapshot
import io.github.kdh949.beanflow.merchant.api.ReplaceMenuDisplayContentCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class MenuDisplayContentService(
    private val menus: MenuJpaRepository,
) : MenuDisplayContentOperations {
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    override fun find(
        storeId: UUID,
        menuId: UUID,
    ): MenuDisplayContentSnapshot =
        persistence {
            menus.findByIdAndStoreId(menuId, storeId)?.snapshot() ?: notFound()
        }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun replace(command: ReplaceMenuDisplayContentCommand): MenuDisplayContentChange =
        persistence {
            if (command.expectedVersion < 0) invalid("Expected version must not be negative")
            val category = text(command.displayCategory, MAX_CATEGORY_LENGTH, "Display category")
            val description = text(command.description, MAX_DESCRIPTION_LENGTH, "Menu description")
            val menu = menus.findByIdAndStoreIdForUpdate(command.menuId, command.storeId) ?: notFound()
            val previous = menu.snapshot()
            if (previous.version != command.expectedVersion) stale()
            if (previous.displayCategory == category && previous.description == description) {
                return@persistence MenuDisplayContentChange(previous, previous, false)
            }
            menu.replaceDisplayContent(category, description)
            val current = menus.saveAndFlush(menu).snapshot()
            if (current.version != nextVersion(previous.version)) {
                throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Menu display version did not advance")
            }
            MenuDisplayContentChange(previous, current, true)
        }

    private fun MenuEntity.snapshot() =
        MenuDisplayContentSnapshot(
            storeId = storeId,
            menuId = id,
            displayCategory = displayCategory,
            description = publicDescription,
            version = version,
        )

    private fun text(
        raw: String?,
        maximumLength: Int,
        field: String,
    ): String? {
        if (raw == null) return null
        val normalized = raw.trim()
        val length = normalized.codePointCount(0, normalized.length)
        if (length !in 1..maximumLength || normalized.any(Char::isISOControl)) invalid("$field is invalid")
        return normalized
    }

    private fun nextVersion(current: Long): Long =
        try {
            Math.addExact(current, 1)
        } catch (failure: ArithmeticException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Menu display version is exhausted").also {
                it.initCause(failure)
            }
        }

    private fun <T> persistence(block: () -> T): T =
        try {
            block()
        } catch (failure: DomainFailure) {
            throw failure
        } catch (failure: DataAccessException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Menu display content is unavailable").also {
                it.initCause(failure)
            }
        }

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    private fun stale(): Nothing = throw DomainFailure(FailureCode.MERCHANT_CONTENT_STALE, "Menu display version is stale")

    private fun notFound(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Menu was not found")

    private companion object {
        const val MAX_CATEGORY_LENGTH = 50
        const val MAX_DESCRIPTION_LENGTH = 500
    }
}

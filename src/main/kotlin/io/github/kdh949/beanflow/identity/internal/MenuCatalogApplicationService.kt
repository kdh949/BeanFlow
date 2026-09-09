package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.identity.api.StoreAccessOperations
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.merchant.api.ArchiveMenuCatalogCommand
import io.github.kdh949.beanflow.merchant.api.CreateMenuCatalogCommand
import io.github.kdh949.beanflow.merchant.api.MenuCatalogLifecycle
import io.github.kdh949.beanflow.merchant.api.MenuCatalogOperations
import io.github.kdh949.beanflow.merchant.api.MenuCatalogSummary
import io.github.kdh949.beanflow.merchant.api.MenuTradeContent
import io.github.kdh949.beanflow.merchant.api.MenuTradeDefinition
import io.github.kdh949.beanflow.merchant.api.ReplaceMenuTradeContentCommand
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.HexFormat
import java.util.UUID

internal data class MenuCatalogPageView(
    val items: List<MenuCatalogSummary>,
    val nextCursor: String?,
)

internal data class MenuCatalogCommandContext(
    val actorId: UUID,
    val idempotencyKey: String,
)

internal data class MenuCatalogSort(
    val name: String,
    val menuId: UUID,
)

@Service
internal class MenuCatalogApplicationService(
    private val storeAccess: StoreAccessOperations,
    private val catalog: MenuCatalogOperations,
    private val cursors: SignedCursorCodec,
    private val audits: AuditRecordOperations,
    private val correlationIds: CorrelationIdSource,
    private val clock: Clock,
) {
    @Transactional
    fun list(
        actorId: UUID,
        storeId: UUID,
        lifecycle: MenuCatalogLifecycle,
        cursor: String?,
        limit: Int?,
    ): MenuCatalogPageView {
        storeAccess.requireCatalogAccess(actorId, storeId, ALLOWED_ROLES)
        val pageSize = limit ?: DEFAULT_PAGE_SIZE
        if (pageSize !in 1..MAX_PAGE_SIZE) invalid("limit must be between 1 and $MAX_PAGE_SIZE")
        val scope = cursorScope(actorId, storeId, lifecycle, pageSize)
        val after = cursor?.let { cursors.verify(it, scope).sort }
        val page = catalog.list(storeId, lifecycle, after?.name, after?.menuId, pageSize)
        val nextCursor =
            if (page.nextName != null && page.nextMenuId != null) {
                cursors.issue(scope, MenuCatalogSort(page.nextName, page.nextMenuId), clock.instant().plus(CURSOR_TTL))
            } else {
                null
            }
        return MenuCatalogPageView(page.items, nextCursor)
    }

    @Transactional
    fun find(
        actorId: UUID,
        storeId: UUID,
        menuId: UUID,
    ): MenuTradeContent {
        storeAccess.requireCatalogAccess(actorId, storeId, ALLOWED_ROLES)
        return catalog.find(storeId, menuId)
    }

    @Transactional
    fun create(
        context: MenuCatalogCommandContext,
        storeId: UUID,
        definition: MenuTradeDefinition,
    ): MenuTradeContent {
        val actor = storeAccess.requireCatalogAccess(context.actorId, storeId, ALLOWED_ROLES)
        val now = clock.instant()
        val result = catalog.create(CreateMenuCatalogCommand(context.actorId, context.idempotencyKey, storeId, definition, now))
        if (result.changed) audit(actor.role, context, result.content, null, ACTION_CREATED, now)
        return result.content
    }

    @Transactional
    fun replace(
        context: MenuCatalogCommandContext,
        storeId: UUID,
        menuId: UUID,
        expectedVersion: Long,
        definition: MenuTradeDefinition,
    ): MenuTradeContent {
        val actor = storeAccess.requireCatalogAccess(context.actorId, storeId, ALLOWED_ROLES)
        val now = clock.instant()
        val result =
            catalog.replace(
                ReplaceMenuTradeContentCommand(
                    context.actorId,
                    context.idempotencyKey,
                    storeId,
                    menuId,
                    expectedVersion,
                    definition,
                    now,
                ),
            )
        if (result.changed) audit(actor.role, context, result.content, result.previous, ACTION_UPDATED, now)
        return result.content
    }

    @Transactional
    fun archive(
        context: MenuCatalogCommandContext,
        storeId: UUID,
        menuId: UUID,
        expectedVersion: Long,
    ): MenuTradeContent {
        val actor = storeAccess.requireCatalogAccess(context.actorId, storeId, ALLOWED_ROLES)
        val now = clock.instant()
        val result =
            catalog.archive(
                ArchiveMenuCatalogCommand(context.actorId, context.idempotencyKey, storeId, menuId, expectedVersion, now),
            )
        if (result.changed) audit(actor.role, context, result.content, result.previous, ACTION_ARCHIVED, now)
        return result.content
    }

    private fun audit(
        role: StoreActorRole,
        context: MenuCatalogCommandContext,
        after: MenuTradeContent,
        before: MenuTradeContent?,
        action: String,
        now: java.time.Instant,
    ) {
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = context.actorId.toString(),
                    actorType = if (role == StoreActorRole.OWNER) AuditActorType.STORE_OWNER else AuditActorType.STORE_STAFF,
                    category = AuditCategory.OPERATIONS_POLICY,
                    action = action,
                    targetType = "Menu",
                    targetId = after.menuId,
                    occurredAt = now,
                    reason = "MERCHANT_MENU_CATALOG_CHANGE",
                    beforeSummary = before?.auditSummary().orEmpty(),
                    afterSummary = after.auditSummary(),
                    correlationId = correlationIds.currentOrCreate(),
                    sourceReference = "menu-catalog:${context.actorId}:${sha256(context.idempotencyKey)}",
                ),
            ),
        )
    }

    private fun MenuTradeContent.auditSummary(): Map<String, String> =
        mapOf(
            "lifecycle" to lifecycle.name,
            "available" to available.toString(),
            "optionCount" to options.size.toString(),
            "configurationCount" to configurations.size.toString(),
            "version" to version.toString(),
        )

    private fun cursorScope(
        actorId: UUID,
        storeId: UUID,
        lifecycle: MenuCatalogLifecycle,
        limit: Int,
    ): SignedCursorScope<MenuCatalogSort> =
        SignedCursorScope(
            endpoint = CURSOR_ENDPOINT,
            filterHash = sha256("$CURSOR_ENDPOINT\n$actorId\n$storeId\n${lifecycle.name}\n$limit"),
            sortAdapter =
                object : CursorSortAdapter<MenuCatalogSort> {
                    override fun encode(sort: MenuCatalogSort): List<String> = listOf(sort.name, sort.menuId.toString())

                    override fun decode(values: List<String>): MenuCatalogSort? {
                        if (values.size != 2) return null
                        val menuId = runCatching { UUID.fromString(values[1]) }.getOrNull() ?: return null
                        return MenuCatalogSort(values[0], menuId)
                    }
                },
        )

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    private companion object {
        val ALLOWED_ROLES = setOf(StoreActorRole.OWNER, StoreActorRole.STAFF)
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 50
        const val CURSOR_ENDPOINT = "merchant-menu-catalog"
        val CURSOR_TTL: Duration = Duration.ofMinutes(30)
        const val ACTION_CREATED = "MENU_CATALOG_CREATED"
        const val ACTION_UPDATED = "MENU_CATALOG_UPDATED"
        const val ACTION_ARCHIVED = "MENU_CATALOG_ARCHIVED"
    }
}

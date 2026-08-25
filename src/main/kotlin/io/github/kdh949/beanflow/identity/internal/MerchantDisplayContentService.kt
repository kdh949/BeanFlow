package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.identity.api.StoreAccessOperations
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.merchant.api.MenuDisplayContentChange
import io.github.kdh949.beanflow.merchant.api.MenuDisplayContentOperations
import io.github.kdh949.beanflow.merchant.api.MenuDisplayContentSnapshot
import io.github.kdh949.beanflow.merchant.api.ReplaceMenuDisplayContentCommand
import io.github.kdh949.beanflow.merchant.api.ReplaceStoreCustomerDisplayCommand
import io.github.kdh949.beanflow.merchant.api.StoreCustomerDisplayChange
import io.github.kdh949.beanflow.merchant.api.StoreCustomerDisplayOperations
import io.github.kdh949.beanflow.merchant.api.StoreCustomerDisplaySnapshot
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class MerchantDisplayContentService(
    private val transactions: MerchantDisplayContentTransaction,
    private val clock: Clock,
) {
    fun profile(
        actorId: UUID,
        storeId: UUID,
    ): StoreCustomerDisplaySnapshot = transactions.profile(actorId, storeId)

    fun replaceProfile(
        actorId: UUID,
        command: ReplaceStoreCustomerDisplayCommand,
    ): StoreCustomerDisplaySnapshot = transactions.replaceProfile(actorId, command, clock.instant())

    fun menu(
        actorId: UUID,
        storeId: UUID,
        menuId: UUID,
    ): MenuDisplayContentSnapshot = transactions.menu(actorId, storeId, menuId)

    fun replaceMenu(
        actorId: UUID,
        command: ReplaceMenuDisplayContentCommand,
    ): MenuDisplayContentSnapshot = transactions.replaceMenu(actorId, command, clock.instant())
}

@Component
internal class MerchantDisplayContentTransaction(
    private val storeAccess: StoreAccessOperations,
    private val profiles: StoreCustomerDisplayOperations,
    private val menus: MenuDisplayContentOperations,
    private val audits: AuditRecordOperations,
    private val correlationIds: CorrelationIdSource,
) {
    @Transactional(readOnly = true)
    fun profile(
        actorId: UUID,
        storeId: UUID,
    ): StoreCustomerDisplaySnapshot {
        storeAccess.requireStoreAccess(actorId, storeId, PROFILE_ROLES)
        return profiles.find(storeId)
    }

    @Transactional
    fun replaceProfile(
        actorId: UUID,
        command: ReplaceStoreCustomerDisplayCommand,
        now: Instant,
    ): StoreCustomerDisplaySnapshot {
        storeAccess.requireStoreAccess(actorId, command.storeId, PROFILE_ROLES)
        val change = profiles.replace(command, now)
        if (change.changed) auditProfile(actorId, command.storeId, change, now)
        return change.current
    }

    @Transactional(readOnly = true)
    fun menu(
        actorId: UUID,
        storeId: UUID,
        menuId: UUID,
    ): MenuDisplayContentSnapshot {
        storeAccess.requireStoreAccess(actorId, storeId, MENU_ROLES)
        return menus.find(storeId, menuId)
    }

    @Transactional
    fun replaceMenu(
        actorId: UUID,
        command: ReplaceMenuDisplayContentCommand,
        now: Instant,
    ): MenuDisplayContentSnapshot {
        val actor = storeAccess.requireStoreAccess(actorId, command.storeId, MENU_ROLES)
        val change = menus.replace(command)
        if (change.changed) auditMenu(actorId, actor.role, command.menuId, change, now)
        return change.current
    }

    private fun auditProfile(
        actorId: UUID,
        storeId: UUID,
        change: StoreCustomerDisplayChange,
        now: Instant,
    ) {
        append(
            actorId = actorId,
            actorType = AuditActorType.STORE_OWNER,
            action = "STORE_CUSTOMER_DISPLAY_UPDATED",
            targetType = "Store",
            targetId = storeId,
            reason = "MERCHANT_STORE_CUSTOMER_DISPLAY_CHANGE",
            before = change.previous.summary(),
            after = change.current.summary(),
            now = now,
        )
    }

    private fun auditMenu(
        actorId: UUID,
        role: StoreActorRole,
        menuId: UUID,
        change: MenuDisplayContentChange,
        now: Instant,
    ) {
        append(
            actorId = actorId,
            actorType = if (role == StoreActorRole.OWNER) AuditActorType.STORE_OWNER else AuditActorType.STORE_STAFF,
            action = "MENU_DISPLAY_CONTENT_UPDATED",
            targetType = "Menu",
            targetId = menuId,
            reason = "MERCHANT_MENU_DISPLAY_CONTENT_CHANGE",
            before = change.previous.summary(),
            after = change.current.summary(),
            now = now,
        )
    }

    private fun append(
        actorId: UUID,
        actorType: AuditActorType,
        action: String,
        targetType: String,
        targetId: UUID,
        reason: String,
        before: Map<String, String>,
        after: Map<String, String>,
        now: Instant,
    ) {
        val correlationId = correlationIds.currentOrCreate()
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = actorId.toString(),
                    actorType = actorType,
                    category = AuditCategory.OPERATIONS_POLICY,
                    action = action,
                    targetType = targetType,
                    targetId = targetId,
                    occurredAt = now,
                    reason = reason,
                    beforeSummary = before,
                    afterSummary = after,
                    correlationId = correlationId,
                    sourceReference = "$action:$correlationId",
                ),
            ),
        )
    }

    private fun StoreCustomerDisplaySnapshot.summary() =
        mapOf(
            "displayTextCount" to listOf(addressLine, directionsHint).count { it != null }.toString(),
            "scheduleState" to if (operatingHours == null) "ABSENT" else "COMPLETE",
            "version" to version.toString(),
        )

    private fun MenuDisplayContentSnapshot.summary() =
        mapOf(
            "displayTextCount" to listOf(displayCategory, description).count { it != null }.toString(),
            "version" to version.toString(),
        )

    private companion object {
        val PROFILE_ROLES = setOf(StoreActorRole.OWNER)
        val MENU_ROLES = setOf(StoreActorRole.OWNER, StoreActorRole.STAFF)
    }
}

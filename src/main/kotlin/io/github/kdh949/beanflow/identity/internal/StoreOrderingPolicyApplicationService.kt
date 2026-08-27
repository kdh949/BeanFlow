package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.identity.api.StoreAccessOperations
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.merchant.api.ReplaceStoreOrderingPolicyCommand
import io.github.kdh949.beanflow.merchant.api.StoreOrderingPolicyOperations
import io.github.kdh949.beanflow.merchant.api.StoreOrderingPolicySnapshot
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat
import java.util.UUID

internal data class StoreOrderingPolicyCommandContext(
    val actorId: UUID,
    val idempotencyKey: String,
)

@Service
internal class StoreOrderingPolicyApplicationService(
    private val storeAccess: StoreAccessOperations,
    private val policies: StoreOrderingPolicyOperations,
    private val audits: AuditRecordOperations,
    private val correlationIds: CorrelationIdSource,
    private val clock: Clock,
) {
    // PostgreSQL rejects SELECT FOR SHARE in a read-only transaction. Authoring GET deliberately
    // uses a writable transaction solely so the membership shared lock is held through the read.
    @Transactional
    fun find(
        actorId: UUID,
        storeId: UUID,
    ): StoreOrderingPolicySnapshot {
        storeAccess.requireCatalogAccess(actorId, storeId, ALLOWED_ROLES)
        return policies.find(storeId)
    }

    @Transactional
    fun replace(
        context: StoreOrderingPolicyCommandContext,
        storeId: UUID,
        acceptingOrders: Boolean,
        pickupEnabled: Boolean,
        expectedVersion: Long,
    ): StoreOrderingPolicySnapshot {
        val actor = storeAccess.requireCatalogAccess(context.actorId, storeId, ALLOWED_ROLES)
        val now = clock.instant()
        val replacement =
            policies.replace(
                ReplaceStoreOrderingPolicyCommand(
                    actorId = context.actorId,
                    idempotencyKey = context.idempotencyKey,
                    storeId = storeId,
                    acceptingOrders = acceptingOrders,
                    pickupEnabled = pickupEnabled,
                    expectedVersion = expectedVersion,
                    now = now,
                ),
            )
        if (replacement.changed) {
            audits.appendAll(
                listOf(
                    AppendAuditRecordCommand(
                        actorId = actor.actorId.toString(),
                        actorType =
                            when (actor.role) {
                                StoreActorRole.OWNER -> AuditActorType.STORE_OWNER
                                StoreActorRole.STAFF -> AuditActorType.STORE_STAFF
                            },
                        category = AuditCategory.OPERATIONS_POLICY,
                        action = ACTION,
                        targetType = TARGET_TYPE,
                        targetId = storeId,
                        occurredAt = now,
                        reason = REASON,
                        beforeSummary = replacement.previous.auditSummary(),
                        afterSummary = replacement.policy.auditSummary(),
                        correlationId = correlationIds.currentOrCreate(),
                        sourceReference =
                            "store-ordering-policy:${context.actorId}:${sha256(context.idempotencyKey)}",
                    ),
                ),
            )
        }
        return replacement.policy
    }

    private fun StoreOrderingPolicySnapshot.auditSummary(): Map<String, String> =
        mapOf(
            "acceptingOrders" to acceptingOrders.toString(),
            "pickupEnabled" to pickupEnabled.toString(),
            "version" to version.toString(),
        )

    private fun sha256(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8)))

    private companion object {
        val ALLOWED_ROLES = setOf(StoreActorRole.OWNER, StoreActorRole.STAFF)
        const val ACTION = "STORE_ORDERING_POLICY_UPDATED"
        const val TARGET_TYPE = "Store"
        const val REASON = "MERCHANT_STORE_ORDERING_POLICY_CHANGE"
    }
}

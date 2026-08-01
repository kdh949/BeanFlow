package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.merchant.api.StorePolicyScopeOperations
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.ListOrdinaryPointAccrualPolicyVersionsCommand
import io.github.kdh949.beanflow.operations.api.ListStorePointAccrualPolicyHeadsCommand
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyOperations
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyPage
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyQueryOperations
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyScopeType
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicySnapshot
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyState
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyVersionView
import io.github.kdh949.beanflow.operations.api.ReadOrdinaryPointAccrualPolicyCommand
import io.github.kdh949.beanflow.operations.api.StoreOrdinaryPointAccrualPolicyView
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import jakarta.persistence.EntityManager
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

@Service
internal class OrdinaryPointAccrualPolicyQueryService(
    private val queryPersistence: OrdinaryPointAccrualPolicyQueryPersistence,
    private val versionRepository: OrdinaryPointAccrualPolicyVersionJpaRepository,
    private val headRepository: OrdinaryPointAccrualPolicyHeadJpaRepository,
    private val selector: OrdinaryPointAccrualPolicyOperations,
    private val authorization: OperatorPermissionAuthorization,
    private val storePolicyScopeOperations: StorePolicyScopeOperations,
    private val signedCursorCodec: SignedCursorCodec,
    private val auditRecordOperations: AuditRecordOperations,
    private val correlationIdSource: CorrelationIdSource,
    private val identifierSource: IdentifierSource,
    private val entityManager: EntityManager,
    private val clock: Clock,
    private val metrics: OperatorSecurityMetrics,
) : OrdinaryPointAccrualPolicyQueryOperations {
    @Transactional
    override fun currentGlobal(command: ReadOrdinaryPointAccrualPolicyCommand): OrdinaryPointAccrualPolicyVersionView =
        readBoundary("GLOBAL_CURRENT", command.actorId, command.accessReason, command.now, GLOBAL_TARGET) {
            val head =
                headRepository.findShared(
                    OrdinaryPointAccrualPolicyScopeType.GLOBAL,
                    OrdinaryPointAccrualPolicySnapshot.GLOBAL_SCOPE_REFERENCE,
                ) ?: dependency("GLOBAL ordinary point accrual policy head is missing")
            version(head).toView()
        }

    @Transactional
    override fun globalHistory(
        command: ListOrdinaryPointAccrualPolicyVersionsCommand,
    ): OrdinaryPointAccrualPolicyPage<OrdinaryPointAccrualPolicyVersionView> =
        readBoundary("GLOBAL_HISTORY", command.actorId, command.accessReason, command.now, GLOBAL_TARGET) {
            historyPage(
                endpoint = GLOBAL_HISTORY_ENDPOINT,
                scopeType = OrdinaryPointAccrualPolicyScopeType.GLOBAL,
                scopeReference = OrdinaryPointAccrualPolicySnapshot.GLOBAL_SCOPE_REFERENCE,
                command = command,
            )
        }

    @Transactional
    override fun storeHeads(
        command: ListStorePointAccrualPolicyHeadsCommand,
    ): OrdinaryPointAccrualPolicyPage<OrdinaryPointAccrualPolicyVersionView> =
        readBoundary("STORE_HEADS", command.actorId, command.accessReason, command.now, STORE_COLLECTION_TARGET) {
            val limit = normalizeLimit(command.limit)
            val scope = storeHeadCursorScope(command.state)
            val before =
                command.cursor?.let { signedCursorCodec.verify(it, scope).sort }
                    ?: StorePolicyHeadSort(Long.MAX_VALUE, MAX_UUID)
            val fetched = queryPersistence.findStoreHeads(command.state, before, limit + 1)
            val items = fetched.take(limit)
            OrdinaryPointAccrualPolicyPage(
                items,
                nextCursor =
                    if (fetched.size > limit) {
                        val last = items.last()
                        signedCursorCodec.issue(
                            scope,
                            StorePolicyHeadSort(last.policyVersionId, last.scopeReference),
                            clock.instant().plus(CURSOR_TTL),
                        )
                    } else {
                        null
                    },
            )
        }

    @Transactional
    override fun currentStore(
        storeId: UUID,
        command: ReadOrdinaryPointAccrualPolicyCommand,
    ): StoreOrdinaryPointAccrualPolicyView =
        readBoundary("STORE_CURRENT", command.actorId, command.accessReason, command.now, storeId) {
            storePolicyScopeOperations.requireExisting(storeId)
            val selected = selector.selectForOrder(storeId)
            val explicitHead =
                headRepository
                    .findById(
                        OrdinaryPointAccrualPolicyHeadId(OrdinaryPointAccrualPolicyScopeType.STORE, storeId),
                    ).orElse(null)
                    ?.let(::version)
                    ?.toView()
            StoreOrdinaryPointAccrualPolicyView(
                storeId,
                explicitHead,
                selected.policy,
                selected.selectionSource,
            )
        }

    @Transactional
    override fun storeHistory(
        storeId: UUID,
        command: ListOrdinaryPointAccrualPolicyVersionsCommand,
    ): OrdinaryPointAccrualPolicyPage<OrdinaryPointAccrualPolicyVersionView> =
        readBoundary("STORE_HISTORY", command.actorId, command.accessReason, command.now, storeId) {
            storePolicyScopeOperations.requireExisting(storeId)
            historyPage(STORE_HISTORY_ENDPOINT_PREFIX + storeId, OrdinaryPointAccrualPolicyScopeType.STORE, storeId, command)
        }

    private fun historyPage(
        endpoint: String,
        scopeType: OrdinaryPointAccrualPolicyScopeType,
        scopeReference: UUID,
        command: ListOrdinaryPointAccrualPolicyVersionsCommand,
    ): OrdinaryPointAccrualPolicyPage<OrdinaryPointAccrualPolicyVersionView> {
        val limit = normalizeLimit(command.limit)
        val cursorScope = versionCursorScope(endpoint, scopeType, scopeReference)
        val before = command.cursor?.let { signedCursorCodec.verify(it, cursorScope).sort } ?: Long.MAX_VALUE
        val fetched = queryPersistence.findHistory(scopeType, scopeReference, before, limit + 1)
        val items = fetched.take(limit)
        return OrdinaryPointAccrualPolicyPage(
            items,
            if (fetched.size > limit) {
                signedCursorCodec.issue(cursorScope, items.last().policyVersionId, clock.instant().plus(CURSOR_TTL))
            } else {
                null
            },
        )
    }

    private fun <T> readBoundary(
        endpoint: String,
        actorId: UUID,
        accessReason: String,
        now: Instant,
        targetId: UUID,
        projection: () -> T,
    ): T =
        observedRead(endpoint) {
            persistenceBoundary {
                val reason = normalizeAccessReason(accessReason)
                authorization.requireActive(actorId, OperatorPermission.POINT_ACCRUAL_POLICY_READ)
                val result = projection()
                auditRecordOperations.appendAll(
                    listOf(
                        AppendAuditRecordCommand(
                            actorId = actorId.toString(),
                            actorType = AuditActorType.PLATFORM_OPERATOR,
                            action = "POINT_ACCRUAL_POLICY_READ",
                            targetType = endpoint,
                            targetId = targetId,
                            occurredAt = now,
                            reason = reason,
                            afterSummary = mapOf("endpoint" to endpoint),
                            correlationId = correlationIdSource.currentOrCreate(),
                            sourceReference = "point-accrual-policy-read:${identifierSource.next()}",
                        ),
                    ),
                )
                entityManager.flush()
                result
            }
        }

    private fun version(head: OrdinaryPointAccrualPolicyHeadEntity): OrdinaryPointAccrualPolicyVersionEntity =
        versionRepository
            .findById(head.policyVersionId)
            .orElseThrow { dependency("Ordinary point accrual policy head points to a missing version") }
            .also {
                if (it.scopeType != head.scopeType || it.scopeReference != head.scopeReference) {
                    dependency("Ordinary point accrual policy head scope does not match its version")
                }
            }

    private fun OrdinaryPointAccrualPolicyVersionEntity.toView() =
        OrdinaryPointAccrualPolicyVersionView(
            policyVersionId,
            scopeType,
            scopeReference,
            state,
            accrualRateBps,
            roundingMode,
            issuerType,
            issuerReference,
            expiryRule,
            validityDays,
            effectiveAt,
            actorType,
            actorReference,
            reason,
        )

    private fun versionCursorScope(
        endpoint: String,
        scopeType: OrdinaryPointAccrualPolicyScopeType,
        scopeReference: UUID,
    ) = SignedCursorScope(endpoint, sha256("$endpoint|${scopeType.name}|$scopeReference"), LONG_SORT_ADAPTER)

    private fun storeHeadCursorScope(state: OrdinaryPointAccrualPolicyState?) =
        SignedCursorScope(
            STORE_HEADS_ENDPOINT,
            sha256("$STORE_HEADS_ENDPOINT|state=${state?.name ?: "ALL"}"),
            STORE_HEAD_SORT_ADAPTER,
        )

    private fun normalizeLimit(limit: Int?): Int {
        val normalized = limit ?: DEFAULT_LIMIT
        if (normalized !in 1..MAX_LIMIT) invalid("Policy list limit must be between 1 and 100")
        return normalized
    }

    private fun normalizeAccessReason(reason: String): String {
        val normalized = reason.trim()
        if (normalized.length !in 1..200 || reason.hasControlCharacter()) {
            invalid("Access reason must contain between 1 and 200 non-control characters")
        }
        return normalized
    }

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

    private fun <T> persistenceBoundary(block: () -> T): T =
        try {
            block()
        } catch (failure: DomainFailure) {
            throw failure
        } catch (_: DataAccessException) {
            dependency("Ordinary point accrual policy query persistence is unavailable")
        }

    private fun <T> observedRead(
        endpoint: String,
        block: () -> T,
    ): T =
        try {
            block().also {
                TransactionSynchronizationManager.registerSynchronization(
                    object : TransactionSynchronization {
                        override fun afterCompletion(status: Int) {
                            metrics.pointAccrualPolicyRead(
                                endpoint,
                                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                                    OperatorSecurityOutcome.SUCCEEDED
                                } else {
                                    OperatorSecurityOutcome.DEPENDENCY_UNAVAILABLE
                                },
                            )
                        }
                    },
                )
            }
        } catch (failure: DomainFailure) {
            metrics.pointAccrualPolicyRead(endpoint, failure.toMetricOutcome())
            throw failure
        }

    private fun DomainFailure.toMetricOutcome(): OperatorSecurityOutcome =
        when (code) {
            FailureCode.INVALID_REQUEST -> OperatorSecurityOutcome.INVALID_INPUT
            FailureCode.RESOURCE_NOT_FOUND -> OperatorSecurityOutcome.NOT_FOUND
            FailureCode.ACCESS_DENIED -> OperatorSecurityOutcome.DENIED
            else -> OperatorSecurityOutcome.DEPENDENCY_UNAVAILABLE
        }

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    private fun dependency(message: String): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)

    private companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
        val CURSOR_TTL: Duration = Duration.ofMinutes(15)
        val GLOBAL_TARGET: UUID = OrdinaryPointAccrualPolicySnapshot.GLOBAL_SCOPE_REFERENCE
        val STORE_COLLECTION_TARGET: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val MAX_UUID: UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")
        const val GLOBAL_HISTORY_ENDPOINT = "ordinary-point-accrual/global/versions"
        const val STORE_HEADS_ENDPOINT = "ordinary-point-accrual/stores"
        const val STORE_HISTORY_ENDPOINT_PREFIX = "ordinary-point-accrual/stores/"
        val LONG_SORT_ADAPTER =
            object : CursorSortAdapter<Long> {
                override fun encode(sort: Long): List<String> = listOf(sort.toString())

                override fun decode(values: List<String>): Long? = values.singleOrNull()?.toLongOrNull()?.takeIf { it > 0 }
            }
        val STORE_HEAD_SORT_ADAPTER =
            object : CursorSortAdapter<StorePolicyHeadSort> {
                override fun encode(sort: StorePolicyHeadSort): List<String> =
                    listOf(sort.policyVersionId.toString(), sort.storeId.toString())

                override fun decode(values: List<String>): StorePolicyHeadSort? =
                    if (values.size == 2) {
                        val version = values[0].toLongOrNull()
                        val store = runCatching { UUID.fromString(values[1]) }.getOrNull()
                        if (version != null && version > 0 && store != null) StorePolicyHeadSort(version, store) else null
                    } else {
                        null
                    }
            }
    }
}

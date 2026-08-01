package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.merchant.api.StorePolicyScopeOperations
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualExpiryRule
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyOperations
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyScopeType
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicySelectionSource
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicySnapshot
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyState
import io.github.kdh949.beanflow.operations.api.PointAccrualIssuerType
import io.github.kdh949.beanflow.operations.api.PointAccrualRoundingMode
import io.github.kdh949.beanflow.operations.api.SelectedOrdinaryPointAccrualPolicy
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import jakarta.persistence.EntityManager
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

internal data class ChangeOrdinaryPointAccrualPolicyCommand(
    val scopeType: OrdinaryPointAccrualPolicyScopeType,
    val scopeReference: UUID,
    val state: OrdinaryPointAccrualPolicyState,
    val accrualRateBps: Int?,
    val roundingMode: PointAccrualRoundingMode?,
    val issuerType: PointAccrualIssuerType?,
    val issuerReference: String?,
    val expiryRule: OrdinaryPointAccrualExpiryRule?,
    val validityDays: Int?,
    val expectedPolicyVersionId: Long?,
    val actorId: UUID,
    val idempotencyKey: String,
    val reason: String,
    val now: Instant,
)

internal data class OrdinaryPointAccrualPolicyVersionResult(
    val policyVersionId: Long,
    val scopeType: OrdinaryPointAccrualPolicyScopeType,
    val scopeReference: UUID,
    val state: OrdinaryPointAccrualPolicyState,
    val accrualRateBps: Int?,
    val roundingMode: PointAccrualRoundingMode?,
    val issuerType: PointAccrualIssuerType?,
    val issuerReference: String?,
    val expiryRule: OrdinaryPointAccrualExpiryRule?,
    val validityDays: Int?,
    val effectiveAt: Instant,
    val actorType: AuditActorType,
    val actorReference: String,
    val reason: String,
)

@Service
internal class OrdinaryPointAccrualPolicyService(
    private val versionRepository: OrdinaryPointAccrualPolicyVersionJpaRepository,
    private val headRepository: OrdinaryPointAccrualPolicyHeadJpaRepository,
    private val authorization: OperatorPermissionAuthorization,
    private val storePolicyScopeOperations: StorePolicyScopeOperations,
    private val advisoryLock: DatabaseAdvisoryLock,
    private val auditRecordOperations: AuditRecordOperations,
    private val correlationIdSource: CorrelationIdSource,
    private val entityManager: EntityManager,
    private val metrics: OperatorSecurityMetrics,
) : OrdinaryPointAccrualPolicyOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun selectForOrder(storeId: UUID): SelectedOrdinaryPointAccrualPolicy =
        persistenceBoundary {
            advisoryLock.lockShared(storeLock(storeId))
            val storeHead =
                headRepository.findShared(OrdinaryPointAccrualPolicyScopeType.STORE, storeId)
            if (storeHead != null) {
                val storeVersion = version(storeHead)
                if (storeVersion.state == OrdinaryPointAccrualPolicyState.OVERRIDE) {
                    return@persistenceBoundary SelectedOrdinaryPointAccrualPolicy(
                        storeVersion.toSnapshot(),
                        OrdinaryPointAccrualPolicySelectionSource.STORE_OVERRIDE,
                    )
                }
                return@persistenceBoundary SelectedOrdinaryPointAccrualPolicy(
                    globalVersionForSelection().toSnapshot(),
                    OrdinaryPointAccrualPolicySelectionSource.GLOBAL_INHERITED,
                )
            }
            SelectedOrdinaryPointAccrualPolicy(
                globalVersionForSelection().toSnapshot(),
                OrdinaryPointAccrualPolicySelectionSource.GLOBAL_NO_OVERRIDE,
            )
        }

    @Transactional
    fun change(command: ChangeOrdinaryPointAccrualPolicyCommand): OrdinaryPointAccrualPolicyVersionResult =
        observedChange(command) {
            persistenceBoundary {
                validate(command)
                authorization.requireActive(command.actorId, OperatorPermission.POINT_ACCRUAL_POLICY_WRITE)
                advisoryLock.lock("ordinary-point-accrual-policy:idempotency:${command.actorId}:${command.idempotencyKey}")
                val hash = payloadHash(command)
                versionRepository
                    .findByIdempotencyActorIdAndIdempotencyKey(command.actorId, command.idempotencyKey)
                    ?.let { existing ->
                        if (existing.payloadHash != hash) {
                            conflict(FailureCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was reused with another policy command")
                        }
                        return@persistenceBoundary existing.toResult()
                    }

                if (command.scopeType == OrdinaryPointAccrualPolicyScopeType.STORE) {
                    storePolicyScopeOperations.requireExisting(command.scopeReference)
                    advisoryLock.lock(storeLock(command.scopeReference))
                }
                val head = headRepository.findLocked(command.scopeType, command.scopeReference)
                validateExpectedHead(command, head)
                val previous = head?.let(::version)
                val next =
                    versionRepository.saveAndFlush(
                        OrdinaryPointAccrualPolicyVersionEntity(
                            scopeType = command.scopeType,
                            scopeReference = command.scopeReference,
                            state = command.state,
                            accrualRateBps = command.accrualRateBps,
                            roundingMode = command.roundingMode,
                            issuerType = command.issuerType,
                            issuerReference = command.issuerReference?.trim(),
                            expiryRule = command.expiryRule,
                            validityDays = command.validityDays,
                            effectiveAt = command.now,
                            actorType = AuditActorType.PLATFORM_OPERATOR,
                            actorReference = command.actorId.toString(),
                            reason = command.reason.trim(),
                            idempotencyActorId = command.actorId,
                            idempotencyKey = command.idempotencyKey,
                            payloadHash = hash,
                            sourceReference =
                                "point-accrual-policy:${command.actorId}:${sha256(command.idempotencyKey)}",
                        ),
                    )
                if (head == null) {
                    headRepository.save(
                        OrdinaryPointAccrualPolicyHeadEntity(
                            scopeType = command.scopeType,
                            scopeReference = command.scopeReference,
                            policyVersionId = next.policyVersionId,
                        ),
                    )
                } else {
                    head.policyVersionId = next.policyVersionId
                }
                appendChangeAudit(command, previous, next)
                entityManager.flush()
                next.toResult()
            }
        }

    private fun globalVersionForSelection(): OrdinaryPointAccrualPolicyVersionEntity {
        val head =
            headRepository.findShared(
                OrdinaryPointAccrualPolicyScopeType.GLOBAL,
                OrdinaryPointAccrualPolicySnapshot.GLOBAL_SCOPE_REFERENCE,
            ) ?: dependency("GLOBAL ordinary point accrual policy head is missing")
        return version(head).also {
            if (it.state != OrdinaryPointAccrualPolicyState.OVERRIDE) {
                dependency("GLOBAL ordinary point accrual policy is incomplete")
            }
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

    private fun validate(command: ChangeOrdinaryPointAccrualPolicyCommand) {
        if (command.idempotencyKey.length !in 8..128 || command.idempotencyKey.hasControlCharacter()) {
            invalid("Idempotency-Key must contain between 8 and 128 non-control characters")
        }
        if (command.reason.trim().length !in 1..500 || command.reason.hasControlCharacter()) {
            invalid("Policy reason must contain between 1 and 500 non-control characters")
        }
        when (command.scopeType) {
            OrdinaryPointAccrualPolicyScopeType.GLOBAL -> {
                if (command.scopeReference != OrdinaryPointAccrualPolicySnapshot.GLOBAL_SCOPE_REFERENCE ||
                    command.state != OrdinaryPointAccrualPolicyState.OVERRIDE ||
                    command.expectedPolicyVersionId == null || command.expectedPolicyVersionId < 1
                ) {
                    invalid("GLOBAL change requires its singleton scope, OVERRIDE state and a positive expected version")
                }
            }

            OrdinaryPointAccrualPolicyScopeType.STORE -> {
                if (command.scopeReference == OrdinaryPointAccrualPolicySnapshot.GLOBAL_SCOPE_REFERENCE ||
                    command.expectedPolicyVersionId?.let { it < 1 } == true
                ) {
                    invalid("STORE change requires a Store scope and a positive expected version when supplied")
                }
            }
        }
        if (command.state == OrdinaryPointAccrualPolicyState.OVERRIDE) {
            val issuerReference = command.issuerReference?.trim()
            if (command.accrualRateBps !in 0..10_000 ||
                command.roundingMode == null ||
                command.issuerType == null ||
                issuerReference == null || issuerReference.length !in 1..240 || issuerReference.hasControlCharacter() ||
                command.expiryRule == null || command.validityDays !in 1..3650
            ) {
                invalid("OVERRIDE requires a complete valid ordinary point accrual policy")
            }
        } else if (
            command.scopeType != OrdinaryPointAccrualPolicyScopeType.STORE ||
            listOf(
                command.accrualRateBps,
                command.roundingMode,
                command.issuerType,
                command.issuerReference,
                command.expiryRule,
                command.validityDays,
            ).any { it != null }
        ) {
            invalid("INHERIT_GLOBAL is value-free and available only for STORE scope")
        }
    }

    private fun validateExpectedHead(
        command: ChangeOrdinaryPointAccrualPolicyCommand,
        head: OrdinaryPointAccrualPolicyHeadEntity?,
    ) {
        if (head == null && command.expectedPolicyVersionId != null) {
            conflict(FailureCode.ORDER_STATE_CONFLICT, "Ordinary point accrual policy head does not exist")
        }
        if (head != null && head.policyVersionId != command.expectedPolicyVersionId) {
            conflict(FailureCode.ORDER_STATE_CONFLICT, "Ordinary point accrual policy version has changed")
        }
    }

    private fun appendChangeAudit(
        command: ChangeOrdinaryPointAccrualPolicyCommand,
        previous: OrdinaryPointAccrualPolicyVersionEntity?,
        next: OrdinaryPointAccrualPolicyVersionEntity,
    ) {
        auditRecordOperations.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = command.actorId.toString(),
                    actorType = AuditActorType.PLATFORM_OPERATOR,
                    action = "POINT_ACCRUAL_POLICY_CHANGED",
                    targetType = "POINT_ACCRUAL_POLICY_HEAD",
                    targetId = command.scopeReference,
                    occurredAt = command.now,
                    reason = command.reason.trim(),
                    beforeSummary =
                        mapOf(
                            "state" to (previous?.state?.name ?: "ABSENT"),
                            "policyVersionId" to (previous?.policyVersionId?.toString() ?: "ABSENT"),
                        ),
                    afterSummary =
                        mapOf(
                            "scopeType" to next.scopeType.name,
                            "state" to next.state.name,
                            "policyVersionId" to next.policyVersionId.toString(),
                        ),
                    correlationId = correlationIdSource.currentOrCreate(),
                    sourceReference = "point-accrual-policy-change:${next.policyVersionId}",
                ),
            ),
        )
    }

    private fun payloadHash(command: ChangeOrdinaryPointAccrualPolicyCommand): String =
        sha256(
            listOf(
                "ordinary-point-accrual-policy-command-v1",
                command.scopeType.name,
                command.scopeReference,
                command.state.name,
                command.expectedPolicyVersionId ?: "ABSENT",
                command.accrualRateBps ?: "ABSENT",
                command.roundingMode?.name ?: "ABSENT",
                command.issuerType?.name ?: "ABSENT",
                command.issuerReference?.trim() ?: "ABSENT",
                command.expiryRule?.name ?: "ABSENT",
                command.validityDays ?: "ABSENT",
                command.reason.trim(),
            ).joinToString("|"),
        )

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

    private fun OrdinaryPointAccrualPolicyVersionEntity.toResult() =
        OrdinaryPointAccrualPolicyVersionResult(
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

    private fun <T> persistenceBoundary(block: () -> T): T =
        try {
            block()
        } catch (failure: DomainFailure) {
            throw failure
        } catch (_: DataAccessException) {
            dependency("Ordinary point accrual policy persistence is unavailable")
        }

    private fun <T> observedChange(
        command: ChangeOrdinaryPointAccrualPolicyCommand,
        block: () -> T,
    ): T =
        try {
            block().also {
                TransactionSynchronizationManager.registerSynchronization(
                    object : TransactionSynchronization {
                        override fun afterCompletion(status: Int) {
                            metrics.pointAccrualPolicyChange(
                                command.scopeType,
                                command.state,
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
            metrics.pointAccrualPolicyChange(command.scopeType, command.state, failure.toMetricOutcome())
            throw failure
        }

    private fun DomainFailure.toMetricOutcome(): OperatorSecurityOutcome =
        when (code) {
            FailureCode.INVALID_REQUEST -> OperatorSecurityOutcome.INVALID_INPUT

            FailureCode.RESOURCE_NOT_FOUND -> OperatorSecurityOutcome.NOT_FOUND

            FailureCode.ACCESS_DENIED -> OperatorSecurityOutcome.DENIED

            FailureCode.ORDER_STATE_CONFLICT,
            FailureCode.IDEMPOTENCY_KEY_REUSED,
            -> OperatorSecurityOutcome.CONFLICT

            else -> OperatorSecurityOutcome.DEPENDENCY_UNAVAILABLE
        }

    private fun storeLock(storeId: UUID) = "ordinary-point-accrual-policy:STORE:$storeId"

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    private fun conflict(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)

    private fun dependency(message: String): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)
}

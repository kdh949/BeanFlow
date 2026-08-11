package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualExpiryRule
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyScopeType
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicySnapshot
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyState
import io.github.kdh949.beanflow.operations.api.PointAccrualIssuerType
import io.github.kdh949.beanflow.operations.api.PointAccrualRoundingMode
import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.EntityManager
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

internal enum class OrdinaryPointAccrualPolicyBootstrapResult {
    APPLIED,
    INVALID_INPUT,
    IDENTITY_VERIFICATION_FAILED,
    POLICY_ALREADY_INITIALIZED,
    DEPENDENCY_UNAVAILABLE,
}

internal data class OrdinaryPointAccrualPolicyBootstrapCommand(
    val accrualRateBps: Int,
    val roundingMode: PointAccrualRoundingMode,
    val issuerType: PointAccrualIssuerType,
    val issuerReference: String,
    val expiryRule: OrdinaryPointAccrualExpiryRule,
    val validityDays: Int,
    val reason: String,
    val evidenceReference: String,
    val correlationId: String,
    val now: Instant,
)

internal class PointAccrualPolicyAlreadyInitialized : RuntimeException()

@Service
internal class OrdinaryPointAccrualPolicyBootstrapTransaction(
    private val versionRepository: OrdinaryPointAccrualPolicyVersionJpaRepository,
    private val headRepository: OrdinaryPointAccrualPolicyHeadJpaRepository,
    private val advisoryLock: DatabaseAdvisoryLock,
    private val auditRecordOperations: AuditRecordOperations,
    private val entityManager: EntityManager,
) {
    @Transactional
    fun apply(
        command: OrdinaryPointAccrualPolicyBootstrapCommand,
        principal: VerifiedReleasePrincipal,
    ) {
        validate(command, principal)
        advisoryLock.lock(GLOBAL_LOCK)
        if (
            headRepository.findLocked(
                OrdinaryPointAccrualPolicyScopeType.GLOBAL,
                OrdinaryPointAccrualPolicySnapshot.GLOBAL_SCOPE_REFERENCE,
            ) != null
        ) {
            throw PointAccrualPolicyAlreadyInitialized()
        }

        val policyHash = canonicalPolicyHash(command)
        val sourceReference = "point-accrual-policy-bootstrap:${sha256(command.correlationId + '|' + command.evidenceReference)}"
        val version =
            versionRepository.save(
                OrdinaryPointAccrualPolicyVersionEntity(
                    scopeType = OrdinaryPointAccrualPolicyScopeType.GLOBAL,
                    scopeReference = OrdinaryPointAccrualPolicySnapshot.GLOBAL_SCOPE_REFERENCE,
                    state = OrdinaryPointAccrualPolicyState.OVERRIDE,
                    accrualRateBps = command.accrualRateBps,
                    roundingMode = command.roundingMode,
                    issuerType = command.issuerType,
                    issuerReference = command.issuerReference,
                    expiryRule = command.expiryRule,
                    validityDays = command.validityDays,
                    effectiveAt = command.now,
                    actorType = AuditActorType.SYSTEM,
                    actorReference = principal.reference,
                    reason = command.reason,
                    idempotencyActorId = null,
                    idempotencyKey = null,
                    payloadHash = policyHash,
                    sourceReference = sourceReference,
                ),
            )
        versionRepository.flush()
        headRepository.save(
            OrdinaryPointAccrualPolicyHeadEntity(
                scopeType = OrdinaryPointAccrualPolicyScopeType.GLOBAL,
                scopeReference = OrdinaryPointAccrualPolicySnapshot.GLOBAL_SCOPE_REFERENCE,
                policyVersionId = version.policyVersionId,
            ),
        )
        auditRecordOperations.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = principal.reference,
                    actorType = AuditActorType.SYSTEM,
                    category = AuditCategory.OPERATIONS_POLICY,
                    action = "POINT_ACCRUAL_POLICY_BOOTSTRAPPED",
                    targetType = "POINT_ACCRUAL_POLICY_GLOBAL",
                    targetId = OrdinaryPointAccrualPolicySnapshot.GLOBAL_SCOPE_REFERENCE,
                    occurredAt = command.now,
                    reason = "VERIFIED_INITIAL_POINT_ACCRUAL_POLICY",
                    beforeSummary = mapOf("state" to "ABSENT"),
                    afterSummary =
                        mapOf(
                            "state" to OrdinaryPointAccrualPolicyState.OVERRIDE.name,
                            "scope" to OrdinaryPointAccrualPolicyScopeType.GLOBAL.name,
                            "policyVersionId" to version.policyVersionId.toString(),
                            "evidenceReference" to command.evidenceReference,
                        ),
                    correlationId = command.correlationId,
                    sourceReference = sourceReference,
                ),
            ),
        )
        entityManager.flush()
    }

    private fun validate(
        command: OrdinaryPointAccrualPolicyBootstrapCommand,
        principal: VerifiedReleasePrincipal,
    ) {
        require(command.accrualRateBps in 0..10_000)
        require(command.validityDays in 1..3650)
        require(command.issuerReference == command.issuerReference.trim())
        require(command.issuerReference.length in 1..240 && !command.issuerReference.hasControlCharacter())
        require(command.reason == command.reason.trim())
        require(command.reason.length in 1..500 && !command.reason.hasControlCharacter())
        require(command.evidenceReference == command.evidenceReference.trim())
        require(command.evidenceReference.length in 1..500 && !command.evidenceReference.hasControlCharacter())
        require(command.correlationId == command.correlationId.trim())
        require(command.correlationId.length in 1..160 && !command.correlationId.hasControlCharacter())
        require(principal.reference.length in 1..500 && !principal.reference.hasControlCharacter())
    }

    private fun canonicalPolicyHash(command: OrdinaryPointAccrualPolicyBootstrapCommand): String =
        sha256(
            listOf(
                "ordinary-point-accrual-policy-v1",
                OrdinaryPointAccrualPolicyScopeType.GLOBAL.name,
                OrdinaryPointAccrualPolicySnapshot.GLOBAL_SCOPE_REFERENCE.toString(),
                OrdinaryPointAccrualPolicyState.OVERRIDE.name,
                command.accrualRateBps,
                command.roundingMode.name,
                command.issuerType.name,
                command.issuerReference,
                command.expiryRule.name,
                command.validityDays,
            ).joinToString("|"),
        )

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

    private companion object {
        const val GLOBAL_LOCK = "ordinary-point-accrual-policy:GLOBAL"
    }
}

@Component
internal class OrdinaryPointAccrualPolicyBootstrapLifecycle(
    private val transaction: OrdinaryPointAccrualPolicyBootstrapTransaction,
    private val meterRegistry: MeterRegistry,
) {
    fun apply(
        command: OrdinaryPointAccrualPolicyBootstrapCommand,
        principal: VerifiedReleasePrincipal,
    ): OrdinaryPointAccrualPolicyBootstrapResult {
        val result =
            try {
                transaction.apply(command, principal)
                OrdinaryPointAccrualPolicyBootstrapResult.APPLIED
            } catch (_: IllegalArgumentException) {
                OrdinaryPointAccrualPolicyBootstrapResult.INVALID_INPUT
            } catch (_: PointAccrualPolicyAlreadyInitialized) {
                OrdinaryPointAccrualPolicyBootstrapResult.POLICY_ALREADY_INITIALIZED
            } catch (_: DataAccessException) {
                OrdinaryPointAccrualPolicyBootstrapResult.DEPENDENCY_UNAVAILABLE
            } catch (_: RuntimeException) {
                OrdinaryPointAccrualPolicyBootstrapResult.DEPENDENCY_UNAVAILABLE
            }
        meterRegistry
            .counter("beanflow.operations.point_accrual_policy.bootstrap.count", "outcome", result.name)
            .increment()
        return result
    }
}

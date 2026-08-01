package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicyHead
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicyOperations
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicySnapshot
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationTrigger
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitType
import io.github.kdh949.beanflow.operations.api.ListExpiredBenefitRestorationPoliciesCommand
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.operations.api.UpdateExpiredBenefitRestorationPolicyCommand
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import jakarta.persistence.EntityManager
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

@Service
internal class ExpiredBenefitRestorationPolicyService(
    private val versionRepository: ExpiredBenefitPolicyVersionJpaRepository,
    private val headRepository: ExpiredBenefitPolicyHeadJpaRepository,
    private val authorization: OperatorPermissionAuthorization,
    private val advisoryLock: DatabaseAdvisoryLock,
    private val auditRecordOperations: AuditRecordOperations,
    private val correlationIdSource: CorrelationIdSource,
    private val identifierSource: IdentifierSource,
    private val entityManager: EntityManager,
    private val metrics: OperatorSecurityMetrics,
) : ExpiredBenefitRestorationPolicyOperations {
    @Transactional
    override fun current(
        trigger: ExpiredBenefitRestorationTrigger,
        benefitType: ExpiredBenefitType,
    ): ExpiredBenefitRestorationPolicySnapshot =
        persistenceBoundary {
            if (!isAllowedKey(trigger, benefitType)) notFound()
            val head = lockedHead(trigger, benefitType)
            version(head).toSnapshot()
        }

    @Transactional
    override fun listCurrent(command: ListExpiredBenefitRestorationPoliciesCommand): List<ExpiredBenefitRestorationPolicyHead> =
        observedRead {
            persistenceBoundary {
                val reason = normalizeAccessReason(command.accessReason)
                authorization.requireActive(command.actorId, OperatorPermission.EXPIRED_BENEFIT_POLICY_READ)
                val heads = headRepository.findAllOrdered()
                val policies = heads.map { head -> version(head).toHead() }
                if (policies.map { it.trigger to it.benefitType }.toSet() != ALLOWED_KEYS || policies.size != 5) {
                    dependency("Expired benefit restoration policy head set is incomplete")
                }
                auditRecordOperations.appendAll(
                    listOf(
                        AppendAuditRecordCommand(
                            actorId = command.actorId.toString(),
                            actorType = AuditActorType.PLATFORM_OPERATOR,
                            action = "EXPIRED_BENEFIT_POLICY_READ",
                            targetType = "EXPIRED_BENEFIT_POLICY_HEAD_SET",
                            targetId = POLICY_HEAD_SET_TARGET_ID,
                            occurredAt = command.now,
                            reason = reason,
                            afterSummary = mapOf("headCount" to policies.size.toString()),
                            correlationId = correlationIdSource.currentOrCreate(),
                            sourceReference = "expired-benefit-policy-head-set:read:${identifierSource.next()}",
                        ),
                    ),
                )
                entityManager.flush()
                policies
            }
        }

    @Transactional
    override fun update(command: UpdateExpiredBenefitRestorationPolicyCommand): ExpiredBenefitRestorationPolicyHead =
        observedChange(command) {
            persistenceBoundary {
                validate(command)
                authorization.requireActive(command.actorId, OperatorPermission.EXPIRED_BENEFIT_POLICY_WRITE)
                if (!isAllowedKey(command.trigger, command.benefitType)) notFound()
                advisoryLock.lock("expired-benefit-policy-idempotency:${command.actorId}:${command.idempotencyKey}")
                val hash = payloadHash(command)
                versionRepository
                    .findByIdempotencyActorIdAndIdempotencyKey(command.actorId, command.idempotencyKey)
                    ?.let { existing ->
                        if (existing.payloadHash != hash) {
                            conflict(FailureCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was reused with a different policy payload")
                        }
                        return@persistenceBoundary existing.toHead()
                    }

                val head = lockedHead(command.trigger, command.benefitType)
                if (head.policyVersion != command.expectedPolicyVersionId) {
                    conflict(FailureCode.ORDER_STATE_CONFLICT, "Expired benefit restoration policy version has changed")
                }
                val previous = version(head)
                val next =
                    versionRepository.saveAndFlush(
                        ExpiredBenefitPolicyVersionEntity(
                            trigger = command.trigger,
                            benefitType = command.benefitType,
                            mode = command.mode,
                            compensationValidityDays = command.compensationValidityDays,
                            effectiveAt = command.now,
                            updatedBy = command.actorId,
                            reason = command.reason.trim(),
                            idempotencyActorId = command.actorId,
                            idempotencyKey = command.idempotencyKey,
                            payloadHash = hash,
                        ),
                    )
                head.policyVersion = next.policyVersion
                auditRecordOperations.appendAll(
                    listOf(
                        AppendAuditRecordCommand(
                            actorId = command.actorId.toString(),
                            actorType = AuditActorType.PLATFORM_OPERATOR,
                            action = "EXPIRED_BENEFIT_POLICY_CHANGED",
                            targetType = "EXPIRED_BENEFIT_POLICY_HEAD",
                            targetId = policyTargetId(command.trigger, command.benefitType),
                            occurredAt = command.now,
                            reason = command.reason.trim(),
                            beforeSummary =
                                mapOf(
                                    "policyVersionId" to previous.policyVersion.toString(),
                                    "mode" to previous.mode.name,
                                ),
                            afterSummary =
                                mapOf(
                                    "policyVersionId" to next.policyVersion.toString(),
                                    "trigger" to next.trigger.name,
                                    "benefitType" to next.benefitType.name,
                                    "mode" to next.mode.name,
                                    "validityDays" to next.compensationValidityDays.toString(),
                                ),
                            correlationId = correlationIdSource.currentOrCreate(),
                            sourceReference =
                                "expired-benefit-policy:${next.trigger.name}:${next.benefitType.name}:${next.policyVersion}",
                        ),
                    ),
                )
                entityManager.flush()
                next.toHead()
            }
        }

    private fun lockedHead(
        trigger: ExpiredBenefitRestorationTrigger,
        benefitType: ExpiredBenefitType,
    ): ExpiredBenefitPolicyHeadEntity =
        headRepository.findLocked(trigger, benefitType)
            ?: dependency("Expired benefit restoration policy head is missing")

    private fun version(head: ExpiredBenefitPolicyHeadEntity): ExpiredBenefitPolicyVersionEntity =
        versionRepository
            .findById(head.policyVersion)
            .orElseThrow { dependency("Expired benefit restoration policy head points to a missing version") }
            .also { version ->
                if (version.trigger != head.trigger || version.benefitType != head.benefitType) {
                    dependency("Expired benefit restoration policy head key does not match its version")
                }
            }

    private fun validate(command: UpdateExpiredBenefitRestorationPolicyCommand) {
        if (command.idempotencyKey.length !in 8..128 || command.idempotencyKey.hasControlCharacter()) {
            invalid("Idempotency-Key must contain between 8 and 128 non-control characters")
        }
        if (command.expectedPolicyVersionId < 1) {
            invalid("Expected policy version ID must be positive")
        }
        if (command.compensationValidityDays !in 1..365) {
            invalid("Compensation validity must be between 1 and 365 days")
        }
        if (command.reason.trim().length !in 1..500 || command.reason.hasControlCharacter()) {
            invalid("Policy change reason must contain between 1 and 500 non-control characters")
        }
    }

    private fun normalizeAccessReason(reason: String): String {
        val normalized = reason.trim()
        if (normalized.length !in 1..200 || reason.hasControlCharacter()) {
            invalid("Access reason must contain between 1 and 200 non-control characters")
        }
        return normalized
    }

    private fun payloadHash(command: UpdateExpiredBenefitRestorationPolicyCommand): String {
        val canonical =
            "${command.trigger.name}|${command.benefitType.name}|${command.expectedPolicyVersionId}|" +
                "${command.mode.name}|${command.compensationValidityDays}|${command.reason.trim()}"
        return MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun ExpiredBenefitPolicyVersionEntity.toSnapshot() =
        ExpiredBenefitRestorationPolicySnapshot(
            policyVersion = policyVersion,
            mode = mode,
            compensationValidityDays = compensationValidityDays,
            effectiveAt = effectiveAt,
            updatedBy = updatedBy,
            reason = reason,
        )

    private fun ExpiredBenefitPolicyVersionEntity.toHead() =
        ExpiredBenefitRestorationPolicyHead(
            policyVersionId = policyVersion,
            trigger = trigger,
            benefitType = benefitType,
            mode = mode,
            compensationValidityDays = compensationValidityDays,
            effectiveAt = effectiveAt,
            updatedBy = updatedBy,
            reason = reason,
        )

    private fun isAllowedKey(
        trigger: ExpiredBenefitRestorationTrigger,
        benefitType: ExpiredBenefitType,
    ): Boolean = trigger to benefitType in ALLOWED_KEYS

    private fun policyTargetId(
        trigger: ExpiredBenefitRestorationTrigger,
        benefitType: ExpiredBenefitType,
    ): UUID =
        UUID.nameUUIDFromBytes(
            "expired-benefit-policy:${trigger.name}:${benefitType.name}".toByteArray(StandardCharsets.UTF_8),
        )

    private fun <T> persistenceBoundary(block: () -> T): T =
        try {
            block()
        } catch (failure: DomainFailure) {
            throw failure
        } catch (_: DataAccessException) {
            dependency("Expired benefit restoration policy persistence is unavailable")
        }

    private fun <T> observedRead(block: () -> T): T =
        try {
            block().also { metrics.policyRead(OperatorSecurityOutcome.SUCCEEDED) }
        } catch (failure: DomainFailure) {
            metrics.policyRead(failure.toMetricOutcome())
            throw failure
        }

    private fun <T> observedChange(
        command: UpdateExpiredBenefitRestorationPolicyCommand,
        block: () -> T,
    ): T =
        try {
            block().also {
                metrics.benefitPolicyChange(
                    command.trigger,
                    command.benefitType,
                    command.mode,
                    OperatorSecurityOutcome.SUCCEEDED,
                )
            }
        } catch (failure: DomainFailure) {
            metrics.benefitPolicyChange(
                command.trigger,
                command.benefitType,
                command.mode,
                failure.toMetricOutcome(),
            )
            throw failure
        }

    private fun DomainFailure.toMetricOutcome(): OperatorSecurityOutcome =
        when (code) {
            FailureCode.INVALID_REQUEST -> OperatorSecurityOutcome.INVALID_INPUT

            FailureCode.RESOURCE_NOT_FOUND -> OperatorSecurityOutcome.NOT_FOUND

            FailureCode.ORDER_STATE_CONFLICT,
            FailureCode.IDEMPOTENCY_KEY_REUSED,
            -> OperatorSecurityOutcome.CONFLICT

            FailureCode.ACCESS_DENIED -> OperatorSecurityOutcome.DENIED

            else -> OperatorSecurityOutcome.DEPENDENCY_UNAVAILABLE
        }

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    private fun notFound(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Policy key was not found")

    private fun conflict(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)

    private fun dependency(message: String): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)

    private companion object {
        val POLICY_HEAD_SET_TARGET_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val ALLOWED_KEYS =
            setOf(
                ExpiredBenefitRestorationTrigger.STORE_REJECTION to ExpiredBenefitType.COUPON,
                ExpiredBenefitRestorationTrigger.STORE_REJECTION to ExpiredBenefitType.POINTS,
                ExpiredBenefitRestorationTrigger.CUSTOMER_CANCELLATION to ExpiredBenefitType.COUPON,
                ExpiredBenefitRestorationTrigger.CUSTOMER_CANCELLATION to ExpiredBenefitType.POINTS,
                ExpiredBenefitRestorationTrigger.PARTIAL_REFUND to ExpiredBenefitType.POINTS,
            )
    }
}

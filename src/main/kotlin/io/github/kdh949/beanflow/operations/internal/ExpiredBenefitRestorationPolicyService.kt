package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicyOperations
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicySnapshot
import io.github.kdh949.beanflow.operations.api.UpdateExpiredBenefitRestorationPolicyCommand
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

@Service
internal class ExpiredBenefitRestorationPolicyService(
    private val versionRepository: ExpiredBenefitPolicyVersionJpaRepository,
    private val headRepository: ExpiredBenefitPolicyHeadJpaRepository,
    private val auditRecordOperations: AuditRecordOperations,
    private val correlationIdSource: CorrelationIdSource,
) : ExpiredBenefitRestorationPolicyOperations {
    @Transactional
    override fun current(): ExpiredBenefitRestorationPolicySnapshot {
        val head = lockedHead()
        return versionRepository
            .findById(head.policyVersion)
            .orElseThrow { dependency("Benefit restoration policy head points to a missing version") }
            .toSnapshot()
    }

    @Transactional
    override fun update(command: UpdateExpiredBenefitRestorationPolicyCommand): ExpiredBenefitRestorationPolicySnapshot {
        validate(command)
        val hash = payloadHash(command)
        val head = lockedHead()
        versionRepository
            .findByIdempotencyActorIdAndIdempotencyKey(
                command.actorId,
                command.idempotencyKey,
            )?.let { existing ->
                if (existing.payloadHash != hash) {
                    conflict("Idempotency-Key was reused with a different policy payload")
                }
                return existing.toSnapshot()
            }
        if (head.policyVersion != command.expectedPolicyVersion) {
            conflict("Expired benefit restoration policy version has changed")
        }
        val next =
            ExpiredBenefitPolicyVersionEntity(
                policyVersion = head.policyVersion + 1,
                mode = command.mode,
                compensationValidityDays = command.compensationValidityDays,
                effectiveAt = command.now,
                updatedBy = command.actorId,
                reason = command.reason.trim(),
                idempotencyActorId = command.actorId,
                idempotencyKey = command.idempotencyKey,
                payloadHash = hash,
            )
        versionRepository.save(next)
        head.policyVersion = next.policyVersion
        auditRecordOperations.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = command.actorId.toString(),
                    actorType = AuditActorType.PLATFORM_OPERATOR,
                    action = "EXPIRED_BENEFIT_POLICY_CHANGED",
                    targetType = "EXPIRED_BENEFIT_POLICY",
                    targetId = POLICY_TARGET_ID,
                    occurredAt = command.now,
                    reason = command.reason.trim(),
                    beforeSummary = mapOf("version" to command.expectedPolicyVersion.toString()),
                    afterSummary =
                        mapOf(
                            "version" to next.policyVersion.toString(),
                            "mode" to next.mode.name,
                            "validityDays" to next.compensationValidityDays.toString(),
                        ),
                    correlationId = correlationIdSource.currentOrCreate(),
                    sourceReference = "expired-benefit-policy:${next.policyVersion}",
                ),
            ),
        )
        return next.toSnapshot()
    }

    private fun lockedHead(): ExpiredBenefitPolicyHeadEntity =
        headRepository.findLocked()
            ?: dependency("Benefit restoration policy head is missing")

    private fun validate(command: UpdateExpiredBenefitRestorationPolicyCommand) {
        if (command.idempotencyKey.length !in 8..128) {
            invalid("Idempotency-Key must contain between 8 and 128 characters")
        }
        if (command.compensationValidityDays !in 1..365) {
            invalid("Compensation validity must be between 1 and 365 days")
        }
        if (command.reason.trim().length !in 1..500) {
            invalid("Policy change reason must contain between 1 and 500 characters")
        }
    }

    private fun payloadHash(command: UpdateExpiredBenefitRestorationPolicyCommand): String {
        val canonical =
            "${command.expectedPolicyVersion}|${command.mode.name}|" +
                "${command.compensationValidityDays}|${command.reason.trim()}"
        return MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun ExpiredBenefitPolicyVersionEntity.toSnapshot() =
        ExpiredBenefitRestorationPolicySnapshot(
            policyVersion,
            mode,
            compensationValidityDays,
            effectiveAt,
            updatedBy,
            reason,
        )

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    private fun conflict(message: String): Nothing = throw DomainFailure(FailureCode.IDEMPOTENCY_KEY_REUSED, message)

    private fun dependency(message: String): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)

    private companion object {
        val POLICY_TARGET_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}

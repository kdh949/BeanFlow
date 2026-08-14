package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.merchant.api.StorePolicyScopeOperations
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.MerchantCredentialMembershipRole
import io.github.kdh949.beanflow.operations.api.MerchantCredentialProvisioningPort
import io.github.kdh949.beanflow.operations.api.MerchantCredentialSecurityPort
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.operations.api.ProvisionMerchantCredentialCommand
import io.github.kdh949.beanflow.operations.api.ProvisionedMerchantCredential
import io.github.kdh949.beanflow.operations.api.ReplaceMerchantTemporaryPasswordCommand
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

internal data class CreateMerchantAccountCommand(
    val operatorId: UUID,
    val idempotencyKey: String,
    val loginId: String,
    val displayName: String,
    val storeId: UUID,
    val membershipRole: MerchantCredentialMembershipRole,
    val reason: String,
)

internal data class MerchantAccountSecretResult(
    val account: ProvisionedMerchantCredential,
    val temporaryPassword: String,
)

internal data class MerchantCredentialMutationCommand(
    val operatorId: UUID,
    val idempotencyKey: String,
    val accountId: UUID,
    val reason: String,
)

@Service
internal class MerchantCredentialAdministrationApplicationService(
    private val credentialSecurity: MerchantCredentialSecurityPort,
    private val transactions: MerchantCredentialAdministrationTransactions,
    private val metrics: MerchantCredentialMetrics,
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
) {
    fun create(command: CreateMerchantAccountCommand): MerchantAccountSecretResult =
        observe(MerchantCredentialOperation.CREATE) {
            val loginId = credentialSecurity.canonicalizeLoginId(command.loginId)
            val displayName = validateDisplayName(command.displayName)
            validateRequest(command.idempotencyKey, command.reason)
            val canonicalCommand = command.copy(loginId = loginId, displayName = displayName)
            transactions.precheckCreate(canonicalCommand)
            val secret = temporaryPassword()
            val hash = credentialSecurity.hashTemporaryPassword(secret)
            val now = clock.instant()
            val account = transactions.create(canonicalCommand, hash, now)
            MerchantAccountSecretResult(account, secret)
        }

    fun resetTemporaryPassword(command: MerchantCredentialMutationCommand): MerchantAccountSecretResult =
        observe(MerchantCredentialOperation.RESET_TEMPORARY_PASSWORD) {
            validateRequest(command.idempotencyKey, command.reason)
            transactions.precheckReset(command)
            val secret = temporaryPassword()
            val hash = credentialSecurity.hashTemporaryPassword(secret)
            val account = transactions.reset(command, hash, clock.instant())
            MerchantAccountSecretResult(account, secret)
        }

    fun releaseLock(command: MerchantCredentialMutationCommand) =
        observe(MerchantCredentialOperation.RELEASE_LOCK) {
            validateRequest(command.idempotencyKey, command.reason)
            transactions.release(command, clock.instant())
        }

    fun findExact(
        operatorId: UUID,
        rawLoginId: String,
        reason: String,
    ): ProvisionedMerchantCredential {
        val loginId = credentialSecurity.canonicalizeLoginId(rawLoginId)
        validateReason(reason)
        return transactions.findExact(operatorId, loginId, reason.trim(), clock.instant())
    }

    private fun temporaryPassword(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun validateDisplayName(raw: String): String =
        raw.trim().also {
            if (it.length !in 1..100 || it.hasControlCharacter()) invalid("Display name is invalid")
        }

    private fun validateRequest(
        idempotencyKey: String,
        reason: String,
    ) {
        if (idempotencyKey.length !in 8..128 || idempotencyKey != idempotencyKey.trim() || idempotencyKey.hasControlCharacter()) {
            invalid("Idempotency-Key must contain 8 to 128 non-control characters without outer whitespace")
        }
        validateReason(reason)
    }

    private fun validateReason(reason: String) {
        if (reason.trim().length !in 1..200 || reason.hasControlCharacter()) invalid("Reason is invalid")
    }

    private fun <T> observe(
        operation: MerchantCredentialOperation,
        command: () -> T,
    ): T =
        try {
            command().also { metrics.result(operation, "success") }
        } catch (failure: RuntimeException) {
            metrics.result(operation, "failed")
            throw failure
        }

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)
}

@Service
internal class MerchantCredentialAdministrationTransactions(
    private val commands: MerchantCredentialCommandJpaRepository,
    private val authorization: OperatorPermissionAuthorization,
    private val advisoryLock: DatabaseAdvisoryLock,
    private val stores: StorePolicyScopeOperations,
    private val identity: MerchantCredentialProvisioningPort,
    private val audits: AuditRecordOperations,
    private val correlationIds: CorrelationIdSource,
    private val entityManager: EntityManager,
    private val metrics: MerchantCredentialMetrics,
) {
    @Transactional
    fun precheckCreate(command: CreateMerchantAccountCommand) {
        authorization.requireActive(command.operatorId, OperatorPermission.MERCHANT_CREDENTIAL_MANAGE)
        rejectExistingSecretCommand(
            command.operatorId,
            MerchantCredentialOperation.CREATE,
            command.idempotencyKey,
            payloadHash("create", command.loginId, command.displayName, command.storeId, command.membershipRole, command.reason.trim()),
        )
    }

    @Transactional
    fun precheckReset(command: MerchantCredentialMutationCommand) {
        authorization.requireActive(command.operatorId, OperatorPermission.MERCHANT_CREDENTIAL_MANAGE)
        rejectExistingSecretCommand(
            command.operatorId,
            MerchantCredentialOperation.RESET_TEMPORARY_PASSWORD,
            command.idempotencyKey,
            payloadHash("reset", command.accountId, command.reason.trim()),
        )
    }

    @Transactional
    fun create(
        command: CreateMerchantAccountCommand,
        passwordHash: String,
        now: Instant,
    ): ProvisionedMerchantCredential {
        authorization.requireActive(command.operatorId, OperatorPermission.MERCHANT_CREDENTIAL_MANAGE)
        val payloadHash =
            payloadHash("create", command.loginId, command.displayName, command.storeId, command.membershipRole, command.reason.trim())
        lockAndRejectSecretReplay(command.operatorId, MerchantCredentialOperation.CREATE, command.idempotencyKey, payloadHash)
        stores.requireExisting(command.storeId)
        val accountId = UUID.randomUUID()
        val account =
            identity.create(
                ProvisionMerchantCredentialCommand(
                    accountId,
                    command.loginId,
                    command.displayName,
                    passwordHash,
                    now.plus(TEMPORARY_PASSWORD_LIFETIME),
                    command.storeId,
                    command.membershipRole,
                    now,
                ),
            )
        appendAudit(
            command.operatorId,
            "MERCHANT_ACCOUNT_CREATED",
            account,
            command.reason,
            now,
            "ABSENT",
            "command:${sha256(command.idempotencyKey)}",
        )
        saveOutcome(
            command.operatorId,
            MerchantCredentialOperation.CREATE,
            command.idempotencyKey,
            payloadHash,
            accountId,
            MerchantCredentialOutcome.ACCOUNT_CREATED,
            now,
        )
        return account
    }

    @Transactional
    fun reset(
        command: MerchantCredentialMutationCommand,
        passwordHash: String,
        now: Instant,
    ): ProvisionedMerchantCredential {
        authorization.requireActive(command.operatorId, OperatorPermission.MERCHANT_CREDENTIAL_MANAGE)
        val payloadHash = payloadHash("reset", command.accountId, command.reason.trim())
        lockAndRejectSecretReplay(
            command.operatorId,
            MerchantCredentialOperation.RESET_TEMPORARY_PASSWORD,
            command.idempotencyKey,
            payloadHash,
        )
        val account =
            identity.resetTemporaryPassword(
                ReplaceMerchantTemporaryPasswordCommand(
                    command.accountId,
                    passwordHash,
                    now.plus(TEMPORARY_PASSWORD_LIFETIME),
                    now,
                ),
            )
        appendAudit(
            command.operatorId,
            "MERCHANT_TEMPORARY_PASSWORD_RESET",
            account,
            command.reason,
            now,
            "CREDENTIAL_REPLACED",
            "command:${sha256(command.idempotencyKey)}",
        )
        saveOutcome(
            command.operatorId,
            MerchantCredentialOperation.RESET_TEMPORARY_PASSWORD,
            command.idempotencyKey,
            payloadHash,
            command.accountId,
            MerchantCredentialOutcome.PASSWORD_RESET,
            now,
        )
        return account
    }

    @Transactional
    fun release(
        command: MerchantCredentialMutationCommand,
        now: Instant,
    ) {
        authorization.requireActive(command.operatorId, OperatorPermission.MERCHANT_CREDENTIAL_MANAGE)
        val payloadHash = payloadHash("release", command.accountId, command.reason.trim())
        advisoryLock.lock(lockKey(command.operatorId, MerchantCredentialOperation.RELEASE_LOCK, command.idempotencyKey))
        commands
            .findByOperatorIdAndOperationAndIdempotencyKey(
                command.operatorId,
                MerchantCredentialOperation.RELEASE_LOCK,
                command.idempotencyKey,
            )?.let { existing ->
                if (existing.payloadHash != payloadHash) reused()
                metrics.replay(MerchantCredentialOperation.RELEASE_LOCK)
                return
            }
        val account = identity.releaseLock(command.accountId, now)
        appendAudit(
            command.operatorId,
            "MERCHANT_LOCK_RELEASED",
            account,
            command.reason,
            now,
            "LOCK_RELEASED",
            "command:${sha256(command.idempotencyKey)}",
        )
        saveOutcome(
            command.operatorId,
            MerchantCredentialOperation.RELEASE_LOCK,
            command.idempotencyKey,
            payloadHash,
            command.accountId,
            MerchantCredentialOutcome.LOCK_RELEASED,
            now,
        )
    }

    @Transactional
    fun findExact(
        operatorId: UUID,
        loginId: String,
        reason: String,
        now: Instant,
    ): ProvisionedMerchantCredential {
        authorization.requireActive(operatorId, OperatorPermission.MERCHANT_CREDENTIAL_MANAGE)
        val account =
            identity.findExact(loginId)
                ?: throw DomainFailure(FailureCode.MERCHANT_ACCOUNT_NOT_FOUND, "Merchant account was not found")
        appendAudit(operatorId, "MERCHANT_ACCOUNT_READ", account, reason, now, "ACCOUNT_READ", "read:${UUID.randomUUID()}")
        entityManager.flush()
        return account
    }

    private fun lockAndRejectSecretReplay(
        operatorId: UUID,
        operation: MerchantCredentialOperation,
        idempotencyKey: String,
        payloadHash: String,
    ) {
        advisoryLock.lock(lockKey(operatorId, operation, idempotencyKey))
        rejectExistingSecretCommand(operatorId, operation, idempotencyKey, payloadHash)
    }

    private fun rejectExistingSecretCommand(
        operatorId: UUID,
        operation: MerchantCredentialOperation,
        idempotencyKey: String,
        payloadHash: String,
    ) {
        val existing = commands.findByOperatorIdAndOperationAndIdempotencyKey(operatorId, operation, idempotencyKey) ?: return
        if (existing.payloadHash != payloadHash) reused()
        metrics.replay(operation)
        throw DomainFailure(
            FailureCode.TEMPORARY_PASSWORD_NOT_REPLAYABLE,
            "Temporary password cannot be replayed; query the account and issue a new reset if needed",
            targetReference = existing.merchantAccountId.toString(),
        )
    }

    private fun saveOutcome(
        operatorId: UUID,
        operation: MerchantCredentialOperation,
        idempotencyKey: String,
        payloadHash: String,
        accountId: UUID,
        outcome: MerchantCredentialOutcome,
        now: Instant,
    ) {
        commands.save(
            MerchantCredentialCommandEntity(
                UUID.randomUUID(),
                operatorId,
                operation,
                idempotencyKey,
                payloadHash,
                accountId,
                outcome,
                now,
                now.plus(Duration.ofDays(90)),
            ),
        )
        entityManager.flush()
    }

    private fun appendAudit(
        operatorId: UUID,
        action: String,
        account: ProvisionedMerchantCredential,
        reason: String,
        now: Instant,
        before: String,
        sourceSuffix: String,
    ) {
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = operatorId.toString(),
                    actorType = AuditActorType.PLATFORM_OPERATOR,
                    category = AuditCategory.SECURITY_AND_PERMISSION,
                    action = action,
                    targetType = "MERCHANT_ACCOUNT",
                    targetId = account.accountId,
                    occurredAt = now,
                    reason = reason.trim(),
                    beforeSummary = mapOf("state" to before),
                    afterSummary =
                        mapOf(
                            "accountState" to account.accountState.name,
                            "credentialVersion" to account.credentialVersion.toString(),
                        ),
                    correlationId = correlationIds.currentOrCreate(),
                    sourceReference = "merchant-credential:${account.accountId}:$sourceSuffix",
                ),
            ),
        )
    }

    private fun reused(): Nothing =
        throw DomainFailure(FailureCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was reused with a different merchant credential command")

    private fun lockKey(
        operatorId: UUID,
        operation: MerchantCredentialOperation,
        key: String,
    ) = "merchant-credential:$operatorId:${operation.name}:${sha256(key)}"

    private fun payloadHash(vararg fields: Any): String = sha256(fields.joinToString("\u001f"))

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

    private companion object {
        val TEMPORARY_PASSWORD_LIFETIME: Duration = Duration.ofHours(24)
    }
}

@Component
internal class MerchantCredentialMetrics(
    private val registry: MeterRegistry,
) {
    fun result(
        operation: MerchantCredentialOperation,
        outcome: String,
    ) = registry.counter("beanflow.operations.merchant_credential.command", "operation", operation.name, "outcome", outcome).increment()

    fun replay(operation: MerchantCredentialOperation) =
        registry.counter("beanflow.operations.merchant_credential.idempotency_replay", "operation", operation.name).increment()
}

@Component
internal class MerchantCredentialRetentionWorker(
    private val repository: MerchantCredentialRetentionRepository,
    private val clock: Clock,
    private val registry: MeterRegistry,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${beanflow.merchant-credential-retention.fixed-delay-ms:3600000}",
        initialDelayString = "\${beanflow.merchant-credential-retention.initial-delay-ms:300000}",
    )
    fun runOnce(): Int =
        try {
            val now = clock.instant()
            val result = repository.purgeDue(now, 100)
            registry.counter("beanflow.operations.merchant_credential.retention.deleted").increment(result.deletedCount.toDouble())
            result.oldestDueAt?.let {
                registry
                    .summary("beanflow.operations.merchant_credential.retention.oldest_due_age.seconds")
                    .record(Duration.between(it, now).toMillis().coerceAtLeast(0) / 1000.0)
            }
            result.deletedCount
        } catch (failure: RuntimeException) {
            registry.counter("beanflow.operations.merchant_credential.retention.failure").increment()
            logger.error("merchant_credential_retention outcome=FAILED", failure)
            throw failure
        }
}

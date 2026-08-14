package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.merchant.api.StoreDisplayNameOperations
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.shared.api.BrowserActorLoader
import io.github.kdh949.beanflow.shared.api.BrowserActorType
import io.github.kdh949.beanflow.shared.api.BrowserAuthenticationInvalid
import io.github.kdh949.beanflow.shared.api.BrowserSessionLifecycle
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.CreateLoginSession
import io.github.kdh949.beanflow.shared.api.CurrentActor
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.LoginSessionCoordinator
import io.github.kdh949.beanflow.shared.api.LoginSessionHandle
import io.github.kdh949.beanflow.shared.api.MerchantAccountState
import io.github.kdh949.beanflow.shared.api.MerchantActor
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal data class MerchantLoginResult(
    val merchantId: UUID,
    val displayName: String,
    val accountState: MerchantAccountState,
    val session: LoginSessionHandle,
)

internal data class MerchantView(
    val merchantId: UUID,
    val displayName: String,
    val accountState: MerchantAccountState,
)

internal data class MerchantStoreView(
    val storeId: UUID,
    val storeName: String,
    val membershipRole: String,
)

internal data class PreparedMerchantLogin(
    val loginIdHmac: String,
    val ipHmac: String,
    val snapshot: MerchantCredentialSnapshot?,
    val passwordMatched: Boolean,
    val currentSessionId: String?,
    val now: Instant,
)

internal sealed interface MerchantLoginCompletion {
    data class Succeeded(
        val result: MerchantLoginResult,
    ) : MerchantLoginCompletion

    data class Rejected(
        val rateLimitedUntil: Instant? = null,
    ) : MerchantLoginCompletion
}

internal data class MerchantPasswordChangeResult(
    val credentialVersion: Long,
    val session: LoginSessionHandle,
)

internal data class ChangedMerchantCredential(
    val accountId: UUID,
    val credentialVersion: Long,
)

private class MerchantCredentialChanged : RuntimeException()

@Service
internal class MerchantAccountApplicationService(
    private val accounts: MerchantAccountJpaRepository,
    private val memberships: StoreMembershipJpaRepository,
    private val storeNames: StoreDisplayNameOperations,
    private val passwordSecurity: CustomerPasswordSecurity,
    private val scopeHmac: AuthenticationScopeHmac,
    private val transactions: MerchantAccountTransactions,
    private val sessionTransactions: MerchantSessionTransactions,
    private val accessPolicy: MerchantAccountAccessPolicy,
    private val clock: Clock,
    private val registry: MeterRegistry,
) {
    fun login(
        rawLoginId: String,
        password: String,
        sourceIp: String,
        currentSessionId: String?,
    ): MerchantLoginResult {
        val loginId = passwordSecurity.validateLoginId(rawLoginId)
        passwordSecurity.validatePasswordSyntax(password)
        val snapshot = accounts.findByLoginId(loginId)?.snapshot()
        val matched = passwordSecurity.matches(password, snapshot?.passwordHash ?: passwordSecurity.dummyHash)
        val prepared =
            PreparedMerchantLogin(
                loginIdHmac = scopeHmac.loginId(LoginAttemptActorType.MERCHANT, loginId),
                ipHmac = scopeHmac.ip(LoginAttemptActorType.MERCHANT, sourceIp),
                snapshot = snapshot,
                passwordMatched = matched && snapshot != null,
                currentSessionId = currentSessionId,
                now = clock.instant(),
            )
        val completion =
            try {
                transactions.completeLogin(prepared)
            } catch (_: MerchantCredentialChanged) {
                MerchantLoginCompletion.Rejected()
            }
        return when (completion) {
            is MerchantLoginCompletion.Succeeded -> {
                registry.counter("beanflow.identity.merchant.login", "outcome", "success").increment()
                completion.result
            }

            is MerchantLoginCompletion.Rejected -> {
                completion.rateLimitedUntil?.let { until ->
                    val seconds = Duration.between(prepared.now, until).seconds.coerceIn(1, 900)
                    registry.counter("beanflow.identity.merchant.login", "outcome", "rate_limited").increment()
                    throw DomainFailure(FailureCode.AUTHENTICATION_RATE_LIMITED, "Authentication rate limit exceeded", seconds)
                }
                registry.counter("beanflow.identity.merchant.login", "outcome", "failed").increment()
                throw DomainFailure(FailureCode.AUTHENTICATION_FAILED, "Authentication failed")
            }
        }
    }

    fun changePassword(
        accountId: UUID,
        currentPassword: String,
        newPassword: String,
        currentSessionId: String?,
    ): MerchantPasswordChangeResult {
        passwordSecurity.validatePasswordSyntax(currentPassword)
        val snapshot =
            accounts
                .findById(accountId)
                .orElseThrow {
                    DomainFailure(FailureCode.AUTHENTICATION_FAILED, "Authentication failed")
                }.snapshot()
        if (!passwordSecurity.matches(currentPassword, snapshot.passwordHash)) {
            throw DomainFailure(FailureCode.AUTHENTICATION_FAILED, "Authentication failed")
        }
        if (currentPassword == newPassword) {
            throw DomainFailure(FailureCode.PASSWORD_POLICY_VIOLATION, "New password must differ from current password")
        }
        passwordSecurity.validateRegistrationPassword(snapshot.loginId, newPassword)
        val newHash = passwordSecurity.encode(newPassword)
        val changed = transactions.changePassword(snapshot, newHash, clock.instant())
        val session =
            sessionTransactions.create(
                CreateLoginSession(
                    actorType = BrowserActorType.MERCHANT,
                    actorId = changed.accountId,
                    authenticatedAtEpochMilli = clock.instant().toEpochMilli(),
                    credentialVersion = changed.credentialVersion,
                    currentSessionId = currentSessionId,
                ),
            )
        registry.counter("beanflow.identity.merchant.password_change", "outcome", "success").increment()
        return MerchantPasswordChangeResult(changed.credentialVersion, session)
    }

    @Transactional(readOnly = true)
    fun me(accountId: UUID): MerchantView {
        val account =
            accounts.findById(accountId).orElseThrow {
                BrowserAuthenticationInvalid("Merchant account is unavailable")
            }
        return MerchantView(account.id, account.displayName, account.state)
    }

    @Transactional(readOnly = true)
    fun stores(accountId: UUID): List<MerchantStoreView> {
        accessPolicy.requireActive(accountId)
        return memberships
            .findAllByActorIdAndStatusOrderByStoreIdAsc(accountId, StoreMembershipStatus.ACTIVE)
            .map { membership ->
                MerchantStoreView(
                    storeId = membership.storeId,
                    storeName = storeNames.requireCurrentName(membership.storeId),
                    membershipRole = membership.membershipRole.name,
                )
            }
    }

    fun logout(sessionId: String) = transactions.logout(sessionId)
}

@Service
internal class MerchantAccountTransactions(
    private val accounts: MerchantAccountJpaRepository,
    private val attempts: LoginAttemptRepository,
    private val sessions: LoginSessionCoordinator,
    private val sessionLifecycle: BrowserSessionLifecycle,
    private val audits: AuditRecordOperations,
    private val correlationIds: CorrelationIdSource,
    private val registry: MeterRegistry,
) {
    @Transactional
    fun completeLogin(command: PreparedMerchantLogin): MerchantLoginCompletion {
        if (!command.passwordMatched) return completeFailure(command)

        val lockedAttempts = attempts.lockExisting(LoginAttemptActorType.MERCHANT, command.loginIdHmac, command.ipHmac)
        lockedAttempts.rows[LoginAttemptScope.IP]?.blockedUntil?.takeIf(command.now::isBefore)?.let {
            return MerchantLoginCompletion.Rejected(it)
        }
        lockedAttempts.rows[LoginAttemptScope.LOGIN_ID]?.blockedUntil?.takeIf(command.now::isBefore)?.let {
            return MerchantLoginCompletion.Rejected()
        }
        val snapshot = command.snapshot ?: return completeFailure(command)
        val account = accounts.findLockedById(snapshot.id) ?: throw MerchantCredentialChanged()
        if (!account.matches(snapshot)) throw MerchantCredentialChanged()
        if (account.state == MerchantAccountState.INITIAL_PASSWORD && !account.temporaryPasswordUsable(command.now)) {
            account.materializeTemporaryPasswordExpiry(command.now)
            return MerchantLoginCompletion.Rejected()
        }
        if (account.state == MerchantAccountState.EXPIRED) return MerchantLoginCompletion.Rejected()
        account.lockedUntil?.let { until ->
            if (command.now.isBefore(until)) return MerchantLoginCompletion.Rejected()
            account.clearExpiredLock(command.now)
            registry.counter("beanflow.identity.merchant.lock", "outcome", "expired").increment()
        }
        attempts.deleteLoginId(LoginAttemptActorType.MERCHANT, command.loginIdHmac)
        val session =
            sessions.create(
                CreateLoginSession(
                    actorType = BrowserActorType.MERCHANT,
                    actorId = account.id,
                    authenticatedAtEpochMilli = command.now.toEpochMilli(),
                    credentialVersion = account.credentialVersion,
                    currentSessionId = command.currentSessionId,
                ),
            )
        return MerchantLoginCompletion.Succeeded(
            MerchantLoginResult(account.id, account.displayName, account.state, session),
        )
    }

    @Transactional
    fun changePassword(
        snapshot: MerchantCredentialSnapshot,
        newPasswordHash: String,
        now: Instant,
    ): ChangedMerchantCredential {
        val account = accounts.findLockedById(snapshot.id) ?: throw MerchantCredentialChanged()
        if (!account.matches(snapshot) || !account.loginAllowed(now)) {
            throw DomainFailure(FailureCode.AUTHENTICATION_FAILED, "Authentication failed")
        }
        val beforeState = account.state
        val beforeVersion = account.credentialVersion
        account.changePassword(newPasswordHash, now)
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = account.id.toString(),
                    actorType = AuditActorType.MERCHANT,
                    category = AuditCategory.SECURITY_AND_PERMISSION,
                    action = "MERCHANT_PASSWORD_CHANGED",
                    targetType = "MERCHANT_ACCOUNT",
                    targetId = account.id,
                    occurredAt = now,
                    reason = "MERCHANT_SELF_PASSWORD_CHANGE",
                    beforeSummary =
                        mapOf(
                            "accountState" to beforeState.name,
                            "credentialVersion" to beforeVersion.toString(),
                        ),
                    afterSummary =
                        mapOf(
                            "accountState" to account.state.name,
                            "credentialVersion" to account.credentialVersion.toString(),
                        ),
                    correlationId = correlationIds.currentOrCreate(),
                    sourceReference = "merchant-password-change:${account.id}:version:${account.credentialVersion}",
                ),
            ),
        )
        return ChangedMerchantCredential(account.id, account.credentialVersion)
    }

    @Transactional
    fun logout(sessionId: String) = sessionLifecycle.logout(sessionId)

    private fun completeFailure(command: PreparedMerchantLogin): MerchantLoginCompletion {
        val attemptLock = attempts.beginFailure(LoginAttemptActorType.MERCHANT, command.loginIdHmac, command.ipHmac, command.now)
        val account =
            command.snapshot?.let { snapshot ->
                accounts.findLockedById(snapshot.id)?.also {
                    if (!it.matches(snapshot)) throw MerchantCredentialChanged()
                } ?: throw MerchantCredentialChanged()
            }
        val outcome = attempts.applyFailure(attemptLock, command.now)
        if (outcome.loginId.failureCount == LoginAttemptScope.LOGIN_ID.limit && outcome.authenticationBlocked(command.now)) {
            if (account != null && account.lockedUntil?.let(command.now::isBefore) != true) {
                account.lock(checkNotNull(outcome.loginId.blockedUntil), command.now)
                registry.counter("beanflow.identity.merchant.lock", "outcome", "created").increment()
            }
        }
        return MerchantLoginCompletion.Rejected(outcome.ip.blockedUntil?.takeIf(command.now::isBefore))
    }

    private fun MerchantAccountEntity.matches(snapshot: MerchantCredentialSnapshot): Boolean =
        id == snapshot.id &&
            loginId == snapshot.loginId &&
            passwordHash == snapshot.passwordHash &&
            credentialVersion == snapshot.credentialVersion &&
            state == snapshot.state &&
            temporaryPasswordExpiresAt == snapshot.temporaryPasswordExpiresAt &&
            lockedUntil == snapshot.lockedUntil
}

@Service
internal class MerchantSessionTransactions(
    private val sessions: LoginSessionCoordinator,
) {
    @Transactional
    fun create(command: CreateLoginSession): LoginSessionHandle = sessions.create(command)
}

@Component
internal class MerchantAccountAccessPolicy(
    private val accounts: MerchantAccountJpaRepository,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun requireActive(accountId: UUID) {
        val account =
            accounts.findById(accountId).orElseThrow {
                DomainFailure(FailureCode.ACCESS_DENIED, "Active merchant account is required")
            }
        when (account.state) {
            MerchantAccountState.INITIAL_PASSWORD -> {
                throw DomainFailure(
                    FailureCode.INITIAL_PASSWORD_CHANGE_REQUIRED,
                    "Initial password must be changed before using merchant features",
                )
            }

            MerchantAccountState.EXPIRED -> {
                throw DomainFailure(FailureCode.ACCESS_DENIED, "Merchant credential is expired")
            }

            MerchantAccountState.ACTIVE -> {
                Unit
            }
        }
        if (account.lockedUntil?.let(clock.instant()::isBefore) == true) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Merchant credential is locked")
        }
    }
}

@Component
internal class MerchantBrowserActorLoader(
    private val accounts: MerchantAccountJpaRepository,
    private val clock: Clock,
) : BrowserActorLoader {
    override val actorType: BrowserActorType = BrowserActorType.MERCHANT

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    override fun load(
        actorId: UUID,
        credentialVersion: Long,
    ): CurrentActor {
        val account =
            accounts.findById(actorId).orElseThrow {
                BrowserAuthenticationInvalid("Merchant session is no longer valid")
            }
        val now = clock.instant()
        if (account.credentialVersion != credentialVersion ||
            account.state == MerchantAccountState.EXPIRED ||
            account.lockedUntil?.let(now::isBefore) == true ||
            (account.state == MerchantAccountState.INITIAL_PASSWORD && !account.temporaryPasswordUsable(now))
        ) {
            throw BrowserAuthenticationInvalid("Merchant session is no longer valid")
        }
        return MerchantActor(account.id, account.state)
    }
}

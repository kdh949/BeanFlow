package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.loyalty.api.CustomerPointAccountProvisioningFailed
import io.github.kdh949.beanflow.loyalty.api.CustomerPointAccountProvisioningOperations
import io.github.kdh949.beanflow.shared.api.BrowserActorLoader
import io.github.kdh949.beanflow.shared.api.BrowserActorType
import io.github.kdh949.beanflow.shared.api.BrowserAuthenticationInvalid
import io.github.kdh949.beanflow.shared.api.BrowserSessionLifecycle
import io.github.kdh949.beanflow.shared.api.CreateLoginSession
import io.github.kdh949.beanflow.shared.api.CurrentActor
import io.github.kdh949.beanflow.shared.api.CustomerActor
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.LoginSessionCoordinator
import io.github.kdh949.beanflow.shared.api.LoginSessionHandle
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal data class CustomerRegistration(
    val loginId: String,
    val password: String,
    val displayName: String,
)

internal data class CustomerLoginResult(
    val customerId: UUID,
    val displayName: String,
    val session: LoginSessionHandle,
)

internal data class CustomerView(
    val customerId: UUID,
    val displayName: String,
)

internal data class PreparedCustomerLogin(
    val loginIdHmac: String,
    val ipHmac: String,
    val snapshot: CustomerCredentialSnapshot?,
    val passwordMatched: Boolean,
    val currentSessionId: String?,
    val now: Instant,
)

internal sealed interface CustomerLoginCompletion {
    data class Succeeded(
        val result: CustomerLoginResult,
    ) : CustomerLoginCompletion

    data class Rejected(
        val rateLimitedUntil: Instant? = null,
    ) : CustomerLoginCompletion
}

private class CustomerCredentialChanged : RuntimeException()

@Service
internal class CustomerAccountApplicationService(
    private val accounts: CustomerAccountJpaRepository,
    private val passwordSecurity: CustomerPasswordSecurity,
    private val scopeHmac: AuthenticationScopeHmac,
    private val transactions: CustomerAccountTransactions,
    private val clock: Clock,
    private val registry: MeterRegistry,
) {
    fun register(command: CustomerRegistration): String {
        val loginId = passwordSecurity.validateLoginId(command.loginId)
        passwordSecurity.validateRegistrationPassword(loginId, command.password)
        validateDisplayName(command.displayName)
        if (accounts.existsByLoginId(loginId)) unavailableLoginId()
        val passwordHash = passwordSecurity.encode(command.password)
        try {
            transactions.register(
                loginId,
                passwordHash,
                command.displayName,
                scopeHmac.loginId(LoginAttemptActorType.CUSTOMER, loginId),
                clock.instant(),
            )
        } catch (failure: DataIntegrityViolationException) {
            if (failure.containsConstraint("ux_identity_customer_account_login_id")) unavailableLoginId()
            registry.counter("beanflow.identity.customer.registration", "outcome", "dependency_failure").increment()
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Customer registration persistence failed")
        } catch (_: CustomerPointAccountProvisioningFailed) {
            registry.counter("beanflow.identity.customer.registration", "outcome", "dependency_failure").increment()
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Customer registration persistence failed")
        }
        registry.counter("beanflow.identity.customer.registration", "outcome", "success").increment()
        registry.counter("beanflow.identity.customer.point_provision", "outcome", "success").increment()
        return loginId
    }

    fun login(
        rawLoginId: String,
        password: String,
        sourceIp: String,
        currentSessionId: String?,
    ): CustomerLoginResult {
        val loginId = passwordSecurity.validateLoginId(rawLoginId)
        passwordSecurity.validatePasswordSyntax(password)
        val snapshot = accounts.findByLoginId(loginId)?.snapshot()
        val matched = passwordSecurity.matches(password, snapshot?.passwordHash ?: passwordSecurity.dummyHash)
        val prepared =
            PreparedCustomerLogin(
                loginIdHmac = scopeHmac.loginId(LoginAttemptActorType.CUSTOMER, loginId),
                ipHmac = scopeHmac.ip(LoginAttemptActorType.CUSTOMER, sourceIp),
                snapshot = snapshot,
                passwordMatched = matched && snapshot != null,
                currentSessionId = currentSessionId,
                now = clock.instant(),
            )
        val completion =
            try {
                transactions.completeLogin(prepared)
            } catch (_: CustomerCredentialChanged) {
                CustomerLoginCompletion.Rejected()
            }
        return when (completion) {
            is CustomerLoginCompletion.Succeeded -> {
                registry.counter("beanflow.identity.customer.login", "outcome", "success").increment()
                completion.result
            }

            is CustomerLoginCompletion.Rejected -> {
                completion.rateLimitedUntil?.let { until ->
                    val seconds = Duration.between(prepared.now, until).seconds.coerceIn(1, 900)
                    registry.counter("beanflow.identity.customer.login", "outcome", "rate_limited").increment()
                    throw DomainFailure(FailureCode.AUTHENTICATION_RATE_LIMITED, "Authentication rate limit exceeded", seconds)
                }
                registry.counter("beanflow.identity.customer.login", "outcome", "failed").increment()
                throw DomainFailure(FailureCode.AUTHENTICATION_FAILED, "Authentication failed")
            }
        }
    }

    @Transactional(readOnly = true)
    fun me(customerId: UUID): CustomerView {
        val account = accounts.findById(customerId).orElseThrow { BrowserAuthenticationInvalid("Customer account is unavailable") }
        return CustomerView(account.id, account.displayName)
    }

    fun logout(sessionId: String) = transactions.logout(sessionId)

    private fun unavailableLoginId(): Nothing {
        registry.counter("beanflow.identity.customer.registration", "outcome", "login_id_unavailable").increment()
        throw DomainFailure(FailureCode.LOGIN_ID_UNAVAILABLE, "Login ID is unavailable")
    }

    private fun validateDisplayName(displayName: String) {
        val length = displayName.codePointCount(0, displayName.length)
        if (length !in 1..100 || displayName.isBlank() || displayName.any(Char::isISOControl)) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Display name is invalid")
        }
    }

    private fun Throwable.containsConstraint(constraint: String): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current.message?.contains(constraint, ignoreCase = true) == true) return true
            current = current.cause
        }
        return false
    }
}

@Service
internal class CustomerAccountTransactions(
    private val accounts: CustomerAccountJpaRepository,
    private val attempts: LoginAttemptRepository,
    private val pointAccounts: CustomerPointAccountProvisioningOperations,
    private val sessions: LoginSessionCoordinator,
    private val sessionLifecycle: BrowserSessionLifecycle,
    private val registry: MeterRegistry,
) {
    @Transactional
    fun register(
        loginId: String,
        passwordHash: String,
        displayName: String,
        loginIdHmac: String,
        now: Instant,
    ) {
        val customerId = UUID.randomUUID()
        accounts.saveAndFlush(
            CustomerAccountEntity(
                id = customerId,
                loginId = loginId,
                passwordHash = passwordHash,
                displayName = displayName,
                createdAt = now,
                updatedAt = now,
            ),
        )
        try {
            pointAccounts.create(customerId)
        } catch (failure: RuntimeException) {
            registry.counter("beanflow.identity.customer.point_provision", "outcome", "failed").increment()
            throw failure
        }
        attempts.deleteLoginId(LoginAttemptActorType.CUSTOMER, loginIdHmac)
    }

    @Transactional
    fun completeLogin(command: PreparedCustomerLogin): CustomerLoginCompletion {
        if (!command.passwordMatched) return completeFailure(command)

        val lockedAttempts =
            attempts.lockExisting(LoginAttemptActorType.CUSTOMER, command.loginIdHmac, command.ipHmac)
        lockedAttempts.rows[LoginAttemptScope.IP]?.blockedUntil?.takeIf(command.now::isBefore)?.let {
            return CustomerLoginCompletion.Rejected(it)
        }
        lockedAttempts.rows[LoginAttemptScope.LOGIN_ID]?.blockedUntil?.takeIf(command.now::isBefore)?.let {
            return CustomerLoginCompletion.Rejected()
        }
        val snapshot = command.snapshot ?: return completeFailure(command)
        val account = accounts.findLockedById(snapshot.id) ?: throw CustomerCredentialChanged()
        if (!account.matches(snapshot)) throw CustomerCredentialChanged()
        if (account.state == CustomerAccountState.LOCKED) {
            val until = checkNotNull(account.lockedUntil)
            if (command.now.isBefore(until)) return CustomerLoginCompletion.Rejected()
            account.activateAfterExpiredLock(command.now)
            registry.counter("beanflow.identity.customer.lock", "outcome", "expired").increment()
        }
        attempts.deleteLoginId(LoginAttemptActorType.CUSTOMER, command.loginIdHmac)
        val session =
            sessions.create(
                CreateLoginSession(
                    actorType = BrowserActorType.CUSTOMER,
                    actorId = account.id,
                    authenticatedAtEpochMilli = command.now.toEpochMilli(),
                    credentialVersion = account.credentialVersion,
                    currentSessionId = command.currentSessionId,
                ),
            )
        return CustomerLoginCompletion.Succeeded(CustomerLoginResult(account.id, account.displayName, session))
    }

    @Transactional
    fun logout(sessionId: String) = sessionLifecycle.logout(sessionId)

    private fun completeFailure(command: PreparedCustomerLogin): CustomerLoginCompletion {
        val attemptLock =
            attempts.beginFailure(LoginAttemptActorType.CUSTOMER, command.loginIdHmac, command.ipHmac, command.now)
        val account =
            command.snapshot?.let { snapshot ->
                accounts.findLockedById(snapshot.id)?.also {
                    if (!it.matches(snapshot)) throw CustomerCredentialChanged()
                } ?: throw CustomerCredentialChanged()
            }
        val outcome = attempts.applyFailure(attemptLock, command.now)
        if (outcome.loginId.failureCount == LoginAttemptScope.LOGIN_ID.limit && outcome.authenticationBlocked(command.now)) {
            if (account != null &&
                !(account.state == CustomerAccountState.LOCKED && account.lockedUntil?.let(command.now::isBefore) == true)
            ) {
                account.lock(checkNotNull(outcome.loginId.blockedUntil), command.now)
                registry.counter("beanflow.identity.customer.lock", "outcome", "created").increment()
            }
        }
        return CustomerLoginCompletion.Rejected(outcome.ip.blockedUntil?.takeIf(command.now::isBefore))
    }

    private fun CustomerAccountEntity.matches(snapshot: CustomerCredentialSnapshot): Boolean =
        id == snapshot.id &&
            passwordHash == snapshot.passwordHash &&
            credentialVersion == snapshot.credentialVersion &&
            state == snapshot.state &&
            lockedUntil == snapshot.lockedUntil
}

@Component
internal class CustomerBrowserActorLoader(
    private val accounts: CustomerAccountJpaRepository,
) : BrowserActorLoader {
    override val actorType: BrowserActorType = BrowserActorType.CUSTOMER

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    override fun load(
        actorId: UUID,
        credentialVersion: Long,
    ): CurrentActor {
        val account = accounts.findById(actorId).orElseThrow { BrowserAuthenticationInvalid("Customer session is no longer valid") }
        if (account.state != CustomerAccountState.ACTIVE || account.credentialVersion != credentialVersion) {
            throw BrowserAuthenticationInvalid("Customer session is no longer valid")
        }
        return CustomerActor(account.id)
    }
}

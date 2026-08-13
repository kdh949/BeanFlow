package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.identity.api.CreateMerchantCredentialCommand
import io.github.kdh949.beanflow.identity.api.MerchantCredentialAdministrationOperations
import io.github.kdh949.beanflow.identity.api.MerchantCredentialAdministrationSnapshot
import io.github.kdh949.beanflow.identity.api.MerchantCredentialSecurityOperations
import io.github.kdh949.beanflow.identity.api.MerchantMembershipSnapshot
import io.github.kdh949.beanflow.identity.api.ResetMerchantTemporaryPasswordCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.MerchantAccountState
import jakarta.persistence.EntityManager
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class MerchantCredentialSecurityService(
    private val passwords: CustomerPasswordSecurity,
) : MerchantCredentialSecurityOperations {
    override fun canonicalizeLoginId(rawLoginId: String): String = passwords.validateLoginId(rawLoginId)

    override fun hashTemporaryPassword(temporaryPassword: String): String = passwords.encode(temporaryPassword)
}

@Service
internal class MerchantCredentialAdministrationService(
    private val accounts: MerchantAccountJpaRepository,
    private val memberships: StoreMembershipJpaRepository,
    private val attempts: LoginAttemptRepository,
    private val scopeHmac: AuthenticationScopeHmac,
    private val entityManager: EntityManager,
) : MerchantCredentialAdministrationOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun create(command: CreateMerchantCredentialCommand): MerchantCredentialAdministrationSnapshot {
        if (accounts.existsByLoginId(command.loginId)) {
            throw DomainFailure(FailureCode.LOGIN_ID_UNAVAILABLE, "Merchant login ID is unavailable")
        }
        val account =
            MerchantAccountEntity(
                id = command.accountId,
                loginId = command.loginId,
                passwordHash = command.passwordHash,
                displayName = command.displayName,
                state = MerchantAccountState.INITIAL_PASSWORD,
                temporaryPasswordExpiresAt = command.temporaryPasswordExpiresAt,
                passwordChangedAt = null,
                createdAt = command.now,
                updatedAt = command.now,
            )
        try {
            accounts.save(account)
            memberships.save(
                StoreMembershipEntity(
                    id = UUID.randomUUID(),
                    actorId = command.accountId,
                    storeId = command.storeId,
                    membershipRole = command.membershipRole,
                    status = StoreMembershipStatus.ACTIVE,
                    createdAt = command.now,
                    updatedAt = command.now,
                ),
            )
            clearLoginAttempts(command.loginId)
            entityManager.flush()
        } catch (failure: DataIntegrityViolationException) {
            throw DomainFailure(FailureCode.LOGIN_ID_UNAVAILABLE, "Merchant account or membership already exists")
        }
        return account.toAdministrationSnapshot(memberships.findAllByActorIdOrderByStoreIdAsc(account.id))
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun resetTemporaryPassword(command: ResetMerchantTemporaryPasswordCommand): MerchantCredentialAdministrationSnapshot {
        val account = requireLocked(command.accountId)
        account.resetTemporaryPassword(command.passwordHash, command.temporaryPasswordExpiresAt, command.now)
        clearLoginAttempts(account.loginId)
        entityManager.flush()
        return account.toAdministrationSnapshot(memberships.findAllByActorIdOrderByStoreIdAsc(account.id))
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun releaseLock(
        accountId: UUID,
        now: java.time.Instant,
    ): MerchantCredentialAdministrationSnapshot {
        val account = requireLocked(accountId)
        account.releaseLock(now)
        clearLoginAttempts(account.loginId)
        entityManager.flush()
        return account.toAdministrationSnapshot(memberships.findAllByActorIdOrderByStoreIdAsc(account.id))
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    override fun findExact(loginId: String): MerchantCredentialAdministrationSnapshot? =
        accounts.findByLoginId(loginId)?.let { account ->
            account.toAdministrationSnapshot(memberships.findAllByActorIdOrderByStoreIdAsc(account.id))
        }

    private fun requireLocked(accountId: UUID): MerchantAccountEntity =
        accounts.findLockedById(accountId)
            ?: throw DomainFailure(FailureCode.MERCHANT_ACCOUNT_NOT_FOUND, "Merchant account was not found")

    private fun clearLoginAttempts(loginId: String) {
        attempts.deleteLoginId(
            LoginAttemptActorType.MERCHANT,
            scopeHmac.loginId(LoginAttemptActorType.MERCHANT, loginId),
        )
    }

    private fun MerchantAccountEntity.toAdministrationSnapshot(
        rows: List<StoreMembershipEntity>,
    ): MerchantCredentialAdministrationSnapshot =
        MerchantCredentialAdministrationSnapshot(
            accountId = id,
            loginId = loginId,
            displayName = displayName,
            accountState = state,
            lockedUntil = lockedUntil,
            temporaryPasswordExpiresAt = temporaryPasswordExpiresAt,
            credentialVersion = credentialVersion,
            memberships = rows.map { MerchantMembershipSnapshot(it.storeId, it.membershipRole) },
        )
}

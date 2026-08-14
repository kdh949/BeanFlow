package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.operations.api.MerchantCredentialMembershipRole
import io.github.kdh949.beanflow.operations.api.MerchantCredentialProvisioningPort
import io.github.kdh949.beanflow.operations.api.MerchantCredentialSecurityPort
import io.github.kdh949.beanflow.operations.api.ProvisionMerchantCredentialCommand
import io.github.kdh949.beanflow.operations.api.ProvisionedMerchantCredential
import io.github.kdh949.beanflow.operations.api.ProvisionedMerchantMembership
import io.github.kdh949.beanflow.operations.api.ReplaceMerchantTemporaryPasswordCommand
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
internal class MerchantCredentialAdministrationService(
    private val accounts: MerchantAccountJpaRepository,
    private val memberships: StoreMembershipJpaRepository,
    private val attempts: LoginAttemptRepository,
    private val scopeHmac: AuthenticationScopeHmac,
    private val entityManager: EntityManager,
    private val passwords: CustomerPasswordSecurity,
) : MerchantCredentialProvisioningPort,
    MerchantCredentialSecurityPort {
    override fun canonicalizeLoginId(rawLoginId: String): String = passwords.validateLoginId(rawLoginId)

    override fun hashTemporaryPassword(temporaryPassword: String): String = passwords.encode(temporaryPassword)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun create(command: ProvisionMerchantCredentialCommand): ProvisionedMerchantCredential {
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
                    membershipRole = command.membershipRole.toStoreActorRole(),
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
    override fun resetTemporaryPassword(command: ReplaceMerchantTemporaryPasswordCommand): ProvisionedMerchantCredential {
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
    ): ProvisionedMerchantCredential {
        val account = requireLocked(accountId)
        account.releaseLock(now)
        clearLoginAttempts(account.loginId)
        entityManager.flush()
        return account.toAdministrationSnapshot(memberships.findAllByActorIdOrderByStoreIdAsc(account.id))
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    override fun findExact(loginId: String): ProvisionedMerchantCredential? =
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

    private fun MerchantAccountEntity.toAdministrationSnapshot(rows: List<StoreMembershipEntity>): ProvisionedMerchantCredential =
        ProvisionedMerchantCredential(
            accountId = id,
            loginId = loginId,
            displayName = displayName,
            accountState = state,
            lockedUntil = lockedUntil,
            temporaryPasswordExpiresAt = temporaryPasswordExpiresAt,
            credentialVersion = credentialVersion,
            memberships =
                rows.map {
                    ProvisionedMerchantMembership(
                        it.storeId,
                        MerchantCredentialMembershipRole.valueOf(it.membershipRole.name),
                    )
                },
        )

    private fun MerchantCredentialMembershipRole.toStoreActorRole(): StoreActorRole = StoreActorRole.valueOf(name)
}

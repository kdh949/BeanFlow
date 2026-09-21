package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.identity.api.CustomerOrderingAccess
import io.github.kdh949.beanflow.identity.api.DemoIdentityOperations
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.loyalty.api.CustomerPointAccountProvisioningOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.MerchantAccountState
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
@Transactional(propagation = Propagation.MANDATORY)
internal class DemoIdentityService(
    private val customers: CustomerAccountJpaRepository,
    private val merchants: MerchantAccountJpaRepository,
    private val memberships: StoreMembershipJpaRepository,
    private val points: CustomerPointAccountProvisioningOperations,
    private val passwords: CustomerPasswordSecurity,
) : DemoIdentityOperations {
    override fun provision(
        customerId: UUID,
        merchantId: UUID,
        storeId: UUID,
        now: Instant,
        expiresAt: Instant,
    ) {
        require(expiresAt.isAfter(now))
        check(!customers.existsById(customerId) && !merchants.existsById(merchantId))
        // Random secrets are discarded immediately; they are never returned or logged.
        customers.saveAndFlush(
            CustomerAccountEntity(
                id = customerId,
                loginId = "dc." + customerId.toString().replace("-", "").take(28),
                passwordHash = passwords.encode(UUID.randomUUID().toString()),
                displayName = "체험 고객",
                createdAt = now,
                updatedAt = now,
                demoStoreId = storeId,
                demoExpiresAt = expiresAt,
            ),
        )
        points.create(customerId)
        merchants.saveAndFlush(
            MerchantAccountEntity(
                id = merchantId,
                loginId = "dm." + merchantId.toString().replace("-", "").take(28),
                passwordHash = passwords.encode(UUID.randomUUID().toString()),
                displayName = "체험 점주",
                state = MerchantAccountState.ACTIVE,
                temporaryPasswordExpiresAt = null,
                passwordChangedAt = now,
                createdAt = now,
                updatedAt = now,
                demoExpiresAt = expiresAt,
            ),
        )
        memberships.saveAndFlush(
            StoreMembershipEntity(
                id = UUID.randomUUID(),
                actorId = merchantId,
                storeId = storeId,
                membershipRole = StoreActorRole.OWNER,
                status = StoreMembershipStatus.ACTIVE,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    override fun end(
        customerId: UUID,
        merchantId: UUID,
        now: Instant,
    ) {
        val customer = customers.findLockedById(customerId) ?: error("Demo customer missing")
        val merchant = merchants.findLockedById(merchantId) ?: error("Demo merchant missing")
        check(customer.demoExpiresAt != null && merchant.demoExpiresAt != null)
        customer.demoExpiresAt = minOf(requireNotNull(customer.demoExpiresAt), now)
        merchant.demoExpiresAt = minOf(requireNotNull(merchant.demoExpiresAt), now)
        customer.credentialVersion += 1
        merchant.credentialVersion += 1
    }
}

@Service
internal class CustomerOrderingAccessService(
    private val accounts: CustomerAccountJpaRepository,
    private val clock: Clock,
    @Value("\${beanflow.demo.enabled:false}") private val demoEnabled: Boolean,
) : CustomerOrderingAccess {
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    override fun requireStore(
        customerId: UUID,
        storeId: UUID,
    ) {
        if (!demoEnabled) return
        if (accounts.findByDemoStoreId(storeId)?.id?.let { it != customerId } == true) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Demo store is private to its visitor")
        }
        val account = accounts.findById(customerId).orElse(null) ?: return
        if (account.demoStoreId != null &&
            (account.demoStoreId != storeId || account.demoExpiresAt?.isAfter(clock.instant()) != true)
        ) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Demo account cannot order outside its active workspace")
        }
    }
}

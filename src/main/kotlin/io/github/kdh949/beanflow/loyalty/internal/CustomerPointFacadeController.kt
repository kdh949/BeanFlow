package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.loyalty.api.ListPointTransactionsCommand
import io.github.kdh949.beanflow.loyalty.api.PointAccountQueryOperations
import io.github.kdh949.beanflow.loyalty.api.PointAccountReadActor
import io.github.kdh949.beanflow.loyalty.api.PointAccountReadActorType
import io.github.kdh949.beanflow.loyalty.api.PointTransactionPage
import io.github.kdh949.beanflow.loyalty.api.ReadPointAccountCommand
import io.github.kdh949.beanflow.shared.api.CustomerActor
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import jakarta.validation.constraints.Size
import org.springframework.dao.DataAccessException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class ExpiringPointAmountResponse(
    val expiresAt: Instant,
    val amountKrw: Long,
)

/**
 * Actor-scoped point balance. The internal PointAccount ID is deliberately not
 * part of this representation; the browser never learns or sends it.
 */
internal data class CustomerPointSummaryResponse(
    val availablePointsKrw: Long,
    val recoveryPendingKrw: Long,
    val currency: String,
    val expiring: List<ExpiringPointAmountResponse>,
    val expiringHasMore: Boolean,
)

internal data class ExpiringPointAmounts(
    val items: List<ExpiringPointAmountProjection>,
    val hasMore: Boolean,
)

/**
 * Resolves the caller's PointAccount. BR-42 provisions exactly one account with
 * the CustomerAccount, so a missing account is an integrity failure to
 * investigate, never a zero balance, a lazy creation or a 404.
 */
@Component
internal class CustomerPointAccountLocator(
    private val repository: PointAccountQueryRepository,
) {
    @Transactional(readOnly = true)
    fun locate(customerId: UUID): UUID {
        val accountId =
            try {
                repository.findAccountIdByCustomer(customerId)
            } catch (failure: DataAccessException) {
                throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Point account lookup is unavailable").also {
                    it.initCause(failure)
                }
            }
        return accountId
            ?: throw DomainFailure(
                FailureCode.POINT_ACCOUNT_INTEGRITY_FAILURE,
                "Customer account has no point account",
            )
    }

    /** Requests one lot beyond the public limit so truncation can be reported instead of hidden. */
    @Transactional(readOnly = true)
    fun expiring(
        accountId: UUID,
        now: Instant,
    ): ExpiringPointAmounts =
        try {
            val fetched = repository.findExpiringLots(accountId, now, EXPIRING_LOT_LIMIT + 1)
            ExpiringPointAmounts(items = fetched.take(EXPIRING_LOT_LIMIT), hasMore = fetched.size > EXPIRING_LOT_LIMIT)
        } catch (failure: DataAccessException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Point expiration lookup is unavailable").also {
                it.initCause(failure)
            }
        }

    private companion object {
        const val EXPIRING_LOT_LIMIT = 20
    }
}

/**
 * Reads the balance and expiring lots within one PostgreSQL snapshot. Under the
 * default READ COMMITTED isolation, separate statements — even inside one
 * default-isolation transaction — can each see a different commit: a point use
 * committed between reading the balance and reading expiring lots could report
 * availablePointsKrw=0 together with an expiring amount that was never actually
 * available at any single instant. REPEATABLE READ pins one snapshot for every
 * read below. This is a separate bean so the transactional proxy is not
 * bypassed by self-invocation.
 */
@Component
internal class CustomerPointSummaryReader(
    private val accounts: CustomerPointAccountLocator,
    private val queries: PointAccountQueryOperations,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(
        customerId: UUID,
        now: Instant,
    ): CustomerPointSummaryResponse {
        val accountId = accounts.locate(customerId)
        val account =
            queries.get(
                ReadPointAccountCommand(
                    actor = PointAccountReadActor(customerId, PointAccountReadActorType.CUSTOMER),
                    accountId = accountId,
                    accessReason = null,
                    now = now,
                ),
            )
        val expiring = accounts.expiring(accountId, now)
        return CustomerPointSummaryResponse(
            availablePointsKrw = account.availablePointsKrw,
            recoveryPendingKrw = account.recoveryPendingKrw,
            currency = "KRW",
            expiring = expiring.items.map { ExpiringPointAmountResponse(it.expiresAt, it.amountKrw) },
            expiringHasMore = expiring.hasMore,
        )
    }
}

@Validated
@RestController
@RequestMapping("/api/v1/me")
internal class CustomerPointFacadeController(
    private val accounts: CustomerPointAccountLocator,
    private val queries: PointAccountQueryOperations,
    private val summaries: CustomerPointSummaryReader,
    private val clock: Clock,
) {
    @GetMapping("/points")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun points(actor: CustomerActor): CustomerPointSummaryResponse = summaries.read(actor.actorId, clock.instant())

    @GetMapping("/point-transactions")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun transactions(
        actor: CustomerActor,
        @RequestParam(required = false) @Size(max = 2048) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): PointTransactionPageResponse =
        queries
            .listTransactions(
                ListPointTransactionsCommand(
                    actor = PointAccountReadActor(actor.actorId, PointAccountReadActorType.CUSTOMER),
                    accountId = accounts.locate(actor.actorId),
                    accessReason = null,
                    cursor = cursor,
                    limit = limit,
                    now = clock.instant(),
                ),
            ).toCustomerResponse()
}

private fun PointTransactionPage.toCustomerResponse() = PointTransactionPageResponse(items, PointAccountPageInfoResponse(nextCursor))

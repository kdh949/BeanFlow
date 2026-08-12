package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.loyalty.api.GoodwillPointFundingIssuer
import io.github.kdh949.beanflow.loyalty.api.GoodwillPointIssuanceResult
import io.github.kdh949.beanflow.loyalty.api.GoodwillPointOperations
import io.github.kdh949.beanflow.loyalty.api.IssueGoodwillPointsCommand
import io.github.kdh949.beanflow.loyalty.api.PointIssuerType
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "loyalty_goodwill_point_issuance")
internal class GoodwillPointIssuanceEntity(
    @Id
    val id: UUID,
    @Column(name = "compensation_request_id", nullable = false)
    val compensationRequestId: UUID,
    @Column(name = "point_account_id", nullable = false)
    val pointAccountId: UUID,
    @Column(name = "source_reference", nullable = false, length = 240)
    val sourceReference: String,
    @Column(name = "payload_hash", nullable = false, length = 64)
    val payloadHash: String,
    @Column(name = "total_amount_krw", nullable = false)
    val totalAmountKrw: Long,
    @Column(name = "policy_version_id", nullable = false)
    val policyVersionId: UUID,
    @Column(name = "issued_at", nullable = false)
    val issuedAt: Instant,
)

@Entity
@Table(name = "loyalty_goodwill_point_funding_leg")
internal class GoodwillPointFundingLegEntity(
    @Id
    val id: UUID,
    @Column(name = "issuance_id", nullable = false)
    val issuanceId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "issuer_type", nullable = false, length = 16)
    val issuerType: GoodwillPointFundingIssuer,
    @Column(name = "store_id")
    val storeId: UUID?,
    @Column(name = "amount_krw", nullable = false)
    val amountKrw: Long,
    @Column(name = "point_lot_id", nullable = false)
    val pointLotId: UUID,
    @Column(name = "point_transaction_id", nullable = false)
    val pointTransactionId: UUID,
)

internal interface GoodwillPointIssuanceJpaRepository : JpaRepository<GoodwillPointIssuanceEntity, UUID> {
    fun findByCompensationRequestId(compensationRequestId: UUID): GoodwillPointIssuanceEntity?

    fun findBySourceReference(sourceReference: String): GoodwillPointIssuanceEntity?
}

internal interface GoodwillPointFundingLegJpaRepository : JpaRepository<GoodwillPointFundingLegEntity, UUID> {
    fun findAllByIssuanceIdOrderByIssuerTypeAsc(issuanceId: UUID): List<GoodwillPointFundingLegEntity>
}

@Service
internal class GoodwillPointIssuanceService(
    private val accounts: PointAccountJpaRepository,
    private val lots: PointLotJpaRepository,
    private val pointTransactions: PointTransactionJpaRepository,
    private val issuances: GoodwillPointIssuanceJpaRepository,
    private val fundingLegs: GoodwillPointFundingLegJpaRepository,
    private val identifiers: IdentifierSource,
) : GoodwillPointOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun issue(command: IssueGoodwillPointsCommand): GoodwillPointIssuanceResult {
        validate(command)
        issuances.findByCompensationRequestId(command.compensationRequestId)?.let { return replay(it, command) }
        issuances.findBySourceReference(command.sourceReference)?.let { return replay(it, command) }
        val account =
            accounts.findLockedByCustomerId(command.customerId)
                ?: fail(FailureCode.RESOURCE_NOT_FOUND, "Point account was not found")
        val issuanceId = identifiers.next()
        val persistedLegs =
            command.fundingLegs.map { leg ->
                val lotId = identifiers.next()
                val transactionId = identifiers.next()
                lots.save(
                    PointLotEntity(
                        id = lotId,
                        pointAccountId = account.id,
                        availableAmountKrw = leg.amountKrw,
                        expiresAt = command.expiresAt,
                        issuerType = leg.issuerType.toPointIssuer(),
                        issuerReference = leg.storeId?.toString() ?: PLATFORM_REFERENCE,
                    ),
                )
                pointTransactions.save(
                    PointTransactionEntity(
                        id = transactionId,
                        pointAccountId = account.id,
                        pointLotId = lotId,
                        amountKrw = leg.amountKrw,
                        type = PointTransactionType.GOODWILL_COMPENSATION,
                        sourceReference = "${command.sourceReference}:${leg.issuerType.name}",
                        occurredAt = command.issuedAt,
                    ),
                )
                GoodwillPointFundingLegEntity(
                    identifiers.next(),
                    issuanceId,
                    leg.issuerType,
                    leg.storeId,
                    leg.amountKrw,
                    lotId,
                    transactionId,
                )
            }
        account.availablePointsKrw = Math.addExact(account.availablePointsKrw, command.totalAmountKrw)
        val issuance =
            issuances.save(
                GoodwillPointIssuanceEntity(
                    issuanceId,
                    command.compensationRequestId,
                    account.id,
                    command.sourceReference,
                    command.payloadHash,
                    command.totalAmountKrw,
                    command.policyVersionId,
                    command.issuedAt,
                ),
            )
        fundingLegs.saveAll(persistedLegs)
        return issuance.result(persistedLegs, false)
    }

    private fun replay(
        issuance: GoodwillPointIssuanceEntity,
        command: IssueGoodwillPointsCommand,
    ): GoodwillPointIssuanceResult {
        if (issuance.compensationRequestId != command.compensationRequestId ||
            issuance.sourceReference != command.sourceReference || issuance.payloadHash != command.payloadHash ||
            issuance.totalAmountKrw != command.totalAmountKrw || issuance.policyVersionId != command.policyVersionId ||
            issuance.issuedAt != command.issuedAt
        ) {
            fail(FailureCode.IDEMPOTENCY_KEY_REUSED, "Goodwill point source was reused with another payload")
        }
        return issuance.result(fundingLegs.findAllByIssuanceIdOrderByIssuerTypeAsc(issuance.id), true)
    }

    private fun GoodwillPointIssuanceEntity.result(
        legs: List<GoodwillPointFundingLegEntity>,
        replayed: Boolean,
    ) = GoodwillPointIssuanceResult(id, pointAccountId, legs.map { it.pointLotId }, sourceReference, replayed)

    private fun validate(command: IssueGoodwillPointsCommand) {
        val legs = command.fundingLegs
        if (command.totalAmountKrw <= 0 || command.sourceReference.isBlank() ||
            command.sourceReference != command.sourceReference.trim() || command.sourceReference.length > 240 ||
            !command.payloadHash.matches(SHA_256) || !command.expiresAt.isAfter(command.issuedAt) ||
            Duration.between(command.issuedAt, command.expiresAt) != Duration.ofDays(30) ||
            legs.isEmpty() || legs.size > 2 || legs.map { it.issuerType }.distinct().size != legs.size ||
            legs.any { it.amountKrw <= 0 || (it.issuerType == GoodwillPointFundingIssuer.STORE) != (it.storeId != null) } ||
            legs.fold(0L) { total, leg -> Math.addExact(total, leg.amountKrw) } != command.totalAmountKrw
        ) {
            fail(FailureCode.INVALID_REQUEST, "Goodwill point issuance command is invalid")
        }
    }

    private fun GoodwillPointFundingIssuer.toPointIssuer(): PointIssuerType =
        when (this) {
            GoodwillPointFundingIssuer.PLATFORM -> PointIssuerType.PLATFORM
            GoodwillPointFundingIssuer.STORE -> PointIssuerType.STORE
        }

    private fun fail(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)

    private companion object {
        const val PLATFORM_REFERENCE = "SUPPORT_GOODWILL"
        val SHA_256 = Regex("^[0-9a-f]{64}$")
    }
}

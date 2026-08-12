package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.promotion.api.CouponCostBearer
import io.github.kdh949.beanflow.promotion.api.CouponDiscountType
import io.github.kdh949.beanflow.promotion.api.GoodwillCouponIssuanceResult
import io.github.kdh949.beanflow.promotion.api.GoodwillCouponOperations
import io.github.kdh949.beanflow.promotion.api.GoodwillCouponResponsibility
import io.github.kdh949.beanflow.promotion.api.GoodwillCouponTemplateView
import io.github.kdh949.beanflow.promotion.api.IssueGoodwillCouponCommand
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
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Entity
@Table(name = "promotion_goodwill_coupon_template")
internal class GoodwillCouponTemplateEntity(
    @Id
    val id: UUID,
    @Column(nullable = false, length = 80)
    val code: String,
    @Column(name = "fixed_amount_krw", nullable = false)
    val fixedAmountKrw: Long,
    @Column(name = "validity_days", nullable = false)
    val validityDays: Int,
    @Column(name = "minimum_eligible_subtotal_krw", nullable = false)
    val minimumEligibleSubtotalKrw: Long,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)

@Entity
@Table(name = "promotion_goodwill_coupon_issuance")
internal class GoodwillCouponIssuanceEntity(
    @Id
    val id: UUID,
    @Column(name = "compensation_request_id", nullable = false)
    val compensationRequestId: UUID,
    @Column(name = "coupon_template_id", nullable = false)
    val couponTemplateId: UUID,
    @Column(name = "campaign_id", nullable = false)
    val campaignId: UUID,
    @Column(name = "coupon_issuance_id", nullable = false)
    val couponIssuanceId: UUID,
    @Column(name = "source_reference", nullable = false, length = 240)
    val sourceReference: String,
    @Column(name = "payload_hash", nullable = false, length = 64)
    val payloadHash: String,
    @Column(name = "amount_krw", nullable = false)
    val amountKrw: Long,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val responsibility: GoodwillCouponResponsibility,
    @Column(name = "platform_share_bps", nullable = false)
    val platformShareBps: Int,
    @Column(name = "store_share_bps", nullable = false)
    val storeShareBps: Int,
    @Column(name = "issued_at", nullable = false)
    val issuedAt: Instant,
)

internal interface GoodwillCouponTemplateJpaRepository : JpaRepository<GoodwillCouponTemplateEntity, UUID>

internal interface GoodwillCouponIssuanceJpaRepository : JpaRepository<GoodwillCouponIssuanceEntity, UUID> {
    fun findByCompensationRequestId(compensationRequestId: UUID): GoodwillCouponIssuanceEntity?

    fun findBySourceReference(sourceReference: String): GoodwillCouponIssuanceEntity?
}

@Service
internal class GoodwillCouponIssuanceService(
    private val templates: GoodwillCouponTemplateJpaRepository,
    private val campaigns: CampaignJpaRepository,
    private val couponIssuances: CouponIssuanceJpaRepository,
    private val goodwillIssuances: GoodwillCouponIssuanceJpaRepository,
    private val identifiers: IdentifierSource,
) : GoodwillCouponOperations {
    @Transactional(readOnly = true)
    override fun findTemplate(templateId: UUID): GoodwillCouponTemplateView? =
        templates.findById(templateId).orElse(null)?.let { GoodwillCouponTemplateView(it.id, it.fixedAmountKrw, it.validityDays) }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun issue(command: IssueGoodwillCouponCommand): GoodwillCouponIssuanceResult {
        validate(command)
        goodwillIssuances.findByCompensationRequestId(command.compensationRequestId)?.let { return replay(it, command) }
        goodwillIssuances.findBySourceReference(command.sourceReference)?.let { return replay(it, command) }
        val template = templates.findById(command.couponTemplateId).orElse(null) ?: notFound()
        if (template.fixedAmountKrw != command.amountKrw || template.validityDays != VALIDITY_DAYS) {
            fail(FailureCode.COMPENSATION_SOURCE_CONFLICT, "Coupon template does not match compensation")
        }
        val campaignId = identifiers.next()
        val couponIssuanceId = identifiers.next()
        val expiresAt = command.issuedAt.plus(template.validityDays.toLong(), ChronoUnit.DAYS)
        campaigns.save(
            CampaignEntity(
                id = campaignId,
                storeId = command.storeId,
                active = true,
                discountType = CouponDiscountType.FIXED_KRW,
                fixedAmountKrw = template.fixedAmountKrw,
                rateBps = null,
                minimumEligibleSubtotalKrw = template.minimumEligibleSubtotalKrw,
                maximumDiscountKrw = template.fixedAmountKrw,
                allMenusEligible = true,
                costBearer = command.responsibility.toCostBearer(),
                platformShareBps = command.platformShareBps,
                storeShareBps = command.storeShareBps,
            ),
        )
        couponIssuances.save(
            CouponIssuanceEntity(
                id = couponIssuanceId,
                campaignId = campaignId,
                customerId = command.customerId,
                state = CouponIssuanceState.AVAILABLE,
                couponExpiresAt = expiresAt,
            ),
        )
        val issuance =
            goodwillIssuances.save(
                GoodwillCouponIssuanceEntity(
                    id = identifiers.next(),
                    compensationRequestId = command.compensationRequestId,
                    couponTemplateId = command.couponTemplateId,
                    campaignId = campaignId,
                    couponIssuanceId = couponIssuanceId,
                    sourceReference = command.sourceReference,
                    payloadHash = command.payloadHash,
                    amountKrw = command.amountKrw,
                    responsibility = command.responsibility,
                    platformShareBps = command.platformShareBps,
                    storeShareBps = command.storeShareBps,
                    issuedAt = command.issuedAt,
                ),
            )
        return issuance.result(expiresAt, false)
    }

    private fun replay(
        issuance: GoodwillCouponIssuanceEntity,
        command: IssueGoodwillCouponCommand,
    ): GoodwillCouponIssuanceResult {
        if (issuance.compensationRequestId != command.compensationRequestId ||
            issuance.couponTemplateId != command.couponTemplateId || issuance.sourceReference != command.sourceReference ||
            issuance.payloadHash != command.payloadHash || issuance.amountKrw != command.amountKrw ||
            issuance.responsibility != command.responsibility || issuance.platformShareBps != command.platformShareBps ||
            issuance.storeShareBps != command.storeShareBps || issuance.issuedAt != command.issuedAt
        ) {
            fail(FailureCode.IDEMPOTENCY_KEY_REUSED, "Goodwill coupon source was reused with another payload")
        }
        val coupon = couponIssuances.findById(issuance.couponIssuanceId).orElse(null) ?: dependency()
        return issuance.result(coupon.couponExpiresAt, true)
    }

    private fun GoodwillCouponIssuanceEntity.result(
        expiresAt: Instant,
        replayed: Boolean,
    ) = GoodwillCouponIssuanceResult(id, couponIssuanceId, campaignId, sourceReference, expiresAt, replayed)

    private fun validate(command: IssueGoodwillCouponCommand) {
        if (command.amountKrw <= 0 || command.sourceReference.isBlank() ||
            command.sourceReference != command.sourceReference.trim() || command.sourceReference.length > 240 ||
            !command.payloadHash.matches(SHA_256) || command.platformShareBps !in 0..10_000 ||
            command.storeShareBps !in 0..10_000 || command.platformShareBps + command.storeShareBps != 10_000 ||
            (command.responsibility == GoodwillCouponResponsibility.PLATFORM && command.platformShareBps != 10_000) ||
            (command.responsibility == GoodwillCouponResponsibility.STORE && command.storeShareBps != 10_000) ||
            (command.responsibility == GoodwillCouponResponsibility.SHARED &&
                (command.platformShareBps == 0 || command.storeShareBps == 0))
        ) {
            fail(FailureCode.INVALID_REQUEST, "Goodwill coupon issuance command is invalid")
        }
    }

    private fun GoodwillCouponResponsibility.toCostBearer(): CouponCostBearer = CouponCostBearer.valueOf(name)

    private fun notFound(): Nothing = fail(FailureCode.RESOURCE_NOT_FOUND, "Coupon template was not found")

    private fun dependency(): Nothing = fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Coupon issuance is missing")

    private fun fail(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)

    private companion object {
        const val VALIDITY_DAYS = 30
        val SHA_256 = Regex("^[0-9a-f]{64}$")
    }
}

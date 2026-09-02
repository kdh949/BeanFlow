package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.promotion.api.CouponCostBearer
import io.github.kdh949.beanflow.promotion.api.CouponDiscountType
import io.github.kdh949.beanflow.promotion.api.CreateLimitedCouponCampaignDraftCommand
import io.github.kdh949.beanflow.promotion.api.LimitedCouponCampaignOperations
import io.github.kdh949.beanflow.promotion.api.LimitedCouponCampaignPage
import io.github.kdh949.beanflow.promotion.api.LimitedCouponCampaignSnapshot
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

@Service
internal class LimitedCouponCampaignService(
    private val persistence: LimitedCouponCampaignPersistence,
) : LimitedCouponCampaignOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun createDraft(command: CreateLimitedCouponCampaignDraftCommand): LimitedCouponCampaignSnapshot =
        dependencyBoundary {
            val normalized = validate(command)
            val requestHash = requestHash(normalized)
            persistence.lockCommand(normalized.actorId, LimitedCouponCampaignPersistence.CREATE_OPERATION, normalized.idempotencyKey)
            val previous =
                persistence.findCommand(
                    normalized.actorId,
                    LimitedCouponCampaignPersistence.CREATE_OPERATION,
                    normalized.idempotencyKey,
                )
            if (previous != null) {
                if (previous.requestHash != requestHash) {
                    fail(FailureCode.IDEMPOTENCY_KEY_REUSED, "Idempotency key was already used for different campaign terms")
                }
                return@dependencyBoundary persistence.find(previous.campaignId)
                    ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Stored campaign command result is unavailable")
            }
            persistence.createDraft(normalized, requestHash)
        }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun find(campaignId: UUID): LimitedCouponCampaignSnapshot? = dependencyBoundary { persistence.find(campaignId) }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun list(
        afterCreatedAt: Instant?,
        afterCampaignId: UUID?,
        limit: Int,
    ): LimitedCouponCampaignPage =
        dependencyBoundary {
            if ((afterCreatedAt == null) != (afterCampaignId == null)) invalid("Campaign list cursor boundary is incomplete")
            if (limit !in 1..MAX_PAGE_SIZE) invalid("Campaign list limit must be between 1 and $MAX_PAGE_SIZE")
            val fetched = persistence.list(afterCreatedAt, afterCampaignId, limit + 1)
            val campaigns = fetched.take(limit)
            val boundary = campaigns.lastOrNull().takeIf { fetched.size > limit }
            LimitedCouponCampaignPage(campaigns, boundary?.createdAt, boundary?.campaignId)
        }

    private fun validate(command: CreateLimitedCouponCampaignDraftCommand): CreateLimitedCouponCampaignDraftCommand {
        val key = command.idempotencyKey.trim()
        if (key.length !in 8..128 || key.any(Char::isISOControl)) invalid("Idempotency key is invalid")
        val title = text(command.title, "Campaign title", 80)
        val summary = text(command.summary, "Campaign summary", 160)
        val alt = text(command.bannerAltText, "Campaign banner alt text", 200)
        val reason = text(command.reason, "Campaign command reason", 200)
        if (command.minimumOrderKrw < 0) invalid("Minimum order amount must not be negative")
        if (command.totalQuota !in 1..1_000_000) invalid("Total quota must be between 1 and 1000000")
        if (!command.claimStartsAt.isBefore(command.claimEndsAt) || command.claimEndsAt.isAfter(command.couponExpiresAt)) {
            invalid("Campaign period must satisfy claimStartsAt < claimEndsAt <= couponExpiresAt")
        }
        validateDiscount(command)
        validateCost(command)
        if (command.allMenusEligible && command.eligibleMenuIds.isNotEmpty()) invalid("All-menu campaign must not include menu ids")
        if (!command.allMenusEligible && command.eligibleMenuIds.isEmpty()) invalid("Menu-scoped campaign requires at least one menu")
        return command.copy(
            idempotencyKey = key,
            title = title,
            summary = summary,
            bannerAltText = alt,
            reason = reason,
            eligibleMenuIds = command.eligibleMenuIds.toSortedSet(),
        )
    }

    private fun validateDiscount(command: CreateLimitedCouponCampaignDraftCommand) {
        val discount = command.discount
        when (discount.discountType) {
            CouponDiscountType.FIXED_KRW -> {
                if (discount.fixedAmountKrw == null || discount.fixedAmountKrw <= 0 || discount.rateBps != null ||
                    discount.maximumDiscountKrw != null
                ) {
                    invalid("Fixed discount terms are invalid")
                }
            }

            CouponDiscountType.RATE_BPS -> {
                if (discount.fixedAmountKrw != null || discount.rateBps == null || discount.rateBps !in 1..10_000 ||
                    discount.maximumDiscountKrw == null || discount.maximumDiscountKrw <= 0
                ) {
                    invalid("Rate discount terms are invalid")
                }
            }
        }
    }

    private fun validateCost(command: CreateLimitedCouponCampaignDraftCommand) {
        val cost = command.cost
        val valid =
            when (cost.costBearer) {
                CouponCostBearer.PLATFORM -> {
                    cost.platformShareBps == 10_000 && cost.storeShareBps == 0
                }

                CouponCostBearer.STORE -> {
                    cost.platformShareBps == 0 && cost.storeShareBps == 10_000
                }

                CouponCostBearer.SHARED -> {
                    cost.platformShareBps in 1..9_999 && cost.storeShareBps in 1..9_999 &&
                        cost.platformShareBps + cost.storeShareBps == 10_000
                }
            }
        if (!valid) invalid("Campaign cost shares are invalid")
    }

    private fun requestHash(command: CreateLimitedCouponCampaignDraftCommand): String {
        val canonical =
            listOf(
                command.storeId,
                command.title,
                command.summary,
                command.bannerAltText,
                command.discount.discountType,
                command.discount.fixedAmountKrw,
                command.discount.rateBps,
                command.discount.maximumDiscountKrw,
                command.minimumOrderKrw,
                command.allMenusEligible,
                command.eligibleMenuIds.joinToString(","),
                command.cost.costBearer,
                command.cost.platformShareBps,
                command.cost.storeShareBps,
                command.totalQuota,
                command.claimStartsAt,
                command.claimEndsAt,
                command.couponExpiresAt,
                command.reason,
            ).joinToString("|")
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun text(
        raw: String,
        field: String,
        max: Int,
    ): String {
        val normalized = raw.trim()
        if (normalized.length !in 1..max || normalized.any(Char::isISOControl)) invalid("$field must be between 1 and $max characters")
        return normalized
    }

    private fun <T> dependencyBoundary(action: () -> T): T =
        try {
            action()
        } catch (failure: DomainFailure) {
            throw failure
        } catch (failure: DataAccessException) {
            logger.error("limited_coupon_campaign_persistence outcome=FAILED", failure)
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Campaign persistence is unavailable").also { it.initCause(failure) }
        }

    private fun invalid(message: String): Nothing = fail(FailureCode.INVALID_REQUEST, message)

    private fun fail(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)

    private companion object {
        const val MAX_PAGE_SIZE = 100
        val logger = LoggerFactory.getLogger(LimitedCouponCampaignService::class.java)
    }
}

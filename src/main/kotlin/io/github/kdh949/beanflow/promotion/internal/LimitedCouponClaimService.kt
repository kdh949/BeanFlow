package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

internal data class LimitedCouponClaimCampaign(
    val campaignId: UUID,
    val active: Boolean,
    val state: String,
    val claimStartsAt: Instant,
    val claimEndsAt: Instant,
    val couponExpiresAt: Instant,
    val totalQuota: Int,
    val issuedCount: Int,
)

internal data class LimitedCouponClaimCommandRecord(
    val requestHash: String,
    val responseBody: String,
)

internal data class StoredLimitedCouponClaimOutcome(
    val campaignId: UUID,
    val couponIssuanceId: UUID? = null,
    val claimedAt: Instant? = null,
    val couponExpiresAt: Instant? = null,
    val failureCode: FailureCode? = null,
    val failureMessage: String? = null,
) {
    fun response(): CustomerCouponClaimResponse {
        failureCode?.let { throw DomainFailure(it, requireNotNull(failureMessage)) }
        return CustomerCouponClaimResponse(
            campaignId = campaignId,
            couponIssuanceId = requireNotNull(couponIssuanceId),
            claimedAt = requireNotNull(claimedAt),
            couponExpiresAt = requireNotNull(couponExpiresAt),
        )
    }

    companion object {
        fun rejected(
            campaignId: UUID,
            code: FailureCode,
            message: String,
        ) = StoredLimitedCouponClaimOutcome(campaignId, failureCode = code, failureMessage = message)
    }
}

internal data class CustomerCouponClaimResponse(
    val campaignId: UUID,
    val couponIssuanceId: UUID,
    val claimedAt: Instant,
    val couponExpiresAt: Instant,
)

internal data class LimitedCouponClaimExecution(
    val outcome: StoredLimitedCouponClaimOutcome,
    val replayed: Boolean,
)

@Component
internal class LimitedCouponClaimMetrics(
    private val meterRegistry: MeterRegistry,
) {
    fun outcome(value: String) {
        meterRegistry.counter("beanflow.promotion.limited_coupon.claim", "outcome", value).increment()
    }

    fun lockWait(duration: Duration) {
        meterRegistry.timer("beanflow.promotion.limited_coupon.lock_wait").record(duration)
    }
}

@Component
internal class LimitedCouponClaimPersistence(
    private val jdbc: JdbcTemplate,
    private val identifiers: IdentifierSource,
) {
    fun lockCommand(
        actorId: UUID,
        idempotencyKey: String,
    ) {
        jdbc.query(
            "select pg_advisory_xact_lock(hashtextextended(cast(? as text), 0))",
            { _, _ -> Unit },
            "$actorId:$CLAIM_OPERATION:$idempotencyKey",
        )
    }

    fun findCommand(
        actorId: UUID,
        idempotencyKey: String,
    ): LimitedCouponClaimCommandRecord? =
        jdbc
            .query(
                """
                SELECT request_hash, response_body
                  FROM promotion_limited_coupon_claim_command
                 WHERE actor_id = ? AND operation = ? AND idempotency_key = ?
                """.trimIndent(),
                { row, _ -> LimitedCouponClaimCommandRecord(row.getString("request_hash"), row.getString("response_body")) },
                actorId,
                CLAIM_OPERATION,
                idempotencyKey,
            ).singleOrNull()

    fun lockCampaign(campaignId: UUID): LimitedCouponClaimCampaign? {
        jdbc
            .query(
                "SELECT campaign_id FROM promotion_limited_campaign WHERE campaign_id = ? FOR UPDATE",
                { row, _ -> row.getObject("campaign_id", UUID::class.java) },
                campaignId,
            ).singleOrNull()
            ?: return null
        return jdbc
            .query(
                """
                SELECT limited.campaign_id, limited.state, limited.claim_starts_at, limited.claim_ends_at,
                       limited.coupon_expires_at, campaign.active, counter.total_quota, counter.issued_count
                  FROM promotion_limited_campaign limited
                 JOIN promotion_campaign campaign ON campaign.id = limited.campaign_id
                 JOIN promotion_limited_campaign_counter counter ON counter.campaign_id = limited.campaign_id
                 WHERE limited.campaign_id = ?
                """.trimIndent(),
                { row, _ ->
                    LimitedCouponClaimCampaign(
                        campaignId = row.getObject("campaign_id", UUID::class.java),
                        active = row.getBoolean("active"),
                        state = row.getString("state"),
                        claimStartsAt = row.getTimestamp("claim_starts_at").toInstant(),
                        claimEndsAt = row.getTimestamp("claim_ends_at").toInstant(),
                        couponExpiresAt = row.getTimestamp("coupon_expires_at").toInstant(),
                        totalQuota = row.getInt("total_quota"),
                        issuedCount = row.getInt("issued_count"),
                    )
                },
                campaignId,
            ).singleOrNull()
            ?: throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Locked coupon campaign disappeared")
    }

    fun existingIssuance(
        campaignId: UUID,
        customerId: UUID,
    ): UUID? =
        jdbc
            .query(
                "SELECT issuance_id FROM promotion_limited_coupon_claim WHERE campaign_id = ? AND customer_id = ?",
                { row, _ -> row.getObject("issuance_id", UUID::class.java) },
                campaignId,
                customerId,
            ).singleOrNull()

    fun issue(
        campaign: LimitedCouponClaimCampaign,
        customerId: UUID,
        now: Instant,
    ): UUID {
        val issuanceId = identifiers.next()
        jdbc.update(
            "INSERT INTO promotion_limited_coupon_claim (campaign_id, customer_id, issuance_id, claimed_at) VALUES (?, ?, ?, ?)",
            campaign.campaignId,
            customerId,
            issuanceId,
            Timestamp.from(now),
        )
        val incremented =
            jdbc.update(
                """
                UPDATE promotion_limited_campaign_counter
                   SET issued_count = issued_count + 1
                 WHERE campaign_id = ? AND issued_count < total_quota
                """.trimIndent(),
                campaign.campaignId,
            )
        if (incremented != 1) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Coupon campaign counter changed outside its root lock")
        }
        jdbc.update(
            """
            INSERT INTO promotion_coupon_issuance (
                id, campaign_id, customer_id, state, coupon_expires_at, reserved_order_id, version
            ) VALUES (?, ?, ?, 'AVAILABLE', ?, null, 0)
            """.trimIndent(),
            issuanceId,
            campaign.campaignId,
            customerId,
            Timestamp.from(campaign.couponExpiresAt),
        )
        return issuanceId
    }

    fun saveCommand(
        actorId: UUID,
        idempotencyKey: String,
        requestHash: String,
        outcome: StoredLimitedCouponClaimOutcome,
        responseBody: String,
        now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO promotion_limited_coupon_claim_command (
                id, actor_id, operation, idempotency_key, request_hash, campaign_id, issuance_id,
                http_status, response_body, created_at, retention_expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            identifiers.next(),
            actorId,
            CLAIM_OPERATION,
            idempotencyKey,
            requestHash,
            outcome.campaignId,
            outcome.couponIssuanceId,
            if (outcome.failureCode == null) 201 else 409,
            responseBody,
            Timestamp.from(now),
            Timestamp.from(now.plus(COMMAND_RETENTION)),
        )
    }

    internal companion object {
        const val CLAIM_OPERATION = "LIMITED_COUPON_CLAIM_V1"
        val COMMAND_RETENTION: Duration = Duration.ofDays(90)
    }
}

@Service
internal class LimitedCouponClaimTransaction(
    private val persistence: LimitedCouponClaimPersistence,
    private val objectMapper: JsonMapper,
    private val metrics: LimitedCouponClaimMetrics,
) {
    @Transactional
    fun execute(
        customerId: UUID,
        campaignId: UUID,
        idempotencyKey: String,
        now: Instant,
    ): LimitedCouponClaimExecution {
        val requestHash = sha256(campaignId.toString())
        persistence.lockCommand(customerId, idempotencyKey)
        persistence.findCommand(customerId, idempotencyKey)?.let { existing ->
            if (existing.requestHash != requestHash) {
                return LimitedCouponClaimExecution(
                    StoredLimitedCouponClaimOutcome.rejected(
                        campaignId,
                        FailureCode.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency-Key was reused for another coupon campaign",
                    ),
                    replayed = false,
                )
            }
            return LimitedCouponClaimExecution(
                objectMapper.readValue(existing.responseBody, StoredLimitedCouponClaimOutcome::class.java),
                replayed = true,
            )
        }

        val lockStartedAt = System.nanoTime()
        val campaign =
            persistence
                .lockCampaign(campaignId)
                .also { metrics.lockWait(Duration.ofNanos(System.nanoTime() - lockStartedAt)) }
                ?: return LimitedCouponClaimExecution(
                    StoredLimitedCouponClaimOutcome.rejected(
                        campaignId,
                        FailureCode.CAMPAIGN_NOT_ISSUABLE,
                        "Coupon campaign is not issuable",
                    ),
                    replayed = false,
                )
        val rejected = rejectCampaign(campaign, customerId, now)
        val outcome =
            rejected ?: run {
                val issuanceId = persistence.issue(campaign, customerId, now)
                StoredLimitedCouponClaimOutcome(
                    campaignId = campaignId,
                    couponIssuanceId = issuanceId,
                    claimedAt = now,
                    couponExpiresAt = campaign.couponExpiresAt,
                )
            }
        val responseBody = objectMapper.writeValueAsString(outcome)
        persistence.saveCommand(customerId, idempotencyKey, requestHash, outcome, responseBody, now)
        return LimitedCouponClaimExecution(outcome, replayed = false)
    }

    private fun rejectCampaign(
        campaign: LimitedCouponClaimCampaign,
        customerId: UUID,
        now: Instant,
    ): StoredLimitedCouponClaimOutcome? {
        if (!campaign.active || campaign.state != "PUBLISHED" || now < campaign.claimStartsAt || now >= campaign.claimEndsAt) {
            return StoredLimitedCouponClaimOutcome.rejected(
                campaign.campaignId,
                FailureCode.CAMPAIGN_NOT_ISSUABLE,
                "Coupon campaign is not issuable",
            )
        }
        if (persistence.existingIssuance(campaign.campaignId, customerId) != null) {
            return StoredLimitedCouponClaimOutcome.rejected(
                campaign.campaignId,
                FailureCode.COUPON_ALREADY_ISSUED,
                "Customer already received this coupon",
            )
        }
        if (campaign.issuedCount >= campaign.totalQuota) {
            return StoredLimitedCouponClaimOutcome.rejected(
                campaign.campaignId,
                FailureCode.CAMPAIGN_QUOTA_EXHAUSTED,
                "Coupon campaign quota is exhausted",
            )
        }
        return null
    }

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))
}

@Service
internal class LimitedCouponClaimService(
    private val transaction: LimitedCouponClaimTransaction,
    private val metrics: LimitedCouponClaimMetrics,
) {
    fun claim(
        customerId: UUID,
        campaignId: UUID,
        idempotencyKey: String,
        now: Instant,
    ): CustomerCouponClaimResponse {
        if (idempotencyKey.trim() != idempotencyKey || idempotencyKey.length !in 8..128 || idempotencyKey.any(Char::isISOControl)) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Idempotency-Key is invalid")
        }
        val execution =
            try {
                transaction.execute(customerId, campaignId, idempotencyKey, now)
            } catch (failure: RuntimeException) {
                metrics.outcome(if (failure is DomainFailure) outcome(failure.code) else "dependency_unavailable")
                throw failure
            }
        metrics.outcome(if (execution.replayed) "replayed" else execution.outcome.failureCode?.let(::outcome) ?: "created")
        return execution.outcome.response()
    }

    private fun outcome(code: FailureCode): String =
        when (code) {
            FailureCode.CAMPAIGN_QUOTA_EXHAUSTED -> "quota_exhausted"
            FailureCode.COUPON_ALREADY_ISSUED -> "already_issued"
            FailureCode.CAMPAIGN_NOT_ISSUABLE -> "not_issuable"
            FailureCode.IDEMPOTENCY_KEY_REUSED -> "key_reused"
            FailureCode.DEPENDENCY_UNAVAILABLE -> "dependency_unavailable"
            else -> "rejected"
        }
}

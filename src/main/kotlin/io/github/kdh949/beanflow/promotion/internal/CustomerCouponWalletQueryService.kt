package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.promotion.api.CouponCostBearer
import io.github.kdh949.beanflow.promotion.api.CouponDiscountType
import io.github.kdh949.beanflow.promotion.api.CouponWalletInapplicableReason
import io.github.kdh949.beanflow.promotion.api.CustomerCouponWalletBenefit
import io.github.kdh949.beanflow.promotion.api.CustomerCouponWalletItem
import io.github.kdh949.beanflow.promotion.api.CustomerCouponWalletPage
import io.github.kdh949.beanflow.promotion.api.CustomerCouponWalletQueryOperations
import io.github.kdh949.beanflow.promotion.api.ListCustomerCouponWalletCommand
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.PersistenceException
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionException
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.TimeUnit

@Service
internal class CustomerCouponWalletQueryService(
    private val validation: CustomerCouponWalletQueryValidation,
    private val reads: CustomerCouponWalletReadTransaction,
    private val metrics: CustomerCouponWalletQueryMetrics,
) : CustomerCouponWalletQueryOperations {
    override fun list(command: ListCustomerCouponWalletCommand): CustomerCouponWalletPage =
        observed {
            reads.list(validation.prepare(command))
        }

    private fun observed(action: () -> CustomerCouponWalletPage): CustomerCouponWalletPage {
        val startedAt = System.nanoTime()
        var outcome = CustomerCouponWalletQueryOutcome.SUCCEEDED
        var page: CustomerCouponWalletPage? = null
        return try {
            action().also { page = it }
        } catch (failure: DomainFailure) {
            outcome = failure.toCouponWalletOutcome()
            throw failure
        } catch (failure: DataAccessException) {
            outcome = CustomerCouponWalletQueryOutcome.DEPENDENCY_UNAVAILABLE
            dependencyUnavailable(failure)
        } catch (failure: PersistenceException) {
            outcome = CustomerCouponWalletQueryOutcome.DEPENDENCY_UNAVAILABLE
            dependencyUnavailable(failure)
        } catch (failure: TransactionException) {
            outcome = CustomerCouponWalletQueryOutcome.DEPENDENCY_UNAVAILABLE
            dependencyUnavailable(failure)
        } catch (failure: RuntimeException) {
            outcome = CustomerCouponWalletQueryOutcome.UNEXPECTED_FAILURE
            throw failure
        } finally {
            metrics.record(outcome, startedAt, page?.items?.count { !it.applicable } ?: 0)
        }
    }

    private fun dependencyUnavailable(cause: RuntimeException): Nothing =
        throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Customer coupon wallet read dependency is unavailable").also {
            it.initCause(cause)
        }
}

internal enum class CustomerCouponWalletQueryOutcome {
    SUCCEEDED,
    INVALID_REQUEST,
    SETTLEMENT_INPUT_UNAVAILABLE,
    DEPENDENCY_UNAVAILABLE,
    UNEXPECTED_FAILURE,
}

/**
 * Only a closed outcome vocabulary is observed. Customer, store, coupon, Campaign, cursor and
 * filter values must never become metric tags or trace attributes.
 */
@Component
internal class CustomerCouponWalletQueryMetrics(
    private val meterRegistry: MeterRegistry,
) {
    fun record(
        outcome: CustomerCouponWalletQueryOutcome,
        startedAtNanos: Long,
        storeNotApplicableCount: Int,
    ) {
        meterRegistry.counter("beanflow.coupon.wallet.query.count", "outcome", outcome.name).increment()
        meterRegistry
            .timer("beanflow.coupon.wallet.query.latency", "outcome", outcome.name)
            .record(System.nanoTime() - startedAtNanos, TimeUnit.NANOSECONDS)
        if (storeNotApplicableCount > 0) {
            meterRegistry.counter("beanflow.coupon.wallet.store_not_applicable.count").increment(storeNotApplicableCount.toDouble())
        }
    }
}

private fun DomainFailure.toCouponWalletOutcome(): CustomerCouponWalletQueryOutcome =
    when (code) {
        FailureCode.INVALID_REQUEST -> CustomerCouponWalletQueryOutcome.INVALID_REQUEST
        FailureCode.SETTLEMENT_INPUT_UNAVAILABLE -> CustomerCouponWalletQueryOutcome.SETTLEMENT_INPUT_UNAVAILABLE
        else -> CustomerCouponWalletQueryOutcome.DEPENDENCY_UNAVAILABLE
    }

@Component
internal class CustomerCouponWalletReadTransaction(
    private val repository: CustomerCouponWalletQueryRepository,
    private val signedCursorCodec: SignedCursorCodec,
) {
    @Transactional(readOnly = true)
    fun list(prepared: PreparedCustomerCouponWalletQuery): CustomerCouponWalletPage {
        val fetched = repository.findCandidates(prepared)
        val candidates = fetched.take(prepared.limit)
        val items = candidates.map { candidate -> candidate.toItem(prepared.storeId) }
        val nextCursor =
            if (fetched.size > prepared.limit) {
                val boundary = candidates.last()
                signedCursorCodec.issue(
                    prepared.cursorScope,
                    CustomerCouponWalletSort(boundary.couponExpiresAt, boundary.couponIssuanceId),
                    prepared.cursorExpiresAt,
                )
            } else {
                null
            }
        return CustomerCouponWalletPage(items, nextCursor)
    }
}

@Component
internal class CustomerCouponWalletQueryValidation(
    private val signedCursorCodec: SignedCursorCodec,
) {
    fun prepare(command: ListCustomerCouponWalletCommand): PreparedCustomerCouponWalletQuery {
        val storeId = storeId(command.storeId)
        val limit = limit(command.limit)
        val cursor = cursor(command.cursor)
        val scope =
            SignedCursorScope(
                endpoint = CURSOR_ENDPOINT,
                filterHash = filterHash(command.customerId, storeId),
                sortAdapter = SORT_ADAPTER,
            )
        return PreparedCustomerCouponWalletQuery(
            customerId = command.customerId,
            storeId = storeId,
            now = command.now,
            limit = limit,
            after = cursor?.let { signedCursorCodec.verify(it, scope).sort },
            cursorScope = scope,
            cursorExpiresAt = command.now.plus(CURSOR_TTL),
        )
    }

    private fun storeId(raw: String?): UUID {
        if (raw.isNullOrBlank()) invalid("Customer coupon wallet store id is required")
        return try {
            UUID.fromString(raw)
        } catch (_: IllegalArgumentException) {
            invalid("Customer coupon wallet store id is invalid")
        }
    }

    private fun limit(raw: String?): Int {
        if (raw == null) return DEFAULT_LIMIT
        val parsed = raw.toIntOrNull() ?: invalid("Customer coupon wallet limit is invalid")
        if (parsed !in 1..MAX_LIMIT) invalid("Customer coupon wallet limit must be between 1 and 100")
        return parsed
    }

    private fun cursor(raw: String?): String? {
        if (raw != null && raw.length > MAX_CURSOR_LENGTH) invalid("Customer coupon wallet cursor is too long")
        return raw
    }

    private fun filterHash(
        customerId: UUID,
        storeId: UUID,
    ): String {
        val canonical = "{\"endpoint\":\"$CURSOR_ENDPOINT\",\"customerId\":\"$customerId\",\"storeId\":\"$storeId\"}"
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8)),
        )
    }

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    internal companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
        const val MAX_CURSOR_LENGTH = 2048
        const val CURSOR_ENDPOINT = "customer-coupons"
        val CURSOR_TTL: Duration = Duration.ofHours(24)
        val SORT_ADAPTER =
            object : CursorSortAdapter<CustomerCouponWalletSort> {
                override fun encode(sort: CustomerCouponWalletSort): List<String> =
                    listOf(sort.couponExpiresAt.toString(), sort.couponIssuanceId.toString())

                override fun decode(values: List<String>): CustomerCouponWalletSort? {
                    if (values.size != 2) return null
                    return try {
                        val couponExpiresAt = Instant.parse(values[0])
                        val couponIssuanceId = UUID.fromString(values[1])
                        if (couponExpiresAt.toString() != values[0] || couponIssuanceId.toString() != values[1]) {
                            null
                        } else {
                            CustomerCouponWalletSort(couponExpiresAt, couponIssuanceId)
                        }
                    } catch (_: DateTimeParseException) {
                        null
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
            }
    }
}

internal data class PreparedCustomerCouponWalletQuery(
    val customerId: UUID,
    val storeId: UUID,
    val now: Instant,
    val limit: Int,
    val after: CustomerCouponWalletSort?,
    val cursorScope: SignedCursorScope<CustomerCouponWalletSort>,
    val cursorExpiresAt: Instant,
)

internal data class CustomerCouponWalletSort(
    val couponExpiresAt: Instant,
    val couponIssuanceId: UUID,
)

/**
 * Read projection intentionally discriminates the two sources. A normal issuance reads its live,
 * active Campaign. A compensation issuance reads its immutable terms snapshot and does not use the
 * Campaign's current activity as an eligibility predicate.
 */
@Component
internal class CustomerCouponWalletQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findCandidates(prepared: PreparedCustomerCouponWalletQuery): List<CustomerCouponWalletProjection> {
        val keyset =
            prepared.after
                ?.let {
                    """
                    AND (
                        ci.coupon_expires_at > ?
                        OR (ci.coupon_expires_at = ? AND ci.id > ?)
                    )
                    """.trimIndent()
                }.orEmpty()
        val parameters =
            buildList<Any> {
                add(prepared.customerId)
                add(Timestamp.from(prepared.now))
                prepared.after?.let {
                    add(Timestamp.from(it.couponExpiresAt))
                    add(Timestamp.from(it.couponExpiresAt))
                    add(it.couponIssuanceId)
                }
                add(prepared.limit + 1)
            }.toTypedArray()
        return jdbcTemplate.query(SQL.replace("/* keyset */", keyset), { resultSet, _ -> resultSet.toProjection() }, *parameters)
    }

    private fun ResultSet.toProjection() =
        CustomerCouponWalletProjection(
            couponIssuanceId = uuid("coupon_issuance_id"),
            campaignId = uuid("campaign_id"),
            couponExpiresAt = requireNotNull(getTimestamp("coupon_expires_at")).toInstant(),
            originalIssuanceId = uuidOrNull("original_issuance_id"),
            campaignActive = booleanOrNull("campaign_active"),
            campaignStoreId = uuidOrNull("campaign_store_id"),
            campaignDiscountType = getString("campaign_discount_type"),
            campaignFixedAmountKrw = longOrNull("campaign_fixed_amount_krw"),
            campaignRateBps = intOrNull("campaign_rate_bps"),
            campaignMinimumOrderKrw = longOrNull("campaign_minimum_eligible_subtotal_krw"),
            campaignMaximumDiscountKrw = longOrNull("campaign_maximum_discount_krw"),
            snapshotCouponIssuanceId = uuidOrNull("snapshot_coupon_issuance_id"),
            snapshotCampaignId = uuidOrNull("snapshot_campaign_id"),
            snapshotCampaignVersion = longOrNull("snapshot_campaign_version"),
            snapshotStoreId = uuidOrNull("snapshot_store_id"),
            snapshotDiscountType = getString("snapshot_discount_type"),
            snapshotFixedAmountKrw = longOrNull("snapshot_fixed_amount_krw"),
            snapshotRateBps = intOrNull("snapshot_rate_bps"),
            snapshotMinimumOrderKrw = longOrNull("snapshot_minimum_eligible_subtotal_krw"),
            snapshotMaximumDiscountKrw = longOrNull("snapshot_maximum_discount_krw"),
            snapshotAllMenusEligible = booleanOrNull("snapshot_all_menus_eligible"),
            snapshotCostBearer = getString("snapshot_cost_bearer"),
            snapshotPlatformShareBps = intOrNull("snapshot_platform_share_bps"),
            snapshotStoreShareBps = intOrNull("snapshot_store_share_bps"),
        )

    private fun ResultSet.uuid(column: String): UUID = requireNotNull(uuidOrNull(column))

    private fun ResultSet.uuidOrNull(column: String): UUID? =
        getString(column)?.let {
            try {
                UUID.fromString(it)
            } catch (_: IllegalArgumentException) {
                throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Customer coupon wallet UUID projection is invalid")
            }
        }

    private fun ResultSet.longOrNull(column: String): Long? = getLong(column).let { if (wasNull()) null else it }

    private fun ResultSet.intOrNull(column: String): Int? = getInt(column).let { if (wasNull()) null else it }

    private fun ResultSet.booleanOrNull(column: String): Boolean? = getBoolean(column).let { if (wasNull()) null else it }

    private companion object {
        const val SQL =
            """
            SELECT ci.id AS coupon_issuance_id,
                   ci.campaign_id,
                   ci.coupon_expires_at,
                   ci.original_issuance_id,
                   campaign.active AS campaign_active,
                   campaign.store_id AS campaign_store_id,
                   campaign.discount_type AS campaign_discount_type,
                   campaign.fixed_amount_krw AS campaign_fixed_amount_krw,
                   campaign.rate_bps AS campaign_rate_bps,
                   campaign.minimum_eligible_subtotal_krw AS campaign_minimum_eligible_subtotal_krw,
                   campaign.maximum_discount_krw AS campaign_maximum_discount_krw,
                   snapshot.coupon_issuance_id AS snapshot_coupon_issuance_id,
                   snapshot.campaign_id AS snapshot_campaign_id,
                   snapshot.campaign_version AS snapshot_campaign_version,
                   snapshot.store_id AS snapshot_store_id,
                   snapshot.discount_type AS snapshot_discount_type,
                   snapshot.fixed_amount_krw AS snapshot_fixed_amount_krw,
                   snapshot.rate_bps AS snapshot_rate_bps,
                   snapshot.minimum_eligible_subtotal_krw AS snapshot_minimum_eligible_subtotal_krw,
                   snapshot.maximum_discount_krw AS snapshot_maximum_discount_krw,
                   snapshot.all_menus_eligible AS snapshot_all_menus_eligible,
                   snapshot.cost_bearer AS snapshot_cost_bearer,
                   snapshot.platform_share_bps AS snapshot_platform_share_bps,
                   snapshot.store_share_bps AS snapshot_store_share_bps
              FROM promotion_coupon_issuance ci
              LEFT JOIN promotion_campaign campaign ON campaign.id = ci.campaign_id
              LEFT JOIN promotion_compensation_coupon_terms_snapshot snapshot
                ON snapshot.coupon_issuance_id = ci.id
             WHERE ci.customer_id = ?
               AND ci.state IN ('AVAILABLE', 'RESTORED')
               AND ci.coupon_expires_at > ?
               AND (ci.original_issuance_id IS NOT NULL OR campaign.active = true)
            /* keyset */
             ORDER BY ci.coupon_expires_at ASC, ci.id ASC
             LIMIT ?
            """
    }
}

internal data class CustomerCouponWalletProjection(
    val couponIssuanceId: UUID,
    val campaignId: UUID,
    val couponExpiresAt: Instant,
    val originalIssuanceId: UUID?,
    val campaignActive: Boolean?,
    val campaignStoreId: UUID?,
    val campaignDiscountType: String?,
    val campaignFixedAmountKrw: Long?,
    val campaignRateBps: Int?,
    val campaignMinimumOrderKrw: Long?,
    val campaignMaximumDiscountKrw: Long?,
    val snapshotCouponIssuanceId: UUID?,
    val snapshotCampaignId: UUID?,
    val snapshotCampaignVersion: Long?,
    val snapshotStoreId: UUID?,
    val snapshotDiscountType: String?,
    val snapshotFixedAmountKrw: Long?,
    val snapshotRateBps: Int?,
    val snapshotMinimumOrderKrw: Long?,
    val snapshotMaximumDiscountKrw: Long?,
    val snapshotAllMenusEligible: Boolean?,
    val snapshotCostBearer: String?,
    val snapshotPlatformShareBps: Int?,
    val snapshotStoreShareBps: Int?,
) {
    fun toItem(requestedStoreId: UUID): CustomerCouponWalletItem {
        val terms = if (originalIssuanceId == null) liveCampaignTerms() else immutableCompensationTerms()
        val applicable = terms.storeId == requestedStoreId
        return CustomerCouponWalletItem(
            couponIssuanceId = couponIssuanceId,
            benefit =
                CustomerCouponWalletBenefit(
                    discountType = terms.discountType,
                    fixedAmountKrw = terms.fixedAmountKrw,
                    rateBps = terms.rateBps,
                    maximumDiscountKrw = terms.maximumDiscountKrw,
                ),
            minimumOrderKrw = terms.minimumOrderKrw,
            couponExpiresAt = couponExpiresAt,
            applicable = applicable,
            reasonCode = if (applicable) null else CouponWalletInapplicableReason.STORE_NOT_APPLICABLE,
        )
    }

    private fun liveCampaignTerms(): CouponWalletTerms {
        if (campaignActive != true || campaignStoreId == null) dependency("Campaign source is unavailable")
        return terms(
            source = "Campaign source is invalid",
            storeId = campaignStoreId,
            discountType = campaignDiscountType,
            fixedAmountKrw = campaignFixedAmountKrw,
            rateBps = campaignRateBps,
            minimumOrderKrw = campaignMinimumOrderKrw,
            maximumDiscountKrw = campaignMaximumDiscountKrw,
        )
    }

    private fun immutableCompensationTerms(): CouponWalletTerms {
        if (
            snapshotCouponIssuanceId != couponIssuanceId ||
            snapshotCampaignId != campaignId ||
            snapshotCampaignVersion == null ||
            snapshotCampaignVersion < 0 ||
            snapshotStoreId == null ||
            snapshotAllMenusEligible == null ||
            snapshotCostBearer == null ||
            snapshotPlatformShareBps == null ||
            snapshotStoreShareBps == null
        ) {
            snapshotUnavailable()
        }
        validateBurden(snapshotCostBearer, snapshotPlatformShareBps, snapshotStoreShareBps)
        return terms(
            source = "Compensation coupon terms snapshot is invalid",
            storeId = snapshotStoreId,
            discountType = snapshotDiscountType,
            fixedAmountKrw = snapshotFixedAmountKrw,
            rateBps = snapshotRateBps,
            minimumOrderKrw = snapshotMinimumOrderKrw,
            maximumDiscountKrw = snapshotMaximumDiscountKrw,
            failure = ::snapshotUnavailable,
        )
    }

    private fun validateBurden(
        rawBearer: String,
        platformShareBps: Int,
        storeShareBps: Int,
    ) {
        val bearer =
            try {
                CouponCostBearer.valueOf(rawBearer)
            } catch (_: IllegalArgumentException) {
                snapshotUnavailable()
            }
        val valid =
            when (bearer) {
                CouponCostBearer.PLATFORM -> {
                    platformShareBps == 10_000 && storeShareBps == 0
                }

                CouponCostBearer.STORE -> {
                    platformShareBps == 0 && storeShareBps == 10_000
                }

                CouponCostBearer.SHARED -> {
                    platformShareBps in 1..9_999 && storeShareBps in 1..9_999 &&
                        platformShareBps + storeShareBps == 10_000
                }
            }
        if (!valid) snapshotUnavailable()
    }

    private fun terms(
        source: String,
        storeId: UUID,
        discountType: String?,
        fixedAmountKrw: Long?,
        rateBps: Int?,
        minimumOrderKrw: Long?,
        maximumDiscountKrw: Long?,
        failure: () -> Nothing = { dependency(source) },
    ): CouponWalletTerms {
        val type =
            try {
                CouponDiscountType.valueOf(discountType ?: return failure())
            } catch (_: IllegalArgumentException) {
                return failure()
            }
        if (minimumOrderKrw == null || minimumOrderKrw < 0) return failure()
        val valid =
            when (type) {
                CouponDiscountType.FIXED_KRW -> {
                    fixedAmountKrw != null && fixedAmountKrw > 0 && rateBps == null &&
                        maximumDiscountKrw == null
                }

                CouponDiscountType.RATE_BPS -> {
                    fixedAmountKrw == null && rateBps != null && rateBps in 1..10_000 &&
                        maximumDiscountKrw != null &&
                        maximumDiscountKrw > 0
                }
            }
        if (!valid) return failure()
        return CouponWalletTerms(storeId, type, fixedAmountKrw, rateBps, minimumOrderKrw, maximumDiscountKrw)
    }

    private fun snapshotUnavailable(): Nothing =
        throw DomainFailure(FailureCode.SETTLEMENT_INPUT_UNAVAILABLE, "Compensation coupon terms snapshot is missing or invalid")

    private fun dependency(message: String): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)
}

private data class CouponWalletTerms(
    val storeId: UUID,
    val discountType: CouponDiscountType,
    val fixedAmountKrw: Long?,
    val rateBps: Int?,
    val minimumOrderKrw: Long,
    val maximumDiscountKrw: Long?,
)

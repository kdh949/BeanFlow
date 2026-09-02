package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.merchant.api.PreparedStorefrontImage
import io.github.kdh949.beanflow.merchant.api.StoreDisplaySnapshotOperations
import io.github.kdh949.beanflow.merchant.api.StoreMenuQueryOperations
import io.github.kdh949.beanflow.merchant.api.StoreMenuView
import io.github.kdh949.beanflow.merchant.api.StorefrontImageAccess
import io.github.kdh949.beanflow.merchant.api.StorefrontImageStorageOperations
import io.github.kdh949.beanflow.merchant.api.StorefrontImageTarget
import io.github.kdh949.beanflow.merchant.api.StorefrontImageUpload
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordKey
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.AuditRecordQueryOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.promotion.api.CreateLimitedCouponCampaignDraftCommand
import io.github.kdh949.beanflow.promotion.api.LimitedCouponCampaignOperations
import io.github.kdh949.beanflow.promotion.api.LimitedCouponCampaignSnapshot
import io.github.kdh949.beanflow.promotion.api.PublishLimitedCouponCampaignCommand
import io.github.kdh949.beanflow.promotion.api.ReplaceLimitedCouponCampaignBannerCommand
import io.github.kdh949.beanflow.promotion.api.ReplayLimitedCouponCampaignBannerCommand
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.HexFormat
import java.util.UUID

internal data class OperatorCouponCampaignCommandContext(
    val actorId: UUID,
    val idempotencyKey: String,
    val reason: String,
)

internal data class OperatorCouponCampaignView(
    val campaign: LimitedCouponCampaignSnapshot,
    val storeName: String,
    val bannerAccess: StorefrontImageAccess?,
)

internal data class OperatorCouponCampaignPage(
    val campaigns: List<OperatorCouponCampaignView>,
    val nextCursor: String?,
)

internal data class OperatorCouponCampaignMenuOption(
    val menuId: UUID,
    val name: String,
    val basePriceKrw: Long,
)

internal data class OperatorCouponCampaignSort(
    val createdAt: Instant,
    val campaignId: UUID,
)

@Service
internal class OperatorCouponCampaignService(
    private val campaigns: LimitedCouponCampaignOperations,
    private val stores: StoreDisplaySnapshotOperations,
    private val menus: StoreMenuQueryOperations,
    private val authorization: OperatorPermissionAuthorization,
    private val auditRecords: AuditRecordOperations,
    private val auditQueries: AuditRecordQueryOperations,
    private val correlationIds: CorrelationIdSource,
    private val cursors: SignedCursorCodec,
    private val storage: StorefrontImageStorageOperations,
    private val clock: Clock,
) {
    @Transactional
    fun createDraft(
        context: OperatorCouponCampaignCommandContext,
        command: CreateLimitedCouponCampaignDraftCommand,
    ): OperatorCouponCampaignView {
        authorizeWrite(context)
        val store = stores.require(command.storeId)
        verifyMenus(command)
        val campaign =
            campaigns.createDraft(
                command.copy(actorId = context.actorId, idempotencyKey = context.idempotencyKey, now = clock.instant()),
            )
        auditCreated(context, campaign)
        return view(campaign, store.name)
    }

    @Transactional
    fun find(
        actorId: UUID,
        campaignId: UUID,
    ): OperatorCouponCampaignView {
        authorization.requireActive(actorId, OperatorPermission.PROMOTION_CAMPAIGN_READ)
        val campaign = campaigns.find(campaignId) ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Campaign not found")
        return view(campaign)
    }

    @Transactional
    fun list(
        actorId: UUID,
        cursor: String?,
        limit: Int?,
    ): OperatorCouponCampaignPage {
        authorization.requireActive(actorId, OperatorPermission.PROMOTION_CAMPAIGN_READ)
        val pageSize = limit ?: DEFAULT_PAGE_SIZE
        if (pageSize !in 1..MAX_PAGE_SIZE) invalid("limit must be between 1 and $MAX_PAGE_SIZE")
        val after = cursor?.let { cursors.verify(it, cursorScope()).sort }
        val page = campaigns.list(after?.createdAt, after?.campaignId, pageSize)
        val views = page.campaigns.map(::view)
        val nextCursor =
            if (page.nextCreatedAt != null && page.nextCampaignId != null) {
                cursors.issue(
                    cursorScope(),
                    OperatorCouponCampaignSort(page.nextCreatedAt, page.nextCampaignId),
                    clock.instant().plus(CURSOR_TTL),
                )
            } else {
                null
            }
        return OperatorCouponCampaignPage(views, nextCursor)
    }

    @Transactional
    fun listStoreOptions(actorId: UUID) =
        authorization.requireActive(actorId, OperatorPermission.PROMOTION_CAMPAIGN_READ).let { stores.list(STORE_OPTION_LIMIT) }

    @Transactional
    fun listMenuOptions(
        actorId: UUID,
        storeId: UUID,
    ): List<OperatorCouponCampaignMenuOption> {
        authorization.requireActive(actorId, OperatorPermission.PROMOTION_CAMPAIGN_READ)
        stores.require(storeId)
        return menus.listMenus(storeId).filter(StoreMenuView::available).map {
            OperatorCouponCampaignMenuOption(it.menuId, it.name, it.basePriceKrw)
        }
    }

    @Transactional
    fun authorizeBanner(context: OperatorCouponCampaignCommandContext) {
        authorizeWrite(context)
    }

    @Transactional
    fun replayBanner(
        context: OperatorCouponCampaignCommandContext,
        campaignId: UUID,
        expectedVersion: Long,
        sha256: String,
    ): OperatorCouponCampaignView? {
        authorizeWrite(context)
        return campaigns
            .replayBanner(
                ReplayLimitedCouponCampaignBannerCommand(
                    context.actorId,
                    context.idempotencyKey,
                    campaignId,
                    expectedVersion,
                    sha256,
                    context.reason,
                ),
            )?.let(::view)
    }

    @Transactional
    fun replaceBanner(
        context: OperatorCouponCampaignCommandContext,
        campaignId: UUID,
        expectedVersion: Long,
        prepared: PreparedStorefrontImage,
    ): OperatorCouponCampaignView {
        authorizeWrite(context)
        val updated =
            campaigns.replaceBanner(
                ReplaceLimitedCouponCampaignBannerCommand(
                    context.actorId,
                    context.idempotencyKey,
                    campaignId,
                    expectedVersion,
                    prepared,
                    context.reason,
                    clock.instant(),
                ),
            )
        auditChange(context, updated, BANNER_ACTION)
        return view(updated)
    }

    @Transactional
    fun publish(
        context: OperatorCouponCampaignCommandContext,
        campaignId: UUID,
        expectedVersion: Long,
    ): OperatorCouponCampaignView {
        authorizeWrite(context)
        val published =
            campaigns.publish(
                PublishLimitedCouponCampaignCommand(
                    context.actorId,
                    context.idempotencyKey,
                    campaignId,
                    expectedVersion,
                    context.reason,
                    clock.instant(),
                ),
            )
        auditChange(context, published, PUBLISHED_ACTION)
        return view(published)
    }

    private fun view(
        campaign: LimitedCouponCampaignSnapshot,
        knownStoreName: String? = null,
    ) = OperatorCouponCampaignView(
        campaign,
        knownStoreName ?: stores.require(campaign.storeId).name,
        campaign.banner?.let { storage.access(it.thumbnailKey) },
    )

    private fun verifyMenus(command: CreateLimitedCouponCampaignDraftCommand) {
        if (command.allMenusEligible) return
        val currentMenus = menus.listMenus(command.storeId).associateBy { it.menuId }
        val invalidIds = command.eligibleMenuIds.filter { currentMenus[it]?.available != true }
        if (invalidIds.isNotEmpty()) invalid("Eligible menu ids must belong to the store and be available")
    }

    private fun authorizeWrite(context: OperatorCouponCampaignCommandContext) {
        val reason = context.reason.trim()
        if (reason.length !in 1..200 || reason.any(Char::isISOControl)) invalid("Reason must be between 1 and 200 characters")
        authorization.requireActive(context.actorId, OperatorPermission.PROMOTION_CAMPAIGN_WRITE)
    }

    private fun auditCreated(
        context: OperatorCouponCampaignCommandContext,
        campaign: LimitedCouponCampaignSnapshot,
    ) {
        val sourceReference = "coupon-campaign-command:${context.actorId}:${sha256(context.idempotencyKey)}"
        val key = AuditRecordKey(CREATED_ACTION, "LimitedCouponCampaign", campaign.campaignId, sourceReference)
        if (auditQueries.exists(key)) return
        auditRecords.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = context.actorId.toString(),
                    actorType = AuditActorType.PLATFORM_OPERATOR,
                    category = AuditCategory.OPERATIONS_POLICY,
                    action = CREATED_ACTION,
                    targetType = "LimitedCouponCampaign",
                    targetId = campaign.campaignId,
                    occurredAt = clock.instant(),
                    reason = context.reason.trim(),
                    beforeSummary = emptyMap(),
                    afterSummary =
                        mapOf(
                            "state" to campaign.state.name,
                            "storeId" to campaign.storeId.toString(),
                            "totalQuota" to campaign.totalQuota.toString(),
                            "claimEndsAt" to campaign.claimEndsAt.toString(),
                        ),
                    correlationId = correlationIds.currentOrCreate(),
                    sourceReference = sourceReference,
                ),
            ),
        )
    }

    private fun auditChange(
        context: OperatorCouponCampaignCommandContext,
        campaign: LimitedCouponCampaignSnapshot,
        action: String,
    ) {
        val sourceReference = "coupon-campaign-command:${context.actorId}:${sha256(context.idempotencyKey)}:$action"
        val key = AuditRecordKey(action, "LimitedCouponCampaign", campaign.campaignId, sourceReference)
        if (auditQueries.exists(key)) return
        auditRecords.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = context.actorId.toString(),
                    actorType = AuditActorType.PLATFORM_OPERATOR,
                    category = AuditCategory.OPERATIONS_POLICY,
                    action = action,
                    targetType = "LimitedCouponCampaign",
                    targetId = campaign.campaignId,
                    occurredAt = clock.instant(),
                    reason = context.reason.trim(),
                    beforeSummary = emptyMap(),
                    afterSummary = mapOf("state" to campaign.state.name, "version" to campaign.version.toString()),
                    correlationId = correlationIds.currentOrCreate(),
                    sourceReference = sourceReference,
                ),
            ),
        )
    }

    private fun cursorScope() =
        SignedCursorScope(
            endpoint = CURSOR_ENDPOINT,
            filterHash = sha256(CURSOR_ENDPOINT),
            sortAdapter =
                object : CursorSortAdapter<OperatorCouponCampaignSort> {
                    override fun encode(sort: OperatorCouponCampaignSort) = listOf(sort.createdAt.toString(), sort.campaignId.toString())

                    override fun decode(values: List<String>): OperatorCouponCampaignSort? {
                        if (values.size != 2) return null
                        return try {
                            val createdAt = Instant.parse(values[0])
                            val campaignId = UUID.fromString(values[1])
                            OperatorCouponCampaignSort(createdAt, campaignId)
                        } catch (_: DateTimeParseException) {
                            null
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                    }
                },
        )

    private fun sha256(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8)))

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    private companion object {
        const val CREATED_ACTION = "COUPON_CAMPAIGN_DRAFT_CREATED"
        const val BANNER_ACTION = "COUPON_CAMPAIGN_BANNER_UPDATED"
        const val PUBLISHED_ACTION = "COUPON_CAMPAIGN_PUBLISHED"
        const val CURSOR_ENDPOINT = "operations-coupon-campaigns"
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 100
        const val STORE_OPTION_LIMIT = 100
        val CURSOR_TTL: Duration = Duration.ofMinutes(30)
    }
}

@Service
internal class OperatorCouponCampaignMediaService(
    private val transactions: OperatorCouponCampaignService,
    private val storage: StorefrontImageStorageOperations,
) {
    fun replaceBanner(
        context: OperatorCouponCampaignCommandContext,
        campaignId: UUID,
        expectedVersion: Long,
        upload: StorefrontImageUpload,
    ): OperatorCouponCampaignView {
        transactions.authorizeBanner(context)
        val normalized = storage.normalizeCampaignBanner(upload)
        transactions.replayBanner(context, campaignId, expectedVersion, normalized.sha256)?.let { return it }
        val prepared = storage.store(StorefrontImageTarget.CAMPAIGN, campaignId, normalized)
        storage.access(prepared.thumbnailKey)
        return transactions.replaceBanner(context, campaignId, expectedVersion, prepared)
    }
}

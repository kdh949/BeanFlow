package io.github.kdh949.beanflow.demo

import io.github.kdh949.beanflow.fulfillment.internal.PickupSlotEntity
import io.github.kdh949.beanflow.fulfillment.internal.PickupSlotJpaRepository
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.identity.internal.CustomerAccountEntity
import io.github.kdh949.beanflow.identity.internal.CustomerAccountJpaRepository
import io.github.kdh949.beanflow.identity.internal.CustomerCredentialSecurityConfiguration
import io.github.kdh949.beanflow.identity.internal.CustomerPasswordSecurity
import io.github.kdh949.beanflow.identity.internal.MerchantAccountEntity
import io.github.kdh949.beanflow.identity.internal.MerchantAccountJpaRepository
import io.github.kdh949.beanflow.identity.internal.StoreMembershipEntity
import io.github.kdh949.beanflow.identity.internal.StoreMembershipJpaRepository
import io.github.kdh949.beanflow.identity.internal.StoreMembershipStatus
import io.github.kdh949.beanflow.inventory.internal.SellableStockEntity
import io.github.kdh949.beanflow.inventory.internal.SellableStockJpaRepository
import io.github.kdh949.beanflow.loyalty.internal.PointAccountEntity
import io.github.kdh949.beanflow.loyalty.internal.PointAccountJpaRepository
import io.github.kdh949.beanflow.merchant.internal.MenuConfigurationEntity
import io.github.kdh949.beanflow.merchant.internal.MenuConfigurationJpaRepository
import io.github.kdh949.beanflow.merchant.internal.MenuConfigurationRequirementEntity
import io.github.kdh949.beanflow.merchant.internal.MenuConfigurationRequirementJpaRepository
import io.github.kdh949.beanflow.merchant.internal.MenuEntity
import io.github.kdh949.beanflow.merchant.internal.MenuJpaRepository
import io.github.kdh949.beanflow.merchant.internal.MenuOptionEntity
import io.github.kdh949.beanflow.merchant.internal.MenuOptionJpaRepository
import io.github.kdh949.beanflow.merchant.internal.StoreEntity
import io.github.kdh949.beanflow.merchant.internal.StoreJpaRepository
import io.github.kdh949.beanflow.merchant.internal.StoreSettlementTermsEntity
import io.github.kdh949.beanflow.merchant.internal.StoreSettlementTermsJpaRepository
import io.github.kdh949.beanflow.payment.internal.PaymentMethodEntity
import io.github.kdh949.beanflow.payment.internal.PaymentMethodJpaRepository
import io.github.kdh949.beanflow.payment.internal.PaymentMethodStatus
import io.github.kdh949.beanflow.payment.internal.PaymentEntity
import io.github.kdh949.beanflow.payment.internal.PaymentJpaRepository
import io.github.kdh949.beanflow.payment.internal.RefundEntity
import io.github.kdh949.beanflow.payment.internal.RefundJpaRepository
import io.github.kdh949.beanflow.payment.internal.domain.PaymentApprovalState
import io.github.kdh949.beanflow.payment.internal.domain.PaymentType
import io.github.kdh949.beanflow.payment.internal.domain.RefundClaimMode
import io.github.kdh949.beanflow.payment.internal.domain.RefundState
import io.github.kdh949.beanflow.ordering.internal.OptionSelectionSnapshotState
import io.github.kdh949.beanflow.ordering.internal.OrderEntity
import io.github.kdh949.beanflow.ordering.internal.OrderJpaRepository
import io.github.kdh949.beanflow.ordering.internal.OrderLineEntity
import io.github.kdh949.beanflow.ordering.internal.OrderLineJpaRepository
import io.github.kdh949.beanflow.ordering.internal.OrderPointAccrualSnapshotEntity
import io.github.kdh949.beanflow.ordering.internal.OrderPointAccrualSnapshotJpaRepository
import io.github.kdh949.beanflow.ordering.internal.OrderPointAccrualSourceEntity
import io.github.kdh949.beanflow.ordering.internal.OrderPointAccrualSourceJpaRepository
import io.github.kdh949.beanflow.ordering.internal.OrderPointAccrualUnitEntity
import io.github.kdh949.beanflow.ordering.internal.OrderPointAccrualUnitJpaRepository
import io.github.kdh949.beanflow.ordering.internal.OrderSettlementInputSnapshotEntity
import io.github.kdh949.beanflow.ordering.internal.OrderSettlementInputSnapshotJpaRepository
import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.ordering.api.OrderPointAccrualSourceState
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualExpiryRule
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyScopeType
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicySelectionSource
import io.github.kdh949.beanflow.operations.api.PointAccrualIssuerType
import io.github.kdh949.beanflow.operations.api.PointAccrualRoundingMode
import io.github.kdh949.beanflow.promotion.api.CouponCostBearer
import io.github.kdh949.beanflow.promotion.api.CouponDiscountType
import io.github.kdh949.beanflow.promotion.internal.CampaignEligibleMenuEntity
import io.github.kdh949.beanflow.promotion.internal.CampaignEligibleMenuJpaRepository
import io.github.kdh949.beanflow.promotion.internal.CampaignEntity
import io.github.kdh949.beanflow.promotion.internal.CampaignJpaRepository
import io.github.kdh949.beanflow.promotion.internal.CouponIssuanceEntity
import io.github.kdh949.beanflow.promotion.internal.CouponIssuanceJpaRepository
import io.github.kdh949.beanflow.promotion.internal.CouponIssuanceState
import io.github.kdh949.beanflow.shared.api.MerchantAccountState
import io.github.kdh949.beanflow.settlement.internal.SettlementAdjustmentEntity
import io.github.kdh949.beanflow.settlement.internal.SettlementAdjustmentJpaRepository
import io.github.kdh949.beanflow.settlement.internal.SettlementAdjustmentReason
import io.github.kdh949.beanflow.settlement.internal.SettlementBatchCalculation
import io.github.kdh949.beanflow.settlement.internal.SettlementBatchEntity
import io.github.kdh949.beanflow.settlement.internal.SettlementBatchJpaRepository
import io.github.kdh949.beanflow.settlement.internal.SettlementItemEntity
import io.github.kdh949.beanflow.settlement.internal.SettlementItemJpaRepository
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.sql.Timestamp
import java.util.UUID
import kotlin.system.exitProcess

@Configuration(proxyBeanMethods = false)
@Profile("local-demo")
@EnableAutoConfiguration
@EntityScan("io.github.kdh949.beanflow")
@EnableJpaRepositories("io.github.kdh949.beanflow")
// No component scan: the seeder is imported explicitly, like the other bootstrap CLIs.
@Import(LocalDemoSeeder::class, CustomerCredentialSecurityConfiguration::class)
internal class LocalDemoSeedApplication {
    // SharedInfrastructureConfiguration is not component-scanned by this CLI, so the seed provides
    // the same UTC clock the application uses.
    @Bean
    fun seedClock(): Clock = Clock.systemUTC()
}

/**
 * Writes the deterministic `local-demo` fixture through the owner JPA entities.
 *
 * Everything happens in one transaction: a partial failure rolls the whole fixture back rather than
 * leaving a half-seeded environment. Every row uses a fixed identifier and is only inserted when
 * absent, so re-running produces the same fixture with no duplicates.
 *
 * The seed never creates a required policy on its own. `scripts/demo/start.sh` runs the
 * ordinary-accrual policy bootstrap explicitly and this seed fails if that policy is missing.
 */
@Component
@Profile("local-demo")
internal class LocalDemoSeeder(
    private val stores: StoreJpaRepository,
    private val menus: MenuJpaRepository,
    private val menuOptions: MenuOptionJpaRepository,
    private val menuConfigurations: MenuConfigurationJpaRepository,
    private val menuRequirements: MenuConfigurationRequirementJpaRepository,
    private val settlementTerms: StoreSettlementTermsJpaRepository,
    private val memberships: StoreMembershipJpaRepository,
    private val customerAccounts: CustomerAccountJpaRepository,
    private val merchantAccounts: MerchantAccountJpaRepository,
    private val customerPasswords: CustomerPasswordSecurity,
    private val stock: SellableStockJpaRepository,
    private val pickupSlots: PickupSlotJpaRepository,
    private val pointAccounts: PointAccountJpaRepository,
    private val paymentMethods: PaymentMethodJpaRepository,
    private val campaigns: CampaignJpaRepository,
    private val campaignMenus: CampaignEligibleMenuJpaRepository,
    private val couponIssuances: CouponIssuanceJpaRepository,
    private val orders: OrderJpaRepository,
    private val orderLines: OrderLineJpaRepository,
    private val payments: PaymentJpaRepository,
    private val refunds: RefundJpaRepository,
    private val settlementBatches: SettlementBatchJpaRepository,
    private val settlementItems: SettlementItemJpaRepository,
    private val settlementAdjustments: SettlementAdjustmentJpaRepository,
    private val pointAccrualSources: OrderPointAccrualSourceJpaRepository,
    private val pointAccrualSnapshots: OrderPointAccrualSnapshotJpaRepository,
    private val pointAccrualUnits: OrderPointAccrualUnitJpaRepository,
    private val settlementInputSnapshots: OrderSettlementInputSnapshotJpaRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
) {
    @Transactional
    fun seed(): List<String> {
        requireOrdinaryAccrualPolicy()
        val now = clock.instant()
        val created = mutableListOf<String>()

        seedStore(
            LocalDemoFixture.STORE_ID,
            LocalDemoFixture.STORE_NAME,
            LocalDemoFixture.STORE_LONGITUDE,
            LocalDemoFixture.STORE_LATITUDE,
            LocalDemoFixture.STORE_REGION_CODE,
            created,
        )
        seedStore(
            LocalDemoFixture.OTHER_STORE_ID,
            LocalDemoFixture.OTHER_STORE_NAME,
            LocalDemoFixture.OTHER_STORE_LONGITUDE,
            LocalDemoFixture.OTHER_STORE_LATITUDE,
            LocalDemoFixture.OTHER_STORE_REGION_CODE,
            created,
        )
        seedMerchantAccounts(now, created)
        seedMembership(LocalDemoFixture.OWNER_MEMBERSHIP_ID, LocalDemoFixture.STORE_OWNER_ID, LocalDemoFixture.STORE_ID, now, created)
        seedMembership(
            LocalDemoFixture.OTHER_OWNER_MEMBERSHIP_ID,
            LocalDemoFixture.OTHER_STORE_OWNER_ID,
            LocalDemoFixture.OTHER_STORE_ID,
            now,
            created,
        )
        seedCustomerAccount(now, created)
        seedMenus(created)
        seedStock(created)
        seedPickupSlots(now, created)
        seedLoyalty(created)
        seedSettlementTerms(now, created)
        seedPaymentMethod(now, created)
        seedCoupon(now, created)
        seedFinancialTail(now, created)
        return created
    }

    /** The demo must not invent a required policy; it verifies the explicit bootstrap ran. */
    private fun requireOrdinaryAccrualPolicy() {
        val policies =
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM operations_point_accrual_policy_head
                 WHERE scope_type = 'GLOBAL' AND scope_reference = '00000000-0000-0000-0000-000000000000'::uuid
                """.trimIndent(),
                Long::class.java,
            ) ?: 0
        check(policies > 0) {
            "The GLOBAL ordinary point accrual policy is missing. Run the ordinary-accrual-policy-bootstrap " +
                "step in scripts/demo/start.sh before seeding; the demo never creates a default policy silently."
        }
    }

    private fun seedStore(
        storeId: UUID,
        name: String,
        longitude: Double,
        latitude: Double,
        regionCode: String,
        created: MutableList<String>,
    ) {
        if (!stores.existsById(storeId)) {
            // Flushed immediately: the profile row below is written with JDBC, which bypasses the
            // persistence context, so the store must already be visible to its foreign key.
            stores.saveAndFlush(StoreEntity(id = storeId, acceptingOrders = true, pickupEnabled = true))
            created += "store=$storeId"
        }
        // StoreDiscoveryProfile has no JPA entity by design (MD-2026-009), so it is written with
        // JDBC inside this same transaction. Startup requires exact store/profile coverage.
        val inserted =
            jdbcTemplate.update(
                """
                INSERT INTO merchant_store_discovery_profile (store_id, name, location, region_code)
                VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
                ON CONFLICT (store_id) DO NOTHING
                """.trimIndent(),
                storeId,
                name,
                longitude,
                latitude,
                regionCode,
            )
        if (inserted > 0) created += "storeDiscoveryProfile=$storeId"
    }

    private fun seedMembership(
        membershipId: UUID,
        actorId: UUID,
        storeId: UUID,
        now: Instant,
        created: MutableList<String>,
    ) {
        if (memberships.existsById(membershipId)) return
        memberships.save(
            StoreMembershipEntity(
                id = membershipId,
                actorId = actorId,
                storeId = storeId,
                membershipRole = StoreActorRole.OWNER,
                status = StoreMembershipStatus.ACTIVE,
                createdAt = now,
                updatedAt = now,
            ),
        )
        created += "storeOwnerMembership=$membershipId"
    }

    private fun seedMerchantAccounts(
        now: Instant,
        created: MutableList<String>,
    ) {
        if (!merchantAccounts.existsById(LocalDemoFixture.STORE_OWNER_ID)) {
            merchantAccounts.saveAndFlush(
                MerchantAccountEntity(
                    id = LocalDemoFixture.STORE_OWNER_ID,
                    loginId = LocalDemoFixture.MERCHANT_LOGIN_ID,
                    passwordHash = customerPasswords.encode(LocalDemoFixture.MERCHANT_INITIAL_PASSWORD),
                    displayName = LocalDemoFixture.MERCHANT_DISPLAY_NAME,
                    state = MerchantAccountState.INITIAL_PASSWORD,
                    temporaryPasswordExpiresAt = now.plus(Duration.ofHours(24)),
                    passwordChangedAt = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            created += "merchantAccount=${LocalDemoFixture.STORE_OWNER_ID}"
        }
        if (!merchantAccounts.existsById(LocalDemoFixture.OTHER_STORE_OWNER_ID)) {
            merchantAccounts.saveAndFlush(
                MerchantAccountEntity(
                    id = LocalDemoFixture.OTHER_STORE_OWNER_ID,
                    loginId = LocalDemoFixture.OTHER_MERCHANT_LOGIN_ID,
                    passwordHash = customerPasswords.encode(LocalDemoFixture.OTHER_MERCHANT_PASSWORD),
                    displayName = LocalDemoFixture.OTHER_MERCHANT_DISPLAY_NAME,
                    state = MerchantAccountState.ACTIVE,
                    temporaryPasswordExpiresAt = null,
                    passwordChangedAt = now,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            created += "merchantAccount=${LocalDemoFixture.OTHER_STORE_OWNER_ID}"
        }
    }

    private fun seedMenus(created: MutableList<String>) {
        if (!menus.existsById(LocalDemoFixture.AMERICANO_MENU_ID)) {
            menus.save(
                MenuEntity(
                    id = LocalDemoFixture.AMERICANO_MENU_ID,
                    storeId = LocalDemoFixture.STORE_ID,
                    name = "Demo Americano",
                    basePriceKrw = LocalDemoFixture.AMERICANO_PRICE_KRW,
                    available = true,
                ),
            )
            created += "menu=${LocalDemoFixture.AMERICANO_MENU_ID}"
        }
        // A deliberately unavailable menu so the catalogue read shows a false availability flag.
        if (!menus.existsById(LocalDemoFixture.SOLD_OUT_MENU_ID)) {
            menus.save(
                MenuEntity(
                    id = LocalDemoFixture.SOLD_OUT_MENU_ID,
                    storeId = LocalDemoFixture.STORE_ID,
                    name = "Demo Seasonal Latte",
                    basePriceKrw = LocalDemoFixture.SOLD_OUT_PRICE_KRW,
                    available = false,
                ),
            )
            created += "soldOutMenu=${LocalDemoFixture.SOLD_OUT_MENU_ID}"
        }
        if (!menuOptions.existsById(LocalDemoFixture.EXTRA_SHOT_OPTION_ID)) {
            menuOptions.save(
                MenuOptionEntity(
                    id = LocalDemoFixture.EXTRA_SHOT_OPTION_ID,
                    menuId = LocalDemoFixture.AMERICANO_MENU_ID,
                    name = "Extra shot",
                    additionalPriceKrw = LocalDemoFixture.EXTRA_SHOT_PRICE_KRW,
                    available = true,
                ),
            )
            created += "menuOption=${LocalDemoFixture.EXTRA_SHOT_OPTION_ID}"
        }
        if (!menuOptions.existsById(LocalDemoFixture.OAT_MILK_OPTION_ID)) {
            menuOptions.save(
                MenuOptionEntity(
                    id = LocalDemoFixture.OAT_MILK_OPTION_ID,
                    menuId = LocalDemoFixture.AMERICANO_MENU_ID,
                    name = "Oat milk",
                    additionalPriceKrw = LocalDemoFixture.OAT_MILK_PRICE_KRW,
                    available = false,
                ),
            )
            created += "unavailableMenuOption=${LocalDemoFixture.OAT_MILK_OPTION_ID}"
        }
        seedConfiguration(LocalDemoFixture.PLAIN_CONFIGURATION_ID, LocalDemoFixture.PLAIN_REQUIREMENT_ID, "", 1, created)
        seedConfiguration(
            LocalDemoFixture.EXTRA_SHOT_CONFIGURATION_ID,
            LocalDemoFixture.EXTRA_SHOT_REQUIREMENT_ID,
            LocalDemoFixture.EXTRA_SHOT_OPTION_ID.toString(),
            2,
            created,
        )
    }

    private fun seedConfiguration(
        configurationId: UUID,
        requirementId: UUID,
        normalizedOptionKey: String,
        quantityPerLineUnit: Long,
        created: MutableList<String>,
    ) {
        if (!menuConfigurations.existsById(configurationId)) {
            menuConfigurations.save(
                MenuConfigurationEntity(
                    id = configurationId,
                    menuId = LocalDemoFixture.AMERICANO_MENU_ID,
                    normalizedOptionKey = normalizedOptionKey,
                    available = true,
                ),
            )
            created += "menuConfiguration=$configurationId"
        }
        if (!menuRequirements.existsById(requirementId)) {
            menuRequirements.save(
                MenuConfigurationRequirementEntity(
                    id = requirementId,
                    menuConfigurationId = configurationId,
                    sellableUnitId = LocalDemoFixture.COFFEE_SELLABLE_UNIT_ID,
                    quantityPerLineUnit = quantityPerLineUnit,
                ),
            )
            created += "menuConfigurationRequirement=$requirementId"
        }
    }

    private fun seedStock(created: MutableList<String>) {
        if (stock.existsById(LocalDemoFixture.COFFEE_SELLABLE_UNIT_ID)) return
        stock.save(
            SellableStockEntity(
                id = LocalDemoFixture.COFFEE_SELLABLE_UNIT_ID,
                storeId = LocalDemoFixture.STORE_ID,
                availableQuantity = LocalDemoFixture.STOCK_QUANTITY,
            ),
        )
        created += "sellableStock=${LocalDemoFixture.COFFEE_SELLABLE_UNIT_ID}"
    }

    /**
     * Future pickup windows relative to the seed run. Existing slots are left untouched so a
     * re-run stays idempotent; reset the environment when the seeded windows have passed.
     */
    private fun seedPickupSlots(
        now: Instant,
        created: MutableList<String>,
    ) {
        LocalDemoFixture.PICKUP_SLOT_IDS.forEachIndexed { index, slotId ->
            if (pickupSlots.existsById(slotId)) return@forEachIndexed
            val startsAt = now.plus(Duration.ofHours(index + 1L))
            pickupSlots.save(
                PickupSlotEntity(
                    id = slotId,
                    storeId = LocalDemoFixture.STORE_ID,
                    startsAt = startsAt,
                    endsAt = startsAt.plus(Duration.ofMinutes(30)),
                    capacity = 10,
                ),
            )
            created += "pickupSlot=$slotId"
        }
    }

    private fun seedLoyalty(created: MutableList<String>) {
        if (!pointAccounts.existsById(LocalDemoFixture.POINT_ACCOUNT_ID)) {
            pointAccounts.save(
                PointAccountEntity(
                    id = LocalDemoFixture.POINT_ACCOUNT_ID,
                    customerId = LocalDemoFixture.CUSTOMER_ID,
                    // A balance without its append-only transaction history would make this
                    // fixture internally inconsistent before the first demo request. The smoke
                    // flow proves the first real accrual through the production listener.
                    availablePointsKrw = LocalDemoFixture.INITIAL_POINT_BALANCE_KRW,
                ),
            )
            created += "pointAccount=${LocalDemoFixture.POINT_ACCOUNT_ID}"
        }
    }

    private fun seedCustomerAccount(
        now: Instant,
        created: MutableList<String>,
    ) {
        if (customerAccounts.existsById(LocalDemoFixture.CUSTOMER_ID)) return
        customerAccounts.saveAndFlush(
            CustomerAccountEntity(
                id = LocalDemoFixture.CUSTOMER_ID,
                loginId = LocalDemoFixture.CUSTOMER_LOGIN_ID,
                passwordHash = customerPasswords.encode(LocalDemoFixture.CUSTOMER_PASSWORD),
                displayName = LocalDemoFixture.CUSTOMER_DISPLAY_NAME,
                createdAt = now,
                updatedAt = now,
            ),
        )
        created += "customerAccount=${LocalDemoFixture.CUSTOMER_ID}"
    }

    private fun seedSettlementTerms(
        now: Instant,
        created: MutableList<String>,
    ) {
        if (settlementTerms.existsById(LocalDemoFixture.SETTLEMENT_TERMS_ID)) return
        settlementTerms.save(
            StoreSettlementTermsEntity(
                termsVersionId = LocalDemoFixture.SETTLEMENT_TERMS_ID,
                storeId = LocalDemoFixture.STORE_ID,
                sourceReference = "local-demo:settlement-terms:v1",
                feeRateBps = LocalDemoFixture.SETTLEMENT_FEE_RATE_BPS,
                // The deterministic financial tail is three days old, so its immutable order
                // snapshot must reference terms already effective on that historical order date.
                effectiveFrom = now.minus(Duration.ofDays(10)),
                effectiveTo = null,
                createdAt = now,
            ),
        )
        created += "storeSettlementTerms=${LocalDemoFixture.SETTLEMENT_TERMS_ID}"
    }

    private fun seedPaymentMethod(
        now: Instant,
        created: MutableList<String>,
    ) {
        if (paymentMethods.existsById(LocalDemoFixture.PAYMENT_METHOD_ID)) return
        paymentMethods.save(
            PaymentMethodEntity(
                id = LocalDemoFixture.PAYMENT_METHOD_ID,
                customerId = LocalDemoFixture.CUSTOMER_ID,
                provider = LocalDemoFixture.PAYMENT_PROVIDER,
                // Opaque scripted reference only. No PAN, no CVC, no track data.
                tokenReference = LocalDemoFixture.SCRIPTED_TOKEN_REFERENCE,
                displayAlias = "Demo scripted card",
                cardBrand = "SCRIPTED",
                lastFour = "0000",
                status = PaymentMethodStatus.ACTIVE,
                createdAt = now,
                updatedAt = now,
            ),
        )
        created += "paymentMethod=${LocalDemoFixture.PAYMENT_METHOD_ID}"
    }

    private fun seedCoupon(
        now: Instant,
        created: MutableList<String>,
    ) {
        if (!campaigns.existsById(LocalDemoFixture.CAMPAIGN_ID)) {
            campaigns.save(
                CampaignEntity(
                    id = LocalDemoFixture.CAMPAIGN_ID,
                    storeId = LocalDemoFixture.STORE_ID,
                    active = true,
                    discountType = CouponDiscountType.FIXED_KRW,
                    fixedAmountKrw = LocalDemoFixture.COUPON_DISCOUNT_KRW,
                    rateBps = null,
                    minimumEligibleSubtotalKrw = 0,
                    maximumDiscountKrw = null,
                    allMenusEligible = false,
                    costBearer = CouponCostBearer.PLATFORM,
                    platformShareBps = 10_000,
                    storeShareBps = 0,
                ),
            )
            created += "campaign=${LocalDemoFixture.CAMPAIGN_ID}"
        }
        if (!campaignMenus.existsById(LocalDemoFixture.CAMPAIGN_MENU_ID)) {
            campaignMenus.save(
                CampaignEligibleMenuEntity(
                    id = LocalDemoFixture.CAMPAIGN_MENU_ID,
                    campaignId = LocalDemoFixture.CAMPAIGN_ID,
                    menuId = LocalDemoFixture.AMERICANO_MENU_ID,
                ),
            )
            created += "campaignEligibleMenu=${LocalDemoFixture.CAMPAIGN_MENU_ID}"
        }
        if (!couponIssuances.existsById(LocalDemoFixture.COUPON_ISSUANCE_ID)) {
            couponIssuances.save(
                CouponIssuanceEntity(
                    id = LocalDemoFixture.COUPON_ISSUANCE_ID,
                    campaignId = LocalDemoFixture.CAMPAIGN_ID,
                    customerId = LocalDemoFixture.CUSTOMER_ID,
                    state = CouponIssuanceState.AVAILABLE,
                    couponExpiresAt = now.plus(Duration.ofDays(30)),
                ),
            )
            created += "couponIssuance=${LocalDemoFixture.COUPON_ISSUANCE_ID}"
        }
    }

    /**
     * Creates a closed, historical financial trail only through the owning persistence entities.
     * It is deliberately separate from the live customer smoke order: settlement is immutable
     * history, so the smoke reads it through the public merchant APIs and files its dispute there.
     */
    private fun seedFinancialTail(
        now: Instant,
        created: MutableList<String>,
    ) {
        val seoul = ZoneId.of("Asia/Seoul")
        val firstCompletedAt = now.minus(Duration.ofDays(3)).minus(Duration.ofMinutes(5))
        val secondCompletedAt = now.minus(Duration.ofDays(2)).minus(Duration.ofMinutes(5))
        val firstDate = firstCompletedAt.atZone(seoul).toLocalDate()
        val secondDate = secondCompletedAt.atZone(seoul).toLocalDate()

        seedHistoricalOrder(
            orderId = LocalDemoFixture.HISTORICAL_ORDER_ID,
            lineId = LocalDemoFixture.HISTORICAL_ORDER_LINE_ID,
            paymentId = LocalDemoFixture.HISTORICAL_PAYMENT_ID,
            publicReference = LocalDemoFixture.HISTORICAL_ORDER_REFERENCE,
            completedAt = firstCompletedAt,
            pickupBusinessDate = firstDate,
            pickupSequence = 1,
            created = created,
        )
        seedHistoricalOrder(
            orderId = LocalDemoFixture.ADJUSTED_ORDER_ID,
            lineId = LocalDemoFixture.ADJUSTED_ORDER_LINE_ID,
            paymentId = LocalDemoFixture.ADJUSTED_PAYMENT_ID,
            publicReference = LocalDemoFixture.ADJUSTED_ORDER_REFERENCE,
            completedAt = secondCompletedAt,
            pickupBusinessDate = secondDate,
            pickupSequence = 1,
            created = created,
        )

        if (!refunds.existsById(LocalDemoFixture.HISTORICAL_REFUND_ID)) {
            refunds.save(
                RefundEntity(
                    id = LocalDemoFixture.HISTORICAL_REFUND_ID,
                    paymentId = LocalDemoFixture.HISTORICAL_PAYMENT_ID,
                    orderId = LocalDemoFixture.HISTORICAL_ORDER_ID,
                    requestedAmountKrw = LocalDemoFixture.HISTORICAL_REFUND_KRW,
                    succeededAmountKrw = LocalDemoFixture.HISTORICAL_REFUND_KRW,
                    reason = "SETTLEMENT_DEMO_PARTIAL_REFUND",
                    state = RefundState.SUCCEEDED,
                    providerRefundReference = "local-demo-historical-refund",
                    providerIdempotencyKey = "local-demo-historical-refund-key",
                    sourceReference = "local-demo:historical-partial-refund",
                    attemptCount = 0,
                    nextAction = RefundClaimMode.REQUEST,
                    nextAttemptAt = null,
                    createdAt = firstCompletedAt.plus(Duration.ofHours(2)),
                    updatedAt = firstCompletedAt.plus(Duration.ofHours(2)),
                ),
            )
            created += "historicalRefund=${LocalDemoFixture.HISTORICAL_REFUND_ID}"
        }

        if (!settlementBatches.existsById(LocalDemoFixture.HISTORICAL_SETTLEMENT_BATCH_ID)) {
            val batch =
                settlementBatches.saveAndFlush(
                    SettlementBatchEntity(
                        id = LocalDemoFixture.HISTORICAL_SETTLEMENT_BATCH_ID,
                        storeId = LocalDemoFixture.STORE_ID,
                        settlementDate = firstDate,
                        createdAt = firstCompletedAt.plus(Duration.ofHours(3)),
                    ),
                )
            settlementItems.saveAndFlush(
                settlementItem(
                    id = LocalDemoFixture.HISTORICAL_SETTLEMENT_ITEM_ID,
                    batchId = batch.id,
                    orderId = LocalDemoFixture.HISTORICAL_ORDER_ID,
                    completedAt = firstCompletedAt,
                    settlementDate = firstDate,
                ),
            )
            val calculatedAt = firstCompletedAt.plus(Duration.ofHours(4))
            batch.calculate(
                settlementCalculation(
                    itemCount = 1,
                    adjustmentKrw = 0,
                    adjustmentCursorEffectiveAt = null,
                    adjustmentCursorId = null,
                ),
                calculatedAt,
            )
            // The database transition guard intentionally rejects OPEN -> CONFIRMED in one
            // flush, even when the aggregate has performed both valid in-memory transitions.
            // Persist CALCULATED first so this CLI follows the same immutable lifecycle.
            settlementBatches.saveAndFlush(batch)
            batch.confirm(calculatedAt.plus(Duration.ofMinutes(1)))
            settlementBatches.saveAndFlush(batch)
            created += "settlementBatch=${batch.id}"
            created += "settlementItem=${LocalDemoFixture.HISTORICAL_SETTLEMENT_ITEM_ID}"
        }

        if (!settlementAdjustments.existsById(LocalDemoFixture.REFUND_SETTLEMENT_ADJUSTMENT_ID)) {
            settlementAdjustments.saveAndFlush(
                SettlementAdjustmentEntity(
                    id = LocalDemoFixture.REFUND_SETTLEMENT_ADJUSTMENT_ID,
                    storeId = LocalDemoFixture.STORE_ID,
                    settlementItemId = LocalDemoFixture.HISTORICAL_SETTLEMENT_ITEM_ID,
                    sourceSettlementBatchId = LocalDemoFixture.HISTORICAL_SETTLEMENT_BATCH_ID,
                    adjustmentSource = "local-demo:historical-partial-refund-adjustment",
                    reasonCode = SettlementAdjustmentReason.REFUND_SUCCEEDED,
                    effectiveAt = secondCompletedAt.minus(Duration.ofHours(1)),
                    orderCompletedAt = firstCompletedAt,
                    settlementDate = firstDate,
                    currency = "KRW",
                    amountKrw = -LocalDemoFixture.HISTORICAL_REFUND_KRW,
                    createdAt = secondCompletedAt.minus(Duration.ofHours(1)),
                ),
            )
            created += "settlementAdjustment=${LocalDemoFixture.REFUND_SETTLEMENT_ADJUSTMENT_ID}"
        }

        if (!settlementBatches.existsById(LocalDemoFixture.ADJUSTED_SETTLEMENT_BATCH_ID)) {
            val batch =
                settlementBatches.saveAndFlush(
                    SettlementBatchEntity(
                        id = LocalDemoFixture.ADJUSTED_SETTLEMENT_BATCH_ID,
                        storeId = LocalDemoFixture.STORE_ID,
                        settlementDate = secondDate,
                        createdAt = secondCompletedAt.plus(Duration.ofHours(2)),
                    ),
                )
            settlementItems.saveAndFlush(
                settlementItem(
                    id = LocalDemoFixture.ADJUSTED_SETTLEMENT_ITEM_ID,
                    batchId = batch.id,
                    orderId = LocalDemoFixture.ADJUSTED_ORDER_ID,
                    completedAt = secondCompletedAt,
                    settlementDate = secondDate,
                ),
            )
            val calculatedAt = secondCompletedAt.plus(Duration.ofHours(3))
            batch.calculate(
                settlementCalculation(
                    itemCount = 1,
                    adjustmentKrw = -LocalDemoFixture.HISTORICAL_REFUND_KRW,
                    adjustmentCursorEffectiveAt = secondCompletedAt.minus(Duration.ofHours(1)),
                    adjustmentCursorId = LocalDemoFixture.REFUND_SETTLEMENT_ADJUSTMENT_ID,
                ),
                calculatedAt,
            )
            settlementBatches.saveAndFlush(batch)
            batch.confirm(calculatedAt.plus(Duration.ofMinutes(1)))
            settlementBatches.saveAndFlush(batch)
            created += "adjustedSettlementBatch=${batch.id}"
            created += "adjustedSettlementItem=${LocalDemoFixture.ADJUSTED_SETTLEMENT_ITEM_ID}"
        }
    }

    private fun seedHistoricalOrder(
        orderId: UUID,
        lineId: UUID,
        paymentId: UUID,
        publicReference: String,
        completedAt: Instant,
        pickupBusinessDate: LocalDate,
        pickupSequence: Long,
        created: MutableList<String>,
    ) {
        if (!orders.existsById(orderId)) {
            val orderCreatedAt = completedAt.minus(Duration.ofMinutes(10))
            jdbcTemplate.update(
                "INSERT INTO ordering_public_reference_registry (public_reference, allocated_at) VALUES (?, ?) ON CONFLICT DO NOTHING",
                publicReference,
                Timestamp.from(orderCreatedAt),
            )
            val order =
                orders.save(
                    OrderEntity(
                        id = orderId,
                        customerId = LocalDemoFixture.CUSTOMER_ID,
                        storeId = LocalDemoFixture.STORE_ID,
                        pickupSlotId = LocalDemoFixture.PICKUP_SLOT_IDS.first(),
                        publicReference = publicReference,
                        pickupBusinessDate = pickupBusinessDate,
                        pickupSequence = pickupSequence,
                        storeNameSnapshot = LocalDemoFixture.STORE_NAME,
                        pickupWindowStartSnapshot = completedAt.minus(Duration.ofMinutes(30)),
                        pickupWindowEndSnapshot = completedAt,
                        state = OrderState.PENDING_PAYMENT,
                        subtotalKrw = LocalDemoFixture.HISTORICAL_ORDER_GROSS_KRW,
                        couponDiscountKrw = 0,
                        pointsAppliedKrw = 0,
                        payableKrw = LocalDemoFixture.HISTORICAL_ORDER_GROSS_KRW,
                        reservationExpiresAt = orderCreatedAt.plus(Duration.ofMinutes(5)),
                        createdAt = orderCreatedAt,
                        updatedAt = orderCreatedAt,
                    ),
                )
            order.markPaid(orderCreatedAt.plusSeconds(10))
            order.accept(orderCreatedAt.plusSeconds(20))
            order.startPreparing(orderCreatedAt.plusSeconds(30))
            order.markReady(orderCreatedAt.plusSeconds(40))
            order.complete(completedAt)
            orderLines.save(
                OrderLineEntity(
                    id = lineId,
                    orderId = orderId,
                    lineSequence = 0,
                    menuId = LocalDemoFixture.AMERICANO_MENU_ID,
                    menuName = "Demo Americano",
                    optionNamesJson = "[]",
                    optionSelectionSnapshotState = OptionSelectionSnapshotState.SNAPSHOTTED,
                    normalizedOptionIds = emptyList(),
                    sellableRequirementsJson = "[]",
                    unitPriceKrw = LocalDemoFixture.HISTORICAL_ORDER_GROSS_KRW,
                    quantity = 1,
                    grossKrw = LocalDemoFixture.HISTORICAL_ORDER_GROSS_KRW,
                    couponDiscountKrw = 0,
                    pointsAppliedKrw = 0,
                    cashPayableKrw = LocalDemoFixture.HISTORICAL_ORDER_GROSS_KRW,
                ),
            )
            seedHistoricalPointAccrualSnapshot(orderId, lineId, orderCreatedAt)
            seedHistoricalSettlementInputSnapshot(orderId, orderCreatedAt)
            created += "historicalOrder=$orderId"
            created += "historicalOrderReference=$publicReference"
            created += "historicalOrderLine=$lineId"
            created += "historicalOrderPointAccrualSource=$orderId"
            created += "historicalOrderPointAccrualSnapshot=$orderId"
            created += "historicalOrderPointAccrualUnit=$lineId"
            created += "historicalOrderSettlementInputSnapshot=$orderId"
        }
        if (!payments.existsById(paymentId)) {
            payments.save(
                PaymentEntity(
                    id = paymentId,
                    orderId = orderId,
                    customerId = LocalDemoFixture.CUSTOMER_ID,
                    paymentMethodId = LocalDemoFixture.PAYMENT_METHOD_ID,
                    type = PaymentType.EXTERNAL,
                    approvalState = PaymentApprovalState.APPROVED,
                    requestedAmountKrw = LocalDemoFixture.HISTORICAL_ORDER_GROSS_KRW,
                    approvedAmountKrw = LocalDemoFixture.HISTORICAL_ORDER_GROSS_KRW,
                    succeededRefundAmountKrw = if (paymentId == LocalDemoFixture.HISTORICAL_PAYMENT_ID) LocalDemoFixture.HISTORICAL_REFUND_KRW else 0,
                    currency = "KRW",
                    sourceReference = "local-demo:historical-payment:$orderId",
                    providerTransactionReference = "local-demo-historical-payment-$paymentId",
                    correlationId = "local-demo:historical-payment",
                    approvedAt = completedAt.minus(Duration.ofMinutes(1)),
                    createdAt = completedAt.minus(Duration.ofMinutes(8)),
                    updatedAt = completedAt.minus(Duration.ofMinutes(1)),
                ),
            )
            created += "historicalPayment=$paymentId"
        }
    }

    private fun settlementItem(
        id: UUID,
        batchId: UUID,
        orderId: UUID,
        completedAt: Instant,
        settlementDate: LocalDate,
    ) =
        SettlementItemEntity(
            id = id,
            settlementBatchId = batchId,
            orderId = orderId,
            storeId = LocalDemoFixture.STORE_ID,
            itemSource = "local-demo:settlement-item:$orderId",
            completedAt = completedAt,
            settlementDate = settlementDate,
            currency = "KRW",
            grossPaidKrw = LocalDemoFixture.HISTORICAL_ORDER_GROSS_KRW,
            feeRateBps = LocalDemoFixture.SETTLEMENT_FEE_RATE_BPS,
            feeKrw = 300,
            couponCostKrw = 0,
            pointCostKrw = 0,
            benefitCostKrw = 0,
            netSettlementKrw = 9_700,
            createdAt = completedAt.plus(Duration.ofMinutes(1)),
        )

    private fun settlementCalculation(
        itemCount: Int,
        adjustmentKrw: Long,
        adjustmentCursorEffectiveAt: Instant?,
        adjustmentCursorId: UUID?,
    ) =
        SettlementBatchCalculation(
            itemCount = itemCount,
            grossPaidKrw = LocalDemoFixture.HISTORICAL_ORDER_GROSS_KRW,
            feeKrw = 300,
            benefitCostKrw = 0,
            itemNetSettlementKrw = 9_700,
            adjustmentKrw = adjustmentKrw,
            carryForwardInKrw = 0,
            carryForwardSourceBatchId = null,
            adjustmentCursorEffectiveAt = adjustmentCursorEffectiveAt,
            adjustmentCursorId = adjustmentCursorId,
        )

    /** Uses the explicitly bootstrapped GLOBAL policy; it never manufactures a default policy. */
    private fun seedHistoricalPointAccrualSnapshot(
        orderId: UUID,
        lineId: UUID,
        createdAt: Instant,
    ) {
        if (pointAccrualSources.existsById(orderId)) return
        val policy = currentGlobalAccrualPolicy()
        val grossAccrual = Math.floorDiv(LocalDemoFixture.HISTORICAL_ORDER_GROSS_KRW * policy.rateBps, 10_000)
        pointAccrualSources.save(
            OrderPointAccrualSourceEntity(
                orderId = orderId,
                sourceState = OrderPointAccrualSourceState.SNAPSHOTTED,
                createdAt = createdAt,
            ),
        )
        pointAccrualSnapshots.save(
            OrderPointAccrualSnapshotEntity(
                orderId = orderId,
                policyVersionId = policy.versionId,
                selectedScopeType = OrdinaryPointAccrualPolicyScopeType.GLOBAL,
                selectedScopeReference = GLOBAL_SCOPE_REFERENCE,
                selectionSource = OrdinaryPointAccrualPolicySelectionSource.GLOBAL_NO_OVERRIDE,
                accrualRateBps = policy.rateBps,
                roundingMode = PointAccrualRoundingMode.valueOf(policy.roundingMode),
                issuerType = PointAccrualIssuerType.valueOf(policy.issuerType),
                issuerReference = policy.issuerReference,
                expiryRule = OrdinaryPointAccrualExpiryRule.valueOf(policy.expiryRule),
                validityDays = policy.validityDays,
                canonicalPolicyHash = policy.payloadHash,
                orderPayableKrw = LocalDemoFixture.HISTORICAL_ORDER_GROSS_KRW,
                grossAccrualAmountKrw = grossAccrual,
                createdAt = createdAt,
            ),
        )
        pointAccrualUnits.save(
            OrderPointAccrualUnitEntity(
                orderId = orderId,
                orderLineId = lineId,
                lineSequence = 0,
                unitPosition = 0,
                cashPayableKrw = LocalDemoFixture.HISTORICAL_ORDER_GROSS_KRW,
                accruedAmountKrw = grossAccrual,
                createdAt = createdAt,
            ),
        )
    }

    private fun currentGlobalAccrualPolicy(): LocalDemoAccrualPolicy {
        val row =
            jdbcTemplate.queryForMap(
                """
                SELECT version.policy_version_id, version.accrual_rate_bps, version.rounding_mode,
                       version.issuer_type, version.issuer_reference, version.expiry_rule,
                       version.validity_days, version.payload_hash
                  FROM operations_point_accrual_policy_head head
                  JOIN operations_point_accrual_policy_version version
                    ON version.policy_version_id = head.policy_version_id
                   AND version.scope_type = head.scope_type
                   AND version.scope_reference = head.scope_reference
                 WHERE head.scope_type = 'GLOBAL'
                """.trimIndent(),
            )
        return LocalDemoAccrualPolicy(
            versionId = (row["policy_version_id"] as Number).toLong(),
            rateBps = (row["accrual_rate_bps"] as Number).toInt(),
            roundingMode = row["rounding_mode"] as String,
            issuerType = row["issuer_type"] as String,
            issuerReference = row["issuer_reference"] as String,
            expiryRule = row["expiry_rule"] as String,
            validityDays = (row["validity_days"] as Number).toInt(),
            payloadHash = row["payload_hash"] as String,
        )
    }

    private fun seedHistoricalSettlementInputSnapshot(
        orderId: UUID,
        orderCreatedAt: Instant,
    ) {
        if (settlementInputSnapshots.existsById(orderId)) return
        settlementInputSnapshots.save(
            OrderSettlementInputSnapshotEntity(
                orderId = orderId,
                storeId = LocalDemoFixture.STORE_ID,
                storeSettlementTermsVersionId = LocalDemoFixture.SETTLEMENT_TERMS_ID,
                storeSettlementTermsSourceReference = "local-demo:settlement-terms:v1",
                couponReservationId = null,
                couponCampaignId = null,
                couponCampaignVersion = null,
                couponCostBearer = null,
                couponPlatformShareBps = null,
                couponStoreShareBps = null,
                couponDiscountKrw = 0,
                platformCouponCostKrw = 0,
                couponCostKrw = 0,
                pointReservationId = null,
                pointAllocationHash = null,
                pointsAppliedKrw = 0,
                pointCostKrw = 0,
                grossPaidKrw = LocalDemoFixture.HISTORICAL_ORDER_GROSS_KRW,
                feeBaseKrw = LocalDemoFixture.HISTORICAL_ORDER_GROSS_KRW,
                feeRateBps = LocalDemoFixture.SETTLEMENT_FEE_RATE_BPS,
                feeKrw = 300,
                benefitCostKrw = 0,
                netSettlementKrw = 9_700,
                currency = "KRW",
                snapshotSchemaVersion = 1,
                canonicalSnapshotHash = "0".repeat(64),
                createdAt = orderCreatedAt,
            ),
        )
    }

    private data class LocalDemoAccrualPolicy(
        val versionId: Long,
        val rateBps: Int,
        val roundingMode: String,
        val issuerType: String,
        val issuerReference: String,
        val expiryRule: String,
        val validityDays: Int,
        val payloadHash: String,
    )

    private companion object {
        val GLOBAL_SCOPE_REFERENCE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
    }
}

/**
 * Usage: `LocalDemoSeedCliKt`
 *
 * Requires the `local-demo` profile, which cannot run with `prod` (see LocalDemoSafetyConfiguration).
 * Prints only semantic aliases the operator needs and exits non-zero on any failure.
 */
fun main() {
    val application =
        SpringApplicationBuilder(LocalDemoSeedApplication::class.java)
            .web(WebApplicationType.NONE)
            .profiles("local", "local-demo")
            // The Modulith runtime autoconfiguration requires a @SpringBootApplication class, which
            // a CLI configuration is not. It is excluded by name because spring-modulith-runtime is
            // a runtimeOnly dependency. Module boundaries stay covered by ModularityTests.
            .properties(
                "spring.autoconfigure.exclude=" +
                    "org.springframework.modulith.runtime.autoconfigure.SpringModulithRuntimeAutoConfiguration," +
                    "org.springframework.modulith.observability.autoconfigure.ModuleObservabilityAutoConfiguration," +
                    "org.springframework.modulith.actuator.autoconfigure.ApplicationModulesEndpointConfiguration",
            ).build()
    application.setRegisterShutdownHook(false)
    val context =
        try {
            application.run()
        } catch (failure: Exception) {
            System.err.println("local-demo seed could not start: ${failure.message}")
            exitProcess(1)
        }
    try {
        val created = context.getBean(LocalDemoSeeder::class.java).seed()
        println("LOCAL_DEMO_SEED_RESULT inserted=${created.size}")
        println("LOCAL_DEMO_SEED_ALIAS customer=${LocalDemoFixture.CUSTOMER_LOGIN_ID}")
        println("LOCAL_DEMO_SEED_ALIAS merchant=${LocalDemoFixture.MERCHANT_LOGIN_ID}")
        println("LOCAL_DEMO_SEED_ALIAS otherMerchant=${LocalDemoFixture.OTHER_MERCHANT_LOGIN_ID}")
        println("LOCAL_DEMO_SEED_ALIAS store=BeanFlow Demo Roastery")
        println("LOCAL_DEMO_SEED_ALIAS financialTail=confirmed-settlement,partial-refund-adjustment,dispute-ready")
        println("LOCAL_DEMO_SEED_STATUS OK")
    } catch (failure: Exception) {
        // The seed transaction rolled back; nothing partial remains.
        System.err.println("LOCAL_DEMO_SEED_STATUS FAILED ${failure.message}")
        exitProcess(1)
    } finally {
        SpringApplication.exit(context)
    }
    exitProcess(0)
}

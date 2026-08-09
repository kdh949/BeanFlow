package io.github.kdh949.beanflow.demo

import io.github.kdh949.beanflow.fulfillment.internal.PickupSlotEntity
import io.github.kdh949.beanflow.fulfillment.internal.PickupSlotJpaRepository
import io.github.kdh949.beanflow.identity.api.StoreActorRole
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
import io.github.kdh949.beanflow.promotion.api.CouponCostBearer
import io.github.kdh949.beanflow.promotion.api.CouponDiscountType
import io.github.kdh949.beanflow.promotion.internal.CampaignEligibleMenuEntity
import io.github.kdh949.beanflow.promotion.internal.CampaignEligibleMenuJpaRepository
import io.github.kdh949.beanflow.promotion.internal.CampaignEntity
import io.github.kdh949.beanflow.promotion.internal.CampaignJpaRepository
import io.github.kdh949.beanflow.promotion.internal.CouponIssuanceEntity
import io.github.kdh949.beanflow.promotion.internal.CouponIssuanceJpaRepository
import io.github.kdh949.beanflow.promotion.internal.CouponIssuanceState
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
import java.util.UUID
import kotlin.system.exitProcess

@Configuration(proxyBeanMethods = false)
@Profile("local-demo")
@EnableAutoConfiguration
@EntityScan("io.github.kdh949.beanflow")
@EnableJpaRepositories("io.github.kdh949.beanflow")
// No component scan: the seeder is imported explicitly, like the other bootstrap CLIs.
@Import(LocalDemoSeeder::class)
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
    private val stock: SellableStockJpaRepository,
    private val pickupSlots: PickupSlotJpaRepository,
    private val pointAccounts: PointAccountJpaRepository,
    private val paymentMethods: PaymentMethodJpaRepository,
    private val campaigns: CampaignJpaRepository,
    private val campaignMenus: CampaignEligibleMenuJpaRepository,
    private val couponIssuances: CouponIssuanceJpaRepository,
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
            created,
        )
        seedStore(
            LocalDemoFixture.OTHER_STORE_ID,
            LocalDemoFixture.OTHER_STORE_NAME,
            LocalDemoFixture.OTHER_STORE_LONGITUDE,
            LocalDemoFixture.OTHER_STORE_LATITUDE,
            created,
        )
        seedMembership(LocalDemoFixture.OWNER_MEMBERSHIP_ID, LocalDemoFixture.STORE_OWNER_ID, LocalDemoFixture.STORE_ID, now, created)
        seedMembership(
            LocalDemoFixture.OTHER_OWNER_MEMBERSHIP_ID,
            LocalDemoFixture.OTHER_STORE_OWNER_ID,
            LocalDemoFixture.OTHER_STORE_ID,
            now,
            created,
        )
        seedMenus(created)
        seedStock(created)
        seedPickupSlots(now, created)
        seedLoyalty(created)
        seedSettlementTerms(now, created)
        seedPaymentMethod(now, created)
        seedCoupon(now, created)
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
                INSERT INTO merchant_store_discovery_profile (store_id, name, location)
                VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
                ON CONFLICT (store_id) DO NOTHING
                """.trimIndent(),
                storeId,
                name,
                longitude,
                latitude,
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
                effectiveFrom = now.minus(Duration.ofDays(1)),
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
}

/**
 * Usage: `LocalDemoSeedCliKt`
 *
 * Requires the `local-demo` profile, which cannot run with `prod` (see LocalDemoSafetyConfiguration).
 * Prints the identifiers the smoke flow needs and exits non-zero on any failure.
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
        created.forEach { println("LOCAL_DEMO_SEED_CREATED $it") }
        println("LOCAL_DEMO_SEED_STORE_ID ${LocalDemoFixture.STORE_ID}")
        println("LOCAL_DEMO_SEED_OTHER_STORE_ID ${LocalDemoFixture.OTHER_STORE_ID}")
        println("LOCAL_DEMO_SEED_MENU_ID ${LocalDemoFixture.AMERICANO_MENU_ID}")
        println("LOCAL_DEMO_SEED_OPTION_ID ${LocalDemoFixture.EXTRA_SHOT_OPTION_ID}")
        println("LOCAL_DEMO_SEED_POINT_ACCOUNT_ID ${LocalDemoFixture.POINT_ACCOUNT_ID}")
        println("LOCAL_DEMO_SEED_PAYMENT_METHOD_ID ${LocalDemoFixture.PAYMENT_METHOD_ID}")
        println("LOCAL_DEMO_SEED_COUPON_ISSUANCE_ID ${LocalDemoFixture.COUPON_ISSUANCE_ID}")
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

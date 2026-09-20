package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.CreateMenuCatalogCommand
import io.github.kdh949.beanflow.merchant.api.DemoStoreCatalog
import io.github.kdh949.beanflow.merchant.api.DemoStoreProvisioning
import io.github.kdh949.beanflow.merchant.api.MenuCatalogOperations
import io.github.kdh949.beanflow.merchant.api.MenuConfigurationTradeContent
import io.github.kdh949.beanflow.merchant.api.MenuTradeDefinition
import io.github.kdh949.beanflow.merchant.api.ReplaceStoreCustomerDisplayCommand
import io.github.kdh949.beanflow.merchant.api.StoreCustomerDisplayOperations
import io.github.kdh949.beanflow.merchant.api.StoreIdentityCommand
import io.github.kdh949.beanflow.merchant.api.StoreIdentityOperations
import io.github.kdh949.beanflow.merchant.api.StoreOperatingDay
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

@Service
@ConditionalOnProperty(name = ["beanflow.demo.enabled"], havingValue = "true")
@Transactional(propagation = Propagation.MANDATORY)
internal class DemoStoreProvisioningService(
    private val identities: StoreIdentityOperations,
    private val stores: StoreJpaRepository,
    private val catalogs: MenuCatalogOperations,
    private val terms: StoreSettlementTermsJpaRepository,
    private val displays: StoreCustomerDisplayOperations,
) : DemoStoreProvisioning {
    override fun close(
        storeId: UUID,
        now: Instant,
    ) {
        val store = stores.findByIdForUpdate(storeId) ?: error("Demo store missing")
        store.replaceOrderingPolicy(false, false, now)
    }

    override fun create(
        workspaceId: UUID,
        now: Instant,
    ): DemoStoreCatalog {
        val store =
            identities
                .change(
                    StoreIdentityCommand(
                        workspaceId,
                        "demo-store:$workspaceId",
                        null,
                        "BeanFlow 체험점",
                        37.5665,
                        126.978,
                        "1114010300",
                        null,
                        "방문자 전용 체험 매장 생성",
                        now,
                    ),
                ).snapshot
        val entity = stores.findById(store.storeId).orElseThrow()
        entity.replaceOrderingPolicy(true, true, now)
        stores.flush()
        displays.replace(
            ReplaceStoreCustomerDisplayCommand(
                storeId = store.storeId,
                expectedVersion = 0,
                addressLine = null,
                directionsHint = null,
                timezone = "Asia/Seoul",
                operatingDays =
                    DayOfWeek.entries.map { day ->
                        StoreOperatingDay(day, closed = false, opensAt = LocalTime.MIN, closesAt = LocalTime.MAX)
                    },
            ),
            now,
        )
        // Initial terms belong to this newly created store; no existing effective interval is changed.
        terms.saveAndFlush(
            StoreSettlementTermsEntity(
                UUID.randomUUID(),
                store.storeId,
                "demo:$workspaceId:initial-terms",
                300,
                now,
                null,
                now,
            ),
        )
        val sampleMenuId = UUID.randomUUID()
        listOf(Triple(sampleMenuId, "아이스 아메리카노", 4500L), Triple(UUID.randomUUID(), "카페 라테", 5000L)).forEach { (id, name, price) ->
            catalogs.create(
                CreateMenuCatalogCommand(
                    workspaceId,
                    "demo-menu:$id",
                    store.storeId,
                    MenuTradeDefinition(
                        id,
                        name,
                        price,
                        true,
                        emptyList(),
                        listOf(MenuConfigurationTradeContent(UUID.randomUUID(), emptyList(), true)),
                    ),
                    now,
                ),
            )
        }
        return DemoStoreCatalog(store.storeId, sampleMenuId)
    }
}

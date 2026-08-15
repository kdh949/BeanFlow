package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.shared.api.ReplaceBrandSearchTermsCommand
import io.github.kdh949.beanflow.shared.api.ReplaceStoreSearchTermsCommand
import io.github.kdh949.beanflow.shared.api.StoreSearchIndexOperations
import io.github.kdh949.beanflow.shared.api.StoreSearchTermEntry
import io.github.kdh949.beanflow.shared.api.StoreSearchTermKind
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Builds indexed stores for the search query tests.
 *
 * 색인은 반드시 실제 쓰기 port를 거친다. 테스트가 term 행을 직접 INSERT하면 정규화와 가중치를
 * 테스트가 다시 구현하게 되고, 그 순간 조회 경로가 진짜 색인과 같은 문자열을 보는지 확인할 수
 * 없게 된다.
 */
internal class StoreSearchIndexTestFixture(
    private val jdbc: JdbcTemplate,
    private val index: StoreSearchIndexOperations,
    private val transactions: TransactionTemplate,
) {
    fun clear() {
        jdbc.update("DELETE FROM discovery_customer_favorite_store")
        jdbc.update("DELETE FROM discovery_store_search_term")
        jdbc.update("DELETE FROM fulfillment_pickup_slot")
        jdbc.update("DELETE FROM merchant_menu")
        jdbc.update("DELETE FROM merchant_store_discovery_profile")
        jdbc.update("DELETE FROM merchant_store")
    }

    /**
     * One pickup slot for [storeId]. The default is reservable inside the seven-day window, which
     * is what makes the public `pickupAvailable` flag true (ADR-103 2026-08-15 Amendment).
     */
    fun indexPickupSlot(
        storeId: UUID,
        now: Instant,
        startsIn: Duration = Duration.ofDays(1),
        capacity: Long = 4,
        reserved: Long = 0,
        confirmed: Long = 0,
    ) {
        val startsAt = now.plus(startsIn)
        jdbc.update(
            """
            INSERT INTO fulfillment_pickup_slot (
                id, store_id, starts_at, ends_at, capacity, reserved_count, confirmed_count, version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 0)
            """.trimIndent(),
            UUID.randomUUID(),
            storeId,
            Timestamp.from(startsAt),
            Timestamp.from(startsAt.plus(Duration.ofMinutes(20))),
            capacity,
            reserved,
            confirmed,
        )
    }

    fun indexStore(
        name: String,
        longitude: Double = SEOUL_LONGITUDE,
        latitude: Double = SEOUL_LATITUDE,
        brandName: String? = null,
        region: List<Pair<StoreSearchTermKind, String>> = emptyList(),
        menus: List<String> = emptyList(),
        acceptingOrders: Boolean = true,
        pickupEnabled: Boolean = true,
    ): UUID {
        val storeId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, ?, ?, 0)",
            storeId,
            acceptingOrders,
            pickupEnabled,
        )
        jdbc.update(
            """
            INSERT INTO merchant_store_discovery_profile (store_id, name, location, region_code)
            VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, '1168010100')
            """.trimIndent(),
            storeId,
            name,
            longitude,
            latitude,
        )
        transactions.executeWithoutResult {
            index.replaceStoreTerms(
                ReplaceStoreSearchTermsCommand(
                    storeId,
                    setOf(StoreSearchTermKind.STORE_NAME, StoreSearchTermKind.MENU_NAME),
                    buildList {
                        add(StoreSearchTermEntry(StoreSearchTermKind.STORE_NAME, name))
                        menus.forEach { menu ->
                            add(StoreSearchTermEntry(StoreSearchTermKind.MENU_NAME, menu, UUID.randomUUID()))
                        }
                    },
                ),
            )
            if (region.isNotEmpty()) {
                index.replaceStoreTerms(
                    ReplaceStoreSearchTermsCommand(
                        storeId,
                        REGION_KINDS,
                        region.map { (kind, text) -> StoreSearchTermEntry(kind, text) },
                    ),
                )
            }
            if (brandName != null) {
                index.replaceBrandTerms(ReplaceBrandSearchTermsCommand(listOf(storeId), brandName))
            }
        }
        return storeId
    }

    companion object {
        /** 서울특별시 강남구 역삼동 부근. */
        const val SEOUL_LONGITUDE = 127.0361
        const val SEOUL_LATITUDE = 37.5006

        /** 강원특별자치도 춘천시 부근. */
        const val CHUNCHEON_LONGITUDE = 127.7298
        const val CHUNCHEON_LATITUDE = 37.8813

        val REGION_KINDS =
            setOf(
                StoreSearchTermKind.REGION_SIDO,
                StoreSearchTermKind.REGION_SIGUNGU,
                StoreSearchTermKind.REGION_EUPMYEONDONG,
                StoreSearchTermKind.REGION_RI,
            )
    }
}

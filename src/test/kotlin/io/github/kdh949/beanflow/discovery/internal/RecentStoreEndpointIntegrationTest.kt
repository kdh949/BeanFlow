package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.internal.OrderCreationDatabaseFixture
import io.github.kdh949.beanflow.ordering.internal.OrderCreationFixture
import io.github.kdh949.beanflow.shared.api.CustomerRecentStoreQuery
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "beanflow.search-index-coverage.initial-delay-ms=3600000",
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class RecentStoreEndpointIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var clock: Clock

    @Autowired
    private lateinit var createOrderUseCase: CreateOrderUseCase

    @Autowired
    private lateinit var recentStoreQuery: CustomerRecentStoreQuery

    @BeforeEach
    fun clearDatabase() {
        OrderCreationDatabaseFixture.clean(jdbc)
        jdbc.update("DELETE FROM discovery_customer_favorite_store")
        jdbc.update("DELETE FROM discovery_store_search_term")
    }

    @Test
    fun `recent stores include only current eligible orders deduplicate a store and hydrate current pickup availability`() {
        val customerId = UUID.randomUUID()
        val newestStore = createVisibleStore(customerId, "최근 주문 최신 매장")
        val olderStore = createVisibleStore(customerId, "최근 주문 이전 매장")
        val excludedStore = createVisibleStore(customerId, "제외 주문 매장")
        val noLongerPublicStore = createVisibleStore(customerId, "비노출 최근 주문 매장")
        val now = clock.instant()
        insertOrder(newestStore, "PAID", now.minus(Duration.ofMinutes(1)), 1)
        insertOrder(newestStore, "COMPLETED", now.minus(Duration.ofMinutes(2)), 2)
        insertOrder(olderStore, "ACCEPTED", now.minus(Duration.ofMinutes(3)), 3)
        insertOrder(excludedStore, "PENDING_PAYMENT", now, 4)
        insertOrder(noLongerPublicStore, "READY", now, 5)
        jdbc.update("DELETE FROM merchant_store_discovery_profile WHERE store_id = ?", noLongerPublicStore.storeId)
        insertPickupSlot(newestStore.storeId, now)

        mockMvc
            .perform(get("/api/v1/me/recent-stores").with(customerJwt(customerId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].storeId").value(newestStore.storeId.toString()))
            .andExpect(jsonPath("$.items[0].name").value("최근 주문 최신 매장"))
            .andExpect(jsonPath("$.items[0].pickupAvailable").value(true))
            .andExpect(jsonPath("$.items[0].distanceMeters").doesNotExist())
            .andExpect(jsonPath("$.items[1].storeId").value(olderStore.storeId.toString()))
            .andExpect(jsonPath("$.items[1].pickupAvailable").value(false))
    }

    @Test
    fun `recent order projection applies every BR-40 state deduplicates stores breaks ties and isolates customers`() {
        val customerId = UUID.randomUUID()
        val otherCustomerId = UUID.randomUUID()
        // PostgreSQL stores timestamps at microsecond precision. A whole-second fixture keeps
        // this projection assertion about ordering rather than the system clock's nanoseconds.
        val base = Instant.parse("2026-08-01T00:00:00Z")
        val reusedStore = createVisibleStore(customerId, "중복 최근 매장", uuid(1))
        val tiedFirstStore = createVisibleStore(customerId, "동률 첫 매장", uuid(2))
        val tiedSecondStore = createVisibleStore(customerId, "동률 둘째 매장", uuid(3))
        val acceptedStore = createVisibleStore(customerId, "수락 매장", uuid(4))
        val pendingStore = createVisibleStore(customerId, "결제 대기 매장", uuid(5))
        val rejectedStore = createVisibleStore(customerId, "거절 매장", uuid(6))
        val expiredStore = createVisibleStore(customerId, "만료 매장", uuid(7))
        val cancelledStore = createVisibleStore(customerId, "취소 매장", uuid(8))
        val otherCustomerStore = createVisibleStore(otherCustomerId, "다른 고객 매장", uuid(9))

        insertOrder(reusedStore, "PAID", base.plusSeconds(40), 1)
        insertOrder(reusedStore, "COMPLETED", base.plusSeconds(10), 2)
        insertOrder(tiedSecondStore, "PREPARING", base.plusSeconds(30), 3)
        insertOrder(tiedFirstStore, "READY", base.plusSeconds(30), 4)
        insertOrder(acceptedStore, "ACCEPTED", base.plusSeconds(20), 5)
        insertOrder(pendingStore, "PENDING_PAYMENT", base.plusSeconds(70), 6)
        insertOrder(rejectedStore, "REJECTED", base.plusSeconds(60), 7)
        insertOrder(expiredStore, "EXPIRED", base.plusSeconds(50), 8)
        insertOrder(cancelledStore, "CANCELLED", base.plusSeconds(45), 9)
        insertOrder(otherCustomerStore, "PAID", base.plusSeconds(80), 10)

        val recent = recentStoreQuery.top(customerId, 20)

        assertThat(recent.map { it.storeId })
            .containsExactly(reusedStore.storeId, tiedFirstStore.storeId, tiedSecondStore.storeId, acceptedStore.storeId)
        assertThat(recent.first().lastOrderedAt).isEqualTo(base.plusSeconds(40))
    }

    @Test
    fun `limit counts visible stores so a store hidden behind a stale one is still reachable`() {
        val customerId = UUID.randomUUID()
        val staleStore = createVisibleStore(customerId, "가장 최근이지만 비노출", uuid(11))
        val visibleStore = createVisibleStore(customerId, "그 다음 정상 매장", uuid(12))
        val now = clock.instant()
        insertOrder(staleStore, "PAID", now.minus(Duration.ofMinutes(1)), 1)
        insertOrder(visibleStore, "PAID", now.minus(Duration.ofMinutes(2)), 2)
        jdbc.update("DELETE FROM merchant_store_discovery_profile WHERE store_id = ?", staleStore.storeId)

        // The endpoint has no cursor. If limit were applied to raw candidates, the stale newest
        // store would consume the only slot and the visible store would be unreachable entirely.
        mockMvc
            .perform(get("/api/v1/me/recent-stores").param("limit", "1").with(customerJwt(customerId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].storeId").value(visibleStore.storeId.toString()))
    }

    @Test
    fun `recent store keyset continuation returns each store once across windows`() {
        val customerId = UUID.randomUUID()
        val base = clock.instant().minus(Duration.ofDays(1))
        val stores = (1..5).map { createVisibleStore(customerId, "연속 조회 매장 $it", uuid(20L + it)) }
        stores.forEachIndexed { index, store ->
            insertOrder(store, "PAID", base.plusSeconds((10 - index).toLong()), (index + 1).toLong())
        }

        val firstWindow = recentStoreQuery.top(customerId, 2)
        val secondWindow =
            recentStoreQuery.top(
                customerId,
                2,
                io.github.kdh949.beanflow.shared.api.CustomerRecentStoreCursor(
                    firstWindow.last().lastOrderedAt,
                    firstWindow.last().storeId,
                ),
            )

        assertThat(firstWindow.map { it.storeId }).containsExactly(stores[0].storeId, stores[1].storeId)
        assertThat(secondWindow.map { it.storeId }).containsExactly(stores[2].storeId, stores[3].storeId)
    }

    @Test
    fun `recent store compact limit rejects invalid values before any result is returned`() {
        val customerId = UUID.randomUUID()

        listOf("0", "21", "not-a-number").forEach { limit ->
            mockMvc
                .perform(get("/api/v1/me/recent-stores").param("limit", limit).with(customerJwt(customerId)))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }
    }

    private fun createVisibleStore(
        customerId: UUID,
        name: String,
        storeId: UUID = UUID.randomUUID(),
    ): OrderCreationFixture =
        OrderCreationFixture(customerId = customerId, storeId = storeId).also { fixture ->
            OrderCreationDatabaseFixture.insertBase(jdbc, fixture)
            jdbc.update(
                "UPDATE merchant_store_discovery_profile SET name = ? WHERE store_id = ?",
                name,
                fixture.storeId,
            )
        }

    private fun insertPickupSlot(
        storeId: UUID,
        now: Instant,
    ) {
        val startsAt = now.plus(Duration.ofDays(1))
        jdbc.update(
            """
            INSERT INTO fulfillment_pickup_slot (
                id, store_id, starts_at, ends_at, capacity, reserved_count, confirmed_count, version
            ) VALUES (?, ?, ?, ?, 4, 0, 0, 0)
            """.trimIndent(),
            UUID.randomUUID(),
            storeId,
            Timestamp.from(startsAt),
            Timestamp.from(startsAt.plus(Duration.ofMinutes(20))),
        )
    }

    private fun insertOrder(
        fixture: OrderCreationFixture,
        state: String,
        createdAt: Instant,
        sequence: Long,
    ) {
        val response = createOrderUseCase.create("recent-store-$sequence-${UUID.randomUUID()}", fixture.command())
        assertThat(response.status).isEqualTo(201)
        val orderId = orderId(response.body)
        when (state) {
            "PENDING_PAYMENT" -> {
                jdbc.update(
                    "UPDATE ordering_order SET created_at = ?, updated_at = ? WHERE id = ?",
                    Timestamp.from(createdAt),
                    Timestamp.from(createdAt),
                    orderId,
                )
                return
            }

            "EXPIRED" -> {
                jdbc.update(
                    """
                    UPDATE ordering_order
                       SET state = 'EXPIRED', reservation_expires_at = ?, created_at = ?, updated_at = ?, version = version + 1
                     WHERE id = ?
                    """.trimIndent(),
                    Timestamp.from(createdAt.plus(Duration.ofMinutes(5))),
                    Timestamp.from(createdAt),
                    Timestamp.from(createdAt),
                    orderId,
                )
                return
            }

            "CANCELLED" -> {
                jdbc.update(
                    """
                    UPDATE ordering_order
                       SET state = 'CANCELLED', reservation_expires_at = NULL,
                           cancelled_at = ?, cancellation_cause = 'PAYMENT_DECLINED',
                           created_at = ?, updated_at = ?, version = version + 1
                     WHERE id = ?
                    """.trimIndent(),
                    Timestamp.from(createdAt),
                    Timestamp.from(createdAt),
                    Timestamp.from(createdAt),
                    orderId,
                )
                return
            }
        }

        val paidAt = createdAt
        val acceptedAt =
            if (state in setOf("ACCEPTED", "PREPARING", "READY", "COMPLETED")) paidAt.plusSeconds(1) else null
        val preparingAt = if (state in setOf("PREPARING", "READY", "COMPLETED")) paidAt.plusSeconds(2) else null
        val readyAt = if (state in setOf("READY", "COMPLETED")) paidAt.plusSeconds(3) else null
        val completedAt = if (state == "COMPLETED") paidAt.plusSeconds(4) else null
        val rejectedAt = if (state == "REJECTED") paidAt.plusSeconds(1) else null
        jdbc.update(
            """
            UPDATE ordering_order
               SET state = ?, reservation_expires_at = NULL,
                   paid_at = ?, acceptance_warning_at = ?, acceptance_deadline_at = ?,
                   accepted_at = ?, rejected_at = ?, preparing_at = ?, ready_at = ?, completed_at = ?,
                   rejection_reason = ?,
                   created_at = ?, updated_at = ?, version = version + 1
             WHERE id = ?
            """.trimIndent(),
            state,
            Timestamp.from(paidAt),
            Timestamp.from(paidAt.plus(Duration.ofMinutes(2))),
            Timestamp.from(paidAt.plus(Duration.ofMinutes(3))),
            acceptedAt?.let(Timestamp::from),
            rejectedAt?.let(Timestamp::from),
            preparingAt?.let(Timestamp::from),
            readyAt?.let(Timestamp::from),
            completedAt?.let(Timestamp::from),
            if (state == "REJECTED") "Recent-store test rejection" else null,
            Timestamp.from(createdAt),
            Timestamp.from(createdAt),
            orderId,
        )
    }

    private fun orderId(responseBody: String): UUID =
        UUID.fromString(requireNotNull(Regex("\\\"orderId\\\":\\\"([^\\\"]+)\\\"").find(responseBody)).groupValues[1])

    private fun uuid(value: Long): UUID = UUID(0L, value)

    private fun customerJwt(customerId: UUID) =
        jwt()
            .jwt { it.subject(customerId.toString()).claim("roles", listOf("CUSTOMER")) }
            .authorities(SimpleGrantedAuthority("ROLE_CUSTOMER"))
}

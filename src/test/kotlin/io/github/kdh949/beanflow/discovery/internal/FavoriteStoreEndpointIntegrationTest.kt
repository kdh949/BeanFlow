package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.discovery.api.FavoriteStoreOperations
import io.github.kdh949.beanflow.discovery.api.MAX_FAVORITE_STORES
import io.github.kdh949.beanflow.shared.api.BrowserActorType
import io.github.kdh949.beanflow.shared.api.CreateLoginSession
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.LoginSessionCoordinator
import io.github.kdh949.beanflow.shared.api.StoreSearchIndexOperations
import jakarta.servlet.http.Cookie
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies startup, DDL, or committed state across a transaction boundary")
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
internal class FavoriteStoreEndpointIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var index: StoreSearchIndexOperations

    @Autowired
    private lateinit var clock: Clock

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var favorites: FavoriteStoreOperations

    @Autowired
    private lateinit var sessions: LoginSessionCoordinator

    private val fixture by lazy {
        StoreSearchIndexTestFixture(jdbc, index, TransactionTemplate(transactionManager))
    }

    @BeforeEach
    fun clearDatabase() {
        fixture.clear()
        jdbc.update("DELETE FROM spring_session")
        jdbc.update("DELETE FROM identity_customer_account WHERE login_id LIKE 'favorite%' ")
    }

    @Test
    fun `a customer sees only own favorites in newest-first order with current pickup availability`() {
        val customerId = createCustomer()
        val otherCustomerId = createCustomer()
        val availableStoreId = fixture.indexStore(name = "즐겨찾기 최신 매장")
        val closedStoreId = fixture.indexStore(name = "즐겨찾기 이전 매장", acceptingOrders = false)
        val otherStoreId = fixture.indexStore(name = "다른 고객 매장")
        val noLongerPublicStoreId = createStoreWithoutDiscoveryProfile()
        fixture.indexPickupSlot(availableStoreId, clock.instant())
        fixture.indexPickupSlot(closedStoreId, clock.instant())
        fixture.indexPickupSlot(otherStoreId, clock.instant())

        insertFavorite(customerId, closedStoreId, clock.instant().minus(Duration.ofMinutes(1)))
        insertFavorite(customerId, availableStoreId, clock.instant())
        insertFavorite(customerId, noLongerPublicStoreId, clock.instant().minus(Duration.ofMinutes(2)))
        insertFavorite(otherCustomerId, otherStoreId, clock.instant())

        mockMvc
            .perform(get("/api/v1/me/favorite-stores").with(customerJwt(customerId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].storeId").value(availableStoreId.toString()))
            .andExpect(jsonPath("$.items[0].name").value("즐겨찾기 최신 매장"))
            .andExpect(jsonPath("$.items[0].pickupAvailable").value(true))
            .andExpect(jsonPath("$.items[0].distanceMeters").doesNotExist())
            .andExpect(jsonPath("$.items[1].storeId").value(closedStoreId.toString()))
            .andExpect(jsonPath("$.items[1].name").value("즐겨찾기 이전 매장"))
            .andExpect(jsonPath("$.items[1].pickupAvailable").value(false))
        assertThat(favoriteCount(customerId, noLongerPublicStoreId)).isOne()
    }

    @Test
    fun `favorite list breaks equal creation times by store id`() {
        val customerId = createCustomer()
        val firstStoreId = UUID.fromString("00000000-0000-4000-8000-000000000001")
        val secondStoreId = UUID.fromString("00000000-0000-4000-8000-000000000002")
        createVisibleStore(firstStoreId, "동률 첫 매장")
        createVisibleStore(secondStoreId, "동률 둘째 매장")
        insertFavorite(customerId, secondStoreId, clock.instant())
        insertFavorite(customerId, firstStoreId, clock.instant())

        mockMvc
            .perform(get("/api/v1/me/favorite-stores").with(customerJwt(customerId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].storeId").value(firstStoreId.toString()))
            .andExpect(jsonPath("$.items[1].storeId").value(secondStoreId.toString()))
    }

    @Test
    fun `customer session writes require csrf are idempotent and never cross customer scope`() {
        val customerId = createCustomer()
        val otherCustomerId = createCustomer()
        val publicStoreId = fixture.indexStore(name = "즐겨찾기 명령 대상")
        val noLongerPublicStoreId = createStoreWithoutDiscoveryProfile()
        val session = customerSession(customerId)
        val csrf = issueCsrf()

        mockMvc
            .perform(putFavorite(publicStoreId).cookie(session))
            .andExpect(status().isForbidden)
        assertThat(favoriteCount(customerId, publicStoreId)).isZero()

        mockMvc
            .perform(putFavorite(publicStoreId).cookie(session, csrf).header(CSRF_HEADER, csrf.value))
            .andExpect(status().isNoContent)
        mockMvc
            .perform(putFavorite(publicStoreId).cookie(session, csrf).header(CSRF_HEADER, csrf.value))
            .andExpect(status().isNoContent)
        assertThat(favoriteCount(customerId, publicStoreId)).isOne()

        insertFavorite(otherCustomerId, publicStoreId, clock.instant())
        mockMvc
            .perform(deleteFavorite(publicStoreId).cookie(session, csrf).header(CSRF_HEADER, csrf.value))
            .andExpect(status().isNoContent)
        assertThat(favoriteCount(customerId, publicStoreId)).isZero()
        assertThat(favoriteCount(otherCustomerId, publicStoreId)).isOne()

        mockMvc
            .perform(deleteFavorite(UUID.randomUUID()).cookie(session, csrf).header(CSRF_HEADER, csrf.value))
            .andExpect(status().isNoContent)
        mockMvc
            .perform(putFavorite(noLongerPublicStoreId).cookie(session, csrf).header(CSRF_HEADER, csrf.value))
            .andExpect(status().isNotFound)
        assertThat(favoriteCount(customerId, noLongerPublicStoreId)).isZero()
    }

    @Test
    fun `simultaneous favorite adds converge to one customer store row`() {
        val customerId = createCustomer()
        val storeId = fixture.indexStore(name = "동시 즐겨찾기 대상")
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures =
                List(2) {
                    executor.submit {
                        start.await(10, TimeUnit.SECONDS)
                        favorites.add(customerId, storeId, clock.instant())
                    }
                }
            start.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertThat(favoriteCount(customerId, storeId)).isOne()
    }

    @Test
    fun `the cap rejects one more store while a repeat of an existing favorite stays successful`() {
        val customerId = createCustomer()
        val alreadyFavorited = fixture.indexStore(name = "이미 즐겨찾기인 매장")
        val session = customerSession(customerId)
        val csrf = issueCsrf()
        mockMvc
            .perform(putFavorite(alreadyFavorited).cookie(session, csrf).header(CSRF_HEADER, csrf.value))
            .andExpect(status().isNoContent)
        fillFavoritesTo(customerId, MAX_FAVORITE_STORES)
        val oneTooMany = fixture.indexStore(name = "상한 초과 매장")

        mockMvc
            .perform(putFavorite(oneTooMany).cookie(session, csrf).header(CSRF_HEADER, csrf.value))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("FAVORITE_STORE_LIMIT_EXCEEDED"))

        // Re-adding a store that is already a favorite creates no row, so the cap does not apply.
        mockMvc
            .perform(putFavorite(alreadyFavorited).cookie(session, csrf).header(CSRF_HEADER, csrf.value))
            .andExpect(status().isNoContent)

        assertThat(totalFavoriteCount(customerId)).isEqualTo(MAX_FAVORITE_STORES.toLong())
        assertThat(favoriteCount(customerId, oneTooMany)).isZero()
    }

    @Test
    fun `simultaneous adds at the cap boundary cannot settle above the cap`() {
        val customerId = createCustomer()
        fillFavoritesTo(customerId, MAX_FAVORITE_STORES - 1)
        val contenders = List(2) { fixture.indexStore(name = "상한 경계 동시 추가 $it") }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        // Counting before inserting is only safe under a per-customer lock. Without it both threads
        // read 199 and both insert, settling at 201.
        val outcomes =
            try {
                val futures =
                    contenders.map { storeId ->
                        executor.submit<String> {
                            start.await(10, TimeUnit.SECONDS)
                            try {
                                favorites.add(customerId, storeId, clock.instant())
                                "ADDED"
                            } catch (failure: DomainFailure) {
                                failure.code.name
                            }
                        }
                    }
                start.countDown()
                futures.map { it.get(20, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

        assertThat(outcomes).containsExactlyInAnyOrder("ADDED", "FAVORITE_STORE_LIMIT_EXCEEDED")
        assertThat(totalFavoriteCount(customerId)).isEqualTo(MAX_FAVORITE_STORES.toLong())
    }

    @Test
    fun `favorite persistence failure is reported as 503 instead of a successful no-op`() {
        val customerId = createCustomer()
        val storeId = fixture.indexStore(name = "즐겨찾기 장애 대상")
        val session = customerSession(customerId)
        val csrf = issueCsrf()
        jdbc.execute(
            """
            CREATE FUNCTION test_fail_favorite_store_insert() RETURNS trigger AS ${'$'}${'$'}
            BEGIN
              RAISE EXCEPTION 'forced favorite persistence failure';
              RETURN NEW;
            END;
            ${'$'}${'$'} LANGUAGE plpgsql
            """.trimIndent(),
        )
        jdbc.execute(
            "CREATE TRIGGER test_fail_favorite_store_insert BEFORE INSERT ON discovery_customer_favorite_store " +
                "FOR EACH ROW EXECUTE FUNCTION test_fail_favorite_store_insert()",
        )
        try {
            mockMvc
                .perform(putFavorite(storeId).cookie(session, csrf).header(CSRF_HEADER, csrf.value))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS test_fail_favorite_store_insert ON discovery_customer_favorite_store")
            jdbc.execute("DROP FUNCTION IF EXISTS test_fail_favorite_store_insert()")
        }

        assertThat(favoriteCount(customerId, storeId)).isZero()
    }

    private fun createCustomer(): UUID {
        val customerId = UUID.randomUUID()
        val now = Timestamp.from(clock.instant())
        val loginId = "favorite${customerId.toString().replace("-", "").take(12)}"
        jdbc.update(
            """
            INSERT INTO identity_customer_account (
                id, login_id, password_hash, credential_version, display_name,
                state, locked_until, created_at, updated_at, version
            ) VALUES (?, ?, ?, 0, ?, 'ACTIVE', NULL, ?, ?, 0)
            """.trimIndent(),
            customerId,
            loginId,
            "test-password-hash",
            "Favorite test customer",
            now,
            now,
        )
        return customerId
    }

    private fun insertFavorite(
        customerId: UUID,
        storeId: UUID,
        createdAt: java.time.Instant,
    ) {
        jdbc.update(
            "INSERT INTO discovery_customer_favorite_store (customer_id, store_id, created_at) VALUES (?, ?, ?)",
            customerId,
            storeId,
            Timestamp.from(createdAt),
        )
    }

    private fun createStoreWithoutDiscoveryProfile(): UUID =
        UUID.randomUUID().also { storeId ->
            jdbc.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                storeId,
            )
        }

    private fun createVisibleStore(
        storeId: UUID,
        name: String,
    ) {
        jdbc.update(
            "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
            storeId,
        )
        jdbc.update(
            """
            INSERT INTO merchant_store_discovery_profile (store_id, name, location, region_code)
            VALUES (?, ?, ST_SetSRID(ST_MakePoint(127.0361, 37.5006), 4326)::geography, '1168010100')
            """.trimIndent(),
            storeId,
            name,
        )
    }

    /** Tops the customer up to [target] favorites without going through the endpoint. */
    private fun fillFavoritesTo(
        customerId: UUID,
        target: Int,
    ) {
        val missing = target - totalFavoriteCount(customerId).toInt()
        if (missing <= 0) return
        jdbc.update(
            """
            WITH created AS (
                INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version)
                SELECT gen_random_uuid(), true, true, 0 FROM generate_series(1, ?)
                RETURNING id
            )
            INSERT INTO discovery_customer_favorite_store (customer_id, store_id, created_at)
            SELECT ?, id, ? FROM created
            """.trimIndent(),
            missing,
            customerId,
            Timestamp.from(clock.instant().minus(Duration.ofDays(1))),
        )
    }

    private fun totalFavoriteCount(customerId: UUID): Long =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM discovery_customer_favorite_store WHERE customer_id = ?",
                Long::class.java,
                customerId,
            ),
        )

    private fun favoriteCount(
        customerId: UUID,
        storeId: UUID,
    ): Long =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM discovery_customer_favorite_store WHERE customer_id = ? AND store_id = ?",
                Long::class.java,
                customerId,
                storeId,
            ),
        )

    private fun customerSession(customerId: UUID): Cookie {
        val handle =
            requireNotNull(
                TransactionTemplate(transactionManager).execute {
                    sessions.create(
                        CreateLoginSession(
                            actorType = BrowserActorType.CUSTOMER,
                            actorId = customerId,
                            authenticatedAtEpochMilli = clock.millis(),
                            credentialVersion = 0,
                        ),
                    )
                },
            )
        return Cookie(
            "BEANFLOW_CUSTOMER_SESSION",
            Base64.getEncoder().encodeToString(handle.sessionId.toByteArray(Charsets.UTF_8)),
        )
    }

    private fun issueCsrf(): Cookie =
        requireNotNull(
            mockMvc
                .perform(get("/api/v1/auth/customer/csrf"))
                .andExpect(status().isNoContent)
                .andReturn()
                .response
                .getCookie("BEANFLOW_CUSTOMER_XSRF"),
        )

    private fun putFavorite(storeId: UUID) =
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .put("/api/v1/me/favorite-stores/{storeId}", storeId)

    private fun deleteFavorite(storeId: UUID) =
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .delete("/api/v1/me/favorite-stores/{storeId}", storeId)

    private fun customerJwt(customerId: UUID): RequestPostProcessor =
        jwt()
            .jwt { it.subject(customerId.toString()).claim("roles", listOf("CUSTOMER")) }
            .authorities(SimpleGrantedAuthority("ROLE_CUSTOMER"))

    private companion object {
        const val CSRF_HEADER = "X-BEANFLOW-CSRF"
    }
}

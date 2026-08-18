package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.fulfillment.api.PickupReservationOperations
import io.github.kdh949.beanflow.fulfillment.api.ReservePickupCommand
import io.github.kdh949.beanflow.merchant.internal.MAX_STORE_MENUS
import io.github.kdh949.beanflow.merchant.internal.MAX_STORE_MENU_OPTIONS
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
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
import java.time.Instant
import java.util.UUID

/**
 * Menu and pickup slot read contracts against PostgreSQL.
 *
 * Both endpoints project current owner state. The suite pins the public shape, the availability
 * projection, cross-store isolation, the slot window and the failure mapping: a missing store is
 * `404`, a legitimately empty catalogue is `200`, and a persistence failure is `503` rather than
 * `404` or an empty list.
 */
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies startup, DDL, or committed state across a transaction boundary")
@SpringBootTest(
    properties = [
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
    ],
)
internal class DiscoveryStoreCatalogIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val pickupReservations: PickupReservationOperations,
        private val clock: Clock,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)
        private val storeId = UUID.fromString("30000000-0000-0000-0000-000000000001")
        private val otherStoreId = UUID.fromString("30000000-0000-0000-0000-000000000002")

        @BeforeEach
        fun cleanDatabase() {
            dropFailureView("merchant_menu")
            dropFailureView("fulfillment_pickup_slot")
            jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    merchant_menu_configuration_requirement,
                    merchant_menu_configuration,
                    merchant_menu_option,
                    merchant_menu,
                    fulfillment_pickup_reservation,
                    fulfillment_pickup_slot,
                    merchant_store_discovery_profile,
                    merchant_store
                CASCADE
                """.trimIndent(),
            )
            insertStore(storeId)
            insertStore(otherStoreId)
        }

        @AfterEach
        fun removeFailureViews() {
            dropFailureView("merchant_menu")
            dropFailureView("fulfillment_pickup_slot")
        }

        @Test
        fun `menus project current owner availability for menus and options without exposing write fields`() {
            val americano = insertMenu(storeId, "Americano", 4_500, available = true)
            val seasonal = insertMenu(storeId, "Zebra latte", 6_000, available = false)
            insertOption(americano, "Extra shot", 500, available = true)
            insertOption(americano, "Oat milk", 800, available = false)
            insertMenu(otherStoreId, "Another store latte", 5_000, available = true)

            mockMvc
                .perform(get(menuPath(storeId)).with(customerJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].menuId").value(americano.toString()))
                .andExpect(jsonPath("$.items[0].name").value("Americano"))
                .andExpect(jsonPath("$.items[0].basePriceKrw").value(4_500))
                .andExpect(jsonPath("$.items[0].currency").value("KRW"))
                .andExpect(jsonPath("$.items[0].available").value(true))
                .andExpect(jsonPath("$.items[0].options.length()").value(2))
                .andExpect(jsonPath("$.items[0].options[0].name").value("Extra shot"))
                .andExpect(jsonPath("$.items[0].options[0].additionalPriceKrw").value(500))
                .andExpect(jsonPath("$.items[0].options[0].available").value(true))
                .andExpect(jsonPath("$.items[0].options[1].name").value("Oat milk"))
                .andExpect(jsonPath("$.items[0].options[1].available").value(false))
                // A sold-out menu stays in the list with a false flag; it is never reported available.
                .andExpect(jsonPath("$.items[1].menuId").value(seasonal.toString()))
                .andExpect(jsonPath("$.items[1].available").value(false))
                .andExpect(jsonPath("$.items[1].options.length()").value(0))
        }

        @Test
        fun `menu response exposes exactly the contract fields`() {
            val menuId = insertMenu(storeId, "Americano", 4_500, available = true)
            insertOption(menuId, "Extra shot", 500, available = true)

            val body =
                mockMvc
                    .perform(get(menuPath(storeId)).with(customerJwt()))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString

            assertThat(fieldNames(body, "$.items[0]"))
                .containsExactlyInAnyOrder("menuId", "name", "basePriceKrw", "currency", "available", "options")
            assertThat(fieldNames(body, "$.items[0].options[0]"))
                .containsExactlyInAnyOrder("optionId", "name", "additionalPriceKrw", "available")
            assertThat(body).doesNotContain("storeId", "version", "menu_id")
        }

        @Test
        fun `a store with no menus is an empty list and an unknown store is not found`() {
            mockMvc
                .perform(get(menuPath(storeId)).with(customerJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(0))
            mockMvc
                .perform(get(menuPath(UUID.randomUUID())).with(customerJwt()))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            mockMvc
                .perform(get(slotPath(storeId)).with(customerJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(0))
            mockMvc
                .perform(get(slotPath(UUID.randomUUID())).with(customerJwt()))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        }

        @Test
        fun `pickup slots return reservable windows in time order with non negative remaining capacity`() {
            val now = clock.instant()
            val ended = insertSlot(storeId, now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)), capacity = 5)
            // Already started but not yet finished. It is excluded because BR-05 no longer allows
            // reserving it, so listing it would advertise a slot the write path would reject.
            val inProgress = insertSlot(storeId, now.minus(Duration.ofMinutes(5)), now.plus(Duration.ofMinutes(25)), capacity = 5)
            val soon = insertSlot(storeId, now.plus(Duration.ofMinutes(30)), now.plus(Duration.ofMinutes(60)), capacity = 4)
            val later = insertSlot(storeId, now.plus(Duration.ofHours(3)), now.plus(Duration.ofHours(4)), capacity = 2)
            val full =
                insertSlot(
                    storeId,
                    now.plus(Duration.ofHours(1)),
                    now.plus(Duration.ofHours(2)),
                    capacity = 3,
                    reservedCount = 1,
                    confirmedCount = 2,
                )
            insertSlot(otherStoreId, now.plus(Duration.ofMinutes(10)), now.plus(Duration.ofMinutes(40)), capacity = 9)

            mockMvc
                .perform(get(slotPath(storeId)).with(customerJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].pickupSlotId").value(soon.toString()))
                .andExpect(jsonPath("$.items[0].remainingCapacity").value(4))
                // Reserved plus confirmed exactly exhausts capacity, so the arithmetic itself is zero.
                // Nothing clamps it; a negative value would be a corrupted counter and fails the read.
                .andExpect(jsonPath("$.items[1].pickupSlotId").value(full.toString()))
                .andExpect(jsonPath("$.items[1].remainingCapacity").value(0))
                .andExpect(jsonPath("$.items[2].pickupSlotId").value(later.toString()))
                .andExpect(jsonPath("$.items[2].remainingCapacity").value(2))

            val body =
                mockMvc
                    .perform(get(slotPath(storeId)).with(customerJwt()))
                    .andReturn()
                    .response.contentAsString
            assertThat(body).doesNotContain(ended.toString())
            assertThat(body).doesNotContain(inProgress.toString())
            assertThat(fieldNames(body, "$.items[0]"))
                .containsExactlyInAnyOrder("pickupSlotId", "startsAt", "endsAt", "remainingCapacity")
        }

        @Test
        fun `the store read names the store and reports whether pickup is actually reservable`() {
            insertDiscoveryProfile(storeId, "BeanFlow Yeouido")
            insertSlot(storeId, clock.instant().plus(Duration.ofMinutes(30)), clock.instant().plus(Duration.ofMinutes(60)), capacity = 2)

            val body =
                mockMvc
                    .perform(get(storePath(storeId)).with(customerJwt()))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.storeId").value(storeId.toString()))
                    .andExpect(jsonPath("$.name").value("BeanFlow Yeouido"))
                    .andExpect(jsonPath("$.pickupAvailable").value(true))
                    .andReturn()
                    .response.contentAsString

            // This read takes no coordinate, so it must not carry a distance the caller could believe.
            assertThat(fieldNames(body, "$")).containsExactlyInAnyOrder("storeId", "name", "pickupAvailable")
        }

        @Test
        fun `a store with no reservable slot is named but not advertised as pickup available`() {
            insertDiscoveryProfile(storeId, "BeanFlow Yeouido")

            mockMvc
                .perform(get(storePath(storeId)).with(customerJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.name").value("BeanFlow Yeouido"))
                .andExpect(jsonPath("$.pickupAvailable").value(false))
        }

        @Test
        fun `a store the customer must not see is reported the same as one that does not exist`() {
            // `storeId` exists as an owner row but has no public discovery profile.
            mockMvc
                .perform(get(storePath(storeId)).with(customerJwt()))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.name").doesNotExist())
            mockMvc
                .perform(get(storePath(UUID.randomUUID())).with(customerJwt()))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        }

        @Test
        fun `the store read requires a customer session`() {
            insertDiscoveryProfile(storeId, "BeanFlow Yeouido")

            mockMvc
                .perform(get(storePath(storeId)))
                .andExpect(status().isUnauthorized)
        }

        @Test
        fun `a catalogue past the published bound fails explicitly instead of returning truncated rows`() {
            jdbcTemplate.update(
                """
                INSERT INTO merchant_menu (id, store_id, name, base_price_krw, available, version)
                SELECT gen_random_uuid(), ?, 'Menu ' || lpad(i::text, 6, '0'), 1000, true, 0
                  FROM generate_series(1, ?) AS i
                """.trimIndent(),
                storeId,
                MAX_STORE_MENUS + 1,
            )

            // Truncating to 1,000 would look like a complete catalogue to the caller.
            mockMvc
                .perform(get(menuPath(storeId)).with(customerJwt()))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.items").doesNotExist())
        }

        @Test
        fun `a pickup slot list past the published bound fails instead of returning a partial seven day window`() {
            val now = clock.instant()
            jdbcTemplate.update(
                """
                INSERT INTO fulfillment_pickup_slot (
                    id, store_id, starts_at, ends_at, capacity, reserved_count, confirmed_count, version
                )
                SELECT gen_random_uuid(), ?, ?::timestamptz + i * interval '5 minutes',
                       ?::timestamptz + i * interval '5 minutes' + interval '4 minutes', 1, 0, 0, 0
                  FROM generate_series(1, 1001) AS i
                """.trimIndent(),
                storeId,
                Timestamp.from(now),
                Timestamp.from(now),
            )

            mockMvc
                .perform(get(slotPath(storeId)).with(customerJwt()))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.items").doesNotExist())
        }

        @Test
        fun `an option count past the published bound fails explicitly`() {
            val menuId = insertMenu(storeId, "Americano", 4_500, available = true)
            jdbcTemplate.update(
                """
                INSERT INTO merchant_menu_option (id, menu_id, name, additional_price_krw, available)
                SELECT gen_random_uuid(), ?, 'Option ' || lpad(i::text, 6, '0'), 100, true
                  FROM generate_series(1, ?) AS i
                """.trimIndent(),
                menuId,
                MAX_STORE_MENU_OPTIONS + 1,
            )

            mockMvc
                .perform(get(menuPath(storeId)).with(customerJwt()))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.items").doesNotExist())
        }

        @Test
        fun `the slot list reaches seven days ahead and stops there`() {
            val now = clock.instant()
            val inside = insertSlot(storeId, now.plus(Duration.ofDays(7)).minusSeconds(60), now.plus(Duration.ofDays(7)), capacity = 1)
            val outside =
                insertSlot(
                    storeId,
                    now.plus(Duration.ofDays(7)).plusSeconds(60),
                    now.plus(Duration.ofDays(7)).plusSeconds(600),
                    capacity = 1,
                )

            val body =
                mockMvc
                    .perform(get(slotPath(storeId)).with(customerJwt()))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andReturn()
                    .response.contentAsString

            // The bound is a horizon, not a row limit: everything inside it is returned in full.
            assertThat(body).contains(inside.toString())
            assertThat(body).doesNotContain(outside.toString())
        }

        @Test
        fun `a store that cannot take pickup orders lists no slot even though slots exist`() {
            val closed = UUID.fromString("30000000-0000-0000-0000-000000000003")
            val pickupDisabled = UUID.fromString("30000000-0000-0000-0000-000000000004")
            insertStore(closed, acceptingOrders = false)
            insertStore(pickupDisabled, pickupEnabled = false)
            val now = clock.instant()
            listOf(closed, pickupDisabled).forEach {
                insertSlot(it, now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(2)), capacity = 5)
                insertMenu(it, "Americano", 4_500, available = true)
            }

            listOf(closed, pickupDisabled).forEach { unavailable ->
                // Every one of these slots would be rejected at order creation, so none is listed.
                mockMvc
                    .perform(get(slotPath(unavailable)).with(customerJwt()))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.items.length()").value(0))
                // The store exists, so this is 200 with an empty list, never 404.
                mockMvc
                    .perform(get(menuPath(unavailable)).with(customerJwt()))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.items.length()").value(1))
            }
        }

        @Test
        fun `a corrupted counter is reported as 503 instead of a clamped remaining capacity`() {
            val now = clock.instant()
            insertSlot(storeId, now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(2)), capacity = 1)
            mockMvc
                .perform(get(slotPath(storeId)).with(customerJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items[0].remainingCapacity").value(1))

            // The table's own CHECK keeps reserved + confirmed <= capacity, so an over-committed
            // counter can only be simulated. The query must not clamp it into a plausible zero.
            installCorruptedCounterView()
            try {
                mockMvc
                    .perform(get(slotPath(storeId)).with(customerJwt()))
                    .andExpect(status().isServiceUnavailable)
                    .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                    .andExpect(jsonPath("$.items").doesNotExist())
            } finally {
                dropFailureView("fulfillment_pickup_slot")
            }

            mockMvc
                .perform(get(slotPath(storeId)).with(customerJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items[0].remainingCapacity").value(1))
        }

        @Test
        fun `a reservation committed after a read is visible to the next read`() {
            val now = clock.instant()
            val slotId = insertSlot(storeId, now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(2)), capacity = 2)

            mockMvc
                .perform(get(slotPath(storeId)).with(customerJwt()))
                .andExpect(jsonPath("$.items[0].remainingCapacity").value(2))

            // reserve() is MANDATORY: it participates in the order-creation transaction.
            transactions.executeWithoutResult {
                pickupReservations.reserve(
                    ReservePickupCommand(
                        orderId = UUID.randomUUID(),
                        storeId = storeId,
                        pickupSlotId = slotId,
                        expiresAt = now.plus(Duration.ofMinutes(5)),
                        sourceReference = "catalog-test:${UUID.randomUUID()}",
                    ),
                )
            }

            // The earlier response was owner state at read time, never a reservation guarantee.
            mockMvc
                .perform(get(slotPath(storeId)).with(customerJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items[0].remainingCapacity").value(1))
        }

        @Test
        fun `every authenticated role may read the catalogue and anonymous access is rejected`() {
            insertMenu(storeId, "Americano", 4_500, available = true)
            insertSlot(storeId, clock.instant().plus(Duration.ofHours(1)), clock.instant().plus(Duration.ofHours(2)), capacity = 1)

            listOf("CUSTOMER", "STORE_OWNER", "STORE_STAFF", "PLATFORM_OPERATOR", "SETTLEMENT_OPERATOR").forEach { role ->
                mockMvc.perform(get(menuPath(storeId)).with(roleJwt(role))).andExpect(status().isOk)
                mockMvc.perform(get(slotPath(storeId)).with(roleJwt(role))).andExpect(status().isOk)
            }
            mockMvc.perform(get(menuPath(storeId))).andExpect(status().isUnauthorized)
            mockMvc.perform(get(slotPath(storeId))).andExpect(status().isUnauthorized)
        }

        @Test
        fun `a persistence failure is 503 and never an empty list or a not found`() {
            insertMenu(storeId, "Americano", 4_500, available = true)
            insertSlot(storeId, clock.instant().plus(Duration.ofHours(1)), clock.instant().plus(Duration.ofHours(2)), capacity = 1)

            installFailureView("merchant_menu")
            mockMvc
                .perform(get(menuPath(storeId)).with(customerJwt()))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.items").doesNotExist())
            dropFailureView("merchant_menu")

            installFailureView("fulfillment_pickup_slot")
            mockMvc
                .perform(get(slotPath(storeId)).with(customerJwt()))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.items").doesNotExist())
            dropFailureView("fulfillment_pickup_slot")

            mockMvc
                .perform(get(menuPath(storeId)).with(customerJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(1))
            mockMvc
                .perform(get(slotPath(storeId)).with(customerJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(1))
        }

        private fun storePath(storeId: UUID) = "/api/v1/stores/$storeId"

        private fun menuPath(storeId: UUID) = "/api/v1/stores/$storeId/menus"

        private fun slotPath(storeId: UUID) = "/api/v1/stores/$storeId/pickup-slots"

        private fun customerJwt(): RequestPostProcessor = roleJwt("CUSTOMER")

        private fun roleJwt(role: String): RequestPostProcessor =
            jwt()
                .jwt { it.subject(UUID.randomUUID().toString()).claim("roles", listOf(role)) }
                .authorities(SimpleGrantedAuthority("ROLE_$role"))

        private fun insertStore(
            storeId: UUID,
            acceptingOrders: Boolean = true,
            pickupEnabled: Boolean = true,
        ) = jdbcTemplate.update(
            "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, ?, ?, 0)",
            storeId,
            acceptingOrders,
            pickupEnabled,
        )

        /** A store is only publicly visible once its owner-verified discovery profile exists. */
        private fun insertDiscoveryProfile(
            storeId: UUID,
            name: String,
        ) = jdbcTemplate.update(
            """
            INSERT INTO merchant_store_discovery_profile (store_id, name, location, region_code)
            VALUES (?, ?, ST_SetSRID(ST_MakePoint(126.9245, 37.5219), 4326)::geography, '1168010100')
            """.trimIndent(),
            storeId,
            name,
        )

        private fun insertMenu(
            storeId: UUID,
            name: String,
            basePriceKrw: Long,
            available: Boolean,
        ): UUID =
            UUID.randomUUID().also { menuId ->
                jdbcTemplate.update(
                    """
                    INSERT INTO merchant_menu (id, store_id, name, base_price_krw, available, version)
                    VALUES (?, ?, ?, ?, ?, 0)
                    """.trimIndent(),
                    menuId,
                    storeId,
                    name,
                    basePriceKrw,
                    available,
                )
            }

        private fun insertOption(
            menuId: UUID,
            name: String,
            additionalPriceKrw: Long,
            available: Boolean,
        ): UUID =
            UUID.randomUUID().also { optionId ->
                jdbcTemplate.update(
                    """
                    INSERT INTO merchant_menu_option (id, menu_id, name, additional_price_krw, available)
                    VALUES (?, ?, ?, ?, ?)
                    """.trimIndent(),
                    optionId,
                    menuId,
                    name,
                    additionalPriceKrw,
                    available,
                )
            }

        private fun insertSlot(
            storeId: UUID,
            startsAt: Instant,
            endsAt: Instant,
            capacity: Long,
            reservedCount: Long = 0,
            confirmedCount: Long = 0,
        ): UUID =
            UUID.randomUUID().also { slotId ->
                jdbcTemplate.update(
                    """
                    INSERT INTO fulfillment_pickup_slot (
                        id, store_id, starts_at, ends_at, capacity, reserved_count, confirmed_count, version
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                    """.trimIndent(),
                    slotId,
                    storeId,
                    Timestamp.from(startsAt),
                    Timestamp.from(endsAt),
                    capacity,
                    reservedCount,
                    confirmedCount,
                )
            }

        private fun fieldNames(
            body: String,
            path: String,
        ): Set<String> {
            val node =
                com.jayway.jsonpath.JsonPath
                    .read<Map<String, Any?>>(body, path)
            return node.keys
        }

        /** Replaces an owner table with a raising view so the read fails the way a broken table would. */
        private fun installFailureView(table: String) {
            jdbcTemplate.execute("ALTER TABLE $table RENAME TO ${table}_actual")
            jdbcTemplate.execute(
                """
                CREATE FUNCTION test_reject_$table()
                RETURNS SETOF ${table}_actual LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN RAISE EXCEPTION USING ERRCODE = '58030', MESSAGE = 'injected $table failure'; END;
                ${'$'}${'$'}
                """.trimIndent(),
            )
            jdbcTemplate.execute("CREATE VIEW $table AS SELECT * FROM test_reject_$table()")
        }

        /** Swaps the slot table for a view whose confirmed count exceeds capacity. */
        private fun installCorruptedCounterView() {
            jdbcTemplate.execute("ALTER TABLE fulfillment_pickup_slot RENAME TO fulfillment_pickup_slot_actual")
            jdbcTemplate.execute(
                """
                CREATE VIEW fulfillment_pickup_slot AS
                SELECT id, store_id, starts_at, ends_at, capacity, reserved_count,
                       confirmed_count + capacity + 1 AS confirmed_count, version
                  FROM fulfillment_pickup_slot_actual
                """.trimIndent(),
            )
        }

        private fun dropFailureView(table: String) {
            jdbcTemplate.execute(
                """
                DO ${'$'}${'$'}
                BEGIN
                    IF (SELECT relkind FROM pg_class WHERE oid = to_regclass('$table')) = 'v' THEN
                        DROP VIEW $table;
                    END IF;
                    DROP FUNCTION IF EXISTS test_reject_$table();
                    IF to_regclass('${table}_actual') IS NOT NULL THEN
                        ALTER TABLE ${table}_actual RENAME TO $table;
                    END IF;
                END
                ${'$'}${'$'}
                """.trimIndent(),
            )
        }
    }

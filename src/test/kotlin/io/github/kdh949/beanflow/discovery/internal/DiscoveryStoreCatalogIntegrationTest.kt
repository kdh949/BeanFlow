package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.fulfillment.api.PickupReservationOperations
import io.github.kdh949.beanflow.fulfillment.api.ReservePickupCommand
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
import org.springframework.test.annotation.DirtiesContext
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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
        fun `pickup slots return open windows in time order with non negative remaining capacity`() {
            val now = clock.instant()
            val ended = insertSlot(storeId, now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)), capacity = 5)
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
                // Capacity is fully taken by reserved plus confirmed counts, so the slot is zero, not negative.
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
            assertThat(fieldNames(body, "$.items[0]"))
                .containsExactlyInAnyOrder("pickupSlotId", "startsAt", "endsAt", "remainingCapacity")
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

        private fun menuPath(storeId: UUID) = "/api/v1/stores/$storeId/menus"

        private fun slotPath(storeId: UUID) = "/api/v1/stores/$storeId/pickup-slots"

        private fun customerJwt(): RequestPostProcessor = roleJwt("CUSTOMER")

        private fun roleJwt(role: String): RequestPostProcessor =
            jwt()
                .jwt { it.subject(UUID.randomUUID().toString()).claim("roles", listOf(role)) }
                .authorities(SimpleGrantedAuthority("ROLE_$role"))

        private fun insertStore(storeId: UUID) =
            jdbcTemplate.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                storeId,
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

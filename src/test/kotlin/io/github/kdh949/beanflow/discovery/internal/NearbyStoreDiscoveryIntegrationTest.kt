package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.discovery.api.SearchNearbyStoresCommand
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import io.github.kdh949.beanflow.tamperSignedCursorSignature
import io.micrometer.core.instrument.MeterRegistry
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
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * The nearby vertical slice against PostgreSQL 17 with PostGIS 3.5.
 *
 * The fixture uses synthetic coordinates only. Beyond ordering and paging, the suite asserts the
 * privacy invariant: the customer coordinate must not appear in the response, an error body, a
 * metric tag or an audit record, and a PostGIS failure must surface as 503 rather than an empty
 * successful page.
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
internal class NearbyStoreDiscoveryIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val meterRegistry: MeterRegistry,
        private val signedCursorCodec: SignedCursorCodec,
        private val clock: Clock,
    ) {
        @BeforeEach
        fun cleanDatabase() {
            dropSpatialFailureTrigger()
            jdbcTemplate.execute(
                "TRUNCATE TABLE fulfillment_pickup_slot, merchant_store_discovery_profile, " +
                    "merchant_store, operations_audit_record CASCADE",
            )
        }

        @AfterEach
        fun removeSpatialFailureTrigger() = dropSpatialFailureTrigger()

        @Test
        fun `nearby returns pickup-capable stores by distance then store ID with floored integer meters`() {
            // 0.001 degrees of longitude at latitude 37.5 is roughly 88 metres.
            insertStore(store(1), "Near cafe", longitude = 127.0, latitude = 37.5)
            insertStore(store(2), "Far cafe", longitude = 127.004, latitude = 37.5)
            insertStore(store(3), "Closed cafe", longitude = 127.0005, latitude = 37.5, acceptingOrders = false)
            insertStore(store(4), "Pickup disabled cafe", longitude = 127.0006, latitude = 37.5, pickupEnabled = false)
            insertPickupSlot(store(1))

            mockMvc
                .perform(nearby(radiusMeters = "1000"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].storeId").value(store(1).toString()))
                .andExpect(jsonPath("$.items[0].name").value("Near cafe"))
                .andExpect(jsonPath("$.items[0].distanceMeters").value(0))
                .andExpect(jsonPath("$.items[0].open").value(true))
                .andExpect(jsonPath("$.items[0].pickupAvailable").value(true))
                .andExpect(jsonPath("$.items[1].storeId").value(store(2).toString()))
                .andExpect(jsonPath("$.items[1].distanceMeters").value(353))
                // 슬롯이 없는 매장은 결과에 남되 픽업 불가로 표시된다. Milestone 6 이전에는
                // `acceptingOrders && pickupEnabled`라서 항상 true였다.
                .andExpect(jsonPath("$.items[1].pickupAvailable").value(false))
                .andExpect(jsonPath("$.items[0].distanceMicrometers").doesNotExist())
                .andExpect(jsonPath("$.items[0].location").doesNotExist())
                .andExpect(jsonPath("$.page.nextCursor").doesNotExist())
        }

        @Test
        fun `pickupAvailable means a reservable slot exists rather than owner pickup state`() {
            insertStore(store(1), "Reservable cafe", longitude = 127.0, latitude = 37.5)
            insertStore(store(2), "Fully booked cafe", longitude = 127.001, latitude = 37.5)
            insertStore(store(3), "Already started cafe", longitude = 127.002, latitude = 37.5)
            insertStore(store(4), "Beyond horizon cafe", longitude = 127.003, latitude = 37.5)
            insertPickupSlot(store(1))
            insertPickupSlot(store(2), capacity = 2, reserved = 1, confirmed = 1)
            insertPickupSlot(store(3), startsIn = Duration.ofMinutes(-30))
            insertPickupSlot(store(4), startsIn = Duration.ofDays(8))

            mockMvc
                .perform(nearby(radiusMeters = "1000"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[0].pickupAvailable").value(true))
                .andExpect(jsonPath("$.items[1].pickupAvailable").value(false))
                .andExpect(jsonPath("$.items[2].pickupAvailable").value(false))
                .andExpect(jsonPath("$.items[3].pickupAvailable").value(false))
        }

        @Test
        fun `pickupAvailable filter keeps only stores with a reservable slot`() {
            insertStore(store(1), "Slotless cafe", longitude = 127.0, latitude = 37.5)
            insertStore(store(2), "Reservable cafe", longitude = 127.001, latitude = 37.5)
            insertPickupSlot(store(2))

            mockMvc
                .perform(nearby(radiusMeters = "1000", pickupAvailable = "true"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].storeId").value(store(2).toString()))

            // 필터를 끄면 슬롯 없는 매장도 남는다. 두 필터는 독립이다(ADR-103 A6).
            mockMvc
                .perform(nearby(radiusMeters = "1000", pickupAvailable = "false"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(2))
        }

        @Test
        fun `a non boolean pickupAvailable is rejected before the spatial query runs`() {
            mockMvc
                .perform(nearby(radiusMeters = "1000", pickupAvailable = "yes"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }

        /**
         * The availability filter runs after the spatial query, so a page can be short or empty
         * while candidates remain. The cursor must then anchor to the last **examined** candidate:
         * anchoring to the last returned row would skip every rejected store after it.
         */
        @Test
        fun `an availability filtered page pages on without gaps or duplicates`() {
            val ordered = (0 until 6).map { store(20 + it) }
            ordered.forEachIndexed { index, storeId ->
                insertStore(storeId, "Cafe $index", longitude = 127.0 + index * 0.001, latitude = 37.5)
            }
            // 가용 매장은 처음과 마지막뿐이다. 가운데 넷은 필터에 걸려 page를 짧게 만든다.
            insertPickupSlot(ordered.first())
            insertPickupSlot(ordered.last())

            val firstBody =
                mockMvc
                    .perform(nearby(radiusMeters = "5000", pickupAvailable = "true", limit = "2"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response
                    .contentAsString
            // 검사한 2개 중 가용은 1개다. 뒤에 후보가 남았으므로 짧은 page와 cursor가 함께 나온다.
            assertThat(storeIds(firstBody)).containsExactly(ordered.first().toString())

            val visited = mutableListOf<String>()
            visited += storeIds(firstBody)
            var cursor: String? = nextCursor(firstBody)
            var pages = 1
            while (cursor != null) {
                val body =
                    mockMvc
                        .perform(nearby(radiusMeters = "5000", pickupAvailable = "true", limit = "2", cursor = cursor))
                        .andExpect(status().isOk)
                        .andReturn()
                        .response
                        .contentAsString
                visited += storeIds(body)
                cursor = Regex("\"nextCursor\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                pages++
            }

            assertThat(visited).containsExactly(ordered.first().toString(), ordered.last().toString())
            // 6개 후보를 2개씩 검사하므로 3쪽이다. 페이지가 비어도 scan은 전진한다.
            assertThat(pages).isEqualTo(3)
        }

        @Test
        fun `a cursor issued without the availability filter is rejected once the filter is on`() {
            insertStore(store(1), "Near cafe", longitude = 127.0, latitude = 37.5)
            insertStore(store(2), "Far cafe", longitude = 127.001, latitude = 37.5)
            insertPickupSlot(store(1))
            insertPickupSlot(store(2))
            val unfiltered =
                nextCursor(
                    mockMvc
                        .perform(nearby(radiusMeters = "1000", limit = "1"))
                        .andExpect(status().isOk)
                        .andReturn()
                        .response
                        .contentAsString,
                )

            mockMvc
                .perform(nearby(radiusMeters = "1000", pickupAvailable = "true", limit = "1", cursor = unfiltered))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }

        @Test
        fun `stores at the same distance are ordered by store ID and page without gaps or duplicates`() {
            // PostgreSQL orders `uuid` bytewise, which matches the canonical lowercase hex string
            // order rather than Java's signed-long `UUID.compareTo`.
            val identical = (0 until 5).map { store(10 + it) }.sortedBy(UUID::toString)
            identical.forEach { storeId -> insertStore(storeId, "Tie cafe", longitude = 127.001, latitude = 37.5) }

            val first =
                mockMvc
                    .perform(nearby(limit = "2"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.items.length()").value(2))
                    .andExpect(jsonPath("$.page.nextCursor").isString)
                    .andReturn()
            val second =
                mockMvc
                    .perform(nearby(limit = "2", cursor = nextCursor(first.response.contentAsString)))
                    .andExpect(status().isOk)
                    .andReturn()
            val third =
                mockMvc
                    .perform(nearby(limit = "2", cursor = nextCursor(second.response.contentAsString)))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.page.nextCursor").doesNotExist())
                    .andReturn()

            val paged =
                storeIds(first.response.contentAsString) +
                    storeIds(second.response.contentAsString) +
                    storeIds(third.response.contentAsString)
            assertThat(paged).containsExactlyElementsOf(identical.map(UUID::toString))
            assertThat(paged).doesNotHaveDuplicates()
        }

        @Test
        fun `the radius boundary includes the store inside it and excludes the store outside it`() {
            insertStore(store(20), "Inside cafe", longitude = 127.0, latitude = 37.5)
            insertStore(store(21), "Outside cafe", longitude = 127.0, latitude = 37.509)

            val distanceMeters =
                jdbcTemplate.queryForObject(
                    """
                    SELECT floor(ST_Distance(location, ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography))::bigint
                      FROM merchant_store_discovery_profile WHERE store_id = ?
                    """.trimIndent(),
                    Long::class.java,
                    store(21),
                )!!

            mockMvc
                .perform(nearby(radiusMeters = (distanceMeters + 1).toString()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(2))
            mockMvc
                .perform(nearby(radiusMeters = (distanceMeters - 1).toString()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].storeId").value(store(20).toString()))
        }

        @Test
        fun `limit defaults to twenty and the contract bounds are enforced before any query`() {
            repeat(21) { index -> insertStore(store(30 + index), "Paged cafe $index", 127.0 + index * 0.0001, 37.5) }

            mockMvc
                .perform(nearby())
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.page.nextCursor").isString)
            mockMvc
                .perform(nearby(limit = "1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(1))
            mockMvc
                .perform(nearby(limit = "100"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(21))
            listOf("0", "101", "-1", "abc").forEach { limit ->
                mockMvc
                    .perform(nearby(limit = limit))
                    .andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            }
        }

        @Test
        fun `invalid coordinates and radii are rejected without echoing the customer coordinate`() {
            // "1e2" is well-formed for `type: number` but out of the latitude range, so it is
            // rejected by the range rule rather than by the grammar.
            listOf("90.1", "-90.1", "NaN", "1e2", "0x1", "37.5f", "1e99999", "").forEach { latitude ->
                mockMvc
                    .perform(nearby(latitude = latitude))
                    .andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            }
            listOf("180.1", "-180.1", "Infinity").forEach { longitude ->
                mockMvc
                    .perform(nearby(longitude = longitude))
                    .andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            }
            listOf("0", "10001", "1.5", "").forEach { radius ->
                mockMvc
                    .perform(nearby(radiusMeters = radius))
                    .andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            }
            listOf(
                nearby(latitude = null),
                nearby(longitude = null),
                nearby(radiusMeters = null),
            ).forEach { request ->
                mockMvc
                    .perform(request)
                    .andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            }

            val body =
                mockMvc
                    .perform(nearby(latitude = "37.123456789", longitude = "127.987654321", radiusMeters = "10001"))
                    .andExpect(status().isBadRequest)
                    .andReturn()
                    .response.contentAsString
            assertThat(body).doesNotContain("37.123456789", "127.987654321")
        }

        @Test
        fun `every finite notation the OpenAPI number type allows is accepted and canonicalised alike`() {
            insertStore(store(90), "Notation cafe", longitude = 127.0, latitude = 37.5)

            // All four spell the same coordinate pair. `type: number, format: double` permits a
            // leading sign and an exponent, so none of them may be a 400.
            val equivalents =
                listOf(
                    "37.5" to "127.0",
                    "+37.5" to "+127.0",
                    "37.50" to "127.00",
                    "3.75e1" to "1.27E2",
                )
            equivalents.forEach { (latitude, longitude) ->
                mockMvc
                    .perform(nearby(latitude = latitude, longitude = longitude, limit = "1"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.items[0].storeId").value(store(90).toString()))
            }

            // Canonicalisation is what binds a cursor to its filter, so a cursor issued for one
            // notation must keep working for an equivalent one.
            insertStore(store(91), "Notation cafe 2", longitude = 127.0001, latitude = 37.5)
            val cursor =
                nextCursor(
                    mockMvc
                        .perform(nearby(latitude = "37.5", longitude = "127.0", limit = "1"))
                        .andExpect(status().isOk)
                        .andReturn()
                        .response.contentAsString,
                )
            mockMvc
                .perform(nearby(latitude = "3.75e1", longitude = "+127.00", limit = "1", cursor = cursor))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items[0].storeId").value(store(91).toString()))
        }

        @Test
        fun `a cursor is bound to its endpoint, filter and signature`() {
            repeat(3) { index -> insertStore(store(50 + index), "Cursor cafe $index", 127.0 + index * 0.0001, 37.5) }
            val cursor =
                nextCursor(
                    mockMvc
                        .perform(nearby(limit = "1"))
                        .andExpect(status().isOk)
                        .andReturn()
                        .response.contentAsString,
                )

            mockMvc
                .perform(nearby(limit = "1", cursor = cursor))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items[0].storeId").value(store(51).toString()))
            // A different radius, a tampered signature, a foreign endpoint cursor and an oversized
            // token are all client-correctable 400s, never a silently reset first page.
            listOf(
                nearby(radiusMeters = "999", cursor = cursor),
                nearby(latitude = "37.6", cursor = cursor),
                nearby(cursor = tamperSignedCursorSignature(cursor)),
                nearby(cursor = "v1.test-vector.notbase64json.signature"),
                nearby(cursor = "x".repeat(2049)),
                nearby(cursor = ""),
            ).forEach { request ->
                mockMvc
                    .perform(request)
                    .andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            }
            assertThat(cursor).doesNotContain("37.5", "127.0", "1000")
        }

        @Test
        fun `a cursor from another endpoint, an unknown key or an expired token is rejected`() {
            insertStore(store(80), "Scope cafe", longitude = 127.0, latitude = 37.5)
            val nearbyScope = scope()
            val storeId = UUID.randomUUID()

            val foreignEndpoint =
                signedCursorCodec.issue(
                    SignedCursorScope("point-account-transactions", nearbyScope.filterHash, nearbyScope.sortAdapter),
                    NearbyStoreSort(0, storeId),
                    clock.instant().plus(Duration.ofHours(1)),
                )
            val valid =
                signedCursorCodec.issue(nearbyScope, NearbyStoreSort(0, storeId), clock.instant().plus(Duration.ofHours(1)))
            val unknownKey =
                valid
                    .split('.')
                    .toMutableList()
                    .also { it[1] = "rotated-away" }
                    .joinToString(".")
            val shortLived = signedCursorCodec.issue(nearbyScope, NearbyStoreSort(0, storeId), clock.instant().plusSeconds(1))

            mockMvc.perform(nearby(cursor = valid)).andExpect(status().isOk)
            listOf(foreignEndpoint, unknownKey).forEach { cursor ->
                mockMvc
                    .perform(nearby(cursor = cursor))
                    .andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            }

            Thread.sleep(1_200)
            mockMvc
                .perform(nearby(cursor = shortLived))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }

        @Test
        fun `a spatial query failure or timeout is a 503 with no fallback result and no audit record`() {
            insertStore(store(60), "Failure cafe", longitude = 127.0, latitude = 37.5)

            // 58030 is an I/O error such as a damaged index; 57014 is the cancellation a statement
            // timeout produces. Both must surface as 503, never as an empty successful page.
            listOf("58030" to "injected spatial index failure", "57014" to "injected statement timeout").forEach { (state, message) ->
                val failuresBefore = spatialFailureCount()
                installSpatialFailureTrigger(state, message)

                mockMvc
                    .perform(nearby())
                    .andExpect(status().isServiceUnavailable)
                    .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                    .andExpect(jsonPath("$.items").doesNotExist())

                assertThat(spatialFailureCount()).describedAs(state).isEqualTo(failuresBefore + 1.0)
                assertThat(count("operations_audit_record")).isZero()
                dropSpatialFailureTrigger()
            }

            mockMvc.perform(nearby()).andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(1))
        }

        @Test
        fun `an unauthenticated request is rejected and no metric tag carries the coordinate`() {
            insertStore(store(70), "Metric cafe", longitude = 127.0, latitude = 37.5)
            mockMvc
                .perform(get(NEARBY_PATH).param("latitude", "37.5").param("longitude", "127.0").param("radiusMeters", "1000"))
                .andExpect(status().isUnauthorized)

            mockMvc.perform(nearby()).andExpect(status().isOk)

            val tagValues =
                meterRegistry
                    .find("beanflow.discovery.nearby.count")
                    .meters()
                    .flatMap { meter -> meter.id.tags.map { it.value } }
            assertThat(tagValues).isNotEmpty()
            assertThat(tagValues).doesNotContain("37.5", "127.0", "1000", store(70).toString())
            assertThat(count("operations_audit_record")).isZero()
        }

        private fun scope(): SignedCursorScope<NearbyStoreSort> =
            NearbyStoreQueryValidation(signedCursorCodec)
                .prepare(SearchNearbyStoresCommand("37.5", "127.0", "1000", null, null, null, clock.instant()))
                .cursorScope

        private fun nearby(
            latitude: String? = "37.5",
            longitude: String? = "127.0",
            radiusMeters: String? = "1000",
            pickupAvailable: String? = null,
            cursor: String? = null,
            limit: String? = null,
        ) = get(NEARBY_PATH)
            .apply {
                latitude?.let { param("latitude", it) }
                longitude?.let { param("longitude", it) }
                radiusMeters?.let { param("radiusMeters", it) }
                pickupAvailable?.let { param("pickupAvailable", it) }
                cursor?.let { param("cursor", it) }
                limit?.let { param("limit", it) }
            }.with(customerJwt())

        private fun customerJwt(): RequestPostProcessor =
            jwt()
                .jwt { it.subject(UUID.randomUUID().toString()).claim("roles", listOf("CUSTOMER")) }
                .authorities(SimpleGrantedAuthority("ROLE_CUSTOMER"))

        private fun store(sequence: Int): UUID = UUID.nameUUIDFromBytes("nearby-store:$sequence".toByteArray())

        private fun insertStore(
            storeId: UUID,
            name: String,
            longitude: Double,
            latitude: Double,
            acceptingOrders: Boolean = true,
            pickupEnabled: Boolean = true,
        ) {
            jdbcTemplate.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, ?, ?, 0)",
                storeId,
                acceptingOrders,
                pickupEnabled,
            )
            jdbcTemplate.update(
                """
                INSERT INTO merchant_store_discovery_profile (store_id, name, location, region_code)
                VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, '1168010100')
                """.trimIndent(),
                storeId,
                name,
                longitude,
                latitude,
            )
        }

        /**
         * One pickup slot. The default is reservable inside the seven-day window; the parameters
         * exist so a test can place a slot outside the window or with no seats left.
         */
        private fun insertPickupSlot(
            storeId: UUID,
            startsIn: Duration = Duration.ofDays(1),
            capacity: Long = 4,
            reserved: Long = 0,
            confirmed: Long = 0,
        ) {
            val startsAt = clock.instant().plus(startsIn)
            jdbcTemplate.update(
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

        private fun nextCursor(body: String): String =
            Regex("\"nextCursor\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                ?: error("nextCursor is missing from the nearby response")

        private fun storeIds(body: String): List<String> =
            Regex("\"storeId\":\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toList()

        private fun count(table: String): Long = jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!

        private fun spatialFailureCount(): Double =
            meterRegistry
                .find("beanflow.discovery.spatial.failure")
                .tag("reason", "QUERY_FAILED")
                .counter()
                ?.count() ?: 0.0

        /**
         * Makes the spatial read fail the way a broken index or extension would, by replacing the
         * profile table with a view that raises.
         */
        private fun installSpatialFailureTrigger(
            sqlState: String,
            message: String,
        ) {
            jdbcTemplate.execute("ALTER TABLE merchant_store_discovery_profile RENAME TO merchant_store_discovery_profile_actual")
            jdbcTemplate.execute(
                """
                CREATE FUNCTION test_reject_nearby_spatial_query()
                RETURNS SETOF merchant_store_discovery_profile_actual LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN RAISE EXCEPTION USING ERRCODE = '$sqlState', MESSAGE = '$message'; END;
                ${'$'}${'$'}
                """.trimIndent(),
            )
            jdbcTemplate.execute(
                "CREATE VIEW merchant_store_discovery_profile AS SELECT * FROM test_reject_nearby_spatial_query()",
            )
        }

        private fun dropSpatialFailureTrigger() {
            jdbcTemplate.execute(
                """
                DO ${'$'}${'$'}
                BEGIN
                    IF (
                        SELECT relkind FROM pg_class
                         WHERE oid = to_regclass('merchant_store_discovery_profile')
                    ) = 'v' THEN
                        DROP VIEW merchant_store_discovery_profile;
                    END IF;
                    DROP FUNCTION IF EXISTS test_reject_nearby_spatial_query();
                    IF to_regclass('merchant_store_discovery_profile_actual') IS NOT NULL THEN
                        ALTER TABLE merchant_store_discovery_profile_actual RENAME TO merchant_store_discovery_profile;
                    END IF;
                END
                ${'$'}${'$'}
                """.trimIndent(),
            )
        }

        private companion object {
            const val NEARBY_PATH = "/api/v1/stores/nearby"
        }
    }

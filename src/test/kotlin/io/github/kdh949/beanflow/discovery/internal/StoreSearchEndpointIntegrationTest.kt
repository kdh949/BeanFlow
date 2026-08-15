package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.shared.api.StoreSearchIndexOperations
import io.github.kdh949.beanflow.shared.api.StoreSearchTermKind
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
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
import java.time.Clock
import java.util.UUID

/**
 * The HTTP surface of `GET /api/v1/stores/search`.
 *
 * `StoreSearchQueryIntegrationTest` pins the port's behaviour; this suite pins what actually reaches
 * the wire, because a field that the `StoreSearchPage` schema requires can be missing from the
 * response without any test of the port ever noticing.
 */
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
internal class StoreSearchEndpointIntegrationTest {
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

    private val fixture by lazy {
        StoreSearchIndexTestFixture(jdbc, index, TransactionTemplate(transactionManager))
    }

    @BeforeEach
    fun clearStoresAndTerms() {
        fixture.clear()
    }

    @Test
    fun `a search response carries every field the page schema requires`() {
        val storeId =
            fixture.indexStore(
                name = "스타벅스 강남점",
                brandName = "스타벅스",
                region =
                    listOf(
                        StoreSearchTermKind.REGION_SIDO to "서울특별시",
                        StoreSearchTermKind.REGION_SIGUNGU to "강남구",
                    ),
                menus = listOf("아메리카노"),
            )
        fixture.indexPickupSlot(storeId, clock.instant())

        mockMvc
            .perform(search(query = "스타벅스"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.distanceAvailable").value(false))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].storeId").value(storeId.toString()))
            .andExpect(jsonPath("$.items[0].name").value("스타벅스 강남점"))
            .andExpect(jsonPath("$.items[0].brandName").value("스타벅스"))
            .andExpect(jsonPath("$.items[0].regionName").value("서울특별시 강남구"))
            .andExpect(jsonPath("$.items[0].matchReason").isArray)
            .andExpect(jsonPath("$.items[0].matchReason[0]").value("STORE_NAME"))
            .andExpect(jsonPath("$.items[0].open").value(true))
            .andExpect(jsonPath("$.items[0].pickupAvailable").value(true))
            .andExpect(jsonPath("$.items[0].matchedMenus").isArray)
            // 좌표를 주지 않았으므로 거리 항은 `null`이 아니라 아예 없다.
            .andExpect(jsonPath("$.items[0].distanceMeters").doesNotExist())
            .andExpect(jsonPath("$.page.nextCursor").doesNotExist())
    }

    @Test
    fun `supplying a coordinate turns on the distance projection`() {
        fixture.indexStore(name = "스타벅스 강남점")

        mockMvc
            .perform(
                search(
                    query = "스타벅스",
                    latitude = StoreSearchIndexTestFixture.SEOUL_LATITUDE.toString(),
                    longitude = StoreSearchIndexTestFixture.SEOUL_LONGITUDE.toString(),
                    radiusMeters = "1000",
                ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.distanceAvailable").value(true))
            .andExpect(jsonPath("$.items[0].distanceMeters").value(0))
    }

    @Test
    fun `a store with no matching menu still returns an empty menu array rather than being dropped`() {
        fixture.indexStore(name = "스타벅스 강남점", menus = listOf("아메리카노"))

        mockMvc
            .perform(search(query = "스타벅스"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].matchedMenus.length()").value(0))
            // 브랜드·지역이 없는 매장은 두 필드를 `null`로 보내지 않고 생략한다.
            .andExpect(jsonPath("$.items[0].brandName").doesNotExist())
            .andExpect(jsonPath("$.items[0].regionName").doesNotExist())
    }

    @Test
    fun `no match is a 200 empty page, not a failure`() {
        fixture.indexStore(name = "스타벅스 강남점")

        mockMvc
            .perform(search(query = "존재하지않는이름"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(0))
            .andExpect(jsonPath("$.distanceAvailable").value(false))
            .andExpect(jsonPath("$.page.nextCursor").doesNotExist())
    }

    @Test
    fun `contract violations are 400 and never echo the search text`() {
        val secret = "비밀검색어"

        mockMvc
            .perform(search(query = secret, sort = "popularity"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value(not(containsString(secret))))

        mockMvc
            .perform(search(query = "스타벅스", sort = "distance"))
            .andExpect(status().isBadRequest)
        mockMvc
            .perform(search(query = "가"))
            .andExpect(status().isBadRequest)
        mockMvc
            .perform(search(query = "스타벅스", limit = "51"))
            .andExpect(status().isBadRequest)
        mockMvc
            .perform(search(query = "스타벅스", pickupAvailable = "yes"))
            .andExpect(status().isBadRequest)
    }

    private fun search(
        query: String? = null,
        sort: String? = null,
        latitude: String? = null,
        longitude: String? = null,
        radiusMeters: String? = null,
        pickupAvailable: String? = null,
        openOnly: String? = null,
        cursor: String? = null,
        limit: String? = null,
    ) = get("/api/v1/stores/search")
        .apply {
            query?.let { param("query", it) }
            sort?.let { param("sort", it) }
            latitude?.let { param("latitude", it) }
            longitude?.let { param("longitude", it) }
            radiusMeters?.let { param("radiusMeters", it) }
            pickupAvailable?.let { param("pickupAvailable", it) }
            openOnly?.let { param("openOnly", it) }
            cursor?.let { param("cursor", it) }
            limit?.let { param("limit", it) }
        }.with(customerJwt())

    private fun customerJwt(): RequestPostProcessor =
        jwt()
            .jwt { it.subject(UUID.randomUUID().toString()).claim("roles", listOf("CUSTOMER")) }
            .authorities(SimpleGrantedAuthority("ROLE_CUSTOMER"))
}

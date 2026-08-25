package io.github.kdh949.beanflow.discovery.internal

import com.jayway.jsonpath.JsonPath
import io.github.kdh949.beanflow.BeanflowSharedDatabaseTest
import io.github.kdh949.beanflow.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * Statement counts for a whole HTTP request, not for a repository call.
 *
 * `DiscoveryStoreCatalogQueryCountTest` counts what one repository method issues, which says
 * nothing about how many statements the endpoint runs in total: store identity, the availability
 * check and the projection are separate calls. This suite wraps the application's own `DataSource`
 * and counts everything a request touches, so an extra query added anywhere on the path — a service,
 * an interceptor, a lazy association — shows up here.
 */
@Import(TestcontainersConfiguration::class, DiscoveryStoreCatalogEndpointQueryCountTest.CountingDataSourceConfiguration::class)
@AutoConfigureMockMvc
@BeanflowSharedDatabaseTest
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
internal class DiscoveryStoreCatalogEndpointQueryCountTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val statements: StatementCounter,
    ) {
        private val smallStore: UUID = UUID.fromString("60000000-0000-0000-0000-000000000001")
        private val largeStore: UUID = UUID.fromString("60000000-0000-0000-0000-000000000002")

        @BeforeEach
        fun seed() {
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
            seedStore(smallStore, menus = 1, optionsPerMenu = 1, slots = 1)
            seedStore(largeStore, menus = 40, optionsPerMenu = 4, slots = 30)
        }

        @Test
        fun `the menu endpoint issues the same statements for a large catalogue as for a small one`() {
            val small = countStatements { readOk(menuPath(smallStore), expectedItems = 1) }
            val large = countStatements { readOk(menuPath(largeStore), expectedItems = 40) }

            // Store identity, the menu projection and the option projection. Nothing per menu.
            assertThat(small).isEqualTo(MENU_ENDPOINT_STATEMENTS)
            assertThat(large).isEqualTo(MENU_ENDPOINT_STATEMENTS)
        }

        @Test
        fun `the pickup slot endpoint issues the same statements for many slots as for one`() {
            val small = countStatements { readOk(slotPath(smallStore), expectedItems = 1) }
            val large = countStatements { readOk(slotPath(largeStore), expectedItems = 30) }

            // Store identity plus availability in one lookup, then the slot projection.
            assertThat(small).isEqualTo(SLOT_ENDPOINT_STATEMENTS)
            assertThat(large).isEqualTo(SLOT_ENDPOINT_STATEMENTS)
        }

        private fun readOk(
            path: String,
            expectedItems: Int,
        ) {
            val body =
                mockMvc
                    .perform(get(path).with(customerJwt()))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString
            // Counting a request that returned the wrong page would prove nothing.
            assertThat(JsonPath.read<List<*>>(body, "$.items")).hasSize(expectedItems)
        }

        private fun countStatements(block: () -> Unit): Int {
            val before = statements.count.get()
            block()
            return statements.count.get() - before
        }

        private fun menuPath(storeId: UUID) = "/api/v1/stores/$storeId/menus"

        private fun slotPath(storeId: UUID) = "/api/v1/stores/$storeId/pickup-slots"

        private fun customerJwt(): RequestPostProcessor =
            jwt()
                .jwt { it.subject(UUID.randomUUID().toString()).claim("roles", listOf("CUSTOMER")) }
                .authorities(SimpleGrantedAuthority("ROLE_CUSTOMER"))

        private fun seedStore(
            storeId: UUID,
            menus: Int,
            optionsPerMenu: Int,
            slots: Int,
        ) {
            jdbcTemplate.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                storeId,
            )
            repeat(menus) { menuIndex ->
                val menuId = UUID.nameUUIDFromBytes("endpoint-count:$storeId:menu:$menuIndex".toByteArray())
                jdbcTemplate.update(
                    """
                    INSERT INTO merchant_menu (id, store_id, name, base_price_krw, available, version)
                    VALUES (?, ?, ?, ?, true, 0)
                    """.trimIndent(),
                    menuId,
                    storeId,
                    "Menu %03d".format(menuIndex),
                    1_000L + menuIndex,
                )
                repeat(optionsPerMenu) { optionIndex ->
                    jdbcTemplate.update(
                        """
                        INSERT INTO merchant_menu_option (id, menu_id, name, additional_price_krw, available)
                        VALUES (?, ?, ?, ?, true)
                        """.trimIndent(),
                        UUID.nameUUIDFromBytes("endpoint-count:$menuId:option:$optionIndex".toByteArray()),
                        menuId,
                        "Option %03d".format(optionIndex),
                        100L * optionIndex,
                    )
                }
            }
            val now = Instant.now()
            repeat(slots) { slotIndex ->
                jdbcTemplate.update(
                    """
                    INSERT INTO fulfillment_pickup_slot (
                        id, store_id, starts_at, ends_at, capacity, reserved_count, confirmed_count, version
                    ) VALUES (?, ?, ?, ?, 4, 0, 0, 0)
                    """.trimIndent(),
                    UUID.nameUUIDFromBytes("endpoint-count:$storeId:slot:$slotIndex".toByteArray()),
                    storeId,
                    Timestamp.from(now.plus(Duration.ofMinutes(30L * slotIndex + 5))),
                    Timestamp.from(now.plus(Duration.ofMinutes(30L * slotIndex + 25))),
                )
            }
        }

        private companion object {
            const val MENU_ENDPOINT_STATEMENTS = 3
            const val SLOT_ENDPOINT_STATEMENTS = 2
        }

        /** Counts statement preparations on the application's own data source. */
        internal class StatementCounter {
            val count = AtomicInteger()
        }

        @TestConfiguration(proxyBeanMethods = false)
        internal class CountingDataSourceConfiguration {
            @Bean
            fun statementCounter() = StatementCounter()

            @Bean
            fun countingDataSourceWrapper(counter: StatementCounter): BeanPostProcessor =
                object : BeanPostProcessor {
                    override fun postProcessAfterInitialization(
                        bean: Any,
                        beanName: String,
                    ): Any = if (bean is DataSource) CountingDataSource(bean, counter) else bean
                }
        }

        private class CountingDataSource(
            private val delegate: DataSource,
            private val counter: StatementCounter,
        ) : DataSource by delegate {
            override fun getConnection(): Connection = counting(delegate.connection)

            override fun getConnection(
                username: String?,
                password: String?,
            ): Connection = counting(delegate.getConnection(username, password))

            private fun counting(connection: Connection): Connection =
                Proxy.newProxyInstance(
                    Connection::class.java.classLoader,
                    arrayOf(Connection::class.java),
                ) { _, method, args ->
                    if (method.name in STATEMENT_METHODS) counter.count.incrementAndGet()
                    try {
                        method.invoke(connection, *(args ?: emptyArray()))
                    } catch (failure: InvocationTargetException) {
                        throw failure.targetException
                    }
                } as Connection

            private companion object {
                val STATEMENT_METHODS = setOf("prepareStatement", "createStatement", "prepareCall")
            }
        }
    }

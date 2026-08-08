package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.BEANFLOW_POSTGRES_IMAGE
import io.github.kdh949.beanflow.fulfillment.internal.PICKUP_SLOT_QUERY_HORIZON
import io.github.kdh949.beanflow.fulfillment.internal.PickupSlotQueryRepository
import io.github.kdh949.beanflow.merchant.internal.StoreMenuQueryRepository
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
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
 * Statement-count regression for the catalogue projections themselves.
 *
 * The number of SQL statements must not grow with the number of menus, options or slots. The
 * counting data source wraps only this test's connections, so the application context is untouched.
 *
 * These are per-repository counts, not endpoint totals: a request also verifies store identity and
 * availability. `DiscoveryStoreCatalogEndpointQueryCountTest` pins what a whole HTTP request issues.
 */
@Testcontainers(disabledWithoutDocker = true)
internal class DiscoveryStoreCatalogQueryCountTest {
    companion object {
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(BEANFLOW_POSTGRES_IMAGE)

        private lateinit var countingDataSource: StatementCountingDataSource
        private lateinit var menuRepository: StoreMenuQueryRepository
        private lateinit var slotRepository: PickupSlotQueryRepository
        private lateinit var fixtures: JdbcTemplate

        private val smallStore: UUID = UUID.fromString("40000000-0000-0000-0000-000000000001")
        private val largeStore: UUID = UUID.fromString("40000000-0000-0000-0000-000000000002")
        private val now: Instant = Instant.parse("2026-08-07T00:00:00Z")
        private val horizonEnd: Instant = now.plus(PICKUP_SLOT_QUERY_HORIZON)

        @BeforeAll
        @JvmStatic
        fun migrateAndSeed() {
            val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate()
            fixtures = JdbcTemplate(dataSource)
            countingDataSource = StatementCountingDataSource(dataSource)
            val countedTemplate = JdbcTemplate(countingDataSource)
            menuRepository = StoreMenuQueryRepository(countedTemplate)
            slotRepository = PickupSlotQueryRepository(countedTemplate)

            seedStore(smallStore, menus = 1, optionsPerMenu = 1, slots = 1)
            seedStore(largeStore, menus = 50, optionsPerMenu = 4, slots = 40)
        }

        private fun seedStore(
            storeId: UUID,
            menus: Int,
            optionsPerMenu: Int,
            slots: Int,
        ) {
            fixtures.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                storeId,
            )
            repeat(menus) { menuIndex ->
                val menuId = UUID.nameUUIDFromBytes("catalog-count:$storeId:menu:$menuIndex".toByteArray())
                fixtures.update(
                    """
                    INSERT INTO merchant_menu (id, store_id, name, base_price_krw, available, version)
                    VALUES (?, ?, ?, ?, ?, 0)
                    """.trimIndent(),
                    menuId,
                    storeId,
                    "Menu %03d".format(menuIndex),
                    1_000L + menuIndex,
                    menuIndex % 3 != 0,
                )
                repeat(optionsPerMenu) { optionIndex ->
                    fixtures.update(
                        """
                        INSERT INTO merchant_menu_option (id, menu_id, name, additional_price_krw, available)
                        VALUES (?, ?, ?, ?, ?)
                        """.trimIndent(),
                        UUID.nameUUIDFromBytes("catalog-count:$menuId:option:$optionIndex".toByteArray()),
                        menuId,
                        "Option %03d".format(optionIndex),
                        100L * optionIndex,
                        optionIndex % 2 == 0,
                    )
                }
            }
            repeat(slots) { slotIndex ->
                fixtures.update(
                    """
                    INSERT INTO fulfillment_pickup_slot (
                        id, store_id, starts_at, ends_at, capacity, reserved_count, confirmed_count, version
                    ) VALUES (?, ?, ?, ?, ?, 0, 0, 0)
                    """.trimIndent(),
                    UUID.nameUUIDFromBytes("catalog-count:$storeId:slot:$slotIndex".toByteArray()),
                    storeId,
                    // Every slot starts after `now`, which is the window findOpenSlots projects.
                    Timestamp.from(now.plus(Duration.ofMinutes(30L * slotIndex + 5))),
                    Timestamp.from(now.plus(Duration.ofMinutes(30L * slotIndex + 25))),
                    4L,
                )
            }
        }
    }

    @Test
    fun `menu projection uses a constant number of statements regardless of catalogue size`() {
        val small =
            countStatements {
                menuRepository.findMenus(smallStore)
                menuRepository.findOptions(smallStore)
            }
        val large =
            countStatements {
                menuRepository.findMenus(largeStore)
                menuRepository.findOptions(largeStore)
            }

        assertThat(small).isEqualTo(2)
        assertThat(large).isEqualTo(2)
        assertThat(menuRepository.findMenus(largeStore)).hasSize(50)
        assertThat(menuRepository.findOptions(largeStore)).hasSize(200)
    }

    @Test
    fun `pickup slot projection uses a single statement regardless of slot count`() {
        val small = countStatements { slotRepository.findOpenSlots(smallStore, now, horizonEnd) }
        val large = countStatements { slotRepository.findOpenSlots(largeStore, now, horizonEnd) }

        assertThat(small).isOne()
        assertThat(large).isOne()
        assertThat(slotRepository.findOpenSlots(largeStore, now, horizonEnd)).hasSize(40)
    }

    @Test
    fun `menu projection never returns another store's menu or option`() {
        val smallMenuIds = menuRepository.findMenus(smallStore).map { it.menuId }.toSet()
        val largeMenuIds = menuRepository.findMenus(largeStore).map { it.menuId }.toSet()

        assertThat(smallMenuIds).doesNotContainAnyElementsOf(largeMenuIds)
        assertThat(menuRepository.findOptions(smallStore).map { it.menuId }.toSet()).isEqualTo(smallMenuIds)
    }

    /** Runs [block] and returns how many JDBC statements it prepared. */
    private fun countStatements(block: () -> Unit): Int {
        val before = countingDataSource.statements.get()
        block()
        return countingDataSource.statements.get() - before
    }
}

/**
 * Counts JDBC statement preparations without touching the application data source.
 */
private class StatementCountingDataSource(
    private val delegate: DataSource,
) : DataSource by delegate {
    val statements = AtomicInteger()

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
            if (method.name in STATEMENT_METHODS) statements.incrementAndGet()
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

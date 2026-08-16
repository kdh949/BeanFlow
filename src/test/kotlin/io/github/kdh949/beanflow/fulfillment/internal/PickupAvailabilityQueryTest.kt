package io.github.kdh949.beanflow.fulfillment.internal

import io.github.kdh949.beanflow.BEANFLOW_POSTGRES_IMAGE
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
 * The batch availability port Discovery calls once per page (ADR-103 2026-08-15 Amendment).
 *
 * Two properties are pinned together because they are the reason the port exists at all: the answer
 * matches what `listOpenSlots` publishes, and the statement count does not grow with the number of
 * candidates. A per-store loop would satisfy the first and fail the second.
 */
@Testcontainers(disabledWithoutDocker = true)
internal class PickupAvailabilityQueryTest {
    companion object {
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(BEANFLOW_POSTGRES_IMAGE)

        private lateinit var countingDataSource: StatementCountingDataSource
        private lateinit var service: PickupAvailabilityQueryService
        private lateinit var fixtures: JdbcTemplate

        private val now: Instant = Instant.parse("2026-08-07T00:00:00Z")

        /** One reservable slot tomorrow. */
        private val availableStore: UUID = UUID.fromString("50000000-0000-0000-0000-000000000001")

        /** Slots exist but every seat is taken. */
        private val fullStore: UUID = UUID.fromString("50000000-0000-0000-0000-000000000002")

        /** The only slot already started, so it is no longer reservable (BR-05). */
        private val startedStore: UUID = UUID.fromString("50000000-0000-0000-0000-000000000003")

        /** The only slot is beyond the seven-day window. */
        private val beyondHorizonStore: UUID = UUID.fromString("50000000-0000-0000-0000-000000000004")

        /** No slot row at all. */
        private val slotlessStore: UUID = UUID.fromString("50000000-0000-0000-0000-000000000005")

        /** Never passed as a candidate; proves the port answers only about what it was asked. */
        private val unaskedStore: UUID = UUID.fromString("50000000-0000-0000-0000-000000000006")

        /** Owns many slots, so a per-slot or per-store statement count would show up. */
        private val busyStore: UUID = UUID.fromString("50000000-0000-0000-0000-000000000007")

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
            service = PickupAvailabilityQueryService(PickupAvailabilityQueryRepository(JdbcTemplate(countingDataSource)))

            listOf(
                availableStore,
                fullStore,
                startedStore,
                beyondHorizonStore,
                slotlessStore,
                unaskedStore,
                busyStore,
            ).forEach(::seedStore)

            insertSlot(availableStore, startsIn = Duration.ofDays(1), capacity = 2, reserved = 1, confirmed = 0)
            insertSlot(fullStore, startsIn = Duration.ofDays(1), capacity = 2, reserved = 1, confirmed = 1)
            // The reservable lower bound is `startsAt > now`, so a slot that already began is out.
            insertSlot(startedStore, startsIn = Duration.ofMinutes(-30), capacity = 5, reserved = 0, confirmed = 0)
            insertSlot(beyondHorizonStore, startsIn = Duration.ofDays(8), capacity = 5, reserved = 0, confirmed = 0)
            insertSlot(unaskedStore, startsIn = Duration.ofDays(1), capacity = 5, reserved = 0, confirmed = 0)
            repeat(60) { index ->
                insertSlot(busyStore, startsIn = Duration.ofHours(index + 1L), capacity = 3, reserved = 3, confirmed = 0)
            }
            // Exactly one reservable slot hides at the end of a long, fully booked list.
            insertSlot(busyStore, startsIn = Duration.ofHours(70), capacity = 3, reserved = 2, confirmed = 0)
        }

        private fun seedStore(storeId: UUID) {
            fixtures.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                storeId,
            )
        }

        private fun insertSlot(
            storeId: UUID,
            startsIn: Duration,
            capacity: Long,
            reserved: Long,
            confirmed: Long,
        ) {
            val startsAt = now.plus(startsIn)
            fixtures.update(
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
    }

    private val allCandidates =
        listOf(availableStore, fullStore, startedStore, beyondHorizonStore, slotlessStore, busyStore)

    @Test
    fun `only stores with a reservable slot inside the window are available`() {
        assertThat(service.findStoresWithAvailableSlots(allCandidates, now))
            .containsExactlyInAnyOrder(availableStore, busyStore)
    }

    @Test
    fun `a store outside the candidate list is never reported`() {
        assertThat(service.findStoresWithAvailableSlots(listOf(availableStore), now))
            .containsExactly(availableStore)
            .doesNotContain(unaskedStore)
    }

    @Test
    fun `the statement count is one regardless of how many candidates or slots are involved`() {
        val single = countStatements { service.findStoresWithAvailableSlots(listOf(availableStore), now) }
        val many = countStatements { service.findStoresWithAvailableSlots(allCandidates, now) }

        assertThat(single).isOne()
        assertThat(many).isOne()
    }

    @Test
    fun `an empty candidate list answers without touching the database`() {
        val statements = countStatements { assertThat(service.findStoresWithAvailableSlots(emptyList(), now)).isEmpty() }

        assertThat(statements).isZero()
    }

    @Test
    fun `a duplicated candidate id does not change the answer or the statement count`() {
        val statements =
            countStatements {
                assertThat(service.findStoresWithAvailableSlots(listOf(availableStore, availableStore), now))
                    .containsExactly(availableStore)
            }

        assertThat(statements).isOne()
    }

    @Test
    fun `a slot that starts exactly at now is not reservable`() {
        val boundaryStore = UUID.randomUUID()
        seedStore(boundaryStore)
        insertSlot(boundaryStore, startsIn = Duration.ZERO, capacity = 5, reserved = 0, confirmed = 0)

        assertThat(service.findStoresWithAvailableSlots(listOf(boundaryStore), now)).isEmpty()
    }

    /**
     * A corrupted counter must not look like an ordinary closed store. The database `CHECK` makes
     * the row impossible through normal writes, so the constraint is lifted only long enough to
     * prove the guard fires rather than silently dropping the store from the result.
     */
    @Test
    fun `a corrupted slot counter is a dependency failure rather than an unavailable store`() {
        withCorruptedStore { corruptedStore ->
            assertThatThrownBy { service.findStoresWithAvailableSlots(listOf(corruptedStore), now) }
                .isInstanceOfSatisfying(DomainFailure::class.java) { failure ->
                    assertThat(failure.code).isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
                }
        }
    }

    /**
     * The corrupted store is rejected even when it is one candidate among many that would otherwise
     * have produced a perfectly plausible page. Shortening the page instead would publish a page
     * that looks like an ordinary result.
     */
    @Test
    fun `one corrupted candidate fails the whole batch instead of shortening the answer`() {
        withCorruptedStore { corruptedStore ->
            assertThatThrownBy { service.findStoresWithAvailableSlots(allCandidates + corruptedStore, now) }
                .isInstanceOfSatisfying(DomainFailure::class.java) { failure ->
                    assertThat(failure.code).isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
                }
        }
    }

    /**
     * The database `CHECK` makes a corrupted counter impossible through normal writes, so it is
     * lifted only long enough to prove the guard fires, then restored with the row removed.
     */
    private fun withCorruptedStore(block: (UUID) -> Unit) {
        val corruptedStore = UUID.randomUUID()
        seedStore(corruptedStore)
        val constraint =
            fixtures.queryForObject(
                """
                SELECT conname FROM pg_constraint
                 WHERE conrelid = 'fulfillment_pickup_slot'::regclass
                   AND contype = 'c'
                   AND pg_get_constraintdef(oid) LIKE '%reserved_count + confirmed_count%'
                """.trimIndent(),
                String::class.java,
            )!!
        fixtures.execute("ALTER TABLE fulfillment_pickup_slot DROP CONSTRAINT $constraint")
        try {
            insertSlot(corruptedStore, startsIn = Duration.ofDays(1), capacity = 1, reserved = 3, confirmed = 0)
            block(corruptedStore)
        } finally {
            fixtures.update("DELETE FROM fulfillment_pickup_slot WHERE store_id = ?", corruptedStore)
            fixtures.execute(
                """
                ALTER TABLE fulfillment_pickup_slot
                  ADD CONSTRAINT $constraint CHECK (reserved_count + confirmed_count <= capacity)
                """.trimIndent(),
            )
        }
    }

    private fun countStatements(block: () -> Unit): Int {
        val before = countingDataSource.statements.get()
        block()
        return countingDataSource.statements.get() - before
    }
}

/** Counts JDBC statement preparations without touching the application data source. */
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

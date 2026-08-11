package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.BEANFLOW_POSTGRES_IMAGE
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

@Testcontainers(disabledWithoutDocker = true)
internal class OrderReferenceMigrationTest {
    @Test
    fun `V43 opens a backfill window and initializes the legacy pickup counter`() {
        val dataSource = database("order_reference_expand")
        flyway(dataSource).target("42").load().migrate()
        val jdbc = JdbcTemplate(dataSource)
        val fixture = insertLegacyOrder(jdbc)

        flyway(dataSource).target("43").load().migrate()

        assertThat(columns(jdbc)).contains(
            "public_reference",
            "pickup_business_date",
            "pickup_sequence",
            "store_name_snapshot",
            "pickup_window_start_snapshot",
            "pickup_window_end_snapshot",
        )
        assertThat(value<Long>(jdbc, "SELECT last_sequence FROM ordering_pickup_counter WHERE store_id = ?", fixture.storeId))
            .isOne()
        assertThat(value<String>(jdbc, "SELECT public_reference FROM ordering_order WHERE id = ?", fixture.orderId)).isNull()
        assertThatThrownBy { flyway(dataSource).load().migrate() }
            .isInstanceOf(FlywayException::class.java)
            .hasMessageContaining("public order reference backfill")
    }

    @Test
    fun `V44 closes the window and makes six display identity fields immutable`() {
        val dataSource = database("order_reference_contract")
        flyway(dataSource).target("42").load().migrate()
        val jdbc = JdbcTemplate(dataSource)
        val fixture = insertLegacyOrder(jdbc)
        flyway(dataSource).target("43").load().migrate()
        val reference = "BF-2345-6789"
        jdbc.update(
            "INSERT INTO ordering_public_reference_registry (public_reference, allocated_at) VALUES (?, ?)",
            reference,
            Timestamp.from(FIXED_NOW),
        )
        jdbc.update(
            """
            UPDATE ordering_order
               SET public_reference = ?, pickup_business_date = DATE '2030-01-01', pickup_sequence = 1,
                   store_name_snapshot = 'Legacy Store', pickup_window_start_snapshot = ?,
                   pickup_window_end_snapshot = ?
             WHERE id = ?
            """.trimIndent(),
            reference,
            Timestamp.from(SLOT_START),
            Timestamp.from(SLOT_END),
            fixture.orderId,
        )

        assertThatCode { flyway(dataSource).load().migrate() }.doesNotThrowAnyException()
        assertThat(nullableColumnCount(jdbc)).isZero()
        assertThatThrownBy {
            jdbc.update("UPDATE ordering_order SET store_name_snapshot = 'Changed' WHERE id = ?", fixture.orderId)
        }.hasMessageContaining("ordering_order_display_identity_immutable")
        assertThatCode {
            jdbc.update("UPDATE ordering_order SET updated_at = ? WHERE id = ?", Timestamp.from(FIXED_NOW.plusSeconds(1)), fixture.orderId)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `bounded backfill is restartable and ranks legacy orders by creation time and id`() {
        val dataSource = database("order_reference_backfill")
        flyway(dataSource).target("42").load().migrate()
        val jdbc = JdbcTemplate(dataSource)
        val later = insertLegacyOrder(jdbc, createdAt = FIXED_NOW.plusSeconds(1))
        val earlier = insertLegacyOrder(jdbc, existing = later, createdAt = FIXED_NOW)
        flyway(dataSource).target("43").load().migrate()
        val references = listOf("BF-2345-6789", "BF-ABCD-EFGH")
        val cursor = AtomicInteger()
        val backfill =
            OrderReferenceBackfillService(
                jdbc,
                PublicOrderReferenceCandidateGenerator {
                    PublicOrderReference.parse(references[cursor.getAndIncrement()])
                },
                SimpleMeterRegistry(),
                DataSourceTransactionManager(dataSource),
            )

        val first = backfill.runAll(batchSize = 1)
        val replay = backfill.runAll(batchSize = 1)

        assertThat(first.processedCount).isEqualTo(2)
        assertThat(replay.processedCount).isZero()
        assertThat(value<Long>(jdbc, "SELECT pickup_sequence FROM ordering_order WHERE id = ?", earlier.orderId)).isEqualTo(1)
        assertThat(value<Long>(jdbc, "SELECT pickup_sequence FROM ordering_order WHERE id = ?", later.orderId)).isEqualTo(2)
        assertThat(value<Long>(jdbc, "SELECT count(*) FROM ordering_public_reference_registry")).isEqualTo(2)
        val earlierReference = value<String>(jdbc, "SELECT public_reference FROM ordering_order WHERE id = ?", earlier.orderId)
        assertThat(value<UUID>(jdbc, "SELECT id FROM ordering_order WHERE public_reference = ?", requireNotNull(earlierReference)))
            .isEqualTo(earlier.orderId)
        assertThatCode { flyway(dataSource).load().migrate() }.doesNotThrowAnyException()
    }

    @Test
    fun `backfill fails without a verified profile and never writes a placeholder`() {
        val dataSource = database("order_reference_backfill_missing_profile")
        flyway(dataSource).target("42").load().migrate()
        val jdbc = JdbcTemplate(dataSource)
        insertLegacyOrder(jdbc, includeProfile = false)
        flyway(dataSource).target("43").load().migrate()
        val backfill =
            OrderReferenceBackfillService(
                jdbc,
                PublicOrderReferenceCandidateGenerator { PublicOrderReference.parse("BF-2345-6789") },
                SimpleMeterRegistry(),
                DataSourceTransactionManager(dataSource),
            )

        assertThatThrownBy { backfill.runAll(batchSize = 10) }
            .isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
            }
        assertThat(value<Long>(jdbc, "SELECT count(*) FROM ordering_order WHERE public_reference IS NOT NULL")).isZero()
        assertThat(value<Long>(jdbc, "SELECT count(*) FROM ordering_public_reference_registry")).isZero()
    }

    @Test
    fun `V44 rejects a missing locked pickup grant window instead of inventing a timestamp`() {
        val dataSource = database("order_reference_missing_grant")
        flyway(dataSource).target("42").load().migrate()
        val jdbc = JdbcTemplate(dataSource)
        val fixture = insertLegacyOrder(jdbc)
        jdbc.update(
            """
            INSERT INTO fulfillment_pickup_reservation (
                id, order_id, slot_id, state, expires_at, source_reference,
                created_at, updated_at, version
            ) VALUES (?, ?, ?, 'RESERVED', ?, ?, ?, ?, 0)
            """.trimIndent(),
            UUID.randomUUID(),
            fixture.orderId,
            fixture.slotId,
            Timestamp.from(SLOT_START.minusSeconds(60)),
            "missing-grant:${fixture.orderId}",
            Timestamp.from(FIXED_NOW),
            Timestamp.from(FIXED_NOW),
        )
        flyway(dataSource).target("43").load().migrate()
        OrderReferenceBackfillService(
            jdbc,
            PublicOrderReferenceCandidateGenerator { PublicOrderReference.parse("BF-2345-6789") },
            SimpleMeterRegistry(),
            DataSourceTransactionManager(dataSource),
        ).runAll(10)
        jdbc.update(
            "UPDATE fulfillment_pickup_reservation SET slot_starts_at_snapshot = NULL WHERE order_id = ?",
            fixture.orderId,
        )

        assertThatThrownBy { flyway(dataSource).load().migrate() }
            .isInstanceOf(FlywayException::class.java)
            .hasMessageContaining("pickup reservation grant snapshot backfill")
    }

    private fun insertLegacyOrder(
        jdbc: JdbcTemplate,
        existing: LegacyFixture? = null,
        createdAt: Instant = FIXED_NOW,
        includeProfile: Boolean = true,
    ): LegacyFixture {
        val storeId = existing?.storeId ?: UUID.randomUUID()
        val slotId = existing?.slotId ?: UUID.randomUUID()
        val orderId = UUID.randomUUID()
        if (existing == null) {
            jdbc.update("INSERT INTO merchant_store (id, accepting_orders, pickup_enabled) VALUES (?, true, true)", storeId)
            if (includeProfile) {
                jdbc.update(
                    """
                    INSERT INTO merchant_store_discovery_profile (store_id, name, location)
                    VALUES (?, 'Legacy Store', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography)
                    """.trimIndent(),
                    storeId,
                )
            }
            jdbc.update(
                """
                INSERT INTO fulfillment_pickup_slot
                    (id, store_id, starts_at, ends_at, capacity, reserved_count, confirmed_count)
                VALUES (?, ?, ?, ?, 10, 0, 0)
                """.trimIndent(),
                slotId,
                storeId,
                Timestamp.from(SLOT_START),
                Timestamp.from(SLOT_END),
            )
        }
        jdbc.execute("ALTER TABLE ordering_order DISABLE TRIGGER USER")
        try {
            jdbc.update(
                """
                INSERT INTO ordering_order (
                    id, customer_id, store_id, pickup_slot_id, state,
                    subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw,
                    currency, reservation_expires_at, created_at, updated_at, version
                ) VALUES (?, ?, ?, ?, 'PENDING_PAYMENT', 1000, 0, 0, 1000, 'KRW', ?, ?, ?, 0)
                """.trimIndent(),
                orderId,
                UUID.randomUUID(),
                storeId,
                slotId,
                Timestamp.from(Instant.parse("2029-12-31T23:00:00Z")),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt),
            )
        } finally {
            jdbc.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
        }
        return LegacyFixture(orderId, storeId, slotId)
    }

    private fun columns(jdbc: JdbcTemplate): List<String> =
        jdbc
            .queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'ordering_order'",
                String::class.java,
            ).filterNotNull()

    private fun nullableColumnCount(jdbc: JdbcTemplate): Long =
        requireNotNull(
            jdbc.queryForObject(
                """
                SELECT count(*) FROM information_schema.columns
                 WHERE table_name = 'ordering_order'
                   AND column_name IN ('public_reference', 'pickup_business_date', 'pickup_sequence',
                       'store_name_snapshot', 'pickup_window_start_snapshot', 'pickup_window_end_snapshot')
                   AND is_nullable = 'YES'
                """.trimIndent(),
                Long::class.java,
            ),
        )

    private inline fun <reified T : Any> value(
        jdbc: JdbcTemplate,
        sql: String,
        vararg args: Any,
    ): T? = jdbc.queryForObject(sql, T::class.java, *args)

    private fun database(name: String): DataSource {
        val databaseName = "${name}_${UUID.randomUUID().toString().replace("-", "")}"
        postgres.createConnection("").use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE $databaseName TEMPLATE template1") }
        }
        return DriverManagerDataSource(
            "jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/$databaseName",
            postgres.username,
            postgres.password,
        )
    }

    private fun flyway(dataSource: DataSource) =
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")

    private data class LegacyFixture(
        val orderId: UUID,
        val storeId: UUID,
        val slotId: UUID,
    )

    private companion object {
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(BEANFLOW_POSTGRES_IMAGE)

        val FIXED_NOW: Instant = Instant.parse("2026-08-12T00:00:00Z")
        val SLOT_START: Instant = Instant.parse("2030-01-01T00:10:00Z")
        val SLOT_END: Instant = Instant.parse("2030-01-01T00:20:00Z")
    }
}

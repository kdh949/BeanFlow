package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal class ImmediateCheckoutMigrationTest : IsolatedPostgresSupport() {
    private val jdbc by lazy { JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)) }

    @BeforeEach
    fun cleanDatabase() {
        flyway(cleanDisabled = false).clean()
    }

    @Test
    fun `V89 preserves V86 Orders as legacy reserved`() {
        flyway(target = MigrationVersion.fromVersion("86")).migrate()
        val orderId = UUID.randomUUID()
        insertLegacy(orderId)

        flyway().migrate()

        assertThat(jdbc.queryForObject("SELECT checkout_mode FROM ordering_order WHERE id = ?", String::class.java, orderId))
            .isEqualTo("LEGACY_RESERVED")
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ordering_order WHERE id = ? AND pickup_slot_id IS NOT NULL AND reservation_expires_at IS NOT NULL",
                Long::class.java,
                orderId,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `V90 accepts a slotless immediate draft without a settlement snapshot and rejects a hidden lease`() {
        flyway().migrate()
        val orderId = UUID.randomUUID()
        insertImmediate(orderId)

        assertThat(
            jdbc.queryForMap(
                "SELECT checkout_mode, pickup_slot_id, reservation_expires_at FROM ordering_order WHERE id = ?",
                orderId,
            ),
        ).containsEntry("checkout_mode", "IMMEDIATE")
            .containsEntry("pickup_slot_id", null)
            .containsEntry("reservation_expires_at", null)

        assertThatThrownBy {
            jdbc.update(
                "UPDATE ordering_order SET reservation_expires_at = created_at + interval '5 minutes' WHERE id = ?",
                orderId,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `V90 permits immediate draft expiry but still requires settlement input for paid orders`() {
        flyway().migrate()
        val draftOrderId = UUID.randomUUID()
        insertImmediate(draftOrderId)

        jdbc.update(
            "UPDATE ordering_order SET state = 'EXPIRED', updated_at = ordering_window_closes_at WHERE id = ?",
            draftOrderId,
        )
        assertThat(jdbc.queryForObject("SELECT state FROM ordering_order WHERE id = ?", String::class.java, draftOrderId))
            .isEqualTo("EXPIRED")

        assertThatThrownBy { insertImmediatePaidWithoutSettlement(UUID.randomUUID()) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("Order requires exactly one settlement input snapshot")
    }

    @Test
    fun `V89 permits cutoff shortening and rejects extension or checkout snapshot mutation`() {
        flyway().migrate()
        val orderId = UUID.randomUUID()
        insertImmediate(orderId)

        jdbc.update(
            "UPDATE ordering_order SET ordering_window_closes_at = ordering_window_closes_at - interval '1 hour' WHERE id = ?",
            orderId,
        )
        assertThatThrownBy {
            jdbc.update(
                "UPDATE ordering_order SET ordering_window_closes_at = ordering_window_closes_at + interval '2 hours' WHERE id = ?",
                orderId,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbc.update(
                "UPDATE ordering_order SET checkout_input_snapshot = '{\"schemaVersion\":1,\"changed\":true}'::jsonb WHERE id = ?",
                orderId,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private fun insertLegacy(orderId: UUID) {
        withoutOrderUserTriggers {
            val reference = OrderCreationDatabaseFixture.registerPublicReference(jdbc, orderId)
            jdbc.update(
                """
                INSERT INTO ordering_order (
                    id, customer_id, store_id, pickup_slot_id, state,
                    subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw, currency,
                    reservation_expires_at, created_at, updated_at, version,
                    public_reference, pickup_business_date, pickup_sequence, store_name_snapshot,
                    pickup_window_start_snapshot, pickup_window_end_snapshot
                ) VALUES (?, ?, ?, ?, 'PENDING_PAYMENT', 1000, 0, 0, 1000, 'KRW', ?, ?, ?, 0, ?, ?, 1, 'BeanFlow', ?, ?)
                """.trimIndent(),
                orderId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                Timestamp.from(Instant.parse("2026-09-16T01:05:00Z")),
                Timestamp.from(Instant.parse("2026-09-16T01:00:00Z")),
                Timestamp.from(Instant.parse("2026-09-16T01:00:00Z")),
                reference,
                LocalDate.parse("2026-09-16"),
                Timestamp.from(Instant.parse("2026-09-16T01:30:00Z")),
                Timestamp.from(Instant.parse("2026-09-16T02:00:00Z")),
            )
        }
    }

    private fun insertImmediate(orderId: UUID) {
        val reference = OrderCreationDatabaseFixture.registerPublicReference(jdbc, orderId)
        withoutPointAccrualSourceTrigger {
            jdbc.update(
                """
                    INSERT INTO ordering_order (
                    id, customer_id, store_id, pickup_slot_id, state,
                    subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw, currency,
                    reservation_expires_at, created_at, updated_at, version,
                    public_reference, pickup_business_date, pickup_sequence, store_name_snapshot,
                    pickup_window_start_snapshot, pickup_window_end_snapshot,
                    checkout_mode, ordering_window_closes_at, checkout_input_snapshot, checkout_input_schema_version
                ) VALUES (?, ?, ?, NULL, 'PENDING_PAYMENT', 1000, 0, 0, 1000, 'KRW', NULL, ?, ?, 0,
                    ?, ?, 1, 'BeanFlow', NULL, NULL, 'IMMEDIATE', ?, '{"schemaVersion":1}'::jsonb, 1)
                """.trimIndent(),
                orderId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                Timestamp.from(Instant.parse("2026-09-16T01:00:00Z")),
                Timestamp.from(Instant.parse("2026-09-16T01:00:00Z")),
                reference,
                LocalDate.parse("2026-09-16"),
                Timestamp.from(Instant.parse("2026-09-16T09:00:00Z")),
            )
        }
    }

    private fun insertImmediatePaidWithoutSettlement(orderId: UUID) {
        val reference = OrderCreationDatabaseFixture.registerPublicReference(jdbc, orderId)
        withoutPointAccrualSourceTrigger {
            jdbc.update(
                """
                INSERT INTO ordering_order (
                    id, customer_id, store_id, pickup_slot_id, state,
                    subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw, currency,
                    reservation_expires_at, paid_at, acceptance_warning_at, acceptance_deadline_at,
                    created_at, updated_at, version,
                    public_reference, pickup_business_date, pickup_sequence, store_name_snapshot,
                    pickup_window_start_snapshot, pickup_window_end_snapshot,
                    checkout_mode, ordering_window_closes_at, checkout_input_snapshot, checkout_input_schema_version
                ) VALUES (?, ?, ?, NULL, 'PAID', 1000, 0, 0, 1000, 'KRW', NULL,
                    TIMESTAMPTZ '2026-09-16 01:00:00Z', TIMESTAMPTZ '2026-09-16 01:02:00Z',
                    TIMESTAMPTZ '2026-09-16 01:03:00Z', TIMESTAMPTZ '2026-09-16 01:00:00Z',
                    TIMESTAMPTZ '2026-09-16 01:00:00Z', 0, ?, DATE '2026-09-16', 1, 'BeanFlow',
                    NULL, NULL, 'IMMEDIATE', TIMESTAMPTZ '2026-09-16 09:00:00Z', '{"schemaVersion":1}'::jsonb, 1)
                """.trimIndent(),
                orderId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                reference,
            )
        }
    }

    private fun withoutPointAccrualSourceTrigger(block: () -> Unit) {
        jdbc.execute("ALTER TABLE ordering_order DISABLE TRIGGER ordering_order_requires_point_accrual_source")
        try {
            block()
        } finally {
            jdbc.execute("ALTER TABLE ordering_order ENABLE TRIGGER ordering_order_requires_point_accrual_source")
        }
    }

    private fun withoutOrderUserTriggers(block: () -> Unit) {
        jdbc.execute("ALTER TABLE ordering_order DISABLE TRIGGER USER")
        try {
            block()
        } finally {
            jdbc.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
        }
    }

    private fun flyway(
        cleanDisabled: Boolean = true,
        target: MigrationVersion? = null,
    ): Flyway {
        val configuration =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .cleanDisabled(cleanDisabled)
        target?.let(configuration::target)
        return configuration.load()
    }
}

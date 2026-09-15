@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal class OrderRejectionSettlementEvidenceMigrationTest : IsolatedPostgresSupport() {
    private val jdbcTemplate by lazy {
        JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @BeforeEach
    fun resetToV86() {
        flyway(cleanDisabled = false).clean()
        flyway(target = "86").migrate()
    }

    @Test
    fun `V87 backfills exact timeout event evidence without changing publications`() {
        val fixture = insertRejected("SYSTEM_TIMEOUT")
        val beforePublication = value<String>("SELECT serialized_event FROM event_publication WHERE id = ?", fixture.publicationId)
        val beforeAttempts = value<Int>("SELECT completion_attempts FROM event_publication WHERE id = ?", fixture.publicationId)

        flyway(target = "87").migrate()

        val evidence =
            jdbcTemplate.queryForMap(
                "SELECT rejection_cause, rejection_actor_type, rejection_event_id, rejection_terminal_version " +
                    "FROM ordering_order WHERE id = ?",
                fixture.orderId,
            )
        assertThat(evidence["rejection_cause"]).isEqualTo("ACCEPTANCE_TIMEOUT")
        assertThat(evidence["rejection_actor_type"]).isEqualTo("SYSTEM_TIMEOUT")
        assertThat(evidence["rejection_event_id"]).isEqualTo(fixture.eventId)
        assertThat((evidence["rejection_terminal_version"] as Number).toLong()).isEqualTo(7L)
        assertThat(value<String>("SELECT serialized_event FROM event_publication WHERE id = ?", fixture.publicationId))
            .isEqualTo(beforePublication)
        assertThat(value<Int>("SELECT completion_attempts FROM event_publication WHERE id = ?", fixture.publicationId))
            .isEqualTo(beforeAttempts)

        assertThatThrownBy {
            jdbcTemplate.update(
                "UPDATE ordering_order SET rejection_cause = 'STORE_REJECTION', " +
                    "rejection_actor_type = 'STORE_OWNER' WHERE id = ?",
                fixture.orderId,
            )
        }.hasMessageContaining("immutable")
    }

    @Test
    fun `V87 leaves a mismatched source unresolved instead of inferring from rejection reason`() {
        val fixture = insertRejected("STORE_OWNER", sourceVersion = 6)

        flyway(target = "87").migrate()

        val evidence =
            jdbcTemplate.queryForMap(
                "SELECT rejection_cause, rejection_actor_type, rejection_event_id, rejection_terminal_version " +
                    "FROM ordering_order WHERE id = ?",
                fixture.orderId,
            )
        assertThat(evidence.values).allMatch { it == null }
        assertThat(value<String>("SELECT rejection_reason FROM ordering_order WHERE id = ?", fixture.orderId))
            .isEqualTo("STORE_ACCEPTANCE_TIMEOUT")
        assertThat(value<Int>("SELECT completion_attempts FROM event_publication WHERE id = ?", fixture.publicationId))
            .isEqualTo(6)
    }

    private fun insertRejected(
        actorType: String,
        sourceVersion: Long = 7,
    ): Fixture {
        val orderId = UUID.randomUUID()
        val customerId = UUID.randomUUID()
        val storeId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val publicationId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
            storeId,
        )
        val publicReference = OrderCreationDatabaseFixture.registerPublicReference(jdbcTemplate, orderId)
        jdbcTemplate.execute("ALTER TABLE ordering_order DISABLE TRIGGER USER")
        try {
            jdbcTemplate.update(
                """
                INSERT INTO ordering_order (
                    id, customer_id, store_id, pickup_slot_id,
                    public_reference, pickup_business_date, pickup_sequence,
                    store_name_snapshot, pickup_window_start_snapshot, pickup_window_end_snapshot,
                    state, subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw,
                    currency, reservation_expires_at, paid_at, acceptance_warning_at,
                    acceptance_deadline_at, rejected_at, rejection_reason,
                    created_at, updated_at, version
                ) VALUES (?, ?, ?, ?, ?, DATE '2026-09-14', ?,
                          'Migration Store', '2026-09-14T00:00:00Z', '2026-09-14T00:10:00Z',
                          'REJECTED', 1000, 0, 0, 1000, 'KRW', NULL,
                          ?, ?, ?, ?, 'STORE_ACCEPTANCE_TIMEOUT', ?, ?, 7)
                """.trimIndent(),
                orderId,
                customerId,
                storeId,
                UUID.randomUUID(),
                publicReference,
                OrderCreationDatabaseFixture.pickupSequence(orderId),
                Timestamp.from(PAID_AT),
                Timestamp.from(PAID_AT.plusSeconds(120)),
                Timestamp.from(PAID_AT.plusSeconds(180)),
                Timestamp.from(REJECTED_AT),
                Timestamp.from(CREATED_AT),
                Timestamp.from(REJECTED_AT),
            )
        } finally {
            jdbcTemplate.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
        }
        jdbcTemplate.execute("ALTER TABLE operations_order_compensation_case DISABLE TRIGGER USER")
        try {
            jdbcTemplate.update(
                """
                INSERT INTO operations_order_compensation_case (
                    id, order_id, terminal_order_version, customer_id, store_id,
                    event_id, trigger, source_reference, state, correlation_id,
                    created_at, updated_at
                ) VALUES (?, ?, 7, ?, ?, ?, 'STORE_REJECTION', ?, 'MANUAL_REVIEW', ?, ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                orderId,
                customerId,
                storeId,
                eventId,
                "order:$orderId:rejection:$sourceVersion",
                "correlation:$orderId",
                Timestamp.from(REJECTED_AT),
                Timestamp.from(REJECTED_AT),
            )
        } finally {
            jdbcTemplate.execute("ALTER TABLE operations_order_compensation_case ENABLE TRIGGER USER")
        }
        val serialized =
            """{"envelope":{"eventId":"$eventId","eventType":"OrderRejectedV1","aggregateId":"$orderId","aggregateVersion":7,"occurredAt":"$REJECTED_AT","payloadVersion":1,"correlationId":"correlation:$orderId","causationId":"test"},"orderId":"$orderId","customerId":"$customerId","storeId":"$storeId","actorType":"$actorType","rejectedAt":"$REJECTED_AT"}"""
        jdbcTemplate.update(
            """
            INSERT INTO event_publication (
                id, listener_id, event_type, serialized_event, publication_date,
                completion_date, status, completion_attempts, last_resubmission_date
            ) VALUES (?, 'beanflow.order-compensation.order-rejected.payment.v1',
                      'io.github.kdh949.beanflow.eventing.api.OrderRejectedV1', ?, ?, NULL, 'FAILED', 6, ?)
            """.trimIndent(),
            publicationId,
            serialized,
            Timestamp.from(REJECTED_AT),
            Timestamp.from(REJECTED_AT.plusSeconds(300)),
        )
        return Fixture(orderId, eventId, publicationId)
    }

    private inline fun <reified T : Any> value(
        sql: String,
        vararg arguments: Any,
    ): T = requireNotNull(jdbcTemplate.queryForObject(sql, T::class.java, *arguments))

    private fun flyway(
        target: String? = null,
        cleanDisabled: Boolean = true,
    ): Flyway {
        val configuration =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .cleanDisabled(cleanDisabled)
        if (target != null) configuration.target(target)
        return configuration.load()
    }

    private data class Fixture(
        val orderId: UUID,
        val eventId: UUID,
        val publicationId: UUID,
    )

    private companion object {
        val CREATED_AT: Instant = Instant.parse("2026-09-14T00:00:00Z")
        val PAID_AT: Instant = Instant.parse("2026-09-14T00:01:00Z")
        val REJECTED_AT: Instant = Instant.parse("2026-09-14T00:02:00Z")
    }
}

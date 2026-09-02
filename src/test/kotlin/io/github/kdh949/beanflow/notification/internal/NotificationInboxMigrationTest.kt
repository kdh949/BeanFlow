package io.github.kdh949.beanflow.notification.internal

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal class NotificationInboxMigrationTest : IsolatedPostgresSupport() {
    private val jdbc by lazy { JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)) }

    @BeforeEach
    fun migrateFromCleanDatabase() {
        flyway(cleanDisabled = false).clean()
        flyway().migrate()
    }

    @Test
    fun `V68 creates customer inbox preference and bounded lookup indexes`() {
        assertThat(
            jdbc.queryForObject(
                "SELECT max(CAST(version AS integer)) FROM flyway_schema_history WHERE success",
                Int::class.java,
            ),
        ).isEqualTo(68)

        assertThat(indexDefinition("ix_notification_inbox_customer_recent"))
            .contains("customer_id", "created_at DESC", "id DESC")
        assertThat(indexDefinition("ix_notification_inbox_customer_unread"))
            .contains("customer_id", "WHERE (read_at IS NULL)")
        assertThat(indexDefinition("ix_notification_inbox_retention"))
            .contains("retention_expires_at", "id")
    }

    @Test
    fun `V68 enforces inbox ownership source target and retention fields`() {
        val customerId = UUID.randomUUID()
        val itemId = UUID.randomUUID()
        insertInbox(itemId, customerId, "source:one", "NONE", null)

        assertThat(
            jdbc.queryForMap(
                "SELECT classification, target_type, target_reference, read_at FROM notification_inbox_item WHERE id = ?",
                itemId,
            ),
        ).containsEntry("classification", "TRANSACTIONAL").containsEntry("target_type", "NONE")

        assertThatThrownBy { insertInbox(UUID.randomUUID(), customerId, "source:one", "NONE", null) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertInbox(UUID.randomUUID(), customerId, "source:two", "ORDER", null) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertInbox(UUID.randomUUID(), customerId, "source:three", "ORDER", "not-public") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `V68 makes delivery order optional only for customer marketing classification`() {
        val customerId = UUID.randomUUID()
        insertDelivery(UUID.randomUUID(), customerId, null, "MARKETING", "marketing:allowed")
        insertDelivery(
            UUID.randomUUID(),
            customerId,
            null,
            "MARKETING",
            "marketing:skipped",
            state = "SKIPPED",
            nextAttemptAt = null,
        )

        assertThatThrownBy {
            insertDelivery(UUID.randomUUID(), customerId, null, "TRANSACTIONAL", "transactional:missing-order")
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertDelivery(
                UUID.randomUUID(),
                customerId,
                UUID.randomUUID(),
                "TRANSACTIONAL",
                "transactional:skipped",
                state = "SKIPPED",
                nextAttemptAt = null,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertDelivery(UUID.randomUUID(), customerId, UUID.randomUUID(), "MARKETING", "marketing:has-order")
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertDelivery(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "MARKETING",
                "store:marketing",
                recipientType = "STORE",
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private fun insertInbox(
        id: UUID,
        customerId: UUID,
        logicalSource: String,
        targetType: String,
        targetReference: String?,
    ) {
        jdbc.update(
            """
            INSERT INTO notification_inbox_item (
                id, customer_id, logical_source, order_id, classification, template,
                title, body, target_type, target_reference, read_at, created_at, retention_expires_at
            ) VALUES (?, ?, ?, ?, 'TRANSACTIONAL', 'ORDER_READY', '준비 완료', '픽업해 주세요.', ?, ?, NULL,
                TIMESTAMPTZ '2026-08-26T00:00:00Z', TIMESTAMPTZ '2026-11-24T00:00:00Z')
            """.trimIndent(),
            id,
            customerId,
            logicalSource,
            UUID.randomUUID(),
            targetType,
            targetReference,
        )
    }

    private fun insertDelivery(
        id: UUID,
        recipientId: UUID,
        orderId: UUID?,
        classification: String,
        logicalSource: String,
        recipientType: String = "CUSTOMER",
        state: String = "PENDING",
        nextAttemptAt: Instant? = Instant.parse("2026-08-26T00:00:00Z"),
    ) {
        jdbc.update(
            """
            INSERT INTO notification_delivery (
                id, event_id, event_type, logical_source, order_id, recipient_type, recipient_id,
                logical_channel, classification, template, payload_json, state, attempt_count,
                next_attempt_at, provider_idempotency_key, correlation_id, created_at, updated_at, version
            ) VALUES (?, ?, 'MigrationContractV1', ?, ?, ?, ?, 'CUSTOMER_APP', ?,
                'SUPPORT_GOODWILL_COMPENSATION_ISSUED', '{}', ?, 0,
                ?, ?, 'migration-contract',
                TIMESTAMPTZ '2026-08-26T00:00:00Z', TIMESTAMPTZ '2026-08-26T00:00:00Z', 0)
            """.trimIndent(),
            id,
            UUID.randomUUID(),
            logicalSource,
            orderId,
            recipientType,
            recipientId,
            classification,
            state,
            nextAttemptAt?.let(Timestamp::from),
            "provider:$logicalSource",
        )
    }

    private fun indexDefinition(indexName: String): String =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ?",
                String::class.java,
                indexName,
            ),
        )

    private fun flyway(cleanDisabled: Boolean = true): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .target("68")
            .cleanDisabled(cleanDisabled)
            .load()
}

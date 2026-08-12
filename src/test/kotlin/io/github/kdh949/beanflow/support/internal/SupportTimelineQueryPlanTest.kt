@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.BEANFLOW_POSTGRES_IMAGE
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
internal class SupportTimelineQueryPlanTest {
    companion object {
        private const val ROW_COUNT = 20_000
        private const val LIMIT = 20

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(BEANFLOW_POSTGRES_IMAGE)
    }

    private val jdbcTemplate by lazy {
        JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @BeforeEach
    fun migrate() {
        flyway(cleanDisabled = false).clean()
        flyway().migrate()
    }

    @Test
    fun `V43 changes identical refund and notification fixtures from sequential to ordered index scans`() {
        val refundOrderId = UUID.fromString("53000000-0000-0000-0000-000000000001")
        val notificationOrderId = UUID.fromString("53000000-0000-0000-0000-000000000002")
        seedRefunds(refundOrderId)
        seedNotifications(notificationOrderId)

        val refundPlans =
            comparePlans(
                indexName = "idx_payment_refund_order_timeline",
                createSql =
                    "CREATE INDEX idx_payment_refund_order_timeline " +
                        "ON payment_refund (order_id, updated_at DESC, id DESC)",
                tableName = "payment_refund",
                orderId = refundOrderId,
            )
        val notificationPlans =
            comparePlans(
                indexName = "idx_notification_delivery_order_timeline",
                createSql =
                    "CREATE INDEX idx_notification_delivery_order_timeline " +
                        "ON notification_delivery (order_id, updated_at DESC, id DESC)",
                tableName = "notification_delivery",
                orderId = notificationOrderId,
            )

        assertThat(refundPlans.withoutIndex).contains("Seq Scan on payment_refund")
        assertThat(refundPlans.withIndex).contains("Index Scan using idx_payment_refund_order_timeline")
        assertThat(notificationPlans.withoutIndex).contains("Seq Scan on notification_delivery")
        assertThat(notificationPlans.withIndex).contains("Index Scan using idx_notification_delivery_order_timeline")

        println("SUPPORT_TIMELINE_EXPLAIN_FIXTURE rows=$ROW_COUNT limit=$LIMIT")
        println("PAYMENT_REFUND_WITHOUT_INDEX\n${refundPlans.withoutIndex}")
        println("PAYMENT_REFUND_WITH_INDEX\n${refundPlans.withIndex}")
        println("NOTIFICATION_DELIVERY_WITHOUT_INDEX\n${notificationPlans.withoutIndex}")
        println("NOTIFICATION_DELIVERY_WITH_INDEX\n${notificationPlans.withIndex}")
    }

    private fun comparePlans(
        indexName: String,
        createSql: String,
        tableName: String,
        orderId: UUID,
    ): Plans {
        jdbcTemplate.execute("ANALYZE $tableName")
        jdbcTemplate.execute("DROP INDEX $indexName")
        val withoutIndex = explain(tableName, orderId)
        jdbcTemplate.execute(createSql)
        jdbcTemplate.execute("ANALYZE $tableName")
        val withIndex = explain(tableName, orderId)
        return Plans(withoutIndex, withIndex)
    }

    private fun explain(
        tableName: String,
        orderId: UUID,
    ): String =
        jdbcTemplate
            .queryForList(
                """
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT id, state, updated_at
                  FROM $tableName
                 WHERE order_id = ?
                 ORDER BY updated_at DESC, id DESC
                 LIMIT $LIMIT
                """.trimIndent(),
                String::class.java,
                orderId,
            ).joinToString("\n")

    private fun seedRefunds(targetOrderId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO payment_payment (
                id, order_id, type, approval_state, approved_amount_krw, currency,
                benefit_snapshot_reference, source_reference, correlation_id,
                requested_amount_krw, approved_at, created_at, updated_at
            )
            SELECT md5('support-plan-payment:' || series)::uuid,
                   CASE WHEN series = 1 THEN ?::uuid ELSE md5('support-plan-refund-order:' || series)::uuid END,
                   'BENEFIT_ONLY', 'APPROVED', 0, 'KRW',
                   'support-plan-benefit:' || series, 'support-plan-payment:' || series,
                   'support-plan-correlation:' || series, 0,
                   timestamptz '2026-08-12 00:00:00+00' + series * interval '1 second',
                   timestamptz '2026-08-12 00:00:00+00' + series * interval '1 second',
                   timestamptz '2026-08-12 00:00:00+00' + series * interval '1 second'
              FROM generate_series(1, $ROW_COUNT) AS series
            """.trimIndent(),
            targetOrderId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO payment_refund (
                id, payment_id, order_id, requested_amount_krw, requested_points_krw,
                reason, state, provider_idempotency_key, source_reference, created_at, updated_at
            )
            SELECT md5('support-plan-refund:' || series)::uuid,
                   md5('support-plan-payment:' || series)::uuid,
                   CASE WHEN series = 1 THEN ?::uuid ELSE md5('support-plan-refund-order:' || series)::uuid END,
                   0, 1, 'QUERY_PLAN', 'FAILED',
                   'support-plan-refund-provider:' || series,
                   'support-plan-refund-source:' || series,
                   timestamptz '2026-08-12 00:00:00+00' + series * interval '1 second',
                   timestamptz '2026-08-12 00:00:00+00' + series * interval '1 second'
              FROM generate_series(1, $ROW_COUNT) AS series
            """.trimIndent(),
            targetOrderId,
        )
    }

    private fun seedNotifications(targetOrderId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO notification_delivery (
                id, event_id, event_type, order_id, recipient_type, recipient_id,
                logical_channel, template, payload_json, state, next_attempt_at,
                provider_idempotency_key, correlation_id, logical_source, created_at, updated_at
            )
            SELECT md5('support-plan-notification:' || series)::uuid,
                   md5('support-plan-event:' || series)::uuid,
                   'SUPPORT_TIMELINE_PLAN',
                   CASE WHEN series = 1 THEN ?::uuid ELSE md5('support-plan-notification-order:' || series)::uuid END,
                   'CUSTOMER', md5('support-plan-recipient:' || series)::uuid,
                   'CUSTOMER_APP', 'ORDER_READY', '{}', 'PENDING',
                   timestamptz '2026-08-13 00:00:00+00',
                   'support-plan-notification-provider:' || series,
                   'support-plan-notification-correlation:' || series,
                   'support-plan-notification-source:' || series,
                   timestamptz '2026-08-12 00:00:00+00' + series * interval '1 second',
                   timestamptz '2026-08-12 00:00:00+00' + series * interval '1 second'
              FROM generate_series(1, $ROW_COUNT) AS series
            """.trimIndent(),
            targetOrderId,
        )
    }

    private fun flyway(cleanDisabled: Boolean = true): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .cleanDisabled(cleanDisabled)
            .load()

    private data class Plans(
        val withoutIndex: String,
        val withIndex: String,
    )
}

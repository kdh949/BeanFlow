@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.support.internal

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

internal class SupportTimelineMigrationTest : IsolatedPostgresSupport() {
    companion object {
        val NOW: Instant = Instant.parse("2026-08-12T04:00:00Z")
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
    fun `V43 adds action verification scope without opening arbitrary values`() {
        val caseId = UUID.randomUUID()
        val linkId = UUID.randomUUID()
        val subjectId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO support_case (
                id, requester_type, requester_reference, category, priority, reason, state,
                current_assignee_id, opened_at, last_changed_at, version, retention_policy_version_id
            ) VALUES (?, 'CUSTOMER', 'subject-ref', 'ORDER_STATUS', 'NORMAL', 'ORDER_STATUS_CHECK', 'OPEN',
                      ?, ?, ?, 0, 7)
            """.trimIndent(),
            caseId,
            UUID.randomUUID(),
            Timestamp.from(NOW),
            Timestamp.from(NOW),
        )
        jdbcTemplate.update(
            """
            INSERT INTO support_case_subject_link (
                id, support_case_id, subject_type, subject_id, relationship, linked_by_actor_id, reason, linked_at
            ) VALUES (?, ?, 'CUSTOMER', ?, 'REQUESTER', ?, 'ORDER_ACTION_SUBJECT', ?)
            """.trimIndent(),
            linkId,
            caseId,
            subjectId,
            UUID.randomUUID(),
            Timestamp.from(NOW),
        )

        insertSession(UUID.randomUUID(), caseId, linkId, subjectId, "SUPPORT_ACTION")
        insertSession(UUID.randomUUID(), caseId, linkId, subjectId, "PERSONAL_DATA_REVEAL")
        assertThatThrownBy {
            insertSession(UUID.randomUUID(), caseId, linkId, subjectId, "UNBOUNDED_ACTION")
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `V43 creates order timeline history indexes`() {
        val indexDefinitions =
            jdbcTemplate.query(
                """
                SELECT indexname, indexdef
                  FROM pg_indexes
                 WHERE schemaname = 'public'
                   AND indexname IN ('idx_payment_refund_order_timeline', 'idx_notification_delivery_order_timeline')
                 ORDER BY indexname
                """.trimIndent(),
            ) { resultSet, _ -> resultSet.getString("indexname") to resultSet.getString("indexdef") }

        assertThat(indexDefinitions).hasSize(2)
        assertThat(indexDefinitions.associate { it })
            .containsEntry(
                "idx_payment_refund_order_timeline",
                "CREATE INDEX idx_payment_refund_order_timeline ON public.payment_refund USING btree (order_id, updated_at DESC, id DESC)",
            ).containsEntry(
                "idx_notification_delivery_order_timeline",
                "CREATE INDEX idx_notification_delivery_order_timeline ON public.notification_delivery USING btree (order_id, updated_at DESC, id DESC)",
            )
    }

    private fun insertSession(
        id: UUID,
        caseId: UUID,
        linkId: UUID,
        subjectId: UUID,
        actionScope: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO support_verification_session (
                id, support_case_id, subject_link_id, subject_type, subject_id, actor_id, purpose, action_scope,
                requested_level, state, invalid_attempts, started_at, expires_at, version
            ) VALUES (?, ?, ?, 'CUSTOMER', ?, ?, 'CASE_RESOLUTION', ?, 'BASIC', 'PENDING', 0, ?, ?, 0)
            """.trimIndent(),
            id,
            caseId,
            linkId,
            subjectId,
            UUID.randomUUID(),
            actionScope,
            Timestamp.from(NOW),
            Timestamp.from(NOW.plusSeconds(900)),
        )
    }

    private fun flyway(cleanDisabled: Boolean = true): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .cleanDisabled(cleanDisabled)
            .load()
}

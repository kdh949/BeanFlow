package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.support.internal.domain.CustomerInquiry
import io.github.kdh949.beanflow.support.internal.domain.CustomerInquiryCategory
import io.github.kdh949.beanflow.support.internal.domain.InquiryMessageAuthor
import io.github.kdh949.beanflow.support.internal.domain.SupportCaseState
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal data class InquirySort(
    val createdAt: Instant,
    val id: UUID,
)

internal data class InquiryMessage(
    val id: UUID,
    val author: InquiryMessageAuthor,
    val content: String,
    val createdAt: Instant,
)

internal data class InquiryReplay(
    val payloadHash: String,
    val inquiryId: UUID,
    val messageId: UUID?,
)

@Repository
internal class CustomerInquiryRepository(
    private val jdbc: JdbcTemplate,
) {
    fun findIdByCase(caseId: UUID): UUID? =
        jdbc
            .query(
                "SELECT id FROM support_customer_inquiry WHERE support_case_id = ?",
                { rs, _ -> rs.getObject("id", UUID::class.java) },
                caseId,
            ).singleOrNull()

    fun find(
        id: UUID,
        lock: Boolean = false,
    ): CustomerInquiry? =
        jdbc
            .query(
                "SELECT * FROM support_customer_inquiry WHERE id = ?${if (lock) " FOR UPDATE" else ""}",
                { rs, _ -> inquiry(rs) },
                id,
            ).singleOrNull()

    fun list(
        customerId: UUID?,
        unclaimed: Boolean,
        after: InquirySort?,
        limit: Int,
    ): List<CustomerInquiry> {
        val clauses = mutableListOf("1=1")
        val args = mutableListOf<Any>()
        customerId?.let {
            clauses += "customer_id = ?"
            args += it
        }
        if (unclaimed) clauses += "support_case_id IS NULL"
        after?.let {
            clauses += "(created_at, id) < (?, ?)"
            args += Timestamp.from(it.createdAt)
            args += it.id
        }
        args += limit
        return jdbc.query(
            "SELECT * FROM support_customer_inquiry WHERE ${clauses.joinToString(" AND ")} ORDER BY created_at DESC, id DESC LIMIT ?",
            { rs, _ -> inquiry(rs) },
            *args.toTypedArray(),
        )
    }

    fun caseStates(ids: Set<UUID>): Map<UUID, SupportCaseState> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc
            .query(
                "SELECT id, state FROM support_case WHERE id IN (${ids.joinToString(",") { "?" }})",
                { rs, _ -> rs.getObject("id", UUID::class.java) to SupportCaseState.valueOf(rs.getString("state")) },
                *ids.toTypedArray(),
            ).toMap()
    }

    fun insert(value: CustomerInquiry) {
        jdbc.update(
            """INSERT INTO support_customer_inquiry
            (id, customer_id, title, category, order_id, order_reference, version, created_at, updated_at, retention_policy_version_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            value.id,
            value.customerId,
            value.title,
            value.category.name,
            value.orderId,
            value.orderReference,
            value.version,
            Timestamp.from(value.createdAt),
            Timestamp.from(value.updatedAt),
            value.retentionPolicyVersionId,
        )
    }

    fun save(value: CustomerInquiry) {
        check(
            jdbc.update(
                "UPDATE support_customer_inquiry SET support_case_id = ?, version = ?, updated_at = ? WHERE id = ?",
                value.caseId,
                value.version,
                Timestamp.from(value.updatedAt),
                value.id,
            ) ==
                1,
        )
    }

    fun message(
        inquiryId: UUID,
        actorId: UUID,
        value: InquiryMessage,
    ) {
        jdbc.update(
            "INSERT INTO support_customer_inquiry_message (id, inquiry_id, author_type, actor_id, content, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            value.id,
            inquiryId,
            value.author.name,
            actorId,
            value.content,
            Timestamp.from(value.createdAt),
        )
    }

    fun messages(
        id: UUID,
        after: InquirySort?,
        limit: Int,
    ): List<InquiryMessage> {
        val args = mutableListOf<Any>(id)
        val clause =
            if (after ==
                null
            ) {
                ""
            } else {
                " AND (created_at, id) < (?, ?)".also {
                    args += Timestamp.from(after.createdAt)
                    args += after.id
                }
            }
        args += limit
        return jdbc.query(
            "SELECT id, author_type, content, created_at FROM support_customer_inquiry_message WHERE inquiry_id = ?$clause ORDER BY created_at DESC, id DESC LIMIT ?",
            { rs, _ ->
                InquiryMessage(
                    rs.getObject("id", UUID::class.java),
                    InquiryMessageAuthor.valueOf(rs.getString("author_type")),
                    rs.getString("content"),
                    rs.getTimestamp("created_at").toInstant(),
                )
            },
            *args.toTypedArray(),
        )
    }

    fun replay(
        actor: UUID,
        operation: String,
        key: String,
        now: Instant,
    ): InquiryReplay? =
        jdbc
            .query(
                "SELECT payload_hash, inquiry_id, message_id FROM support_customer_inquiry_command WHERE actor_id = ? AND operation = ? AND idempotency_key = ? AND expires_at > ?",
                {
                    rs,
                    _,
                    ->
                    InquiryReplay(
                        rs.getString("payload_hash"),
                        rs.getObject("inquiry_id", UUID::class.java),
                        rs.getObject("message_id", UUID::class.java),
                    )
                },
                actor,
                operation,
                key,
                Timestamp.from(now),
            ).singleOrNull()

    fun remember(
        actor: UUID,
        operation: String,
        key: String,
        hash: String,
        inquiryId: UUID,
        messageId: UUID?,
        now: Instant,
    ) {
        jdbc.update(
            "DELETE FROM support_customer_inquiry_command WHERE actor_id = ? AND operation = ? AND idempotency_key = ? AND expires_at <= ?",
            actor,
            operation,
            key,
            Timestamp.from(now),
        )
        jdbc.update(
            "INSERT INTO support_customer_inquiry_command (actor_id, operation, idempotency_key, payload_hash, inquiry_id, message_id, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            actor,
            operation,
            key,
            hash,
            inquiryId,
            messageId,
            Timestamp.from(now),
            Timestamp.from(
                now.plusSeconds(90L * 86400),
            ),
        )
    }

    private fun inquiry(rs: ResultSet) =
        CustomerInquiry(
            rs.getObject("id", UUID::class.java),
            rs.getObject("customer_id", UUID::class.java),
            rs.getString("title"),
            CustomerInquiryCategory.valueOf(rs.getString("category")),
            rs.getObject("order_id", UUID::class.java),
            rs.getString("order_reference"),
            rs.getObject("support_case_id", UUID::class.java),
            rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getLong("retention_policy_version_id"),
        )
}

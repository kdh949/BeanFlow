package io.github.kdh949.beanflow.demo.internal

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal data class DemoWorkspace(
    val id: UUID,
    val browserHash: String,
    val startKey: String,
    val mode: String,
    val customerId: UUID,
    val merchantId: UUID,
    val storeId: UUID,
    val menuId: UUID,
    val customerSessionId: String,
    val merchantSessionId: String,
    val orderReference: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val endedAt: Instant?,
) {
    fun active(now: Instant) = endedAt == null && now.isBefore(expiresAt)

    override fun toString(): String = "DemoWorkspace(id=$id)"
}

@Repository
internal class DemoWorkspaceRepository(
    private val jdbc: JdbcTemplate,
) {
    fun lockAdmission() {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(132001)", Any::class.java)
    }

    fun latest(
        hash: String,
        lock: Boolean = false,
    ): DemoWorkspace? =
        jdbc
            .query(
                "SELECT * FROM demo_workspace WHERE browser_hash = ? " +
                    "ORDER BY ordinal DESC LIMIT 1" + if (lock) " FOR UPDATE" else "",
                { rs, _ -> map(rs) },
                hash,
            ).singleOrNull()

    fun replay(
        hash: String,
        key: String,
    ): DemoWorkspace? =
        jdbc.query("SELECT * FROM demo_workspace WHERE browser_hash = ? AND start_key = ?", { rs, _ -> map(rs) }, hash, key).singleOrNull()

    fun findById(
        id: UUID,
        lock: Boolean = false,
    ): DemoWorkspace? =
        jdbc
            .query(
                "SELECT * FROM demo_workspace WHERE id = ?" + if (lock) " FOR UPDATE" else "",
                { rs, _ -> map(rs) },
                id,
            ).singleOrNull()

    fun activeCount(now: Instant): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM demo_workspace WHERE ended_at IS NULL AND expires_at > ?",
            Int::class.java,
            Timestamp.from(now),
        )!!

    fun dailyCount(
        since: Instant,
        hash: String? = null,
    ): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM demo_workspace WHERE created_at >= ?" + if (hash != null) " AND browser_hash = ?" else "",
            Int::class.java,
            *listOfNotNull<Any>(Timestamp.from(since), hash).toTypedArray(),
        )!!

    fun insert(w: DemoWorkspace) {
        jdbc.update(
            """INSERT INTO demo_workspace
            (id,browser_hash,start_key,mode,customer_id,merchant_id,store_id,menu_id,
             customer_session_id,merchant_session_id,order_reference,created_at,expires_at,ended_at)
             VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            w.id,
            w.browserHash,
            w.startKey,
            w.mode,
            w.customerId,
            w.merchantId,
            w.storeId,
            w.menuId,
            w.customerSessionId,
            w.merchantSessionId,
            w.orderReference,
            Timestamp.from(w.createdAt),
            Timestamp.from(w.expiresAt),
            null,
        )
    }

    fun track(
        id: UUID,
        reference: String,
    ) {
        jdbc.update("UPDATE demo_workspace SET order_reference = ? WHERE id = ?", reference, id)
    }

    fun end(
        id: UUID,
        now: Instant,
    ) {
        jdbc.update("UPDATE demo_workspace SET ended_at = ? WHERE id = ?", Timestamp.from(now), id)
    }

    fun expiredIds(now: Instant): List<UUID> =
        jdbc.query(
            "SELECT id FROM demo_workspace WHERE ended_at IS NULL AND expires_at <= ? ORDER BY expires_at, id LIMIT 20",
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            Timestamp.from(now),
        )

    fun command(
        id: UUID,
        key: String,
    ): List<String>? =
        jdbc
            .query(
                "SELECT operation,payload,order_reference FROM demo_workspace_command WHERE workspace_id = ? AND request_key = ?",
                { rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getString(3)) },
                id,
                key,
            ).singleOrNull()

    fun commandCount(id: UUID): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM demo_workspace_command WHERE workspace_id = ? AND operation = 'SAMPLE'",
            Int::class.java,
            id,
        )!!

    fun record(
        id: UUID,
        key: String,
        operation: String,
        payload: String,
        reference: String,
    ) {
        jdbc.update("INSERT INTO demo_workspace_command VALUES (?,?,?,?,?)", id, key, operation, payload, reference)
    }

    private fun map(rs: ResultSet) =
        DemoWorkspace(
            rs.getObject("id", UUID::class.java),
            rs.getString("browser_hash"),
            rs.getString("start_key"),
            rs.getString("mode"),
            rs.getObject("customer_id", UUID::class.java),
            rs.getObject("merchant_id", UUID::class.java),
            rs.getObject("store_id", UUID::class.java),
            rs.getObject("menu_id", UUID::class.java),
            rs.getString("customer_session_id"),
            rs.getString("merchant_session_id"),
            rs.getString("order_reference"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getTimestamp("ended_at")?.toInstant(),
        )
}

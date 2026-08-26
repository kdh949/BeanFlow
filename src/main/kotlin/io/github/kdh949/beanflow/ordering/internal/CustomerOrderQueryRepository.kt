package io.github.kdh949.beanflow.ordering.internal

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal data class CustomerOrderCandidateProjection(
    val orderId: UUID,
    val customerId: UUID,
    val createdAt: Instant,
    val state: String,
    val reservationExpiresAt: Instant?,
)

internal data class CustomerOrderHeaderProjection(
    val orderId: UUID,
    val storeId: UUID,
    val publicReference: String,
    val pickupSequence: Long,
    val storeName: String,
    val state: String,
    val createdAt: Instant,
    val pickupWindowStart: Instant,
    val pickupWindowEnd: Instant,
    val subtotalKrw: Long,
    val couponDiscountKrw: Long,
    val pointsAppliedKrw: Long,
    val payableKrw: Long,
    val currency: String,
    val reservationExpiresAt: Instant?,
    val acceptanceDeadlineAt: Instant?,
    val cancellationCause: String?,
    val paidAt: Instant?,
    val acceptedAt: Instant?,
    val preparingAt: Instant?,
    val readyAt: Instant?,
    val completedAt: Instant?,
    val version: Long,
)

internal data class CustomerOrderLineProjection(
    val orderId: UUID,
    val lineSequence: Int,
    val menuName: String,
    val optionNamesJson: String,
    val quantity: Long,
    val grossKrw: Long,
)

@Repository
internal class CustomerOrderQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val meterRegistry: MeterRegistry,
) {
    fun findCandidates(prepared: PreparedCustomerOrderPage): List<CustomerOrderCandidateProjection> {
        val arguments =
            mutableListOf<Any>(prepared.customerId, Timestamp.from(prepared.fromInclusive), Timestamp.from(prepared.toExclusive))
        val sql =
            buildString {
                append(
                    """
                    SELECT id, customer_id, created_at, state, reservation_expires_at
                      FROM ordering_order
                     WHERE customer_id = ?
                       AND created_at >= ?
                       AND created_at < ?
                    """.trimIndent(),
                )
                appendStatusFilter(prepared.status, arguments)
                prepared.after?.let { after ->
                    append(" AND (created_at, id) < (?, ?)")
                    arguments += Timestamp.from(after.createdAt)
                    arguments += after.orderId
                }
                append(" ORDER BY created_at DESC, id DESC LIMIT ?")
                arguments += prepared.limit + 1
            }
        recordSql(LIST)
        return jdbcTemplate.query(sql, ::candidate, *arguments.toTypedArray())
    }

    fun findHeaders(
        orderIds: List<UUID>,
        prepared: PreparedCustomerOrderPage,
    ): List<CustomerOrderHeaderProjection> {
        val arguments = mutableListOf<Any>()
        val sql =
            if (orderIds.isEmpty()) {
                "$HEADER_SELECT WHERE false"
            } else {
                buildString {
                    append(HEADER_SELECT)
                    append(" WHERE id IN (${placeholders(orderIds.size)})")
                    arguments.addAll(orderIds)
                    append(" AND customer_id = ? AND created_at >= ? AND created_at < ?")
                    arguments += prepared.customerId
                    arguments += Timestamp.from(prepared.fromInclusive)
                    arguments += Timestamp.from(prepared.toExclusive)
                    appendStatusFilter(prepared.status, arguments)
                }
            }
        recordSql(LIST)
        return jdbcTemplate.query(sql, ::header, *arguments.toTypedArray())
    }

    fun findLinesForList(orderIds: List<UUID>): List<CustomerOrderLineProjection> {
        val sql =
            if (orderIds.isEmpty()) {
                "$LINE_SELECT WHERE false"
            } else {
                "$LINE_SELECT WHERE order_id IN (${placeholders(orderIds.size)}) ORDER BY order_id, line_sequence"
            }
        recordSql(LIST)
        return jdbcTemplate.query(sql, ::line, *orderIds.toTypedArray())
    }

    fun findByReference(publicReference: String): CustomerOrderCandidateProjection? {
        recordSql(DETAIL)
        return jdbcTemplate
            .query(
                """
                SELECT id, customer_id, created_at, state, reservation_expires_at
                  FROM ordering_order
                 WHERE public_reference = ?
                """.trimIndent(),
                ::candidate,
                publicReference,
            ).singleOrNull()
    }

    fun findDetailHeader(
        orderId: UUID,
        customerId: UUID,
    ): CustomerOrderHeaderProjection? {
        recordSql(DETAIL)
        return jdbcTemplate
            .query("$HEADER_SELECT WHERE id = ? AND customer_id = ?", ::header, orderId, customerId)
            .singleOrNull()
    }

    fun findDetailLines(orderId: UUID): List<CustomerOrderLineProjection> {
        recordSql(DETAIL)
        return jdbcTemplate.query("$LINE_SELECT WHERE order_id = ? ORDER BY line_sequence", ::line, orderId)
    }

    private fun StringBuilder.appendStatusFilter(
        status: CustomerOrderStatusFilter?,
        arguments: MutableList<Any>,
    ) {
        if (status == null) return
        val states = CustomerOrderPresentationPolicy.states(status).map { it.name }
        append(" AND state IN (${placeholders(states.size)})")
        arguments.addAll(states)
    }

    private fun recordSql(operation: String) {
        meterRegistry.counter("beanflow.customer.order.query.sql", "operation", operation).increment()
    }

    private fun candidate(
        resultSet: ResultSet,
        ignoredRow: Int,
    ) = CustomerOrderCandidateProjection(
        orderId = resultSet.getObject("id", UUID::class.java),
        customerId = resultSet.getObject("customer_id", UUID::class.java),
        createdAt = resultSet.getTimestamp("created_at").toInstant(),
        state = resultSet.getString("state"),
        reservationExpiresAt = resultSet.getTimestamp("reservation_expires_at")?.toInstant(),
    )

    private fun header(
        resultSet: ResultSet,
        ignoredRow: Int,
    ) = CustomerOrderHeaderProjection(
        orderId = resultSet.getObject("id", UUID::class.java),
        storeId = resultSet.getObject("store_id", UUID::class.java),
        publicReference = resultSet.getString("public_reference"),
        pickupSequence = resultSet.getLong("pickup_sequence"),
        storeName = resultSet.getString("store_name_snapshot"),
        state = resultSet.getString("state"),
        createdAt = resultSet.getTimestamp("created_at").toInstant(),
        pickupWindowStart = resultSet.getTimestamp("pickup_window_start_snapshot").toInstant(),
        pickupWindowEnd = resultSet.getTimestamp("pickup_window_end_snapshot").toInstant(),
        subtotalKrw = resultSet.getLong("subtotal_krw"),
        couponDiscountKrw = resultSet.getLong("coupon_discount_krw"),
        pointsAppliedKrw = resultSet.getLong("points_applied_krw"),
        payableKrw = resultSet.getLong("payable_krw"),
        currency = resultSet.getString("currency"),
        reservationExpiresAt = resultSet.getTimestamp("reservation_expires_at")?.toInstant(),
        acceptanceDeadlineAt = resultSet.getTimestamp("acceptance_deadline_at")?.toInstant(),
        cancellationCause = resultSet.getString("cancellation_cause"),
        paidAt = resultSet.getTimestamp("paid_at")?.toInstant(),
        acceptedAt = resultSet.getTimestamp("accepted_at")?.toInstant(),
        preparingAt = resultSet.getTimestamp("preparing_at")?.toInstant(),
        readyAt = resultSet.getTimestamp("ready_at")?.toInstant(),
        completedAt = resultSet.getTimestamp("completed_at")?.toInstant(),
        version = resultSet.getLong("version"),
    )

    private fun line(
        resultSet: ResultSet,
        ignoredRow: Int,
    ) = CustomerOrderLineProjection(
        orderId = resultSet.getObject("order_id", UUID::class.java),
        lineSequence = resultSet.getInt("line_sequence"),
        menuName = resultSet.getString("menu_name"),
        optionNamesJson = resultSet.getString("option_names_json"),
        quantity = resultSet.getLong("quantity"),
        grossKrw = resultSet.getLong("gross_krw"),
    )

    private companion object {
        const val LIST = "list"
        const val DETAIL = "detail"
        val HEADER_SELECT =
            """
            SELECT id, store_id, public_reference, pickup_sequence, store_name_snapshot, state, created_at,
                   pickup_window_start_snapshot, pickup_window_end_snapshot,
                   subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw, currency,
                   reservation_expires_at, acceptance_deadline_at, cancellation_cause,
                   paid_at, accepted_at, preparing_at, ready_at, completed_at, version
              FROM ordering_order
            """.trimIndent()
        val LINE_SELECT =
            """
            SELECT order_id, line_sequence, menu_name, option_names_json, quantity, gross_krw
              FROM ordering_order_line
            """.trimIndent()

        fun placeholders(size: Int): String = List(size) { "?" }.joinToString(", ")
    }
}

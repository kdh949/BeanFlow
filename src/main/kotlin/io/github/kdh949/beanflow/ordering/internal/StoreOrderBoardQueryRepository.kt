package io.github.kdh949.beanflow.ordering.internal

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal data class StoreOrderBoardOrderProjection(
    val orderId: UUID,
    val publicReference: String,
    val pickupSequence: Long,
    val pickupBusinessDate: LocalDate,
    val state: String,
    val pickupWindowStart: Instant,
    val pickupWindowEnd: Instant,
    val acceptanceWarningAt: Instant?,
    val acceptanceDeadlineAt: Instant?,
)

internal data class StoreOrderBoardLineProjection(
    val orderId: UUID,
    val lineSequence: Int,
    val menuName: String,
    val quantity: Long,
)

internal data class StoreOrderBoardRows(
    val orders: List<StoreOrderBoardOrderProjection>,
    val linesByOrderId: Map<UUID, List<StoreOrderBoardLineProjection>>,
)

@Repository
internal class StoreOrderBoardQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val meterRegistry: MeterRegistry,
) {
    fun findExecutableBoard(
        storeId: UUID,
        lane: StoreOrderBoardLane?,
    ): StoreOrderBoardRows {
        val states = lane?.let { listOf(it.toState()) } ?: EXECUTABLE_STATES
        val statePlaceholders = List(states.size) { "?" }.joinToString(", ")
        val arguments = mutableListOf<Any>(storeId).apply { addAll(states) }
        recordSql(LIST)
        val orders =
            jdbcTemplate.query(
                "$ORDER_SELECT WHERE store_id = ? AND state IN ($statePlaceholders) $BOARD_ORDER",
                ::order,
                *arguments.toTypedArray(),
            )
        return StoreOrderBoardRows(orders, findLines(orders.map { it.orderId }, LIST))
    }

    fun findByReferenceAndStoreId(
        publicReference: String,
        storeId: UUID,
    ): StoreOrderBoardRows? {
        recordSql(DETAIL)
        val order =
            jdbcTemplate
                .query(
                    "$ORDER_SELECT WHERE public_reference = ? AND store_id = ?",
                    ::order,
                    publicReference,
                    storeId,
                ).singleOrNull() ?: return null
        return StoreOrderBoardRows(listOf(order), findLines(listOf(order.orderId), DETAIL))
    }

    fun existsByReference(publicReference: String): Boolean {
        recordSql(DETAIL)
        return jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM ordering_order WHERE public_reference = ?)",
            Boolean::class.java,
            publicReference,
        ) == true
    }

    private fun findLines(
        orderIds: List<UUID>,
        operation: String,
    ): Map<UUID, List<StoreOrderBoardLineProjection>> {
        recordSql(operation)
        if (orderIds.isEmpty()) return emptyMap()
        val placeholders = List(orderIds.size) { "?" }.joinToString(", ")
        return jdbcTemplate
            .query(
                "$LINE_SELECT WHERE order_id IN ($placeholders) ORDER BY order_id, line_sequence",
                ::line,
                *orderIds.toTypedArray(),
            ).groupBy { it.orderId }
    }

    private fun recordSql(operation: String) {
        meterRegistry.counter("beanflow.store.order.board.query.sql", "operation", operation).increment()
    }

    private fun order(
        resultSet: ResultSet,
        ignoredRow: Int,
    ) = StoreOrderBoardOrderProjection(
        orderId = resultSet.getObject("id", UUID::class.java),
        publicReference = resultSet.getString("public_reference"),
        pickupSequence = resultSet.getLong("pickup_sequence"),
        pickupBusinessDate = resultSet.getObject("pickup_business_date", LocalDate::class.java),
        state = resultSet.getString("state"),
        pickupWindowStart = resultSet.getTimestamp("pickup_window_start_snapshot").toInstant(),
        pickupWindowEnd = resultSet.getTimestamp("pickup_window_end_snapshot").toInstant(),
        acceptanceWarningAt = resultSet.getTimestamp("acceptance_warning_at")?.toInstant(),
        acceptanceDeadlineAt = resultSet.getTimestamp("acceptance_deadline_at")?.toInstant(),
    )

    private fun line(
        resultSet: ResultSet,
        ignoredRow: Int,
    ) = StoreOrderBoardLineProjection(
        orderId = resultSet.getObject("order_id", UUID::class.java),
        lineSequence = resultSet.getInt("line_sequence"),
        menuName = resultSet.getString("menu_name"),
        quantity = resultSet.getLong("quantity"),
    )

    private fun StoreOrderBoardLane.toState(): String =
        when (this) {
            StoreOrderBoardLane.PENDING_ACCEPTANCE -> "PAID"
            StoreOrderBoardLane.ACCEPTED -> "ACCEPTED"
            StoreOrderBoardLane.PREPARING -> "PREPARING"
            StoreOrderBoardLane.READY -> "READY"
        }

    private companion object {
        const val LIST = "list"
        const val DETAIL = "detail"
        val EXECUTABLE_STATES = listOf("PAID", "ACCEPTED", "PREPARING", "READY")
        val ORDER_SELECT =
            """
            SELECT id, public_reference, pickup_sequence, pickup_business_date, state,
                   pickup_window_start_snapshot, pickup_window_end_snapshot,
                   acceptance_warning_at, acceptance_deadline_at
              FROM ordering_order
            """.trimIndent()
        val LINE_SELECT =
            """
            SELECT order_id, line_sequence, menu_name, quantity
              FROM ordering_order_line
            """.trimIndent()
        val BOARD_ORDER =
            """
            ORDER BY pickup_business_date,
                     CASE state WHEN 'PAID' THEN 0 WHEN 'ACCEPTED' THEN 1 WHEN 'PREPARING' THEN 2 ELSE 3 END,
                     CASE WHEN state = 'PAID' THEN acceptance_deadline_at ELSE pickup_window_start_snapshot END,
                     id
            """.trimIndent()
    }
}

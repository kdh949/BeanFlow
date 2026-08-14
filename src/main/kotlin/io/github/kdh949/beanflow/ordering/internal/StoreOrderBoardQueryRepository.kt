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

internal data class StoreOrderBoardOverflowBoundary(
    val lane: StoreOrderBoardLane,
    val overflowCount: Long,
    val boundary: StoreOrderBoardOrderProjection,
)

internal data class StoreOrderBoardSnapshotRows(
    val rows: StoreOrderBoardRows,
    val overflow: List<StoreOrderBoardOverflowBoundary>,
)

internal data class StoreOrderBoardOverflowPageRows(
    val rows: StoreOrderBoardRows,
    val nextBoundary: StoreOrderBoardOrderProjection?,
)

@Repository
internal class StoreOrderBoardQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val meterRegistry: MeterRegistry,
) {
    fun findExecutableBoard(
        storeId: UUID,
        lane: StoreOrderBoardLane?,
    ): StoreOrderBoardSnapshotRows {
        recordSql(LIST)
        val lanes = lane?.let(::listOf) ?: StoreOrderBoardLane.entries
        val candidates =
            jdbcTemplate.query(
                StoreOrderBoardQuerySql.primaryBoard(lanes),
                ::order,
                *List(lanes.size) { storeId }.toTypedArray(),
            )
        val visibleByLane =
            lanes.associateWith { selectedLane ->
                candidates
                    .asSequence()
                    .filter { it.state == StoreOrderBoardQuerySql.state(selectedLane) }
                    .sortedWith(boardOrder(selectedLane))
                    .take(StoreOrderBoardPaging.PAGE_SIZE)
                    .toList()
            }
        val overflowLanes =
            lanes.filter { selectedLane ->
                candidates.count { it.state == StoreOrderBoardQuerySql.state(selectedLane) } > StoreOrderBoardPaging.PAGE_SIZE
            }
        val totalCounts = if (overflowLanes.isEmpty()) emptyMap() else countByLane(storeId, overflowLanes)
        val overflow =
            overflowLanes.map { selectedLane ->
                val total = totalCounts[selectedLane] ?: dependency("Store order board overflow count is missing")
                if (total <= StoreOrderBoardPaging.PAGE_SIZE) dependency("Store order board overflow count is inconsistent")
                StoreOrderBoardOverflowBoundary(
                    lane = selectedLane,
                    overflowCount = total - StoreOrderBoardPaging.PAGE_SIZE,
                    boundary =
                        visibleByLane.getValue(selectedLane).lastOrNull()
                            ?: dependency("Store order board overflow boundary is missing"),
                )
            }
        val orders = lanes.flatMap { visibleByLane.getValue(it) }
        return StoreOrderBoardSnapshotRows(
            rows = StoreOrderBoardRows(orders, findLines(orders.map { it.orderId }, LIST)),
            overflow = overflow,
        )
    }

    fun findOverflowPage(
        storeId: UUID,
        lane: StoreOrderBoardLane,
        after: StoreOrderBoardSort,
    ): StoreOrderBoardOverflowPageRows {
        recordSql(OVERFLOW)
        val fetched =
            jdbcTemplate.query(
                StoreOrderBoardQuerySql.overflowPage(lane),
                ::order,
                storeId,
                StoreOrderBoardQuerySql.state(lane),
                java.sql.Timestamp.from(after.sortAt),
                after.orderId,
            )
        val orders = fetched.take(StoreOrderBoardPaging.PAGE_SIZE)
        return StoreOrderBoardOverflowPageRows(
            rows = StoreOrderBoardRows(orders, findLines(orders.map { it.orderId }, OVERFLOW)),
            nextBoundary = if (fetched.size > StoreOrderBoardPaging.PAGE_SIZE) orders.lastOrNull() else null,
        )
    }

    fun findByReferenceAndStoreId(
        publicReference: String,
        storeId: UUID,
    ): StoreOrderBoardRows? {
        recordSql(DETAIL)
        val order =
            jdbcTemplate
                .query(
                    "${StoreOrderBoardQuerySql.orderSelect()} WHERE public_reference = ? AND store_id = ?",
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

    private fun countByLane(
        storeId: UUID,
        lanes: List<StoreOrderBoardLane>,
    ): Map<StoreOrderBoardLane, Long> {
        val statePlaceholders = List(lanes.size) { "?" }.joinToString(", ")
        val arguments = mutableListOf<Any>(storeId).apply { addAll(lanes.map(StoreOrderBoardQuerySql::state)) }
        recordSql(LIST)
        return jdbcTemplate
            .query(
                StoreOrderBoardQuerySql.overflowCount(statePlaceholders),
                { resultSet, _ -> laneForState(resultSet.getString("state")) to resultSet.getLong("total_count") },
                *arguments.toTypedArray(),
            ).toMap()
    }

    private fun boardSortAt(
        lane: StoreOrderBoardLane,
        order: StoreOrderBoardOrderProjection,
    ): Instant =
        when (lane) {
            StoreOrderBoardLane.PENDING_ACCEPTANCE -> {
                order.acceptanceDeadlineAt ?: dependency("Paid store order has no acceptance deadline")
            }

            StoreOrderBoardLane.ACCEPTED,
            StoreOrderBoardLane.PREPARING,
            StoreOrderBoardLane.READY,
            -> {
                order.pickupWindowStart
            }
        }

    private fun boardOrder(lane: StoreOrderBoardLane): Comparator<StoreOrderBoardOrderProjection> =
        Comparator { left, right ->
            val sortAtComparison = boardSortAt(lane, left).compareTo(boardSortAt(lane, right))
            if (sortAtComparison != 0) {
                sortAtComparison
            } else {
                postgresUuidComparison(left.orderId, right.orderId)
            }
        }

    /**
     * PostgreSQL orders `uuid` bytes as unsigned values, while [UUID.compareTo] treats both longs as signed.
     * The in-memory boundary must use the same ordering as the keyset query or a visible order can reappear
     * in the older-work queue.
     */
    private fun postgresUuidComparison(
        left: UUID,
        right: UUID,
    ): Int {
        val high = java.lang.Long.compareUnsigned(left.mostSignificantBits, right.mostSignificantBits)
        return if (high != 0) high else java.lang.Long.compareUnsigned(left.leastSignificantBits, right.leastSignificantBits)
    }

    private fun laneForState(state: String): StoreOrderBoardLane =
        when (state) {
            "PAID" -> StoreOrderBoardLane.PENDING_ACCEPTANCE
            "ACCEPTED" -> StoreOrderBoardLane.ACCEPTED
            "PREPARING" -> StoreOrderBoardLane.PREPARING
            "READY" -> StoreOrderBoardLane.READY
            else -> dependency("Store order board state is unsupported")
        }

    private fun dependency(message: String): Nothing =
        throw io.github.kdh949.beanflow.shared.api.DomainFailure(
            io.github.kdh949.beanflow.shared.api.FailureCode.DEPENDENCY_UNAVAILABLE,
            message,
        )

    private companion object {
        const val LIST = "list"
        const val OVERFLOW = "overflow"
        const val DETAIL = "detail"
        val LINE_SELECT =
            """
            SELECT order_id, line_sequence, menu_name, quantity
              FROM ordering_order_line
            """.trimIndent()
    }
}

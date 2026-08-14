package io.github.kdh949.beanflow.ordering.internal

/**
 * Canonical SQL shape for the bounded store board. Production and the PostgreSQL plan fixture share this
 * builder so a plan can only be accepted for the query that the repository executes.
 */
internal object StoreOrderBoardQuerySql {
    fun primaryBoard(
        lanes: List<StoreOrderBoardLane>,
        table: String = ORDER_TABLE,
    ): String =
        lanes.joinToString("\nUNION ALL\n") { lane ->
            """
            (
            ${orderSelect(table)}
             WHERE store_id = ? AND state = '${state(lane)}'
             ORDER BY ${sortColumn(lane)}, id
             LIMIT ${StoreOrderBoardPaging.FETCH_LIMIT}
            )
            """.trimIndent()
        }

    fun overflowPage(
        lane: StoreOrderBoardLane,
        table: String = ORDER_TABLE,
    ): String {
        val sortColumn = sortColumn(lane)
        return "${orderSelect(table)} WHERE store_id = ? AND state = ? AND ($sortColumn, id) > (?, ?) " +
            "ORDER BY $sortColumn, id LIMIT ${StoreOrderBoardPaging.FETCH_LIMIT}"
    }

    fun overflowCount(
        statePlaceholders: String,
        table: String = ORDER_TABLE,
    ): String =
        "SELECT state, count(*) AS total_count FROM $table " +
            "WHERE store_id = ? AND state IN ($statePlaceholders) GROUP BY state"

    fun orderSelect(table: String = ORDER_TABLE): String =
        """
        SELECT id, public_reference, pickup_sequence, pickup_business_date, state,
               pickup_window_start_snapshot, pickup_window_end_snapshot,
               acceptance_warning_at, acceptance_deadline_at
          FROM $table
        """.trimIndent()

    fun state(lane: StoreOrderBoardLane): String =
        when (lane) {
            StoreOrderBoardLane.PENDING_ACCEPTANCE -> "PAID"
            StoreOrderBoardLane.ACCEPTED -> "ACCEPTED"
            StoreOrderBoardLane.PREPARING -> "PREPARING"
            StoreOrderBoardLane.READY -> "READY"
        }

    fun sortColumn(lane: StoreOrderBoardLane): String =
        when (lane) {
            StoreOrderBoardLane.PENDING_ACCEPTANCE -> "acceptance_deadline_at"

            StoreOrderBoardLane.ACCEPTED,
            StoreOrderBoardLane.PREPARING,
            StoreOrderBoardLane.READY,
            -> "pickup_window_start_snapshot"
        }

    const val ORDER_TABLE = "ordering_order"
}

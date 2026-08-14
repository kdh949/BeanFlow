package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.HexFormat
import java.util.UUID

internal data class StoreOrderBoardSort(
    val sortAt: Instant,
    val orderId: UUID,
)

internal data class PreparedStoreOrderBoardOverflowPage(
    val lane: StoreOrderBoardLane,
    val after: StoreOrderBoardSort,
)

@Component
internal class StoreOrderBoardPaging(
    private val signedCursorCodec: SignedCursorCodec,
) {
    fun prepareOverflowPage(
        storeId: UUID,
        lane: StoreOrderBoardLane,
        cursor: String,
    ): PreparedStoreOrderBoardOverflowPage {
        if (cursor.isBlank() || cursor.length > MAX_CURSOR_LENGTH) invalid("Store order board cursor is invalid")
        val scope = scope(storeId, lane)
        return PreparedStoreOrderBoardOverflowPage(lane, signedCursorCodec.verify(cursor, scope).sort)
    }

    fun issueCursor(
        storeId: UUID,
        lane: StoreOrderBoardLane,
        sort: StoreOrderBoardSort,
        now: Instant,
    ): String =
        signedCursorCodec.issue(
            scope(storeId, lane),
            sort,
            now.plus(CURSOR_TTL),
        )

    fun sortFor(
        lane: StoreOrderBoardLane,
        order: StoreOrderBoardOrderProjection,
    ): StoreOrderBoardSort =
        StoreOrderBoardSort(
            sortAt =
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
                },
            orderId = order.orderId,
        )

    private fun scope(
        storeId: UUID,
        lane: StoreOrderBoardLane,
    ) = SignedCursorScope(
        endpoint = CURSOR_ENDPOINT,
        filterHash = filterHash(storeId, lane),
        sortAdapter = SORT_ADAPTER,
    )

    private fun filterHash(
        storeId: UUID,
        lane: StoreOrderBoardLane,
    ): String {
        val canonical = "{\"endpoint\":\"$CURSOR_ENDPOINT\",\"storeId\":\"$storeId\",\"lane\":\"${lane.name}\"}"
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8)),
        )
    }

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    private fun dependency(message: String): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)

    companion object {
        internal const val PAGE_SIZE = 50
        internal const val FETCH_LIMIT = PAGE_SIZE + 1
        internal const val MAX_CURSOR_LENGTH = 2048
        internal const val CURSOR_ENDPOINT = "store-order-board-overflow"
        internal val CURSOR_TTL: Duration = Duration.ofMinutes(15)
        internal val SORT_ADAPTER =
            object : CursorSortAdapter<StoreOrderBoardSort> {
                override fun encode(sort: StoreOrderBoardSort): List<String> = listOf(sort.sortAt.toString(), sort.orderId.toString())

                override fun decode(values: List<String>): StoreOrderBoardSort? {
                    if (values.size != 2) return null
                    return try {
                        val sortAt = Instant.parse(values[0])
                        val orderId = UUID.fromString(values[1])
                        if (sortAt.toString() != values[0] || orderId.toString() != values[1]) {
                            null
                        } else {
                            StoreOrderBoardSort(sortAt, orderId)
                        }
                    } catch (_: DateTimeParseException) {
                        null
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
            }
    }
}

package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.HexFormat
import java.util.UUID

internal data class CustomerOrderSort(
    val createdAt: Instant,
    val orderId: UUID,
)

internal data class CustomerOrderListCriteria(
    val customerId: UUID,
    val status: CustomerOrderStatusFilter?,
    val from: LocalDate?,
    val to: LocalDate?,
    val cursor: String?,
    val limit: Int?,
    val now: Instant,
)

internal data class PreparedCustomerOrderPage(
    val customerId: UUID,
    val status: CustomerOrderStatusFilter?,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val fromInclusive: Instant,
    val toExclusive: Instant,
    val limit: Int,
    val after: CustomerOrderSort?,
    val cursorScope: SignedCursorScope<CustomerOrderSort>,
    val cursorExpiresAt: Instant,
)

@Component
internal class CustomerOrderPaging(
    private val signedCursorCodec: SignedCursorCodec,
) {
    fun prepare(criteria: CustomerOrderListCriteria): PreparedCustomerOrderPage {
        val limit = criteria.limit ?: DEFAULT_LIMIT
        if (limit !in 1..MAX_LIMIT) invalid("Customer order limit must be between 1 and 100")
        if ((criteria.cursor?.length ?: 0) > MAX_CURSOR_LENGTH) invalid("Customer order cursor is too long")

        val today = criteria.now.atZone(SEOUL).toLocalDate()
        val toDate = criteria.to ?: today
        val fromDate = criteria.from ?: minusDefaultWindow(toDate)
        if (fromDate.isAfter(toDate)) invalid("Customer order from date must not be after to date")
        val fromInclusive = startOfDay(fromDate)
        val toExclusive = startOfDay(plusOneDay(toDate))
        val scope =
            SignedCursorScope(
                endpoint = CURSOR_ENDPOINT,
                filterHash = filterHash(criteria.customerId, criteria.status, fromDate, toDate),
                sortAdapter = SORT_ADAPTER,
            )
        return PreparedCustomerOrderPage(
            customerId = criteria.customerId,
            status = criteria.status,
            fromDate = fromDate,
            toDate = toDate,
            fromInclusive = fromInclusive,
            toExclusive = toExclusive,
            limit = limit,
            after = criteria.cursor?.let { signedCursorCodec.verify(it, scope).sort },
            cursorScope = scope,
            cursorExpiresAt = criteria.now.plus(CURSOR_TTL),
        )
    }

    private fun filterHash(
        customerId: UUID,
        status: CustomerOrderStatusFilter?,
        from: LocalDate,
        to: LocalDate,
    ): String {
        val canonical =
            "{\"endpoint\":\"$CURSOR_ENDPOINT\",\"customerId\":\"$customerId\"," +
                "\"status\":\"${status?.name ?: "ALL"}\",\"from\":\"$from\",\"to\":\"$to\"}"
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8)),
        )
    }

    private fun minusDefaultWindow(to: LocalDate): LocalDate =
        try {
            to.minusDays(DEFAULT_WINDOW_DAYS - 1L)
        } catch (_: DateTimeException) {
            invalid("Customer order date range is invalid")
        }

    private fun plusOneDay(to: LocalDate): LocalDate =
        try {
            to.plusDays(1)
        } catch (_: DateTimeException) {
            invalid("Customer order date range is invalid")
        }

    private fun startOfDay(date: LocalDate): Instant =
        try {
            date.atStartOfDay(SEOUL).toInstant()
        } catch (_: DateTimeException) {
            invalid("Customer order date range is invalid")
        }

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    companion object {
        internal const val DEFAULT_LIMIT = 20
        internal const val MAX_LIMIT = 100
        internal const val MAX_CURSOR_LENGTH = 2048
        internal const val CURSOR_ENDPOINT = "customer-orders"
        internal val CURSOR_TTL: Duration = Duration.ofHours(24)
        private const val DEFAULT_WINDOW_DAYS = 30
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        internal val SORT_ADAPTER =
            object : CursorSortAdapter<CustomerOrderSort> {
                override fun encode(sort: CustomerOrderSort): List<String> = listOf(sort.createdAt.toString(), sort.orderId.toString())

                override fun decode(values: List<String>): CustomerOrderSort? {
                    if (values.size != 2) return null
                    return try {
                        val createdAt = Instant.parse(values[0])
                        val orderId = UUID.fromString(values[1])
                        if (createdAt.toString() != values[0] || orderId.toString() != values[1]) {
                            null
                        } else {
                            CustomerOrderSort(createdAt, orderId)
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

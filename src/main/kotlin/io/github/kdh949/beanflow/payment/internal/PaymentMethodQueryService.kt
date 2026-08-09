package io.github.kdh949.beanflow.payment.internal

import com.fasterxml.jackson.annotation.JsonInclude
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.HexFormat
import java.util.UUID

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class PaymentMethodView(
    val paymentMethodId: UUID,
    val provider: String,
    val displayAlias: String,
    val cardBrand: String,
    val lastFour: String,
    val isDefault: Boolean,
    val status: String,
    val noticeCode: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

internal data class PaymentMethodPageInfo(
    val nextCursor: String?,
)

internal data class PaymentMethodPage(
    val items: List<PaymentMethodView>,
    val page: PaymentMethodPageInfo,
)

internal data class PaymentMethodSort(
    val isDefault: Boolean,
    val createdAt: Instant,
    val paymentMethodId: UUID,
)

@Service
internal class PaymentMethodQueryService(
    private val jdbcTemplate: JdbcTemplate,
    private val cursors: SignedCursorCodec,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(
        customerId: UUID,
        cursor: String?,
        rawLimit: String?,
    ): PaymentMethodPage {
        val limit = parseLimit(rawLimit)
        if (cursor != null && (cursor.isEmpty() || cursor.length > MAX_CURSOR_LENGTH)) invalid("Cursor is invalid")
        val scope = scope(customerId)
        val after = cursor?.let { cursors.verify(it, scope).sort }
        val fetched = query(customerId, after, limit + 1)
        val items = fetched.take(limit)
        val nextCursor =
            if (fetched.size > limit) {
                val last = items.last()
                cursors.issue(
                    scope,
                    PaymentMethodSort(last.isDefault, last.createdAt, last.paymentMethodId),
                    clock.instant().plus(CURSOR_TTL),
                )
            } else {
                null
            }
        return PaymentMethodPage(items, PaymentMethodPageInfo(nextCursor))
    }

    private fun query(
        customerId: UUID,
        after: PaymentMethodSort?,
        limit: Int,
    ): List<PaymentMethodView> {
        val sql =
            buildString {
                append(
                    """
                    SELECT id, provider, display_alias, card_brand, last_four, is_default,
                           status, created_at, updated_at
                      FROM payment_method
                     WHERE customer_id = ?
                       AND provider = 'TOSS_PAYMENTS'
                       AND status <> 'DEACTIVATED'
                    """.trimIndent(),
                )
                if (after != null) {
                    append(
                        """

                           AND (
                               (CASE WHEN is_default THEN 1 ELSE 0 END) < ?
                               OR ((CASE WHEN is_default THEN 1 ELSE 0 END) = ? AND created_at < ?)
                               OR ((CASE WHEN is_default THEN 1 ELSE 0 END) = ? AND created_at = ? AND id < ?)
                           )
                        """.trimIndent(),
                    )
                }
                append(" ORDER BY is_default DESC, created_at DESC, id DESC LIMIT ?")
            }
        val arguments = mutableListOf<Any>(customerId)
        if (after != null) {
            arguments += if (after.isDefault) 1 else 0
            arguments += if (after.isDefault) 1 else 0
            arguments += java.sql.Timestamp.from(after.createdAt)
            arguments += if (after.isDefault) 1 else 0
            arguments += java.sql.Timestamp.from(after.createdAt)
            arguments += after.paymentMethodId
        }
        arguments += limit
        return jdbcTemplate.query(sql, { result, _ -> result.toView() }, *arguments.toTypedArray())
    }

    private fun java.sql.ResultSet.toView(): PaymentMethodView {
        val lifecycle = getString("status")
        return PaymentMethodView(
            paymentMethodId = getObject("id", UUID::class.java),
            provider = getString("provider"),
            displayAlias = getString("display_alias"),
            cardBrand = getString("card_brand"),
            lastFour = getString("last_four"),
            isDefault = getBoolean("is_default"),
            status = if (lifecycle == "ACTIVE") "ACTIVE" else "DEACTIVATION_PENDING",
            noticeCode = if (lifecycle == "MANUAL_REVIEW") "DEACTIVATION_DELAYED" else null,
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )
    }

    private fun scope(customerId: UUID): SignedCursorScope<PaymentMethodSort> =
        SignedCursorScope(
            endpoint = CURSOR_ENDPOINT,
            filterHash =
                HexFormat
                    .of()
                    .formatHex(
                        MessageDigest
                            .getInstance("SHA-256")
                            .digest("$CURSOR_ENDPOINT|customerId=$customerId".toByteArray(StandardCharsets.UTF_8)),
                    ),
            sortAdapter = SORT_ADAPTER,
        )

    private fun parseLimit(raw: String?): Int {
        if (raw == null) return DEFAULT_LIMIT
        if (raw.length > 3 || !UNSIGNED_INTEGER.matches(raw)) invalid("Limit must be an integer between 1 and 100")
        val parsed = raw.toIntOrNull() ?: invalid("Limit must be an integer between 1 and 100")
        if (parsed !in 1..MAX_LIMIT) invalid("Limit must be an integer between 1 and 100")
        return parsed
    }

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    internal companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
        const val MAX_CURSOR_LENGTH = 2048
        const val CURSOR_ENDPOINT = "payment-methods"
        val CURSOR_TTL: Duration = Duration.ofHours(24)
        val UNSIGNED_INTEGER = Regex("0|[1-9][0-9]*")
        val SORT_ADAPTER =
            object : CursorSortAdapter<PaymentMethodSort> {
                override fun encode(sort: PaymentMethodSort): List<String> =
                    listOf(sort.isDefault.toString(), sort.createdAt.toString(), sort.paymentMethodId.toString())

                override fun decode(values: List<String>): PaymentMethodSort? {
                    if (values.size != 3 || values[0] !in setOf("true", "false")) return null
                    return try {
                        val createdAt = Instant.parse(values[1])
                        val methodId = UUID.fromString(values[2])
                        if (createdAt.toString() != values[1] || methodId.toString() != values[2]) {
                            null
                        } else {
                            PaymentMethodSort(values[0].toBooleanStrict(), createdAt, methodId)
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

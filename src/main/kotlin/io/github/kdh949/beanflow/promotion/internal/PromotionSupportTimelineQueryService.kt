package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.promotion.api.PromotionSupportTimelineOperations
import io.github.kdh949.beanflow.shared.api.SUPPORT_TIMELINE_COMPARATOR
import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineFact
import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineQuery
import io.github.kdh949.beanflow.shared.api.SupportTimelineSource
import io.github.kdh949.beanflow.shared.api.SupportTimelineState
import io.github.kdh949.beanflow.shared.api.SupportTimelineType
import io.github.kdh949.beanflow.shared.api.accepts
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

@Service
internal class PromotionSupportTimelineQueryService(
    private val jdbcTemplate: JdbcTemplate,
) : PromotionSupportTimelineOperations {
    override fun findTimelineFacts(query: SupportOwnerTimelineQuery): List<SupportOwnerTimelineFact> {
        val ids = query.orderIds.sortedBy(UUID::toString)
        val placeholders = ids.joinToString(",") { "?" }
        return jdbcTemplate
            .query(
                """
                SELECT id, state, discount_krw, updated_at
                  FROM promotion_coupon_reservation
                 WHERE order_id IN ($placeholders)
                """.trimIndent(),
                { resultSet, _ ->
                    SupportOwnerTimelineFact(
                        source = SupportTimelineSource.PROMOTION,
                        type = SupportTimelineType.COUPON_RESERVATION,
                        itemId = resultSet.getObject("id", UUID::class.java),
                        state = SupportTimelineState.valueOf(resultSet.getString("state")),
                        occurredAt = resultSet.getTimestamp("updated_at").toInstant(),
                        amountKrw = resultSet.getLong("discount_krw"),
                    )
                },
                *ids.toTypedArray(),
            ).asSequence()
            .filter(query::accepts)
            .sortedWith(SUPPORT_TIMELINE_COMPARATOR)
            .take(query.limit)
            .toList()
    }
}

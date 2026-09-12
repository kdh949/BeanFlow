package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.promotion.api.GoodwillCouponTemplateView
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
internal class GoodwillCouponTemplateQuery(
    private val jdbc: JdbcTemplate,
) {
    fun findPage(
        afterId: UUID?,
        limit: Int,
    ): List<GoodwillCouponTemplateView> {
        require(limit in 1..51)
        val where = if (afterId == null) "" else "WHERE id > ?"
        val arguments = if (afterId == null) arrayOf<Any>(limit) else arrayOf<Any>(afterId, limit)
        return jdbc.query(
            "SELECT id, fixed_amount_krw, validity_days, minimum_eligible_subtotal_krw " +
                "FROM promotion_goodwill_coupon_template $where ORDER BY id LIMIT ?",
            { row, _ ->
                GoodwillCouponTemplateView(
                    row.getObject("id", UUID::class.java),
                    row.getLong("fixed_amount_krw"),
                    row.getInt("validity_days"),
                    row.getLong("minimum_eligible_subtotal_krw"),
                )
            },
            *arguments,
        )
    }
}

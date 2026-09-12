package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.operations.api.OrderInvestigationOperations
import io.github.kdh949.beanflow.operations.api.OrderInvestigationState
import io.github.kdh949.beanflow.operations.api.OrderInvestigationTarget
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.util.UUID

@Service
@Transactional(propagation = Propagation.MANDATORY)
internal class OrderInvestigationQueryService(
    private val jdbc: JdbcTemplate,
) : OrderInvestigationOperations {
    override fun findByReference(reference: String): OrderInvestigationTarget? =
        jdbc.query("$SELECT WHERE public_reference = ?", ::map, PublicOrderReference.parse(reference).value).singleOrNull()

    override fun findTargets(orderIds: Set<UUID>): Map<UUID, OrderInvestigationTarget> {
        require(orderIds.size <= 100)
        if (orderIds.isEmpty()) return emptyMap()
        val placeholders = orderIds.joinToString(",") { "?" }
        return jdbc.query("$SELECT WHERE id IN ($placeholders)", ::map, *orderIds.toTypedArray()).associateBy { it.orderId }
    }

    private fun map(
        rs: ResultSet,
        row: Int,
    ) = OrderInvestigationTarget(
        rs.getObject("id", UUID::class.java),
        rs.getString("public_reference"),
        rs.getObject("store_id", UUID::class.java),
        rs.getString("store_name_snapshot"),
        OrderInvestigationState.valueOf(rs.getString("state")),
        rs.getTimestamp("created_at").toInstant(),
    )

    private companion object {
        const val SELECT = "SELECT id, public_reference, store_id, store_name_snapshot, state, created_at FROM ordering_order"
    }
}

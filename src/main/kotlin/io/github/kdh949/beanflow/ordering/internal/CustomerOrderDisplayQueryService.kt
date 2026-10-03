package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.shared.api.CustomerOrderDisplay
import io.github.kdh949.beanflow.shared.api.CustomerOrderDisplayQuery
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class CustomerOrderDisplayQueryService(
    private val repository: CustomerOrderDisplayQueryRepository,
) : CustomerOrderDisplayQuery {
    @Transactional(readOnly = true)
    override fun find(
        customerId: UUID,
        orderIds: Set<UUID>,
    ): List<CustomerOrderDisplay> {
        require(orderIds.size <= 100) { "Order display query exceeds one page" }
        if (orderIds.isEmpty()) return emptyList()
        return try {
            repository.find(customerId, orderIds)
        } catch (failure: DataAccessException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Order display lookup is unavailable").also { it.initCause(failure) }
        }
    }
}

@Repository
internal class CustomerOrderDisplayQueryRepository(
    private val jdbc: JdbcTemplate,
) {
    fun find(
        customerId: UUID,
        orderIds: Set<UUID>,
    ): List<CustomerOrderDisplay> =
        jdbc.query(
            """
            SELECT orders.id, orders.public_reference, orders.store_name_snapshot,
                   (SELECT line.menu_name FROM ordering_order_line line
                     WHERE line.order_id = orders.id ORDER BY line.line_sequence LIMIT 1) AS first_menu_name
              FROM ordering_order orders
             WHERE orders.customer_id = ? AND orders.id IN (${orderIds.joinToString { "?" }})
            """.trimIndent(),
            { row, _ ->
                val menu = row.getString("first_menu_name")
                val store = row.getString("store_name_snapshot")
                val reference = row.getString("public_reference")
                if (menu.isNullOrBlank() || store.isNullOrBlank() ||
                    !reference.matches(Regex("^BF-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$"))
                ) {
                    throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Order display snapshot is unavailable")
                }
                CustomerOrderDisplay(row.getObject("id", UUID::class.java), reference, store, menu)
            },
            customerId,
            *orderIds.toTypedArray(),
        )
}

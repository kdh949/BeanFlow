package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.CustomerRecentStoreProjection
import io.github.kdh949.beanflow.ordering.api.CustomerRecentStoreQuery
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionException
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Reads only the BR-40 recent-store projection. No Order aggregate is materialized and the
 * customer and eligible-state predicates stay in the same statement, so a caller cannot obtain
 * another customer's history and filter it in application memory.
 */
@Service
internal class CustomerRecentStoreQueryService(
    private val repository: CustomerRecentStoreQueryRepository,
) : CustomerRecentStoreQuery {
    @Transactional(readOnly = true)
    override fun top(
        customerId: UUID,
        limit: Int,
    ): List<CustomerRecentStoreProjection> {
        require(limit in MIN_LIMIT..MAX_LIMIT) { "Recent store query limit must be in $MIN_LIMIT..$MAX_LIMIT" }
        return try {
            repository.findTop(customerId, limit)
        } catch (failure: DataAccessException) {
            unavailable(failure)
        } catch (failure: TransactionException) {
            unavailable(failure)
        }
    }

    private fun unavailable(cause: Throwable): Nothing =
        throw DomainFailure(
            FailureCode.DEPENDENCY_UNAVAILABLE,
            "Recent store projection is unavailable",
        ).also { it.initCause(cause) }

    private companion object {
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 20
    }
}

@Repository
internal class CustomerRecentStoreQueryRepository(
    private val jdbc: JdbcTemplate,
) {
    fun findTop(
        customerId: UUID,
        limit: Int,
    ): List<CustomerRecentStoreProjection> =
        jdbc.query(
            """
            SELECT store_id, max(created_at) AS last_ordered_at
              FROM ordering_order
             WHERE customer_id = ?
               AND state IN ('PAID', 'ACCEPTED', 'PREPARING', 'READY', 'COMPLETED')
             GROUP BY store_id
             ORDER BY last_ordered_at DESC, store_id ASC
             LIMIT ?
            """.trimIndent(),
            { resultSet, _ ->
                CustomerRecentStoreProjection(
                    storeId = resultSet.getObject("store_id", UUID::class.java),
                    lastOrderedAt = resultSet.getTimestamp("last_ordered_at").toInstant(),
                )
            },
            customerId,
            limit,
        )
}

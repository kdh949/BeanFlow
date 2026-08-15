package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.discovery.api.CustomerStoreView
import io.github.kdh949.beanflow.discovery.api.RecentStoreOperations
import io.github.kdh949.beanflow.ordering.api.CustomerRecentStoreQuery
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
internal class RecentStoreService(
    private val recentStores: CustomerRecentStoreQuery,
    private val hydrator: CustomerStoreHydrator,
) : RecentStoreOperations {
    @Transactional(readOnly = true)
    override fun list(
        customerId: UUID,
        rawLimit: String?,
        now: Instant,
    ): List<CustomerStoreView> {
        val recent = recentStores.top(customerId, CompactStoreLimit.parse(rawLimit))
        return hydrator.hydrate(recent.map { it.storeId }, now)
    }
}

internal object CompactStoreLimit {
    const val DEFAULT: Int = 10
    const val MAX: Int = 20

    fun parse(raw: String?): Int {
        if (raw == null) return DEFAULT
        if (raw.length > MAX_INTEGER_LENGTH || !INTEGER_PATTERN.matches(raw)) invalid()
        val parsed = raw.toIntOrNull() ?: invalid()
        if (parsed !in 1..MAX) invalid()
        return parsed
    }

    private fun invalid(): Nothing =
        throw io.github.kdh949.beanflow.shared.api.DomainFailure(
            io.github.kdh949.beanflow.shared.api.FailureCode.INVALID_REQUEST,
            "Limit must be an integer between 1 and $MAX",
        )

    private const val MAX_INTEGER_LENGTH = 10
    private val INTEGER_PATTERN = Regex("^[0-9]+$")
}

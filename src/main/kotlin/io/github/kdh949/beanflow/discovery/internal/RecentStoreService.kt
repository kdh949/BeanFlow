package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.discovery.api.CustomerStoreView
import io.github.kdh949.beanflow.discovery.api.RecentStoreOperations
import io.github.kdh949.beanflow.shared.api.CustomerRecentStoreCursor
import io.github.kdh949.beanflow.shared.api.CustomerRecentStoreQuery
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
internal class RecentStoreService(
    private val recentStores: CustomerRecentStoreQuery,
    private val hydrator: CustomerStoreHydrator,
) : RecentStoreOperations {
    /**
     * `limit` counts stores the customer can actually see, not raw candidates.
     *
     * Applying it to the Ordering projection alone let a store that has since lost its public
     * profile consume a slot and hide an eligible store behind it. The endpoint has no cursor, so
     * that store was unreachable rather than merely on a later page. Scanning is still bounded: at
     * most [MAX_WINDOWS] windows, and the loop stops as soon as the candidates run out.
     */
    @Transactional(readOnly = true)
    override fun list(
        customerId: UUID,
        rawLimit: String?,
        now: Instant,
    ): List<CustomerStoreView> {
        val limit = CompactStoreLimit.parse(rawLimit)
        val visible = mutableListOf<CustomerStoreView>()
        var after: CustomerRecentStoreCursor? = null
        repeat(MAX_WINDOWS) {
            val window = recentStores.top(customerId, WINDOW_SIZE, after)
            if (window.isEmpty()) return visible.take(limit)
            visible += hydrator.hydrate(window.map { it.storeId }, now)
            if (visible.size >= limit || window.size < WINDOW_SIZE) return visible.take(limit)
            after = window.last().let { CustomerRecentStoreCursor(it.lastOrderedAt, it.storeId) }
        }
        return visible.take(limit)
    }

    private companion object {
        val WINDOW_SIZE = CompactStoreLimit.MAX

        /** Bounds the work a single request can cause when many recent stores are no longer public. */
        const val MAX_WINDOWS = 5
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

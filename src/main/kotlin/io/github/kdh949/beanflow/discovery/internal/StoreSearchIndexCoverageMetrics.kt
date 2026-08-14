package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.merchant.api.StoreDiscoveryQueryOperations
import io.github.kdh949.beanflow.shared.api.StoreSearchTermKind
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.atomic.AtomicReference

/**
 * Publishes `beanflow.discovery.search.index.coverage`: the share of stores that have a
 * `STORE_NAME` term.
 *
 * The index is written synchronously by the brand and region commands, but store and menu rows can
 * still change outside those commands (seed data, direct DML), and there is no write API to
 * intercept. This gauge is how that limitation stays visible instead of showing up as a search that
 * silently returns nothing.
 *
 * The metric carries no tags. Store ids, names and search text are never used as tag values.
 */
@Component
internal class StoreSearchIndexCoverageMetrics(
    private val stores: StoreDiscoveryQueryOperations,
    private val repository: StoreSearchIndexRepository,
    meterRegistry: MeterRegistry,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * `NaN` until the first successful refresh. Micrometer omits a `NaN` gauge, so a failing
     * refresh makes the metric disappear rather than keep reporting a stale ratio.
     */
    private val coverage = AtomicReference(Double.NaN)

    init {
        Gauge
            .builder("beanflow.discovery.search.index.coverage", coverage) { value -> value.get() }
            .register(meterRegistry)
    }

    @Scheduled(
        fixedDelayString = "\${beanflow.search-index-coverage.fixed-delay-ms:60000}",
        initialDelayString = "\${beanflow.search-index-coverage.initial-delay-ms:60000}",
    )
    fun refreshScheduled() {
        try {
            refresh()
        } catch (failure: DataAccessException) {
            coverage.set(Double.NaN)
            logger.warn("Search index coverage refresh failed; the coverage gauge is not being reported", failure)
        }
    }

    /**
     * Returns the ratio it published. A store count of zero reports `1.0`: no store is missing from
     * the index, which is a different state from "the index lost every store".
     *
     * A ratio above `1.0` would mean terms exist for stores that do not. It is published as-is
     * rather than clamped, because clamping would hide exactly that.
     */
    @Transactional(readOnly = true)
    fun refresh(): Double {
        val total = stores.countIndexableStores()
        val indexed = repository.countStoresWithTerm(StoreSearchTermKind.STORE_NAME)
        val ratio = if (total == 0L) 1.0 else indexed.toDouble() / total.toDouble()
        coverage.set(ratio)
        return ratio
    }
}

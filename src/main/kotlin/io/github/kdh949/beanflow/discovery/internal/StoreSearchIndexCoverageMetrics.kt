package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.merchant.api.StoreDiscoveryQueryOperations
import io.github.kdh949.beanflow.merchant.api.StoreSearchTermSource
import io.github.kdh949.beanflow.merchant.api.StoreSearchTermSourceQuery
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.SearchTextNormalizer
import io.github.kdh949.beanflow.shared.api.StoreSearchTermKind
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.TransactionException
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.atomic.AtomicReference

/**
 * Publishes row-presence and freshness gauges for the derived store search index.
 *
 * `store-row-presence.coverage` only says every store has at least one STORE_NAME row. It does not
 * claim that the row still mirrors the store or menu source. `freshness.mismatches` is the separate
 * reconciliation count for those source facts.
 *
 * The metric carries no tags. Store ids, names and search text are never used as tag values.
 */
@Component
internal class StoreSearchIndexCoverageMetrics(
    private val snapshotReader: StoreSearchIndexCoverageSnapshotReader,
    meterRegistry: MeterRegistry,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * `NaN` until the first successful refresh. Micrometer omits a `NaN` gauge, so a failing
     * refresh makes the metric disappear rather than keep reporting a stale ratio.
     */
    private val storeRowPresenceCoverage = AtomicReference(Double.NaN)
    private val freshnessMismatches = AtomicReference(Double.NaN)

    init {
        Gauge
            .builder("beanflow.discovery.search.index.store-row-presence.coverage", storeRowPresenceCoverage) { value -> value.get() }
            .register(meterRegistry)
        Gauge
            .builder("beanflow.discovery.search.index.freshness.mismatches", freshnessMismatches) { value -> value.get() }
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
            clearAndLog(failure)
        } catch (failure: TransactionException) {
            clearAndLog(failure)
        } catch (failure: DomainFailure) {
            clearAndLog(failure)
        }
    }

    /**
     * Returns the row-presence ratio it published. A store count of zero reports `1.0`: no store is
     * missing from the index, which is a different state from "the index lost every store".
     *
     * A ratio above `1.0` would mean terms exist for stores that do not. It is published as-is
     * rather than clamped, because clamping would hide exactly that.
     */
    fun refresh(): Double {
        val snapshot = snapshotReader.read()
        storeRowPresenceCoverage.set(snapshot.storeRowPresenceCoverage)
        freshnessMismatches.set(snapshot.freshnessMismatchCount.toDouble())
        return snapshot.storeRowPresenceCoverage
    }

    private fun clearAndLog(failure: RuntimeException) {
        storeRowPresenceCoverage.set(Double.NaN)
        freshnessMismatches.set(Double.NaN)
        logger.warn("Search index health refresh failed; its gauges are not being reported", failure)
    }
}

/**
 * Reads all inputs within one PostgreSQL snapshot. This is a separate bean so scheduled calls do
 * not bypass the transactional proxy through self-invocation.
 */
@Component
internal class StoreSearchIndexCoverageSnapshotReader(
    private val stores: StoreDiscoveryQueryOperations,
    private val sources: StoreSearchTermSourceQuery,
    private val repository: StoreSearchIndexRepository,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(): StoreSearchIndexCoverageSnapshot {
        val total = stores.countIndexableStores()
        val indexed = repository.countStoresWithTerm(StoreSearchTermKind.STORE_NAME)
        val coverage = if (total == 0L) 1.0 else indexed.toDouble() / total.toDouble()
        return StoreSearchIndexCoverageSnapshot(
            storeRowPresenceCoverage = coverage,
            freshnessMismatchCount = repository.countSourceFreshnessMismatches(sources.findAllSearchTermSources()),
        )
    }
}

internal data class StoreSearchIndexCoverageSnapshot(
    val storeRowPresenceCoverage: Double,
    val freshnessMismatchCount: Long,
)

internal data class StoreSearchTermFreshnessFact(
    val storeId: java.util.UUID,
    val kind: StoreSearchTermKind,
    val sourceId: java.util.UUID?,
    val normalized: String,
    val displayText: String,
)

internal fun StoreSearchIndexRepository.countSourceFreshnessMismatches(sources: List<StoreSearchTermSource>): Long {
    val expected =
        buildSet {
            sources.forEach { source ->
                add(source.asStoreNameFact())
                source.availableMenus.forEach { menu ->
                    add(
                        StoreSearchTermFreshnessFact(
                            source.storeId,
                            StoreSearchTermKind.MENU_NAME,
                            menu.menuId,
                            SearchTextNormalizer.normalize(menu.name),
                            menu.name,
                        ),
                    )
                }
            }
        }
    val actual =
        findStoreAndMenuFreshnessFacts().toSet()
    return (expected - actual).size.toLong() + (actual - expected).size.toLong()
}

private fun StoreSearchTermSource.asStoreNameFact() =
    StoreSearchTermFreshnessFact(
        storeId,
        StoreSearchTermKind.STORE_NAME,
        null,
        SearchTextNormalizer.normalize(storeName),
        storeName,
    )

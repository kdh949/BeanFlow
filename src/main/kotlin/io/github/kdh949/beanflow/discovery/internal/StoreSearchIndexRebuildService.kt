package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.merchant.api.StoreSearchTermSourceQuery
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.ReplaceStoreSearchTermsCommand
import io.github.kdh949.beanflow.shared.api.StoreSearchIndexOperations
import io.github.kdh949.beanflow.shared.api.StoreSearchTermEntry
import io.github.kdh949.beanflow.shared.api.StoreSearchTermKind
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import org.springframework.transaction.TransactionException
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * What one rebuild pass did.
 *
 * Failures are reported as store ids rather than a count so that a partial rebuild can never be
 * read as a complete one, and so the operator command added later can name what to retry.
 */
internal data class StoreSearchIndexRebuildResult(
    val targetStoreCount: Int,
    val indexedStoreCount: Int,
    val skippedStoreCount: Int,
    val failedStoreIds: List<UUID>,
) {
    /** True only when every store id in this pass's initial target snapshot succeeded. */
    val completeSnapshot: Boolean get() = failedStoreIds.isEmpty()
}

/**
 * Rebuilds the search index from the stores and menus Merchant currently holds.
 *
 * Merchant has no store or menu write API, so seed data and direct DML are the only ways those
 * rows change today and there is no command to hang a synchronous index update on. This rebuild is
 * the explicit, operator-driven way that data reaches the index, and
 * its row-presence and freshness gauges are how the gap between the two stays visible.
 *
 * It also carries the initial load: V59 creates the index empty on purpose, because the migration
 * would have to reimplement the normalizer in SQL to fill it and SQL cannot reproduce it
 * (MD-2026-018).
 */
@Component
internal class StoreSearchIndexRebuildService(
    private val sources: StoreSearchTermSourceQuery,
    private val storeRebuild: StoreSearchIndexStoreRebuild,
    private val metrics: StoreSearchIndexUpdateMetrics,
    private val properties: StoreSearchIndexRebuildProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun rebuildAll(): StoreSearchIndexRebuildResult {
        val targetStoreIds = sources.findRebuildTargetStoreIds()
        var indexed = 0
        var skipped = 0
        val failed = mutableListOf<UUID>()
        targetStoreIds.chunked(properties.chunkSize).forEach { storeIds ->
            storeIds.forEach { storeId ->
                when (rebuildOne(storeId)) {
                    RebuildOutcome.INDEXED -> indexed++
                    RebuildOutcome.SKIPPED -> skipped++
                    RebuildOutcome.FAILED -> failed.add(storeId)
                }
            }
        }
        return StoreSearchIndexRebuildResult(targetStoreIds.size, indexed, skipped, failed)
    }

    fun rebuildStore(storeId: UUID): StoreSearchIndexRebuildResult =
        when (rebuildOne(storeId)) {
            RebuildOutcome.INDEXED -> StoreSearchIndexRebuildResult(1, 1, 0, emptyList())
            RebuildOutcome.SKIPPED -> StoreSearchIndexRebuildResult(1, 0, 1, emptyList())
            RebuildOutcome.FAILED -> StoreSearchIndexRebuildResult(1, 0, 0, listOf(storeId))
        }

    /**
     * One store, one transaction (T4). A store that fails is recorded and the pass continues: the
     * alternative is a rebuild that stops halfway and reports nothing about what it did.
     */
    private fun rebuildOne(storeId: UUID): RebuildOutcome {
        val outcome =
            try {
                if (storeRebuild.rebuild(storeId)) RebuildOutcome.INDEXED else RebuildOutcome.SKIPPED
            } catch (failure: DomainFailure) {
                logFailure(storeId, failure)
                RebuildOutcome.FAILED
            } catch (failure: DataAccessException) {
                logFailure(storeId, failure)
                RebuildOutcome.FAILED
            } catch (failure: TransactionException) {
                logFailure(storeId, failure)
                RebuildOutcome.FAILED
            }
        metrics.record(outcome.updateOutcome, StoreSearchIndexUpdateTrigger.REBUILD)
        return outcome
    }

    private fun logFailure(
        storeId: UUID,
        failure: Throwable,
    ) {
        logger.warn("Search index rebuild failed for store {}", storeId, failure)
    }

    private enum class RebuildOutcome(
        val updateOutcome: StoreSearchIndexUpdateOutcome,
    ) {
        INDEXED(StoreSearchIndexUpdateOutcome.SUCCEEDED),
        SKIPPED(StoreSearchIndexUpdateOutcome.SKIPPED),
        FAILED(StoreSearchIndexUpdateOutcome.FAILED),
    }
}

/** The per-store transaction boundary of a rebuild, separate so the proxy actually applies. */
@Component
internal class StoreSearchIndexStoreRebuild(
    private val sources: StoreSearchTermSourceQuery,
    private val index: StoreSearchIndexOperations,
) {
    /** Returns false when the store disappeared between listing it and reading it. */
    @Transactional
    fun rebuild(storeId: UUID): Boolean {
        val source = sources.findSearchTermSource(storeId) ?: return false
        val terms =
            buildList {
                add(StoreSearchTermEntry(StoreSearchTermKind.STORE_NAME, source.storeName))
                source.availableMenus.forEach { menu ->
                    add(StoreSearchTermEntry(StoreSearchTermKind.MENU_NAME, menu.name, menu.menuId))
                }
            }
        index.replaceStoreTerms(ReplaceStoreSearchTermsCommand(storeId, REBUILT_KINDS, terms))
        return true
    }

    private companion object {
        /**
         * Only the kinds this rebuild has sources for. Brand and region terms belong to the
         * commands that own them, and replacing those kinds here would delete them.
         */
        val REBUILT_KINDS = setOf(StoreSearchTermKind.STORE_NAME, StoreSearchTermKind.MENU_NAME)
    }
}

internal enum class StoreSearchIndexUpdateOutcome {
    SUCCEEDED,
    SKIPPED,
    FAILED,
}

internal enum class StoreSearchIndexUpdateTrigger {
    BRAND,
    REGION,
    REBUILD,
}

/** Closed vocabularies only. Store ids, names and search text are never tag values. */
@Component
internal class StoreSearchIndexUpdateMetrics(
    private val meterRegistry: MeterRegistry,
) {
    fun record(
        outcome: StoreSearchIndexUpdateOutcome,
        trigger: StoreSearchIndexUpdateTrigger,
    ) {
        meterRegistry
            .counter("beanflow.discovery.search.index.update", "outcome", outcome.name, "trigger", trigger.name)
            .increment()
    }
}

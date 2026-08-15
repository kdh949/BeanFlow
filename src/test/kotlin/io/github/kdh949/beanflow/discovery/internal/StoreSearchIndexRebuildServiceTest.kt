package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.merchant.api.StoreSearchMenuSource
import io.github.kdh949.beanflow.merchant.api.StoreSearchTermSource
import io.github.kdh949.beanflow.merchant.api.StoreSearchTermSourceQuery
import io.github.kdh949.beanflow.shared.api.ReplaceBrandSearchTermsCommand
import io.github.kdh949.beanflow.shared.api.ReplaceStoreSearchTermsCommand
import io.github.kdh949.beanflow.shared.api.StoreSearchIndexOperations
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

internal class StoreSearchIndexRebuildServiceTest {
    @Test
    fun `completion is scoped to the initial target snapshot when a lower UUID store is created after the first chunk`() {
        val lowerId = UUID.fromString("10000000-0000-0000-0000-000000000000")
        val firstId = UUID.fromString("80000000-0000-0000-0000-000000000000")
        val sources = SnapshotMutatingSource(firstId, lowerId)
        val indexedStoreIds = mutableListOf<UUID>()
        val index =
            object : StoreSearchIndexOperations {
                override fun replaceStoreTerms(command: ReplaceStoreSearchTermsCommand) {
                    indexedStoreIds += command.storeId
                }

                override fun replaceBrandTerms(command: ReplaceBrandSearchTermsCommand) = Unit
            }
        val service =
            StoreSearchIndexRebuildService(
                sources,
                StoreSearchIndexStoreRebuild(sources, index),
                StoreSearchIndexUpdateMetrics(SimpleMeterRegistry()),
                StoreSearchIndexRebuildProperties(chunkSize = 1),
            )

        val result = service.rebuildAll()

        assertThat(sources.lowerStoreCreatedAfterFirstChunk).isTrue()
        assertThat(result.targetStoreCount).isEqualTo(1)
        assertThat(result.indexedStoreCount).isEqualTo(1)
        assertThat(result.completeSnapshot).isTrue()
        assertThat(indexedStoreIds).containsExactly(firstId)
        assertThat(indexedStoreIds).doesNotContain(lowerId)
    }

    @Test
    fun `rebuild chunk size rejects values outside the configured startup range`() {
        assertThatThrownBy { StoreSearchIndexRebuildProperties(chunkSize = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { StoreSearchIndexRebuildProperties(chunkSize = 1001) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private class SnapshotMutatingSource(
        private val firstId: UUID,
        private val lowerId: UUID,
    ) : StoreSearchTermSourceQuery {
        var lowerStoreCreatedAfterFirstChunk = false
            private set

        override fun findRebuildTargetStoreIds(): List<UUID> = listOf(firstId)

        override fun findAllSearchTermSources(): List<StoreSearchTermSource> = error("not used by rebuild")

        override fun findSearchTermSource(storeId: UUID): StoreSearchTermSource? {
            if (storeId == firstId) lowerStoreCreatedAfterFirstChunk = true
            return StoreSearchTermSource(storeId, "store-$storeId", emptyList<StoreSearchMenuSource>())
        }
    }
}

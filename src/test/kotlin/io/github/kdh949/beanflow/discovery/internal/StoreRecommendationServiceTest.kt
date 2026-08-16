package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.discovery.api.CustomerStoreView
import io.github.kdh949.beanflow.discovery.api.FavoriteStoreOperations
import io.github.kdh949.beanflow.discovery.api.NearbyStorePage
import io.github.kdh949.beanflow.discovery.api.NearbyStoreQueryOperations
import io.github.kdh949.beanflow.discovery.api.NearbyStoreView
import io.github.kdh949.beanflow.discovery.api.RecentStoreOperations
import io.github.kdh949.beanflow.discovery.api.StoreRecommendationCommand
import io.github.kdh949.beanflow.shared.api.SignedCursor
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class StoreRecommendationServiceTest {
    private val now = Instant.parse("2026-08-16T00:00:00Z")
    private val customerId = UUID.randomUUID()

    @Test
    fun `favorite recent nearby merge keeps first reason and removes duplicates`() {
        val favorite = store("favorite")
        val overlap = store("overlap")
        val recent = store("recent")
        val nearby = store("nearby")
        var nearbyCalls = 0
        val service =
            StoreRecommendationService(
                favorites = favoriteSource(favorite, overlap),
                recentStores = recentSource(overlap, recent),
                nearbyStores =
                    object : NearbyStoreQueryOperations {
                        override fun search(command: io.github.kdh949.beanflow.discovery.api.SearchNearbyStoresCommand): NearbyStorePage {
                            nearbyCalls++
                            return NearbyStorePage(
                                items =
                                    listOf(
                                        NearbyStoreView(overlap.storeId, overlap.name, 1, true, true),
                                        NearbyStoreView(nearby.storeId, nearby.name, 2, true, false),
                                    ),
                                nextCursor = null,
                            )
                        }
                    },
                nearbyValidation = NearbyStoreQueryValidation(noOpCursorCodec()),
            )

        val result =
            service.list(
                customerId,
                StoreRecommendationCommand("37.5", "127.0", "1000", "10", now),
            )

        assertThat(result.items.map { it.store.storeId })
            .containsExactly(favorite.storeId, overlap.storeId, recent.storeId, nearby.storeId)
        assertThat(result.items.map { it.reason.name })
            .containsExactly("FAVORITE", "FAVORITE", "RECENT", "NEARBY")
        assertThat(
            result.items
                .last()
                .store.distanceMeters,
        ).isEqualTo(2L)
        assertThat(nearbyCalls).isEqualTo(1)
    }

    @Test
    fun `coordinates omitted keep favorite and recent order without nearby fallback`() {
        val favorite = store("favorite")
        var nearbyCalls = 0
        val service =
            StoreRecommendationService(
                favorites = favoriteSource(favorite),
                recentStores = recentSource(),
                nearbyStores =
                    object : NearbyStoreQueryOperations {
                        override fun search(command: io.github.kdh949.beanflow.discovery.api.SearchNearbyStoresCommand): NearbyStorePage {
                            nearbyCalls++
                            return NearbyStorePage(emptyList(), null)
                        }
                    },
                nearbyValidation = NearbyStoreQueryValidation(noOpCursorCodec()),
            )

        val result = service.list(customerId, StoreRecommendationCommand(null, null, null, null, now))

        assertThat(result.items.map { it.reason.name }).containsExactly("FAVORITE")
        assertThat(
            result.items
                .single()
                .store.distanceMeters,
        ).isNull()
        assertThat(nearbyCalls).isZero()
    }

    @Test
    fun `coordinate pair uses a 3km nearby radius when radius is omitted`() {
        var receivedRadiusMeters: String? = null
        val service =
            StoreRecommendationService(
                favorites = favoriteSource(),
                recentStores = recentSource(),
                nearbyStores =
                    object : NearbyStoreQueryOperations {
                        override fun search(command: io.github.kdh949.beanflow.discovery.api.SearchNearbyStoresCommand): NearbyStorePage {
                            receivedRadiusMeters = command.radiusMeters
                            return NearbyStorePage(emptyList(), null)
                        }
                    },
                nearbyValidation = NearbyStoreQueryValidation(noOpCursorCodec()),
            )

        service.list(customerId, StoreRecommendationCommand("37.5", "127.0", null, null, now))

        assertThat(receivedRadiusMeters).isEqualTo("3000")
    }

    @Test
    fun `partial coordinates or radius without coordinates are rejected before source reads`() {
        val service =
            StoreRecommendationService(
                favorites = favoriteSource(),
                recentStores = recentSource(),
                nearbyStores =
                    object : NearbyStoreQueryOperations {
                        override fun search(command: io.github.kdh949.beanflow.discovery.api.SearchNearbyStoresCommand): NearbyStorePage =
                            NearbyStorePage(emptyList(), null)
                    },
                nearbyValidation = NearbyStoreQueryValidation(noOpCursorCodec()),
            )

        assertThatThrownBy {
            service.list(customerId, StoreRecommendationCommand("37.5", null, "1000", null, now))
        }.hasMessage("Latitude and longitude must be provided together")
        assertThatThrownBy {
            service.list(customerId, StoreRecommendationCommand(null, null, "1000", null, now))
        }.hasMessage("Radius requires latitude and longitude")
    }

    private fun favoriteSource(vararg stores: CustomerStoreView) =
        object : FavoriteStoreOperations {
            override fun list(
                customerId: UUID,
                now: Instant,
                limit: Int,
            ): List<CustomerStoreView> = stores.toList().take(limit)

            override fun add(
                customerId: UUID,
                storeId: UUID,
                now: Instant,
            ) = Unit

            override fun remove(
                customerId: UUID,
                storeId: UUID,
            ) = Unit
        }

    private fun recentSource(vararg stores: CustomerStoreView) =
        object : RecentStoreOperations {
            override fun list(
                customerId: UUID,
                rawLimit: String?,
                now: Instant,
            ): List<CustomerStoreView> = stores.toList()
        }

    private fun store(name: String) = CustomerStoreView(UUID.randomUUID(), name, true)

    private fun noOpCursorCodec() =
        object : SignedCursorCodec {
            override fun <T> issue(
                scope: SignedCursorScope<T>,
                sort: T,
                expiresAt: Instant,
            ): String = error("not used")

            override fun <T> verify(
                token: String,
                scope: SignedCursorScope<T>,
            ): SignedCursor<T> = error("not used")
        }
}

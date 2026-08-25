package io.github.kdh949.beanflow.discovery.api

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

data class StorefrontImageView(
    val url: String,
    val expiresAt: Instant,
)

/**
 * The compact public store representation shared by favorites, recent stores and recommendations.
 *
 * `distanceMeters` is absent for favorites and recent stores; it is only populated by a nearby
 * recommendation path that received a coordinate pair.
 */
data class CustomerStoreView(
    val storeId: UUID,
    val name: String,
    val orderingAvailable: Boolean,
    val pickupAvailable: Boolean,
    val nextPickupWindow: NextPickupWindowView?,
    val customerDisplay: CustomerStoreDisplayView,
    val distanceMeters: Long? = null,
    val image: StorefrontImageView? = null,
)

data class NextPickupWindowView(
    val startsAt: Instant,
    val endsAt: Instant,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CustomerStoreDisplayView(
    val addressLine: String?,
    val directionsHint: String?,
    val operatingStatus: StoreOperatingStatus,
    val operatingHours: CustomerStoreOperatingHoursView?,
)

enum class StoreOperatingStatus {
    OPEN,
    CLOSED,
    UNSPECIFIED,
}

data class CustomerStoreOperatingHoursView(
    val timezone: String,
    val days: List<CustomerStoreOperatingDayView>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CustomerStoreOperatingDayView(
    val dayOfWeek: DayOfWeek,
    val closed: Boolean,
    val opensAt: LocalTime?,
    val closesAt: LocalTime?,
)

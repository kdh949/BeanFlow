package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.discovery.api.CustomerStoreDisplayView
import io.github.kdh949.beanflow.discovery.api.CustomerStoreOperatingDayView
import io.github.kdh949.beanflow.discovery.api.CustomerStoreOperatingHoursView
import io.github.kdh949.beanflow.discovery.api.StoreOperatingStatus
import io.github.kdh949.beanflow.merchant.api.NearbyStoreProfileProjection
import io.github.kdh949.beanflow.merchant.api.StoreCustomerDisplayProjection
import io.github.kdh949.beanflow.merchant.api.StoreDiscoveryDisplayProjection
import java.time.Instant
import java.time.ZoneId

internal fun StoreCustomerDisplayProjection.toCustomerView(now: Instant): CustomerStoreDisplayView {
    val hours = operatingHours
    return CustomerStoreDisplayView(
        addressLine = addressLine,
        directionsHint = directionsHint,
        operatingStatus = hours?.operatingStatus(now) ?: StoreOperatingStatus.UNSPECIFIED,
        operatingHours =
            hours?.let { schedule ->
                CustomerStoreOperatingHoursView(
                    timezone = SEOUL_TIMEZONE,
                    days =
                        schedule.days.map { day ->
                            CustomerStoreOperatingDayView(
                                dayOfWeek = day.dayOfWeek,
                                closed = day.closed,
                                opensAt = day.opensAt,
                                closesAt = day.closesAt,
                            )
                        },
                )
            },
    )
}

internal fun StoreDiscoveryDisplayProjection.immediateOrderingAvailable(now: Instant): Boolean =
    immediateOrderingAvailable(customerDisplay.toCustomerView(now))

internal fun StoreDiscoveryDisplayProjection.immediateOrderingAvailable(display: CustomerStoreDisplayView): Boolean =
    orderingAvailable && display.operatingStatus == StoreOperatingStatus.OPEN

internal fun NearbyStoreProfileProjection.immediateOrderingAvailable(now: Instant): Boolean =
    immediateOrderingAvailable(customerDisplay.toCustomerView(now))

internal fun NearbyStoreProfileProjection.immediateOrderingAvailable(display: CustomerStoreDisplayView): Boolean =
    orderingAvailable && display.operatingStatus == StoreOperatingStatus.OPEN

private fun io.github.kdh949.beanflow.merchant.api.StoreWeeklyOperatingHours.operatingStatus(now: Instant): StoreOperatingStatus {
    val local = now.atZone(SEOUL_ZONE)
    val today = days.first { it.dayOfWeek == local.dayOfWeek }
    if (today.closed) return StoreOperatingStatus.CLOSED
    val opensAt = requireNotNull(today.opensAt)
    val closesAt = requireNotNull(today.closesAt)
    return if (!local.toLocalTime().isBefore(opensAt) && local.toLocalTime().isBefore(closesAt)) {
        StoreOperatingStatus.OPEN
    } else {
        StoreOperatingStatus.CLOSED
    }
}

private const val SEOUL_TIMEZONE = "Asia/Seoul"
private val SEOUL_ZONE: ZoneId = ZoneId.of(SEOUL_TIMEZONE)

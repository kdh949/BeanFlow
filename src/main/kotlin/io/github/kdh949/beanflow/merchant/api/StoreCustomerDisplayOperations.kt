package io.github.kdh949.beanflow.merchant.api

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

data class StoreOperatingDay(
    val dayOfWeek: DayOfWeek,
    val closed: Boolean,
    val opensAt: LocalTime?,
    val closesAt: LocalTime?,
)

data class StoreWeeklyOperatingHours(
    val days: List<StoreOperatingDay>,
)

data class StoreCustomerDisplaySnapshot(
    val storeId: UUID,
    val addressLine: String?,
    val directionsHint: String?,
    val operatingHours: StoreWeeklyOperatingHours?,
    val version: Long,
)

data class ReplaceStoreCustomerDisplayCommand(
    val storeId: UUID,
    val expectedVersion: Long,
    val addressLine: String?,
    val directionsHint: String?,
    val timezone: String?,
    val operatingDays: List<StoreOperatingDay>?,
)

data class StoreCustomerDisplayChange(
    val previous: StoreCustomerDisplaySnapshot,
    val current: StoreCustomerDisplaySnapshot,
    val changed: Boolean,
)

interface StoreCustomerDisplayOperations {
    fun find(storeId: UUID): StoreCustomerDisplaySnapshot

    fun replace(
        command: ReplaceStoreCustomerDisplayCommand,
        now: Instant,
    ): StoreCustomerDisplayChange
}

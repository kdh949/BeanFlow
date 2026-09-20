package io.github.kdh949.beanflow.merchant.api

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
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

enum class StoreOrderAvailabilityReason {
    AVAILABLE,
    STORE_HOURS_NOT_CONFIGURED,
    STORE_CLOSED,
    STORE_NOT_ACCEPTING_ORDERS,
}

data class StoreOrderAvailabilitySnapshot(
    val storeId: UUID,
    val available: Boolean,
    val reason: StoreOrderAvailabilityReason,
    val businessDate: LocalDate,
    val orderingWindowOpensAt: Instant?,
    val orderingWindowClosesAt: Instant?,
    val displayVersion: Long,
    val orderingPolicyVersion: Long,
)

/** Merchant-owned source of truth for admitting a new Order. */
interface StoreOrderAvailabilityOperations {
    /** Reads the current policy without retaining a Store lock. */
    fun inspect(
        storeId: UUID,
        at: Instant,
    ): StoreOrderAvailabilitySnapshot

    /** Holds the Store commerce-root shared lock through the caller-owned transaction. */
    fun lockForOrderCommitment(
        storeId: UUID,
        at: Instant,
    ): StoreOrderAvailabilitySnapshot
}

/** Published only when the currently-open interval is shortened. */
data class StoreOrderingWindowShortened(
    val storeId: UUID,
    val previousClosesAt: Instant,
    val shortenedClosesAt: Instant,
    val displayVersion: Long,
    val changedAt: Instant,
)

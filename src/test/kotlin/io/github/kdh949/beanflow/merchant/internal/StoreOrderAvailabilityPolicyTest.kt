package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.StoreCustomerDisplaySnapshot
import io.github.kdh949.beanflow.merchant.api.StoreOperatingDay
import io.github.kdh949.beanflow.merchant.api.StoreOrderAvailabilityReason
import io.github.kdh949.beanflow.merchant.api.StoreWeeklyOperatingHours
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

internal class StoreOrderAvailabilityPolicyTest {
    private val storeId = UUID.randomUUID()

    @Test
    fun `same-day Seoul window is open-inclusive and close-exclusive`() {
        val display = display(open = LocalTime.of(9, 0), close = LocalTime.of(18, 0))

        val atOpen = evaluate(display, Instant.parse("2026-09-16T00:00:00Z"))
        val atClose = evaluate(display, Instant.parse("2026-09-16T09:00:00Z"))

        assertThat(atOpen.reason).isEqualTo(StoreOrderAvailabilityReason.AVAILABLE)
        assertThat(atOpen.orderingWindowClosesAt).isEqualTo(Instant.parse("2026-09-16T09:00:00Z"))
        assertThat(atClose.reason).isEqualTo(StoreOrderAvailabilityReason.STORE_CLOSED)
    }

    @Test
    fun `missing hours fail closed without inventing a window`() {
        val availability =
            evaluate(
                StoreCustomerDisplaySnapshot(storeId, null, null, null, 0),
                Instant.parse("2026-09-16T01:00:00Z"),
            )

        assertThat(availability.available).isFalse()
        assertThat(availability.reason).isEqualTo(StoreOrderAvailabilityReason.STORE_HOURS_NOT_CONFIGURED)
        assertThat(availability.orderingWindowClosesAt).isNull()
    }

    @Test
    fun `manual ordering off is distinct from closed hours`() {
        val availability =
            StoreOperatingWindowPolicy.evaluate(
                storeId = storeId,
                acceptingOrders = false,
                pickupEnabled = true,
                orderingPolicyVersion = 4,
                display = display(LocalTime.of(9, 0), LocalTime.of(18, 0)),
                at = Instant.parse("2026-09-16T01:00:00Z"),
            )

        assertThat(availability.reason).isEqualTo(StoreOrderAvailabilityReason.STORE_NOT_ACCEPTING_ORDERS)
        assertThat(availability.orderingWindowClosesAt).isEqualTo(Instant.parse("2026-09-16T09:00:00Z"))
    }

    @Test
    fun `current window shortening emits only a smaller cutoff`() {
        val now = Instant.parse("2026-09-16T01:00:00Z")
        val previous = display(LocalTime.of(9, 0), LocalTime.of(18, 0), version = 3)
        val shortened = display(LocalTime.of(9, 0), LocalTime.of(16, 0), version = 4)
        val extended = display(LocalTime.of(9, 0), LocalTime.of(20, 0), version = 4)

        assertThat(StoreOperatingWindowPolicy.shortening(previous, shortened, now)?.shortenedClosesAt)
            .isEqualTo(Instant.parse("2026-09-16T07:00:00Z"))
        assertThat(StoreOperatingWindowPolicy.shortening(previous, extended, now)).isNull()
    }

    @Test
    fun `closing the current day cuts off at the change instant`() {
        val now = Instant.parse("2026-09-16T01:00:00Z")
        val previous = display(LocalTime.of(9, 0), LocalTime.of(18, 0), version = 3)
        val closed = display(closed = true, version = 4)

        assertThat(StoreOperatingWindowPolicy.shortening(previous, closed, now)?.shortenedClosesAt).isEqualTo(now)
    }

    private fun evaluate(
        display: StoreCustomerDisplaySnapshot,
        at: Instant,
    ) = StoreOperatingWindowPolicy.evaluate(storeId, true, true, 2, display, at)

    private fun display(
        open: LocalTime = LocalTime.of(9, 0),
        close: LocalTime = LocalTime.of(18, 0),
        closed: Boolean = false,
        version: Long = 1,
    ): StoreCustomerDisplaySnapshot =
        StoreCustomerDisplaySnapshot(
            storeId = storeId,
            addressLine = null,
            directionsHint = null,
            operatingHours =
                StoreWeeklyOperatingHours(
                    DayOfWeek.entries.map { day ->
                        StoreOperatingDay(
                            dayOfWeek = day,
                            closed = closed,
                            opensAt = open.takeUnless { closed },
                            closesAt = close.takeUnless { closed },
                        )
                    },
                ),
            version = version,
        )
}

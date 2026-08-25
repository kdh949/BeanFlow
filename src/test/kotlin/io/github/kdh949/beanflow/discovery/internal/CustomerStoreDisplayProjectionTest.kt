package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.discovery.api.StoreOperatingStatus
import io.github.kdh949.beanflow.merchant.api.StoreCustomerDisplayProjection
import io.github.kdh949.beanflow.merchant.api.StoreOperatingDay
import io.github.kdh949.beanflow.merchant.api.StoreWeeklyOperatingHours
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

internal class CustomerStoreDisplayProjectionTest {
    @Test
    fun `missing schedule is UNSPECIFIED while optional public text is preserved`() {
        val view = StoreCustomerDisplayProjection("서울시 성동구", "성수역 3번 출구", null).toCustomerView(MONDAY_OPEN)

        assertThat(view.addressLine).isEqualTo("서울시 성동구")
        assertThat(view.directionsHint).isEqualTo("성수역 3번 출구")
        assertThat(view.operatingStatus).isEqualTo(StoreOperatingStatus.UNSPECIFIED)
        assertThat(view.operatingHours).isNull()
    }

    @Test
    fun `Seoul same-day interval is open at opensAt and closed at closesAt`() {
        val projection = projection(openMonday = true)

        assertThat(projection.toCustomerView(MONDAY_OPEN.minusMillis(1)).operatingStatus).isEqualTo(StoreOperatingStatus.CLOSED)
        assertThat(projection.toCustomerView(MONDAY_OPEN).operatingStatus).isEqualTo(StoreOperatingStatus.OPEN)
        assertThat(projection.toCustomerView(MONDAY_CLOSE.minusMillis(1)).operatingStatus).isEqualTo(StoreOperatingStatus.OPEN)
        assertThat(projection.toCustomerView(MONDAY_CLOSE).operatingStatus).isEqualTo(StoreOperatingStatus.CLOSED)
    }

    @Test
    fun `closed day remains CLOSED and schedule carries fixed Seoul timezone`() {
        val view = projection(openMonday = false).toCustomerView(MONDAY_OPEN)

        assertThat(view.operatingStatus).isEqualTo(StoreOperatingStatus.CLOSED)
        assertThat(view.operatingHours?.timezone).isEqualTo("Asia/Seoul")
        assertThat(view.operatingHours?.days).hasSize(7)
    }

    private fun projection(openMonday: Boolean): StoreCustomerDisplayProjection =
        StoreCustomerDisplayProjection(
            addressLine = null,
            directionsHint = null,
            operatingHours =
                StoreWeeklyOperatingHours(
                    DayOfWeek.entries.map { day ->
                        if (day == DayOfWeek.MONDAY && openMonday) {
                            StoreOperatingDay(day, false, LocalTime.of(9, 0), LocalTime.of(18, 0))
                        } else {
                            StoreOperatingDay(day, true, null, null)
                        }
                    },
                ),
        )

    private companion object {
        val MONDAY_OPEN: Instant = Instant.parse("2026-08-24T00:00:00Z")
        val MONDAY_CLOSE: Instant = Instant.parse("2026-08-24T09:00:00Z")
    }
}

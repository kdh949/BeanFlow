package io.github.kdh949.beanflow.ordering.internal

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

internal class FastReorderOptionSnapshotTest {
    private val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val second = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @Test
    fun `snapshotted option IDs accept verified empty and sorted unique selections`() {
        assertThatCode { line(OptionSelectionSnapshotState.SNAPSHOTTED, emptyList()) }
            .doesNotThrowAnyException()
        assertThatCode { line(OptionSelectionSnapshotState.SNAPSHOTTED, listOf(first, second)) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `snapshotted option IDs reject missing duplicate and unsorted selections`() {
        assertThatThrownBy { line(OptionSelectionSnapshotState.SNAPSHOTTED, null) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { line(OptionSelectionSnapshotState.SNAPSHOTTED, listOf(first, first)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { line(OptionSelectionSnapshotState.SNAPSHOTTED, listOf(second, first)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `legacy option state rejects inferred identities`() {
        assertThatCode { line(OptionSelectionSnapshotState.LEGACY_UNAVAILABLE, null) }
            .doesNotThrowAnyException()
        assertThatThrownBy { line(OptionSelectionSnapshotState.LEGACY_UNAVAILABLE, emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun line(
        state: OptionSelectionSnapshotState,
        optionIds: List<UUID>?,
    ) = OrderLineEntity(
        id = UUID.randomUUID(),
        orderId = UUID.randomUUID(),
        lineSequence = 0,
        menuId = UUID.randomUUID(),
        menuName = "Menu",
        optionNamesJson = "[]",
        optionSelectionSnapshotState = state,
        normalizedOptionIds = optionIds,
        sellableRequirementsJson = "[]",
        unitPriceKrw = 1_000,
        quantity = 1,
        grossKrw = 1_000,
        couponDiscountKrw = 0,
        pointsAppliedKrw = 0,
        cashPayableKrw = 1_000,
    )
}

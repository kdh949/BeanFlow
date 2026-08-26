package io.github.kdh949.beanflow.ordering.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate

internal class StoreOrderBoardEtagTest {
    private val etags = Sha256StoreOrderBoardEtagGenerator(JsonMapper.builder().build(), SimpleMeterRegistry())

    @Test
    fun `etag ignores opaque overflow cursor renewal but includes the visible overflow state`() {
        val first =
            StoreOrderBoardResponse(
                groups = emptyList(),
                overflow = listOf(StoreOrderBoardOverflowResponse(StoreOrderBoardLane.READY, 1, "cursor-issued-at-one")),
            )
        val renewedCursor =
            first.copy(
                overflow = listOf(StoreOrderBoardOverflowResponse(StoreOrderBoardLane.READY, 1, "cursor-issued-at-two")),
            )
        val changedOverflow =
            first.copy(
                overflow = listOf(StoreOrderBoardOverflowResponse(StoreOrderBoardLane.READY, 2, "cursor-issued-at-two")),
            )

        assertThat(etags.generate(first)).startsWith("W/")
        assertThat(etags.generate(renewedCursor)).isEqualTo(etags.generate(first))
        assertThat(etags.generate(changedOverflow)).isNotEqualTo(etags.generate(first))
    }

    @Test
    fun `conditional read compares the board weakly even when the client sent an opaque strong form`() {
        assertThat(StoreOrderBoardConditionalRequest.matches("\"same\"", "W/\"same\"")).isTrue()
        assertThat(StoreOrderBoardConditionalRequest.matches("W/\"same\", \"other\"", "W/\"same\"")).isTrue()
        assertThat(StoreOrderBoardConditionalRequest.matches("\"other\"", "W/\"same\"")).isFalse()
    }

    @Test
    fun `etag changes when only a persisted lifecycle timestamp changes`() {
        val paidAt = Instant.parse("2026-08-14T03:00:00Z")
        val item =
            StoreOrderBoardItemResponse(
                orderReference = "BF-2222-2222",
                pickupNumber = "A-1",
                pickupBusinessDate = LocalDate.parse("2026-08-14"),
                lane = StoreOrderBoardLane.ACCEPTED,
                status = "ACCEPTED",
                pickupWindowStart = paidAt.plusSeconds(600),
                pickupWindowEnd = paidAt.plusSeconds(1_200),
                itemSummary = "Americano x 1",
                acceptanceDeadlineAt = paidAt.plusSeconds(180),
                acceptancePhase = null,
                allowedActions = listOf(StoreOrderAction.START_PREPARING),
                lifecycle = StoreOrderBoardLifecycleResponse(paidAt, paidAt.plusSeconds(10), null, null),
            )
        val first =
            StoreOrderBoardResponse(
                groups = listOf(StoreOrderBoardDateGroupResponse(item.pickupBusinessDate, listOf(item))),
            )
        val changed =
            first.copy(
                groups =
                    listOf(
                        StoreOrderBoardDateGroupResponse(
                            item.pickupBusinessDate,
                            listOf(item.copy(lifecycle = item.lifecycle?.copy(acceptedAt = paidAt.plusSeconds(11)))),
                        ),
                    ),
            )

        assertThat(etags.generate(changed)).isNotEqualTo(etags.generate(first))
    }
}

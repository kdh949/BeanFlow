package io.github.kdh949.beanflow.ordering.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

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
}

package io.github.kdh949.beanflow.shared.api

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SupportOwnerTimelineContractTest {
    private val occurredAt = Instant.parse("2026-08-12T05:00:00Z")

    @Test
    fun `global comparator orders time descending source ascending and id descending`() {
        val later = fact(SupportTimelineSource.ORDERING, UUID.fromString("00000000-0000-0000-0000-000000000001"), occurredAt.plusSeconds(1))
        val earlierSource = fact(SupportTimelineSource.ORDERING, UUID.fromString("00000000-0000-0000-0000-000000000001"), occurredAt)
        val laterSource = fact(SupportTimelineSource.PAYMENT, UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), occurredAt)
        val largerId = fact(SupportTimelineSource.ORDERING, UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), occurredAt)

        assertThat(listOf(earlierSource, laterSource, later, largerId).sortedWith(SUPPORT_TIMELINE_COMPARATOR))
            .containsExactly(later, largerId, earlierSource, laterSource)
    }

    @Test
    fun `boundary recognizes only items after the cursor tuple`() {
        val cursor =
            SupportTimelineBoundary(
                occurredAt,
                SupportTimelineSource.PAYMENT,
                UUID.fromString("80000000-0000-0000-0000-000000000000"),
            )

        assertThat(cursor.isBefore(fact(SupportTimelineSource.ORDERING, UUID.randomUUID(), occurredAt))).isFalse()
        assertThat(cursor.isBefore(fact(SupportTimelineSource.LOYALTY, UUID.randomUUID(), occurredAt))).isTrue()
        assertThat(
            cursor.isBefore(
                fact(SupportTimelineSource.PAYMENT, UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"), occurredAt),
            ),
        ).isTrue()
        assertThat(cursor.isBefore(fact(SupportTimelineSource.PAYMENT, UUID.randomUUID(), occurredAt.minusNanos(1)))).isTrue()
    }

    @Test
    fun `owner query rejects unbounded order and page sizes`() {
        val ids = (1..100).map { UUID.randomUUID() }.toSet()
        SupportOwnerTimelineQuery(ids, null, 101)

        assertThatThrownBy { SupportOwnerTimelineQuery(emptySet(), null, 20) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SupportOwnerTimelineQuery(ids + UUID.randomUUID(), null, 20) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SupportOwnerTimelineQuery(ids, null, 102) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun fact(
        source: SupportTimelineSource,
        id: UUID,
        at: Instant,
    ) = SupportOwnerTimelineFact(
        source = source,
        type = SupportTimelineType.ORDER_STATE,
        itemId = id,
        state = SupportTimelineState.PAID,
        occurredAt = at,
        amountKrw = null,
    )
}

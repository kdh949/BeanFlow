package io.github.kdh949.beanflow.ordering.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

internal class OrderReferenceBackfillCliTest {
    @Test
    fun `backfill arguments use a bounded default and accept an explicit batch size`() {
        assertThat(OrderReferenceBackfillArguments.parse(emptyArray()).batchSize).isEqualTo(100)
        assertThat(OrderReferenceBackfillArguments.parse(arrayOf("--batch-size=37")).batchSize).isEqualTo(37)
    }

    @Test
    fun `backfill arguments reject unknown duplicate and unbounded input`() {
        listOf(
            arrayOf("--unknown=1"),
            arrayOf("--batch-size=0"),
            arrayOf("--batch-size=1001"),
            arrayOf("--batch-size=10", "--batch-size=20"),
        ).forEach { arguments ->
            assertThatThrownBy { OrderReferenceBackfillArguments.parse(arguments) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}

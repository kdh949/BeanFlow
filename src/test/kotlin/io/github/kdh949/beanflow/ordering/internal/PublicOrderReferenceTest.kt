package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

internal class PublicOrderReferenceTest {
    @Test
    fun `lookup canonicalizes lowercase before validating the public reference`() {
        assertThat(PublicOrderReference.parse("bf-7k3m-9q2p").value).isEqualTo("BF-7K3M-9Q2P")
    }

    @Test
    fun `lookup rejects ambiguous or malformed public references`() {
        listOf(
            "BF-7K3I-9Q2P",
            "BF-7K3M-9Q0P",
            "BF-7K3M9Q2P",
            " BF-7K3M-9Q2P ",
        ).forEach { candidate ->
            assertThatThrownBy { PublicOrderReference.parse(candidate) }
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.INVALID_REQUEST)
                }
        }
    }

    @Test
    fun `generator uses only the accepted alphabet and exact grouping`() {
        var next = 0
        val generator = PublicOrderReferenceGenerator { bound -> (next++ % bound) }

        assertThat(generator.next().value).isEqualTo("BF-2345-6789")
    }
}

package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.support.internal.domain.PostAcceptanceResolutionOutcome
import io.github.kdh949.beanflow.support.internal.domain.PostAcceptanceResolutionResponsibility
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

internal class SupportResolutionPayloadTest {
    @Test
    fun `resolution action matches the browser vector with store cost and restoration`() {
        val id = UUID.fromString("74000000-0000-4000-8000-000000000001")
        val payloads = PostAcceptanceResolutionPayloadCanonicalizer(SupportCommandPayloadCanonicalizer())
        val command =
            CreatePostAcceptanceResolutionCommand(
                id,
                id,
                id,
                1,
                0,
                0,
                PostAcceptanceResolutionOutcome.PARTIAL_REFUND,
                PostAcceptanceResolutionResponsibility.STORE,
                3000,
                true,
                false,
                -1500,
                "a".repeat(64),
                "resolution-vector",
            )
        assertThat(payloads.actionDigest(command)).isEqualTo("3f1e51131bd4d9a0c1ee345f147b9156f877af0cc548aee252201c0bbefa0604")
    }
}

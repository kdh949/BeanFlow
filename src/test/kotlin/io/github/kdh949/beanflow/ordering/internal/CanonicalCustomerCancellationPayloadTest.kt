package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.CustomerCancellationReasonCode
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

internal class CanonicalCustomerCancellationPayloadTest {
    private val objectMapper = JsonMapper.builder().build()

    @Test
    fun `hash uses sorted canonical JSON with an explicit null detail`() {
        val orderId = UUID.fromString("36a4bb8d-9fec-4927-91fe-c50f57ba2bc4")
        val hash =
            CanonicalCustomerCancellationPayload.hash(
                orderId,
                CustomerCancellationReasonCode.OTHER,
                null,
                objectMapper,
            )
        val canonical =
            """{"detail":null,"orderId":"$orderId","reasonCode":"OTHER"}"""

        assertThat(hash).isEqualTo(sha256(canonical))
        assertThat(
            CanonicalCustomerCancellationPayload.hash(
                orderId,
                CustomerCancellationReasonCode.OTHER,
                CanonicalCustomerCancellationPayload.normalizeDetail("   "),
                objectMapper,
            ),
        ).isEqualTo(hash)
    }

    @Test
    fun `normalization counts Unicode code points and rejects controls`() {
        val twoHundredEmoji = "😀".repeat(200)

        assertThat(CanonicalCustomerCancellationPayload.normalizeDetail("  $twoHundredEmoji  "))
            .isEqualTo(twoHundredEmoji)
        assertThatThrownBy {
            CanonicalCustomerCancellationPayload.normalizeDetail("😀".repeat(201))
        }.isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.INVALID_REQUEST)
        }
        assertThatThrownBy {
            CanonicalCustomerCancellationPayload.normalizeDetail("private\u0000detail")
        }.isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.INVALID_REQUEST)
        }
    }

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
        )
}

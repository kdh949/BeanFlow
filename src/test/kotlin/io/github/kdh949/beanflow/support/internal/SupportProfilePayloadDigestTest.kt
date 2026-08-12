package io.github.kdh949.beanflow.support.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

internal class SupportProfilePayloadDigestTest {
    @Test
    fun `approval digest changes with purpose subject version and exact typed payload`() {
        val first = SupportProfilePayloadDigest.digest(SUBJECT, 4, SupportProfileChangePayload.CustomerPrimaryPhone("010-1111-2222"))

        assertThat(first).matches("^[0-9a-f]{64}$")
        assertThat(SupportProfilePayloadDigest.digest(SUBJECT, 4, SupportProfileChangePayload.CustomerPrimaryPhone("010-1111-2222")))
            .isEqualTo(first)
        assertThat(SupportProfilePayloadDigest.digest(SUBJECT, 5, SupportProfileChangePayload.CustomerPrimaryPhone("010-1111-2222")))
            .isNotEqualTo(first)
        assertThat(SupportProfilePayloadDigest.digest(SUBJECT, 4, SupportProfileChangePayload.CustomerPrimaryPhone("010-1111-3333")))
            .isNotEqualTo(first)
    }

    @Test
    fun `nullable canonical fields distinguish absence literal sentinel empty and delimiters`() {
        val absent =
            SupportProfilePayloadDigest.digest(
                SUBJECT,
                4,
                SupportProfileChangePayload.StorePublicProfile(null, null, "x", null),
            )
        val literalSentinel =
            SupportProfilePayloadDigest.digest(
                SUBJECT,
                4,
                SupportProfileChangePayload.StorePublicProfile("<null>", null, "x", null),
            )
        val empty =
            SupportProfilePayloadDigest.digest(
                SUBJECT,
                4,
                SupportProfileChangePayload.StorePublicProfile("", null, "x", null),
            )
        val delimiterInFirst =
            SupportProfilePayloadDigest.digest(
                SUBJECT,
                4,
                SupportProfileChangePayload.StorePublicProfile("a|b", null, "c", null),
            )
        val delimiterInSecond =
            SupportProfilePayloadDigest.digest(
                SUBJECT,
                4,
                SupportProfileChangePayload.StorePublicProfile("a", null, "b|c", null),
            )

        assertThat(absent).isNotEqualTo(literalSentinel).isNotEqualTo(empty)
        assertThat(delimiterInFirst).isNotEqualTo(delimiterInSecond)
    }

    @Test
    fun `typed payload and command rendering redact raw profile values`() {
        val raw = "account-secret-reference"
        val payload = SupportProfileChangePayload.StoreSettlementAccount(raw)
        val command =
            SubmitSupportProfileChangeCommand(
                SUBJECT,
                CASE,
                SUBJECT,
                1,
                SESSION,
                "reason",
                "e".repeat(64),
                "profile-key-001",
                payload,
            )

        assertThat(payload.toString()).doesNotContain(raw)
        assertThat(command.toString()).doesNotContain(raw).contains("values=<redacted>")
    }

    @Test
    fun `http request rendering redacts raw profile values and binding evidence`() {
        val raw = "account-secret-reference"
        val evidence = "e".repeat(64)
        val request =
            StoreSettlementAccountRequest(
                ProfileChangeBindingRequest(SUBJECT, 1, SESSION, "private reason", evidence),
                raw,
            )

        assertThat(request.toString())
            .doesNotContain(raw)
            .doesNotContain(evidence)
            .doesNotContain("private reason")
            .contains("values=<redacted>")
        assertThat(request.binding.toString()).doesNotContain(evidence).contains("values=<redacted>")
    }

    private companion object {
        val SUBJECT: UUID = UUID.fromString("81000000-0000-0000-0000-000000000001")
        val CASE: UUID = UUID.fromString("81000000-0000-0000-0000-000000000002")
        val SESSION: UUID = UUID.fromString("81000000-0000-0000-0000-000000000003")
    }
}

package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.identity.api.SensitiveVerificationProof
import io.github.kdh949.beanflow.identity.api.VerificationChallengeIssueResult
import io.github.kdh949.beanflow.identity.api.VerifyChallengeCommand
import io.github.kdh949.beanflow.shared.api.PersonalDataField
import io.github.kdh949.beanflow.shared.api.RevealedPersonalData
import io.github.kdh949.beanflow.support.internal.domain.SupportPersonalDataField
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.readText

internal class SupportVerificationPiiLeakTest {
    @Test
    fun `proof provider reference and revealed values are redacted from diagnostic representations`() {
        val proof = SensitiveVerificationProof.copyOf(PROOF.toCharArray())
        val subjectId = UUID.randomUUID()
        val commonIds = List(4) { UUID.randomUUID() }
        val rendered =
            listOf(
                VerifyVerificationChallengeRequest(PROOF),
                proof,
                VerifyChallengeCommand(commonIds[0], PROVIDER_REFERENCE, proof),
                VerificationChallengeIssueResult.Issued(PROVIDER_REFERENCE),
                RevealedPersonalData(subjectId, mapOf(PersonalDataField.PRIMARY_EMAIL to RAW_EMAIL)),
                RevealedPersonalDataResource(
                    commonIds[0],
                    commonIds[1],
                    commonIds[2],
                    subjectId,
                    mapOf(SupportPersonalDataField.CUSTOMER_PRIMARY_EMAIL to RAW_EMAIL),
                    Instant.EPOCH,
                ),
                BreakGlassRevealResource(
                    commonIds[0],
                    commonIds[1],
                    commonIds[2],
                    subjectId,
                    SupportPersonalDataField.CUSTOMER_PRIMARY_EMAIL,
                    RAW_EMAIL,
                    Instant.EPOCH,
                ),
            ).joinToString("\n")

        assertThat(rendered)
            .contains("<redacted>")
            .doesNotContain(PROOF, PROVIDER_REFERENCE, RAW_EMAIL)
        proof.close()
    }

    @Test
    fun `S40 persistence has no secret proof or raw reveal columns`() {
        val migration =
            Path.of("src/main/resources/db/migration/V42__create_support_verification_and_data_access_grant.sql")
                .readText()
                .lowercase()
                .lineSequence()
                .filterNot { it.trimStart().startsWith("comment on") }
                .joinToString("\n")

        assertThat(migration)
            .doesNotContain(" otp", "proof ", "proof_", "raw_value", "raw_link", "revealed_value")
    }

    private companion object {
        const val PROOF = "s40-one-time-proof-canary"
        const val PROVIDER_REFERENCE = "provider-secret-reference-canary"
        const val RAW_EMAIL = "private.s40@example.invalid"
    }
}

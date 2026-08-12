package io.github.kdh949.beanflow.identity.api

import java.util.Arrays
import java.util.UUID

enum class RegisteredVerificationChannel {
    IN_APP,
    REGISTERED_PHONE,
    REGISTERED_EMAIL,
}

data class IssueVerificationChallengeCommand(
    val challengeIntentId: UUID,
    val subjectType: String,
    val subjectId: UUID,
    val channel: RegisteredVerificationChannel,
)

sealed interface VerificationChallengeIssueResult {
    data class Issued(
        val opaqueProviderReference: String,
    ) : VerificationChallengeIssueResult {
        override fun toString(): String = "Issued(opaqueProviderReference=<redacted>)"
    }

    data class Unknown(
        val failureClass: String,
    ) : VerificationChallengeIssueResult
}

class SensitiveVerificationProof private constructor(
    value: CharArray,
) : AutoCloseable {
    private val chars = value.copyOf()

    fun copyChars(): CharArray = chars.copyOf()

    override fun close() {
        Arrays.fill(chars, '\u0000')
    }

    override fun toString(): String = "SensitiveVerificationProof(<redacted>)"

    companion object {
        fun copyOf(value: CharArray): SensitiveVerificationProof = SensitiveVerificationProof(value)
    }
}

data class VerifyChallengeCommand(
    val challengeIntentId: UUID,
    val opaqueProviderReference: String,
    val proof: SensitiveVerificationProof,
) {
    override fun toString(): String =
        "VerifyChallengeCommand(challengeIntentId=$challengeIntentId, opaqueProviderReference=<redacted>, proof=<redacted>)"
}

enum class VerificationChallengeVerifyResult {
    VERIFIED,
    INVALID,
    UNKNOWN,
}

interface VerificationChallengeOperations {
    /** Provider calls must be made outside a BeanFlow database transaction. */
    fun issue(command: IssueVerificationChallengeCommand): VerificationChallengeIssueResult

    /** Provider calls must be made outside a BeanFlow database transaction. */
    fun verify(command: VerifyChallengeCommand): VerificationChallengeVerifyResult
}

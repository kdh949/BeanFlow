package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Locale

@JvmInline
internal value class PublicOrderReference private constructor(
    val value: String,
) {
    companion object {
        private val FORMAT = Regex("^BF-[$ALPHABET]{4}-[$ALPHABET]{4}$")

        fun parse(raw: String): PublicOrderReference {
            val canonical = raw.uppercase(Locale.ROOT)
            if (!FORMAT.matches(canonical)) {
                throw DomainFailure(FailureCode.INVALID_REQUEST, "Public order reference format is invalid")
            }
            return PublicOrderReference(canonical)
        }

        internal fun generated(value: String): PublicOrderReference {
            check(FORMAT.matches(value)) { "Generated public order reference has an invalid format" }
            return PublicOrderReference(value)
        }
    }
}

internal const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

internal fun interface PublicOrderReferenceEntropy {
    fun nextIndex(bound: Int): Int
}

@Component
internal class SecurePublicOrderReferenceEntropy : PublicOrderReferenceEntropy {
    private val secureRandom = SecureRandom()

    override fun nextIndex(bound: Int): Int = secureRandom.nextInt(bound)
}

@Component
internal class PublicOrderReferenceGenerator(
    private val entropy: PublicOrderReferenceEntropy,
) : PublicOrderReferenceCandidateGenerator {
    override fun next(): PublicOrderReference {
        val body = CharArray(8) { ALPHABET[entropy.nextIndex(ALPHABET.length)] }
        return PublicOrderReference.generated("BF-${body.concatToString(0, 4)}-${body.concatToString(4, 8)}")
    }
}

internal fun interface PublicOrderReferenceCandidateGenerator {
    fun next(): PublicOrderReference
}

package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.SignedCursor
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import io.micrometer.core.instrument.MeterRegistry
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class HmacSignedCursorCodec(
    private val keyRing: CursorHmacKeyRing,
    private val clock: Clock,
    private val metrics: CursorMetrics,
) : SignedCursorCodec {
    override fun <T> issue(
        scope: SignedCursorScope<T>,
        sort: T,
        expiresAt: Instant,
    ): String {
        validateScope(scope)
        val issuedAt = clock.instant().epochSecond
        val payload =
            CursorPayload(
                endpoint = scope.endpoint,
                filterHash = scope.filterHash,
                sort = validateSortForIssue(scope.sortAdapter.encode(sort)),
                issuedAt = issuedAt,
                expiresAt = expiresAt.epochSecond,
            )
        validateLifetime(payload.issuedAt, payload.expiresAt, ::invalidIssue)
        val encodedPayload =
            try {
                encodeUrl(canonicalPayload(payload))
            } catch (_: CursorCryptoException) {
                unavailableCursor()
            }
        val signature =
            try {
                hmac(keyRing.activeKey, signatureInput(keyRing.activeKeyId, encodedPayload))
            } catch (_: CursorCryptoException) {
                unavailableCursor()
            }
        val token = "$VERSION.${keyRing.activeKeyId}.$encodedPayload.${encodeUrl(signature)}"
        if (token.length > MAX_TOKEN_LENGTH) {
            throw IllegalArgumentException("Signed cursor exceeds the public maximum length")
        }
        return token
    }

    override fun <T> verify(
        token: String,
        scope: SignedCursorScope<T>,
    ): SignedCursor<T> {
        validateScope(scope)
        return try {
            val verified = verifyToken(token, scope)
            metrics.recordValidation(scope.endpoint, CursorValidationOutcome.VALID)
            verified
        } catch (exception: CursorRejectedException) {
            metrics.recordValidation(scope.endpoint, CursorValidationOutcome.INVALID)
            throw invalidCursor()
        } catch (exception: CursorCryptoException) {
            metrics.recordValidation(scope.endpoint, CursorValidationOutcome.UNAVAILABLE)
            throw unavailableCursor()
        }
    }

    private fun <T> verifyToken(
        token: String,
        scope: SignedCursorScope<T>,
    ): SignedCursor<T> {
        if (token.isEmpty() || token.length > MAX_TOKEN_LENGTH) reject()
        val parts = token.split('.')
        if (parts.size != 4 || parts[0] != VERSION) reject()
        val keyId = parts[1]
        if (!KEY_ID_PATTERN.matches(keyId)) reject()
        val key = keyRing.verificationKey(keyId) ?: reject()
        val encodedPayload = parts[2]
        val encodedSignature = parts[3]
        val signature = decodeUrl(encodedSignature)
        if (signature.size != HMAC_SHA_256_BYTES) reject()
        val expectedSignature = hmac(key, signatureInput(keyId, encodedPayload))
        if (!MessageDigest.isEqual(expectedSignature, signature)) reject()

        val payloadBytes = decodeUrl(encodedPayload)
        val payload = parseCanonicalPayload(payloadBytes)
        if (payload.endpoint != scope.endpoint || payload.filterHash != scope.filterHash) reject()
        validateLifetime(payload.issuedAt, payload.expiresAt, ::reject)
        if (clock.instant().epochSecond >= payload.expiresAt) reject()
        val sort = validateSort(payload.sort)
        val typedSort = decodeSort(scope.sortAdapter, sort)
        return try {
            SignedCursor(
                sort = typedSort,
                issuedAt = Instant.ofEpochSecond(payload.issuedAt),
                expiresAt = Instant.ofEpochSecond(payload.expiresAt),
            )
        } catch (_: DateTimeException) {
            reject()
        }
    }

    private fun <T> validateScope(scope: SignedCursorScope<T>) {
        if (scope.endpoint.isBlank() || !FILTER_HASH_PATTERN.matches(scope.filterHash)) {
            invalidIssue()
        }
    }

    private fun validateSort(sort: List<String>): List<String> {
        if (sort.isEmpty() || sort.any { value -> value.isBlank() || !isCanonicalUuid(value) }) reject()
        return sort
    }

    private fun validateSortForIssue(sort: List<String>): List<String> {
        if (sort.isEmpty() || sort.any { value -> value.isBlank() || !isCanonicalUuid(value) }) invalidIssue()
        return sort
    }

    private fun <T> decodeSort(
        adapter: CursorSortAdapter<T>,
        values: List<String>,
    ): T =
        try {
            adapter.decode(values) ?: reject()
        } catch (_: RuntimeException) {
            reject()
        }

    private fun parseCanonicalPayload(payloadBytes: ByteArray): CursorPayload {
        val text = decodeUtf8(payloadBytes)
        val root =
            try {
                json.readTree(text)
            } catch (_: Exception) {
                reject()
            }
        if (root == null || !root.isObject || root.size() != PAYLOAD_PROPERTIES.size) reject()
        val names = root.propertyNames().toList()
        if (names != PAYLOAD_PROPERTIES) reject()
        val payload =
            CursorPayload(
                endpoint = textValue(root.get("endpoint")),
                filterHash = textValue(root.get("filterHash")),
                sort = stringArray(root.get("sort")),
                issuedAt = epochSecond(root.get("issuedAt")),
                expiresAt = epochSecond(root.get("expiresAt")),
            )
        if (!MessageDigest.isEqual(payloadBytes, canonicalPayload(payload))) reject()
        if (!FILTER_HASH_PATTERN.matches(payload.filterHash)) reject()
        return payload
    }

    private fun canonicalPayload(payload: CursorPayload): ByteArray =
        try {
            json.writeValueAsBytes(
                linkedMapOf(
                    "endpoint" to payload.endpoint,
                    "filterHash" to payload.filterHash,
                    "sort" to payload.sort,
                    "issuedAt" to payload.issuedAt,
                    "expiresAt" to payload.expiresAt,
                ),
            )
        } catch (exception: Exception) {
            throw CursorCryptoException(exception)
        }

    private fun hmac(
        key: ByteArray,
        input: ByteArray,
    ): ByteArray =
        try {
            Mac
                .getInstance(HMAC_ALGORITHM)
                .apply { init(SecretKeySpec(key, HMAC_ALGORITHM)) }
                .doFinal(input)
        } catch (exception: GeneralSecurityException) {
            throw CursorCryptoException(exception)
        }

    private fun decodeUrl(value: String): ByteArray {
        if (!BASE64_URL_PATTERN.matches(value)) reject()
        return try {
            Base64.getUrlDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            reject()
        }
    }

    private fun encodeUrl(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun signatureInput(
        keyId: String,
        encodedPayload: String,
    ): ByteArray = "$VERSION.$keyId.$encodedPayload".toByteArray(StandardCharsets.UTF_8)

    private fun decodeUtf8(value: ByteArray): String =
        try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value))
                .toString()
        } catch (_: CharacterCodingException) {
            reject()
        }

    private fun textValue(node: JsonNode?): String {
        if (node == null || !node.isString) reject()
        return node.stringValue()
    }

    private fun stringArray(node: JsonNode?): List<String> {
        if (node == null || !node.isArray) reject()
        val values = mutableListOf<String>()
        for (element in node) {
            values += textValue(element)
        }
        return values
    }

    private fun epochSecond(node: JsonNode?): Long {
        if (node == null || !node.isIntegralNumber) reject()
        val text = node.asString()
        if (!INTEGER_PATTERN.matches(text)) reject()
        return try {
            text.toLong()
        } catch (_: NumberFormatException) {
            reject()
        }
    }

    private fun validateLifetime(
        issuedAt: Long,
        expiresAt: Long,
        reject: () -> Nothing,
    ) {
        val duration =
            try {
                Math.subtractExact(expiresAt, issuedAt)
            } catch (_: ArithmeticException) {
                reject()
            }
        if (duration !in 1..MAX_LIFETIME_SECONDS) reject()
    }

    private fun isCanonicalUuid(value: String): Boolean =
        runCatching { UUID.fromString(value) }
            .fold(
                onSuccess = { uuid -> uuid.toString() == value },
                onFailure = { true },
            )

    private fun invalidIssue(): Nothing = throw IllegalArgumentException("Signed cursor scope or payload is invalid")

    private fun reject(): Nothing = throw CursorRejectedException()

    private fun invalidCursor(): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, "Cursor is invalid")

    private fun unavailableCursor(): Nothing =
        throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Cursor signing dependency is unavailable")

    private data class CursorPayload(
        val endpoint: String,
        val filterHash: String,
        val sort: List<String>,
        val issuedAt: Long,
        val expiresAt: Long,
    )

    private class CursorRejectedException : RuntimeException()

    private class CursorCryptoException(
        cause: Throwable,
    ) : RuntimeException(cause)

    private companion object {
        const val VERSION = "v1"
        const val MAX_TOKEN_LENGTH = 2048
        const val MAX_LIFETIME_SECONDS = 24 * 60 * 60L
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val HMAC_SHA_256_BYTES = 32
        val PAYLOAD_PROPERTIES = listOf("endpoint", "filterHash", "sort", "issuedAt", "expiresAt")
        val FILTER_HASH_PATTERN = Regex("[0-9a-f]{64}")
        val KEY_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,32}")
        val BASE64_URL_PATTERN = Regex("[A-Za-z0-9_-]+")
        val INTEGER_PATTERN = Regex("-?(0|[1-9][0-9]*)")
        val json = ObjectMapper()
    }
}

internal class CursorHmacKeyRing private constructor(
    val activeKeyId: String,
    private val keys: Map<String, ByteArray>,
) {
    val activeKey: ByteArray
        get() = keys.getValue(activeKeyId)

    fun verificationKey(keyId: String): ByteArray? = keys[keyId]

    companion object {
        private val keyIdPattern = Regex("[A-Za-z0-9_-]{1,32}")
        private val base64UrlPattern = Regex("[A-Za-z0-9_-]+")
        private const val MINIMUM_SECRET_BYTES = 32

        fun from(properties: CursorHmacProperties): CursorHmacKeyRing {
            val activeKeyId = properties.activeKeyId
            if (activeKeyId == null || !keyIdPattern.matches(activeKeyId) || properties.keys.isEmpty()) {
                invalidConfiguration()
            }
            val keys = LinkedHashMap<String, ByteArray>()
            properties.keys.forEach { configured ->
                val id = configured.id
                val encodedSecret = configured.secretBase64Url
                if (
                    id == null ||
                    !keyIdPattern.matches(id) ||
                    encodedSecret == null ||
                    !base64UrlPattern.matches(encodedSecret) ||
                    keys.containsKey(id)
                ) {
                    invalidConfiguration()
                }
                val secret =
                    try {
                        Base64.getUrlDecoder().decode(encodedSecret)
                    } catch (_: IllegalArgumentException) {
                        invalidConfiguration()
                    }
                if (secret.size < MINIMUM_SECRET_BYTES) invalidConfiguration()
                keys[id] = secret
            }
            if (!keys.containsKey(activeKeyId)) invalidConfiguration()
            return CursorHmacKeyRing(activeKeyId, keys)
        }

        private fun invalidConfiguration(): Nothing = throw CursorKeyRingConfigurationException()
    }
}

internal class CursorKeyRingConfigurationException : RuntimeException()

internal class CursorMetrics(
    private val meterRegistry: MeterRegistry,
) {
    fun recordValidation(
        endpoint: String,
        outcome: CursorValidationOutcome,
    ) {
        meterRegistry
            .counter(
                "beanflow.pagination.cursor.validation.count",
                "endpoint",
                endpoint,
                "outcome",
                outcome.tagValue,
            ).increment()
    }

    fun recordStartupValidation(outcome: CursorStartupOutcome) {
        meterRegistry
            .counter(
                "beanflow.pagination.cursor.startup.validation.count",
                "outcome",
                outcome.tagValue,
            ).increment()
    }
}

internal enum class CursorValidationOutcome(
    val tagValue: String,
) {
    VALID("valid"),
    INVALID("invalid"),
    UNAVAILABLE("unavailable"),
}

internal enum class CursorStartupOutcome(
    val tagValue: String,
) {
    VALID("valid"),
    INVALID("invalid"),
}

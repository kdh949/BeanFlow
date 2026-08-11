package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.shared.api.BlindIndex
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.EncryptedPersonalData
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.KeyedBlindIndexPort
import io.github.kdh949.beanflow.shared.api.NormalizedExactSearchValue
import io.github.kdh949.beanflow.shared.api.PersonalDataCryptoPort
import io.github.kdh949.beanflow.shared.api.PersonalDataEncryptionContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

internal class VaultTransitPersonalDataAdapter(
    private val properties: VaultTransitPersonalDataProperties,
    private val objectMapper: ObjectMapper,
) : PersonalDataCryptoPort,
    KeyedBlindIndexPort {
    private val httpClient: HttpClient by lazy {
        HttpClient
            .newBuilder()
            .connectTimeout(properties.validated().connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    override fun encrypt(
        plaintext: ByteArray,
        context: PersonalDataEncryptionContext,
    ): EncryptedPersonalData {
        if (plaintext.isEmpty() || plaintext.size > MAX_PLAINTEXT_BYTES) unavailable()
        val configuration = configured()
        val response =
            post(
                configuration,
                "encrypt/${configuration.encryptionKey}",
                mapOf(
                    "plaintext" to Base64.getEncoder().encodeToString(plaintext),
                    "associated_data" to Base64.getEncoder().encodeToString(context.associatedData()),
                ),
            )
        return encrypted(response.path("ciphertext").asText(), context.aadVersion)
    }

    override fun decrypt(
        encrypted: EncryptedPersonalData,
        context: PersonalDataEncryptionContext,
    ): ByteArray {
        if (encrypted.aadVersion != context.aadVersion) unavailable()
        val configuration = configured()
        val response =
            post(
                configuration,
                "decrypt/${configuration.encryptionKey}",
                mapOf(
                    "ciphertext" to encrypted.ciphertext,
                    "associated_data" to Base64.getEncoder().encodeToString(context.associatedData()),
                ),
            )
        return decodeBase64(response.path("plaintext").asText()).also {
            if (it.isEmpty() || it.size > MAX_PLAINTEXT_BYTES) unavailable()
        }
    }

    override fun rewrap(
        encrypted: EncryptedPersonalData,
        context: PersonalDataEncryptionContext,
    ): EncryptedPersonalData {
        if (encrypted.aadVersion != context.aadVersion) unavailable()
        val configuration = configured()
        val response =
            post(
                configuration,
                "rewrap/${configuration.encryptionKey}",
                mapOf(
                    "ciphertext" to encrypted.ciphertext,
                    "associated_data" to Base64.getEncoder().encodeToString(context.associatedData()),
                ),
            )
        return encrypted(response.path("ciphertext").asText(), context.aadVersion)
    }

    override fun generate(
        normalizedValue: NormalizedExactSearchValue,
        keyVersions: Set<Int>,
    ): List<BlindIndex> {
        val configuration = configured()
        if (keyVersions.isEmpty() || keyVersions.any { it !in configuration.blindIndexSearchKeyVersions }) unavailable()
        val input = Base64.getEncoder().encodeToString(normalizedValue.canonicalBytes())
        return keyVersions.toSortedSet().map { version ->
            val response =
                post(
                    configuration,
                    "hmac/${configuration.blindIndexKey}/sha2-256",
                    mapOf("input" to input, "key_version" to version),
                )
            parseHmac(response.path("hmac").asText(), version)
        }
    }

    override fun activeSearchKeyVersions(): Set<Int> = configured().blindIndexSearchKeyVersions

    fun validateStartup() {
        val configuration = configured()
        validateKeyMetadata(configuration, configuration.encryptionKey, "aes256-gcm96", emptySet())
        validateKeyMetadata(
            configuration,
            configuration.blindIndexKey,
            "hmac",
            configuration.blindIndexSearchKeyVersions,
        )
    }

    override fun writeKeyVersion(): Int = configured().blindIndexWriteKeyVersion

    private fun validateKeyMetadata(
        configuration: ValidatedVaultTransitConfiguration,
        key: String,
        expectedType: String,
        requiredVersions: Set<Int>,
    ) {
        val data = get(configuration, "keys/$key")
        val latest = data.requiredInt("latest_version")
        val minimumEncryption = data.requiredInt("min_encryption_version")
        val minimumDecryption = data.requiredInt("min_decryption_version")
        val derived = data.requiredBoolean("derived")
        val convergentEncryption = data.optionalBoolean("convergent_encryption") ?: false
        val metadataValid =
            data.requiredText("type") == expectedType &&
                !derived &&
                !data.requiredBoolean("exportable") &&
                !data.requiredBoolean("deletion_allowed") &&
                !convergentEncryption &&
                latest > 0 &&
                minimumEncryption in 0..latest &&
                minimumDecryption in 0..latest &&
                requiredVersions.all { it in maxOf(1, minimumEncryption)..latest }
        if (!metadataValid) unavailable()
    }

    private fun post(
        configuration: ValidatedVaultTransitConfiguration,
        operationPath: String,
        body: Map<String, Any>,
    ): JsonNode = send(configuration, operationPath, objectMapper.writeValueAsString(body), "POST")

    private fun get(
        configuration: ValidatedVaultTransitConfiguration,
        operationPath: String,
    ): JsonNode = send(configuration, operationPath, null, "GET")

    private fun send(
        configuration: ValidatedVaultTransitConfiguration,
        operationPath: String,
        body: String?,
        method: String,
    ): JsonNode {
        val requestBuilder =
            HttpRequest
                .newBuilder(URI("${configuration.baseUri}/v1/${configuration.transitMount}/$operationPath"))
                .timeout(configuration.requestTimeout)
                .header("Accept", "application/json")
        if (method == "POST") {
            requestBuilder
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body!!, StandardCharsets.UTF_8))
        } else {
            requestBuilder.GET()
        }
        val response =
            try {
                httpClient.send(requestBuilder.build(), BoundedVaultTransitBodyHandler(MAX_RESPONSE_BYTES))
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                unavailable(VaultTransitRequestFailure())
            } catch (_: IOException) {
                unavailable(VaultTransitRequestFailure())
            } catch (exception: RuntimeException) {
                if (exception is DomainFailure) throw exception
                unavailable(VaultTransitRequestFailure())
            }
        if (response.statusCode() !in 200..299) unavailable()
        val responseBody = response.body().toString(StandardCharsets.UTF_8)
        return try {
            objectMapper.readTree(responseBody).path("data").takeUnless { it.isMissingNode || it.isNull } ?: unavailable()
        } catch (exception: RuntimeException) {
            if (exception is DomainFailure) throw exception
            unavailable(InvalidVaultTransitResponse())
        }
    }

    private fun encrypted(
        ciphertext: String,
        aadVersion: Int,
    ): EncryptedPersonalData {
        val version =
            CIPHERTEXT_PATTERN
                .matchEntire(ciphertext)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: unavailable()
        return try {
            EncryptedPersonalData(ciphertext, version, aadVersion)
        } catch (exception: IllegalArgumentException) {
            unavailable(exception)
        }
    }

    private fun parseHmac(
        encoded: String,
        requestedVersion: Int,
    ): BlindIndex {
        val match = HMAC_PATTERN.matchEntire(encoded) ?: unavailable()
        val version = match.groupValues[1].toIntOrNull() ?: unavailable()
        if (version != requestedVersion) unavailable()
        return try {
            BlindIndex(version, decodeBase64(match.groupValues[2]))
        } catch (exception: IllegalArgumentException) {
            unavailable(exception)
        }
    }

    private fun decodeBase64(encoded: String): ByteArray =
        try {
            Base64.getDecoder().decode(encoded)
        } catch (exception: IllegalArgumentException) {
            unavailable(exception)
        }

    private fun JsonNode.requiredText(field: String): String =
        path(field).takeUnless { it.isMissingNode || !it.isTextual }?.asText() ?: unavailable()

    private fun JsonNode.requiredInt(field: String): Int =
        path(field).takeUnless { it.isMissingNode || !it.isIntegralNumber }?.asInt() ?: unavailable()

    private fun JsonNode.requiredBoolean(field: String): Boolean =
        path(field).takeUnless { it.isMissingNode || !it.isBoolean }?.asBoolean() ?: unavailable()

    private fun JsonNode.optionalBoolean(field: String): Boolean? {
        val value = path(field)
        if (value.isMissingNode) return null
        if (!value.isBoolean) unavailable()
        return value.asBoolean()
    }

    private fun configured(): ValidatedVaultTransitConfiguration =
        try {
            properties.validated()
        } catch (exception: VaultTransitConfigurationException) {
            unavailable(exception)
        }

    private fun unavailable(cause: Throwable? = null): Nothing =
        throw DomainFailure(
            FailureCode.DEPENDENCY_UNAVAILABLE,
            "Personal-data protection service is unavailable",
        ).also { failure -> cause?.let(failure::initCause) }

    private companion object {
        const val MAX_PLAINTEXT_BYTES = 4096
        const val MAX_RESPONSE_BYTES = 32768
        val CIPHERTEXT_PATTERN = Regex("^vault:v([1-9][0-9]*):[^\\s]{1,16000}$")
        val HMAC_PATTERN = Regex("^vault:v([1-9][0-9]*):([A-Za-z0-9+/=]{40,64})$")
    }
}

private class VaultTransitRequestFailure : RuntimeException("Vault Transit request failed")

private class InvalidVaultTransitResponse : RuntimeException("Vault Transit response was invalid")

private class VaultTransitResponseTooLarge : RuntimeException("Vault Transit response exceeded the byte limit")

private class BoundedVaultTransitBodyHandler(
    private val maxBytes: Int,
) : HttpResponse.BodyHandler<ByteArray> {
    init {
        require(maxBytes > 0)
    }

    override fun apply(responseInfo: HttpResponse.ResponseInfo): HttpResponse.BodySubscriber<ByteArray> =
        BoundedVaultTransitBodySubscriber(
            maxBytes = maxBytes,
            rejectOnSubscribe = responseInfo.headers().firstValueAsLong("Content-Length").orElse(-1L) > maxBytes,
        )
}

private class BoundedVaultTransitBodySubscriber(
    private val maxBytes: Int,
    private val rejectOnSubscribe: Boolean,
) : HttpResponse.BodySubscriber<ByteArray> {
    private val body = ByteArrayOutputStream(minOf(maxBytes, INITIAL_CAPACITY))
    private val completion = CompletableFuture<ByteArray>()
    private var subscription: Flow.Subscription? = null
    private var receivedBytes = 0

    override fun getBody(): CompletionStage<ByteArray> = completion

    override fun onSubscribe(subscription: Flow.Subscription) {
        if (this.subscription != null) {
            subscription.cancel()
            return
        }
        this.subscription = subscription
        if (rejectOnSubscribe) {
            subscription.cancel()
            completion.completeExceptionally(VaultTransitResponseTooLarge())
        } else {
            subscription.request(1)
        }
    }

    override fun onNext(item: List<ByteBuffer>) {
        if (completion.isDone) return
        val incomingBytes = item.sumOf { it.remaining().toLong() }
        if (receivedBytes.toLong() + incomingBytes > maxBytes) {
            subscription?.cancel()
            completion.completeExceptionally(VaultTransitResponseTooLarge())
            return
        }
        item.forEach { source ->
            val buffer = source.asReadOnlyBuffer()
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            body.write(bytes)
            receivedBytes += bytes.size
        }
        subscription?.request(1)
    }

    override fun onError(throwable: Throwable) {
        completion.completeExceptionally(VaultTransitRequestFailure())
    }

    override fun onComplete() {
        completion.complete(body.toByteArray())
    }

    private companion object {
        const val INITIAL_CAPACITY = 8192
    }
}

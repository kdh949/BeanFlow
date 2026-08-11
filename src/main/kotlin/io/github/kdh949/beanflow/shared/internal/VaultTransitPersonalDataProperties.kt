package io.github.kdh949.beanflow.shared.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

@ConfigurationProperties(prefix = "beanflow.security.personal-data.vault")
internal data class VaultTransitPersonalDataProperties(
    val proxyBaseUri: URI? = null,
    val transitMount: String? = null,
    val encryptionKey: String? = null,
    val blindIndexKey: String? = null,
    val blindIndexWriteKeyVersion: Int? = null,
    val blindIndexSearchKeyVersions: Set<Int> = emptySet(),
    val connectTimeout: Duration = Duration.ofSeconds(2),
    val requestTimeout: Duration = Duration.ofSeconds(3),
) {
    fun validated(): ValidatedVaultTransitConfiguration {
        val baseUri = proxyBaseUri ?: invalid()
        val mount = transitMount?.takeIf(::safePath) ?: invalid()
        val encryption = encryptionKey?.takeIf(::safeSegment) ?: invalid()
        val blindIndex = blindIndexKey?.takeIf(::safeSegment) ?: invalid()
        val writeVersion = blindIndexWriteKeyVersion?.takeIf { it > 0 } ?: invalid()
        val searchVersions = blindIndexSearchKeyVersions.filter { it > 0 }.toSortedSet()
        if (
            encryption == blindIndex ||
            searchVersions.size !in 1..8 ||
            searchVersions.size != blindIndexSearchKeyVersions.size ||
            writeVersion !in searchVersions ||
            !baseUri.isLoopbackHttpUri() ||
            connectTimeout.isZero ||
            connectTimeout.isNegative ||
            connectTimeout > Duration.ofSeconds(10) ||
            requestTimeout.isZero ||
            requestTimeout.isNegative ||
            requestTimeout > Duration.ofSeconds(30)
        ) {
            invalid()
        }
        return ValidatedVaultTransitConfiguration(
            baseUri = baseUri.toString().removeSuffix("/"),
            transitMount = mount,
            encryptionKey = encryption,
            blindIndexKey = blindIndex,
            blindIndexWriteKeyVersion = writeVersion,
            blindIndexSearchKeyVersions = searchVersions,
            connectTimeout = connectTimeout,
            requestTimeout = requestTimeout,
        )
    }

    private fun invalid(): Nothing = throw VaultTransitConfigurationException()

    private fun safeSegment(value: String): Boolean = value.matches(Regex("^[A-Za-z0-9_-]{1,128}$"))

    private fun safePath(value: String): Boolean =
        value.length in 1..128 &&
            !value.startsWith('/') &&
            !value.endsWith('/') &&
            value.split('/').all(::safeSegment)

    private fun URI.isLoopbackHttpUri(): Boolean =
        scheme?.lowercase() in setOf("http", "https") &&
            host?.lowercase() in setOf("127.0.0.1", "localhost", "::1", "[::1]") &&
            userInfo == null &&
            query == null &&
            fragment == null &&
            (path.isNullOrEmpty() || path == "/")
}

internal data class ValidatedVaultTransitConfiguration(
    val baseUri: String,
    val transitMount: String,
    val encryptionKey: String,
    val blindIndexKey: String,
    val blindIndexWriteKeyVersion: Int,
    val blindIndexSearchKeyVersions: Set<Int>,
    val connectTimeout: Duration,
    val requestTimeout: Duration,
)

internal class VaultTransitConfigurationException : IllegalArgumentException("Invalid Vault Transit configuration")

package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@ConfigurationProperties(prefix = "beanflow.authentication")
internal data class CustomerAuthenticationProperties(
    val attemptHmacKeyBase64Url: String? = null,
    val trustedProxyCidrs: List<String> = emptyList(),
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CustomerAuthenticationProperties::class)
internal class CustomerCredentialSecurityConfiguration {
    @Bean
    fun customerPasswordSecurity(registry: MeterRegistry): CustomerPasswordSecurity = CustomerPasswordSecurity(registry)

    @Bean
    fun authenticationScopeHmac(properties: CustomerAuthenticationProperties): AuthenticationScopeHmac {
        val encoded =
            properties.attemptHmacKeyBase64Url?.takeIf(String::isNotBlank)
                ?: error("beanflow.authentication.attempt-hmac-key-base64-url is required")
        val key =
            try {
                Base64.getUrlDecoder().decode(encoded)
            } catch (_: IllegalArgumentException) {
                error("Authentication attempt HMAC key must be unpadded base64url")
            }
        check(key.size >= 32) { "Authentication attempt HMAC key must contain at least 32 bytes" }
        return AuthenticationScopeHmac(key)
    }

    @Bean
    fun customerSourceIpResolver(properties: CustomerAuthenticationProperties): CustomerSourceIpResolver =
        CustomerSourceIpResolver(properties.trustedProxyCidrs)

    @Bean
    fun customerPasswordHashStartupValidator(
        jdbc: JdbcTemplate,
        passwords: CustomerPasswordSecurity,
    ): ApplicationRunner =
        ApplicationRunner {
            jdbc
                .queryForList(
                    "SELECT password_hash FROM identity_customer_account " +
                        "UNION ALL SELECT password_hash FROM identity_merchant_account",
                    String::class.java,
                ).forEach { hash -> passwords.validateStoredHash(checkNotNull(hash)) }
        }
}

internal class CustomerPasswordSecurity(
    private val registry: MeterRegistry,
) {
    private val encoder = Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2)
    private val commonPasswords =
        ClassPathResource("security/common-passwords-v1.txt")
            .inputStream
            .bufferedReader(StandardCharsets.UTF_8)
            .useLines { lines ->
                lines.filterNot { it.isBlank() || it.startsWith('#') }.toSet()
            }

    // Fixed, versioned absent-account PHC: every process verifies the same Argon2id contract and
    // does not create a timing-varying credential. It is never accepted as a real account hash.
    val dummyHash: String = DUMMY_HASH

    init {
        check(encoder.matches("beanflow-absent-account-dummy-password", dummyHash)) {
            "Argon2id startup self-test failed"
        }
        check(dummyHash.startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$")) {
            "Argon2id provider returned an unexpected PHC parameter contract"
        }
        validateStoredHash(dummyHash)
    }

    fun validateLoginId(raw: String): String {
        val canonical = raw.trim().map { character -> if (character in 'A'..'Z') character.lowercaseChar() else character }.joinToString("")
        if (!LOGIN_ID.matches(canonical)) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Login ID format is invalid")
        }
        return canonical
    }

    fun validatePasswordSyntax(password: String) {
        val codePoints = password.codePointCount(0, password.length)
        val utf8Bytes = password.toByteArray(StandardCharsets.UTF_8).size
        if (codePoints !in 15..128 || utf8Bytes > 512) {
            throw DomainFailure(
                FailureCode.PASSWORD_POLICY_VIOLATION,
                "Password must contain 15 to 128 Unicode characters and at most 512 UTF-8 bytes",
            )
        }
    }

    fun validateRegistrationPassword(
        loginId: String,
        password: String,
    ) {
        validatePasswordSyntax(password)
        if (password == loginId || password in commonPasswords) {
            throw DomainFailure(FailureCode.PASSWORD_POLICY_VIOLATION, "Password is not allowed by the local password policy")
        }
    }

    fun encode(password: String): String = checkNotNull(encoder.encode(password))

    fun matches(
        password: String,
        hash: String,
    ): Boolean {
        val sample = Timer.start(registry)
        return try {
            encoder.matches(password, hash)
        } catch (_: RuntimeException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Password hash verification failed")
        } finally {
            sample.stop(registry.timer("beanflow.identity.password.verify", "algorithm", "argon2id"))
        }
    }

    fun validateStoredHash(hash: String) {
        check(ARGON2ID_PHC.matches(hash)) { "Stored browser-account password hash violates the accepted Argon2id PHC contract" }
    }

    private companion object {
        val LOGIN_ID = Regex("^[a-z0-9][a-z0-9._-]{3,30}[a-z0-9]$")
        val ARGON2ID_PHC = Regex("^\\\$argon2id\\\$v=19\\\$m=19456,t=2,p=1\\\$[A-Za-z0-9+/]{22}\\\$[A-Za-z0-9+/]{43}$")
        const val DUMMY_HASH =
            "\$argon2id\$v=19\$m=19456,t=2,p=1\$YYYjcqG9IK/3ghl/6bYF6A\$mpj5J4vG2tHyThf93J0u87QMwvdfVxh1VTp/vfwf+GI"
    }
}

internal class AuthenticationScopeHmac(
    key: ByteArray,
) {
    private val key = SecretKeySpec(key.copyOf(), "HmacSHA256")

    fun loginId(
        actorType: LoginAttemptActorType,
        canonicalLoginId: String,
    ): String = digest("${actorType.name}|LOGIN_ID|$canonicalLoginId")

    fun ip(
        actorType: LoginAttemptActorType,
        canonicalIp: String,
    ): String = digest("${actorType.name}|IP|$canonicalIp")

    private fun digest(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return HexFormat.of().formatHex(mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)))
    }
}

internal class CustomerSourceIpResolver(
    trustedProxyCidrs: List<String>,
) {
    private val trustedProxies = trustedProxyCidrs.filter(String::isNotBlank).map(::IpNetwork)

    fun resolve(
        remoteAddress: String,
        forwardedFor: String?,
    ): String {
        val remote = parseLiteral(remoteAddress)
        if (trustedProxies.none { it.contains(remote) }) return remote.hostAddress
        val forwarded = forwardedFor ?: return remote.hostAddress
        return forwarded
            .split(',')
            .map { value -> parseLiteral(value.trim()) }
            .asReversed()
            .firstOrNull { candidate -> trustedProxies.none { proxy -> proxy.contains(candidate) } }
            ?.hostAddress
            ?: remote.hostAddress
    }

    private fun parseLiteral(value: String): InetAddress {
        if (value.isBlank() || value.any { !(it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' || it in 'A'..'F') }) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Source IP is invalid")
        }
        if (':' !in value) {
            val octets = value.split('.')
            if (octets.size != 4 || octets.any { it.isEmpty() || (it.length > 1 && it.startsWith('0')) || it.toIntOrNull() !in 0..255 }) {
                throw DomainFailure(FailureCode.INVALID_REQUEST, "Source IP is invalid")
            }
        }
        return try {
            InetAddress.getByName(value).also { parsed ->
                if (':' in value && parsed.address.size != 16) {
                    throw DomainFailure(FailureCode.INVALID_REQUEST, "Source IP is invalid")
                }
            }
        } catch (_: RuntimeException) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Source IP is invalid")
        }
    }
}

private class IpNetwork(
    cidr: String,
) {
    private val address: ByteArray
    private val prefix: Int

    init {
        val parts = cidr.split('/', limit = 2)
        check(parts.size == 2) { "Trusted proxy entry must be CIDR" }
        address = InetAddress.getByName(parts[0]).address
        prefix = parts[1].toIntOrNull() ?: error("Trusted proxy prefix is invalid")
        check(prefix in 0..address.size * 8) { "Trusted proxy prefix is outside its address family" }
    }

    fun contains(candidate: InetAddress): Boolean {
        val bytes = candidate.address
        if (bytes.size != address.size) return false
        val fullBytes = prefix / 8
        val remainingBits = prefix % 8
        if (!MessageDigest.isEqual(address.copyOfRange(0, fullBytes), bytes.copyOfRange(0, fullBytes))) return false
        if (remainingBits == 0) return true
        val mask = (0xff shl (8 - remainingBits)) and 0xff
        return (address[fullBytes].toInt() and mask) == (bytes[fullBytes].toInt() and mask)
    }
}

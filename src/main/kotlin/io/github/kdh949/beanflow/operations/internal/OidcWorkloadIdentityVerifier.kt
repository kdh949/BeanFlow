package io.github.kdh949.beanflow.operations.internal

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.time.Clock
import java.time.Instant

internal data class OidcWorkloadIdentityConfiguration(
    val tokenFile: Path,
    val jwkSetFile: Path,
    val issuer: String,
    val audience: String,
    val allowedSubjects: Set<String>,
    val deploymentRunClaim: String,
)

internal class WorkloadIdentityVerificationException : RuntimeException()

@Component
internal class OidcWorkloadIdentityVerifier(
    private val clock: Clock = Clock.systemUTC(),
) {
    fun verify(configuration: OidcWorkloadIdentityConfiguration): VerifiedReleasePrincipal =
        try {
            validateConfiguration(configuration)
            val token = readOnlyFile(configuration.tokenFile, MAX_TOKEN_BYTES).trim()
            if (token.isEmpty() || token.any(Char::isWhitespace)) identityFailure()
            val jwkSetJson = readOnlyFile(configuration.jwkSetFile, MAX_JWK_SET_BYTES)
            val jwkSet = JWKSet.parse(jwkSetJson)
            if (jwkSet.keys.isEmpty() || jwkSet.keys.any { it.isPrivate }) identityFailure()
            val jwkSource = JWKSource<SecurityContext> { selector, _ -> selector.select(jwkSet) }
            val decoder =
                NimbusJwtDecoder
                    .withJwkSource(jwkSource)
                    .jwsAlgorithm(SignatureAlgorithm.RS256)
                    .build()
            decoder.setJwtValidator(validators(configuration))
            val jwt = decoder.decode(token)
            val subject = jwt.subject ?: identityFailure()
            val deploymentRun = jwt.getClaimAsString(configuration.deploymentRunClaim) ?: identityFailure()
            if (deploymentRun.isBlank() || deploymentRun.length > 200 || deploymentRun.hasControlCharacter()) {
                identityFailure()
            }
            val reference =
                "issuer=${configuration.issuer}|subject=$subject|audience=${configuration.audience}|" +
                    "deploymentRun=$deploymentRun"
            if (reference.length > 500 || reference.hasControlCharacter()) identityFailure()
            VerifiedReleasePrincipal(reference)
        } catch (_: WorkloadIdentityVerificationException) {
            throw WorkloadIdentityVerificationException()
        } catch (_: Exception) {
            throw WorkloadIdentityVerificationException()
        }

    private fun validators(configuration: OidcWorkloadIdentityConfiguration): OAuth2TokenValidator<Jwt> {
        val timestamp =
            validator { jwt ->
                val now = Instant.now(clock)
                val expiresAt = jwt.expiresAt
                val notBefore = jwt.notBefore
                expiresAt != null && now.isBefore(expiresAt) && notBefore != null && !now.isBefore(notBefore)
            }
        val audience = validator { jwt -> configuration.audience in (jwt.audience ?: emptyList()) }
        val subject = validator { jwt -> jwt.subject in configuration.allowedSubjects }
        val deploymentRun =
            validator { jwt ->
                jwt.getClaimAsString(configuration.deploymentRunClaim)?.let {
                    it.isNotBlank() && it.length <= 200 && !it.hasControlCharacter()
                } == true
            }
        return DelegatingOAuth2TokenValidator(
            timestamp,
            JwtIssuerValidator(configuration.issuer),
            audience,
            subject,
            deploymentRun,
        )
    }

    private fun validator(predicate: (Jwt) -> Boolean): OAuth2TokenValidator<Jwt> =
        OAuth2TokenValidator { jwt ->
            if (predicate(jwt)) {
                OAuth2TokenValidatorResult.success()
            } else {
                OAuth2TokenValidatorResult.failure(
                    OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "Workload identity claim validation failed", null),
                )
            }
        }

    private fun validateConfiguration(configuration: OidcWorkloadIdentityConfiguration) {
        if (configuration.issuer.isBlank() || configuration.issuer.hasControlCharacter()) identityFailure()
        if (configuration.audience.isBlank() || configuration.audience.hasControlCharacter()) identityFailure()
        if (configuration.allowedSubjects.isEmpty() ||
            configuration.allowedSubjects.any { it.isBlank() || it.hasControlCharacter() }
        ) {
            identityFailure()
        }
        if (configuration.deploymentRunClaim.isBlank() || configuration.deploymentRunClaim.hasControlCharacter()) {
            identityFailure()
        }
    }

    private fun readOnlyFile(
        path: Path,
        maxBytes: Long,
    ): String {
        val attributes =
            Files.readAttributes(
                path,
                PosixFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        if (!attributes.isRegularFile || attributes.size() !in 1..maxBytes) identityFailure()
        if (attributes.permissions().any { it in WRITE_PERMISSIONS }) identityFailure()
        return Files.readString(path, StandardCharsets.UTF_8)
    }

    private fun identityFailure(): Nothing = throw WorkloadIdentityVerificationException()

    private companion object {
        const val MAX_TOKEN_BYTES = 64L * 1024L
        const val MAX_JWK_SET_BYTES = 1024L * 1024L
        val WRITE_PERMISSIONS =
            setOf(
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.OTHERS_WRITE,
            )
    }
}

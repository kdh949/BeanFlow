package io.github.kdh949.beanflow.shared.internal

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.JwtValidationException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Date

internal class SecurityConfigurationJwtValidationTest {
    @Test
    fun `operations decoder accepts only the configured issuer and audience`() {
        val key = RSAKeyGenerator(2048).keyID("operations-test").generate()
        val server = jwkServer(key)
        server.start()

        try {
            val decoder =
                SecurityConfiguration().jwtDecoder(
                    "http://127.0.0.1:${server.address.port}/jwks.json",
                    ISSUER,
                    AUDIENCE,
                )

            assertThat(decoder.decode(token(key, ISSUER, AUDIENCE)).issuer.toString()).isEqualTo(ISSUER)
            assertThatThrownBy { decoder.decode(token(key, "https://other.example/realms/beanflow", AUDIENCE)) }
                .isInstanceOf(JwtValidationException::class.java)
            assertThatThrownBy { decoder.decode(token(key, ISSUER, "other-client")) }
                .isInstanceOf(JwtValidationException::class.java)
        } finally {
            server.stop(0)
        }
    }

    private fun jwkServer(key: RSAKey): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/jwks.json") { exchange ->
                val body = JWKSet(key.toPublicJWK()).toString().toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }

    private fun token(
        key: RSAKey,
        issuer: String,
        audience: String,
    ): String {
        val now = Instant.now()
        val claims =
            JWTClaimsSet
                .Builder()
                .subject("00000000-0000-4000-8000-000000000001")
                .issuer(issuer)
                .audience(audience)
                .claim("roles", listOf("PLATFORM_OPERATOR"))
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .build()
        val jwt =
            SignedJWT(
                JWSHeader
                    .Builder(JWSAlgorithm.RS256)
                    .keyID(key.keyID)
                    .type(JOSEObjectType.JWT)
                    .build(),
                claims,
            )
        jwt.sign(RSASSASigner(key))
        return jwt.serialize()
    }

    private companion object {
        const val ISSUER = "https://portfolio.example.test/auth/realms/beanflow"
        const val AUDIENCE = "beanflow-operations"
    }
}

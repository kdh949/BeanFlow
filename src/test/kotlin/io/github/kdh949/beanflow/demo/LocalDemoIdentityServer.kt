package io.github.kdh949.beanflow.demo

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
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.CountDownLatch

/**
 * Ephemeral local identity source for the `local-demo` environment.
 *
 * Generates an RSA keypair at run time, serves only the public JWK set over HTTP, and writes the
 * demo JWTs and the cursor HMAC key into a runtime directory that is not tracked by git. Nothing
 * secret is ever committed: every run produces new key material.
 *
 * This deliberately lives in the test source set so demo tooling can never be packaged into the
 * production artifact, while still reusing the same Nimbus library and the same RS256 contract the
 * application's resource server enforces.
 */
internal object LocalDemoIdentity {
    const val JWKS_PATH = "/jwks.json"
    const val ISSUER = "https://local-demo.beanflow.invalid"
    const val API_AUDIENCE = "beanflow-local-demo"
    const val WORKLOAD_AUDIENCE = "beanflow-local-demo-bootstrap"
    const val WORKLOAD_SUBJECT = "local-demo-release-runner"
    const val DEPLOYMENT_RUN_CLAIM = "deployment_run"

    /** Fixed demo actors. These are synthetic identifiers, not real people. */
    val CUSTOMER_ID: String = "d0000000-0000-4000-8000-000000000001"
    val STORE_OWNER_ID: String = "d0000000-0000-4000-8000-000000000002"
    val OTHER_STORE_OWNER_ID: String = "d0000000-0000-4000-8000-000000000003"
    val PLATFORM_OPERATOR_ID: String = "d0000000-0000-4000-8000-000000000004"
    val SETTLEMENT_OPERATOR_ID: String = "d0000000-0000-4000-8000-000000000005"

    val API_ACTORS: List<Triple<String, String, String>> =
        listOf(
            Triple("PLATFORM_OPERATOR_TOKEN", PLATFORM_OPERATOR_ID, "PLATFORM_OPERATOR"),
            Triple("SETTLEMENT_OPERATOR_TOKEN", SETTLEMENT_OPERATOR_ID, "SETTLEMENT_OPERATOR"),
        )

    fun generateKey(): RSAKey = RSAKeyGenerator(2048).keyID("local-demo").generate()

    fun signApiToken(
        key: RSAKey,
        subject: String,
        role: String,
        lifetime: Duration,
        now: Instant = Instant.now(),
    ): String =
        sign(key) {
            subject(subject)
                .issuer(ISSUER)
                .audience(API_AUDIENCE)
                .claim("roles", listOf(role))
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now.minusSeconds(30)))
                .expirationTime(Date.from(now.plus(lifetime)))
        }

    fun signWorkloadToken(
        key: RSAKey,
        deploymentRun: String,
        lifetime: Duration,
        now: Instant = Instant.now(),
    ): String =
        sign(key) {
            subject(WORKLOAD_SUBJECT)
                .issuer(ISSUER)
                .audience(WORKLOAD_AUDIENCE)
                .claim(DEPLOYMENT_RUN_CLAIM, deploymentRun)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now.minusSeconds(30)))
                .expirationTime(Date.from(now.plus(lifetime)))
        }

    private fun sign(
        key: RSAKey,
        claims: JWTClaimsSet.Builder.() -> JWTClaimsSet.Builder,
    ): String {
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.RS256)
                .keyID(key.keyID)
                .type(JOSEObjectType.JWT)
                .build()
        val jwt = SignedJWT(header, JWTClaimsSet.Builder().claims().build())
        jwt.sign(RSASSASigner(key))
        return jwt.serialize()
    }

    /** Base64URL without padding, matching the cursor key-ring contract in ADR-070. */
    fun generateCursorSecret(): String {
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
    }
}

/**
 * Usage: `LocalDemoIdentityServerKt <port> <runtime-directory>`
 *
 * Writes `jwks.json`, `workload-token.txt` and `demo-identity.env` into the runtime directory and
 * then serves the public JWK set until the process is stopped.
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: <port> <runtime-directory>" }
    val port = args[0].toInt()
    val runtimeDirectory = Path.of(args[1]).toAbsolutePath()
    Files.createDirectories(runtimeDirectory)

    val key = LocalDemoIdentity.generateKey()
    val publicJwkSet = JWKSet(key.toPublicJWK()).toString()
    write(runtimeDirectory.resolve("jwks.json"), publicJwkSet, readOnly = true)

    val deploymentRun = "local-demo-${Instant.now().epochSecond}"
    write(
        runtimeDirectory.resolve("workload-token.txt"),
        LocalDemoIdentity.signWorkloadToken(key, deploymentRun, Duration.ofHours(12)),
        readOnly = true,
    )

    val environment = StringBuilder()
    environment.appendLine("# Generated by scripts/demo/start.sh. Never commit this file.")
    environment.appendLine("BEANFLOW_DEMO_JWKS_URI=http://127.0.0.1:$port${LocalDemoIdentity.JWKS_PATH}")
    environment.appendLine("BEANFLOW_DEMO_ISSUER=${LocalDemoIdentity.ISSUER}")
    environment.appendLine("BEANFLOW_DEMO_WORKLOAD_AUDIENCE=${LocalDemoIdentity.WORKLOAD_AUDIENCE}")
    environment.appendLine("BEANFLOW_DEMO_WORKLOAD_SUBJECT=${LocalDemoIdentity.WORKLOAD_SUBJECT}")
    environment.appendLine("BEANFLOW_DEMO_DEPLOYMENT_RUN_CLAIM=${LocalDemoIdentity.DEPLOYMENT_RUN_CLAIM}")
    environment.appendLine("BEANFLOW_DEMO_CURSOR_SECRET=${LocalDemoIdentity.generateCursorSecret()}")
    LocalDemoIdentity.API_ACTORS.forEach { (name, subject, role) ->
        environment.appendLine("${name}_SUBJECT=$subject")
        environment.appendLine("$name=${LocalDemoIdentity.signApiToken(key, subject, role, Duration.ofHours(12))}")
    }
    write(runtimeDirectory.resolve("demo-identity.env"), environment.toString())

    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    server.createContext(LocalDemoIdentity.JWKS_PATH) { exchange ->
        val body = publicJwkSet.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
    server.start()
    println("local-demo identity server listening on http://127.0.0.1:$port${LocalDemoIdentity.JWKS_PATH}")
    println("runtime directory: $runtimeDirectory")
    Runtime.getRuntime().addShutdownHook(Thread { server.stop(0) })
    CountDownLatch(1).await()
}

private fun write(
    path: Path,
    content: String,
    readOnly: Boolean = false,
) {
    // A previous run may have left the file read-only, so replace rather than overwrite in place.
    Files.deleteIfExists(path)
    Files.writeString(path, content)
    val file = path.toFile()
    // Key material and tokens must not be readable by other local users.
    file.setReadable(false, false)
    file.setReadable(true, true)
    if (readOnly) {
        // OidcWorkloadIdentityVerifier refuses any identity file that is writable by anyone. The
        // demo satisfies that requirement instead of relaxing the check.
        file.setWritable(false, false)
    }
}

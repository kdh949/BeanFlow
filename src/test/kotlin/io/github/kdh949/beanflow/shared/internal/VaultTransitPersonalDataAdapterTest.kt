package io.github.kdh949.beanflow.shared.internal

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.EncryptedPersonalData
import io.github.kdh949.beanflow.shared.api.ExactSearchCriterionType
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.PersonalDataEncryptionContext
import io.github.kdh949.beanflow.shared.api.PersonalDataField
import io.github.kdh949.beanflow.shared.api.PersonalDataNormalizer
import io.github.kdh949.beanflow.shared.api.PersonalDataOwnerContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

internal class VaultTransitPersonalDataAdapterTest {
    private lateinit var server: HttpServer
    private lateinit var adapter: VaultTransitPersonalDataAdapter
    private val responses = ConcurrentLinkedQueue<StubResponse>()
    private val requests = ConcurrentLinkedQueue<CapturedRequest>()

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/", ::handle)
        server.start()
        adapter = VaultTransitPersonalDataAdapter(properties(), ObjectMapper())
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `encrypt and decrypt bind ciphertext to stable AAD without an application token`() {
        responses += StubResponse(200, """{"data":{"ciphertext":"vault:v7:opaque-ciphertext"}}""")
        responses +=
            StubResponse(
                200,
                """{"data":{"plaintext":"${Base64.getEncoder().encodeToString("홍길동".toByteArray())}"}}""",
            )
        val context = encryptionContext()

        val encrypted = adapter.encrypt("홍길동".toByteArray(), context)
        val decrypted = adapter.decrypt(encrypted, context)

        assertThat(encrypted).isEqualTo(EncryptedPersonalData("vault:v7:opaque-ciphertext", 7, 1))
        assertThat(String(decrypted, StandardCharsets.UTF_8)).isEqualTo("홍길동")
        val captured = requests.toList()
        assertThat(captured.map { it.path }).containsExactly(
            "/v1/transit/encrypt/customer-profile",
            "/v1/transit/decrypt/customer-profile",
        )
        assertThat(captured).allSatisfy { request ->
            assertThat(request.tokenHeader).isNull()
            assertThat(request.authorizationHeader).isNull()
        }
        val encryptBody = ObjectMapper().readTree(captured[0].body)
        assertThat(String(Base64.getDecoder().decode(encryptBody.path("plaintext").asText()))).isEqualTo("홍길동")
        assertThat(String(Base64.getDecoder().decode(encryptBody.path("associated_data").asText())))
            .isEqualTo(String(context.associatedData(), StandardCharsets.UTF_8))
        val decryptBody = ObjectMapper().readTree(captured[1].body)
        assertThat(decryptBody.path("ciphertext").asText()).isEqualTo("vault:v7:opaque-ciphertext")
        assertThat(decryptBody.path("associated_data").asText()).isEqualTo(encryptBody.path("associated_data").asText())
    }

    @Test
    fun `HMAC returns an exact 32-byte digest for each explicit key version`() {
        val digest3 = ByteArray(32) { 3 }
        val digest4 = ByteArray(32) { 4 }
        responses += StubResponse(200, hmacResponse(3, digest3))
        responses += StubResponse(200, hmacResponse(4, digest4))
        val normalized = PersonalDataNormalizer.normalize(ExactSearchCriterionType.EMAIL, "Test@Example.com")

        val indexes = adapter.generate(normalized, setOf(3, 4))

        assertThat(adapter.writeKeyVersion()).isEqualTo(4)
        assertThat(adapter.activeSearchKeyVersions()).containsExactly(3, 4)
        assertThat(indexes.map { it.keyVersion }).containsExactly(3, 4)
        assertThat(indexes[0].digestBytes()).containsExactly(*digest3)
        assertThat(indexes[1].digestBytes()).containsExactly(*digest4)
        assertThat(requests.map { it.path }).containsExactly(
            "/v1/transit/hmac/support-exact-index/sha2-256",
            "/v1/transit/hmac/support-exact-index/sha2-256",
        )
        requests.forEachIndexed { index, request ->
            val body = ObjectMapper().readTree(request.body)
            assertThat(body.path("key_version").asInt()).isEqualTo(index + 3)
            assertThat(Base64.getDecoder().decode(body.path("input").asText()))
                .containsExactly(*normalized.canonicalBytes())
        }
    }

    @Test
    fun `rewrap keeps AAD and records the returned latest ciphertext version`() {
        responses += StubResponse(200, """{"data":{"ciphertext":"vault:v8:rewrapped"}}""")
        val previous = EncryptedPersonalData("vault:v7:old", 7, 1)

        val rewrapped = adapter.rewrap(previous, encryptionContext())

        assertThat(rewrapped).isEqualTo(EncryptedPersonalData("vault:v8:rewrapped", 8, 1))
        assertThat(requests.single().path).isEqualTo("/v1/transit/rewrap/customer-profile")
    }

    @Test
    fun `provider errors are generic and never include request or response PII`() {
        responses += StubResponse(503, """{"errors":["private@example.com vault:v9:secret"]}""")

        assertThatThrownBy {
            adapter.generate(
                PersonalDataNormalizer.normalize(ExactSearchCriterionType.EMAIL, "private@example.com"),
                setOf(3),
            )
        }.isInstanceOfSatisfying(DomainFailure::class.java) { failure ->
            assertThat(failure.code).isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
            assertThat(failure.message).isEqualTo("Personal-data protection service is unavailable")
            assertThat(failure.message).doesNotContain("private@example.com", "vault:v9", "support-exact-index")
        }
    }

    private fun properties(): VaultTransitPersonalDataProperties =
        VaultTransitPersonalDataProperties(
            proxyBaseUri = URI("http://127.0.0.1:${server.address.port}"),
            transitMount = "transit",
            encryptionKey = "customer-profile",
            blindIndexKey = "support-exact-index",
            blindIndexWriteKeyVersion = 4,
            blindIndexSearchKeyVersions = setOf(3, 4),
            connectTimeout = Duration.ofSeconds(1),
            requestTimeout = Duration.ofSeconds(1),
        )

    private fun encryptionContext() =
        PersonalDataEncryptionContext(
            ownerContext = PersonalDataOwnerContext.IDENTITY,
            subjectId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
            field = PersonalDataField.PRIMARY_PHONE,
            aadVersion = 1,
        )

    private fun hmacResponse(
        version: Int,
        digest: ByteArray,
    ): String = """{"data":{"hmac":"vault:v$version:${Base64.getEncoder().encodeToString(digest)}"}}"""

    private fun handle(exchange: HttpExchange) {
        val response = responses.poll() ?: StubResponse(500, """{"errors":["missing stub"]}""")
        requests +=
            CapturedRequest(
                path = exchange.requestURI.path,
                body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8),
                tokenHeader = exchange.requestHeaders.getFirst("X-Vault-Token"),
                authorizationHeader = exchange.requestHeaders.getFirst("Authorization"),
            )
        val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(response.status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private data class StubResponse(
        val status: Int,
        val body: String,
    )

    private data class CapturedRequest(
        val path: String,
        val body: String,
        val tokenHeader: String?,
        val authorizationHeader: String?,
    )
}

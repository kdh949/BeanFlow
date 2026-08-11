package io.github.kdh949.beanflow.shared.internal

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

internal class VaultTransitStartupValidationTest {
    private var server: HttpServer? = null

    @AfterEach
    fun tearDown() {
        server?.stop(0)
    }

    @Test
    fun `production fails startup when Vault settings are missing non-loopback or reuse one key`() {
        listOf<List<String>>(
            emptyList(),
            validProperties("http://vault.service:8200"),
            validProperties("http://127.0.0.1:8200", encryptionKey = "same", blindIndexKey = "same"),
            validProperties("http://127.0.0.1:8200") +
                (0..8).map { "beanflow.security.personal-data.vault.blind-index-search-key-versions[$it]=${it + 1}" },
        ).forEach { properties ->
            contextRunner().withPropertyValues(*properties.toTypedArray()).run { context ->
                assertThat(context.startupFailure).isNotNull
                assertThat(context.startupFailure).hasMessageContaining("Vault Transit personal-data configuration is invalid")
            }
        }
    }

    @Test
    fun `production fails startup when configured Proxy is unreachable`() {
        contextRunner().withPropertyValues(*validProperties("http://127.0.0.1:1").toTypedArray()).run { context ->
            assertThat(context.startupFailure).isNotNull
            assertThat(context.startupFailure).hasMessageContaining("Vault Transit personal-data startup validation failed")
        }
    }

    @Test
    fun `production validates distinct encryption and HMAC key metadata through the loopback Proxy`() {
        startServer { exchange ->
            val isEncryption = exchange.requestURI.path.endsWith("/keys/customer-profile")
            respond(exchange, 200, keyMetadata(if (isEncryption) "aes256-gcm96" else "hmac", if (isEncryption) 8 else 4))
        }

        contextRunner().withPropertyValues(*validProperties(baseUri()).toTypedArray()).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(VaultTransitPersonalDataAdapter::class.java)
        }
    }

    @Test
    fun `production rejects derived convergent key metadata`() {
        startServer { exchange ->
            val isEncryption = exchange.requestURI.path.endsWith("/keys/customer-profile")
            respond(
                exchange,
                200,
                keyMetadata(
                    if (isEncryption) "aes256-gcm96" else "hmac",
                    if (isEncryption) 8 else 4,
                    derived = true,
                    convergentEncryption = true,
                ),
            )
        }

        contextRunner().withPropertyValues(*validProperties(baseUri()).toTypedArray()).run { context ->
            assertThat(context.startupFailure).isNotNull
            assertThat(context.startupFailure).hasMessageContaining("Vault Transit personal-data startup validation failed")
        }
    }

    @Test
    fun `malformed successful metadata cannot expose provider data through startup cause chain`() {
        startServer { exchange ->
            respond(
                exchange,
                200,
                """{"data":{"type":private@example.com-vault:v9:secret-010-1234-5678}}""",
            )
        }

        contextRunner().withPropertyValues(*validProperties(baseUri()).toTypedArray()).run { context ->
            assertThat(context.startupFailure).isNotNull
            assertThat(causeChain(context.startupFailure!!))
                .doesNotContain(
                    "private@example.com",
                    "vault:v9",
                    "010-1234-5678",
                    "customer-profile",
                    "JsonParseException",
                    "tools.jackson",
                )
        }
    }

    @Test
    fun `wrong key type and malformed provider response fail without exposing provider body`() {
        startServer { exchange ->
            if (exchange.requestURI.path.endsWith("/keys/customer-profile")) {
                respond(exchange, 200, keyMetadata("hmac", 8))
            } else {
                respond(exchange, 500, """{"errors":["vault:v7:secret private@example.com"]}""")
            }
        }

        contextRunner().withPropertyValues(*validProperties(baseUri()).toTypedArray()).run { context ->
            assertThat(context.startupFailure).isNotNull
            assertThat(context.startupFailure).hasMessageContaining("Vault Transit personal-data startup validation failed")
            assertThat(context.startupFailure!!.message).doesNotContain("vault:v7", "private@example.com")
        }
    }

    private fun contextRunner() =
        ApplicationContextRunner()
            .withUserConfiguration(VaultTransitPersonalDataConfiguration::class.java)
            .withBean(ObjectMapper::class.java, { ObjectMapper() })
            .withPropertyValues("spring.profiles.active=prod")

    private fun validProperties(
        proxyBaseUri: String,
        encryptionKey: String = "customer-profile",
        blindIndexKey: String = "support-exact-index",
    ): List<String> =
        listOf(
            "beanflow.security.personal-data.vault.proxy-base-uri=$proxyBaseUri",
            "beanflow.security.personal-data.vault.transit-mount=transit",
            "beanflow.security.personal-data.vault.encryption-key=$encryptionKey",
            "beanflow.security.personal-data.vault.blind-index-key=$blindIndexKey",
            "beanflow.security.personal-data.vault.blind-index-write-key-version=4",
            "beanflow.security.personal-data.vault.blind-index-search-key-versions[0]=3",
            "beanflow.security.personal-data.vault.blind-index-search-key-versions[1]=4",
            "beanflow.security.personal-data.vault.connect-timeout=PT1S",
            "beanflow.security.personal-data.vault.request-timeout=PT1S",
        )

    private fun keyMetadata(
        type: String,
        latestVersion: Int,
        derived: Boolean = false,
        convergentEncryption: Boolean? = null,
    ): String =
        """
        {"data":{
          "type":"$type","derived":$derived,"exportable":false,"deletion_allowed":false,
          ${convergentEncryption?.let { "\"convergent_encryption\":$it," }.orEmpty()}"latest_version":$latestVersion,
          "min_decryption_version":1,"min_encryption_version":0
        }}
        """.trimIndent()

    private fun startServer(handler: (HttpExchange) -> Unit) {
        server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { httpServer ->
                httpServer.createContext("/", handler)
                httpServer.start()
            }
    }

    private fun baseUri(): String = "http://127.0.0.1:${server!!.address.port}"

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun causeChain(failure: Throwable): String =
        generateSequence(failure) { it.cause }
            .joinToString(" | ") { "${it.javaClass.name}:${it.message}" }
}

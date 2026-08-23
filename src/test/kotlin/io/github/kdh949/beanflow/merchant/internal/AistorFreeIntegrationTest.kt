package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.StorefrontImageTarget
import io.github.kdh949.beanflow.merchant.api.StorefrontImageUpload
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.util.UUID
import javax.imageio.ImageIO

/** Runs only when a licensed AIStor Free instance and its private test bucket are provided. */
internal class AistorFreeIntegrationTest {
    @Test
    fun `private AIStor stores signs reads and removes original and thumbnail`() {
        val environment = requiredEnvironment()
        assumeTrue(environment.values.all(String::isNotBlank), "AIStor Free integration environment is unavailable")
        val properties =
            AistorMediaProperties(
                endpoint = environment.getValue("BEANFLOW_AISTOR_ENDPOINT"),
                publicEndpoint = environment.getValue("BEANFLOW_AISTOR_PUBLIC_ENDPOINT"),
                accessKey = environment.getValue("BEANFLOW_AISTOR_ACCESS_KEY"),
                secretKey = environment.getValue("BEANFLOW_AISTOR_SECRET_KEY"),
                bucket = environment.getValue("BEANFLOW_AISTOR_BUCKET"),
                region = environment["BEANFLOW_AISTOR_REGION"].orEmpty().ifBlank { "us-east-1" },
            )
        val client = AistorMediaConfiguration().aistorObjectClient(properties)
        assertThat(client.verifyBucket()).isEqualTo(AistorBucketVerification.AVAILABLE)
        val storage =
            AistorStorefrontImageStorage(
                StorefrontImageNormalizer(),
                client,
                AistorMediaMetrics(SimpleMeterRegistry()),
                Clock.systemUTC(),
            )
        val normalized = storage.normalize(StorefrontImageUpload(jpeg(), "image/jpeg"))
        val prepared = storage.store(StorefrontImageTarget.MENU, UUID.randomUUID(), normalized)

        try {
            assertThat(client.stat(prepared.originalKey).sha256).isEqualTo(prepared.sha256)
            assertThat(client.stat(prepared.thumbnailKey).sha256).isEqualTo(prepared.sha256)
            val access = storage.access(prepared.thumbnailKey)
            assertThat(access.url).contains("X-Amz-Expires=900")
            assertThat(get(access.url).statusCode()).isEqualTo(200)

            val unsigned =
                properties.publicEndpoint.trimEnd('/') + "/" + properties.bucket + "/" + prepared.thumbnailKey
            assertThat(get(unsigned).statusCode() !in 200..299).isTrue()
        } finally {
            storage.delete(prepared.originalKey, prepared.thumbnailKey)
        }

        assertThatThrownBy { client.stat(prepared.originalKey) }.isInstanceOf(Exception::class.java)
        assertThatThrownBy { client.stat(prepared.thumbnailKey) }.isInstanceOf(Exception::class.java)
    }

    private fun requiredEnvironment(): Map<String, String> = REQUIRED_ENVIRONMENT.associateWith { name -> System.getenv(name).orEmpty() }

    private fun get(url: String): HttpResponse<ByteArray> =
        HttpClient
            .newHttpClient()
            .send(
                HttpRequest.newBuilder(URI(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )

    private fun jpeg(): ByteArray =
        ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB), "jpg", output)
            output.toByteArray()
        }

    private companion object {
        val REQUIRED_ENVIRONMENT =
            listOf(
                "BEANFLOW_AISTOR_ENDPOINT",
                "BEANFLOW_AISTOR_PUBLIC_ENDPOINT",
                "BEANFLOW_AISTOR_ACCESS_KEY",
                "BEANFLOW_AISTOR_SECRET_KEY",
                "BEANFLOW_AISTOR_BUCKET",
            )
    }
}

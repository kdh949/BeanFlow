package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.StorefrontImageTarget
import io.github.kdh949.beanflow.merchant.api.StorefrontImageUpload
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.imageio.ImageIO

internal class AistorStorefrontImageStorageTest {
    private val now = Instant.parse("2026-08-24T00:00:00Z")

    @Test
    fun `immutable keys hold normalized original and thumbnail`() {
        val client = FakeAistorObjectClient()
        val storage = storage(client)
        val targetId = UUID.randomUUID()

        val prepared = storage.store(StorefrontImageTarget.STORE, targetId, storage.normalize(StorefrontImageUpload(jpeg(), "image/jpeg")))

        assertThat(prepared.originalKey).isEqualTo("stores/$targetId/${prepared.sha256}/original.jpg")
        assertThat(prepared.thumbnailKey).isEqualTo("stores/$targetId/${prepared.sha256}/thumbnail.jpg")
        assertThat(client.objects.keys).containsExactlyInAnyOrder(prepared.originalKey, prepared.thumbnailKey)
    }

    @Test
    fun `ambiguous PUT is accepted only when one HEAD confirms size and hash`() {
        val client = FakeAistorObjectClient(throwAfterPut = true)

        val storage = storage(client)
        val prepared =
            storage.store(
                StorefrontImageTarget.STORE,
                UUID.randomUUID(),
                storage.normalize(StorefrontImageUpload(jpeg(), "image/jpeg")),
            )

        assertThat(client.statCalls).containsExactly(prepared.originalKey, prepared.thumbnailKey)
    }

    @Test
    fun `unresolved PUT leaves the database caller with an explicit dependency failure`() {
        val client = FakeAistorObjectClient(failBeforePut = true)

        assertThatThrownBy {
            val storage = storage(client)
            storage.store(StorefrontImageTarget.STORE, UUID.randomUUID(), storage.normalize(StorefrontImageUpload(jpeg(), "image/jpeg")))
        }.isInstanceOf(DomainFailure::class.java)
            .extracting("code")
            .isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
        assertThat(client.statCalls).hasSize(1)
    }

    @Test
    fun `signed access expires after fifteen minutes without reading the object`() {
        val client = FakeAistorObjectClient()

        val access = storage(client).access("stores/id/hash/thumbnail.jpg")

        assertThat(access.url).isEqualTo("https://media.beanflow.test/stores/id/hash/thumbnail.jpg?signed=true")
        assertThat(access.expiresAt).isEqualTo(now.plusSeconds(900))
        assertThat(client.statCalls).isEmpty()
    }

    private fun storage(client: AistorObjectClient) =
        AistorStorefrontImageStorage(
            StorefrontImageNormalizer(),
            client,
            AistorMediaMetrics(SimpleMeterRegistry()),
            Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun jpeg(): ByteArray =
        ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(320, 280, BufferedImage.TYPE_INT_RGB), "jpg", output)
            output.toByteArray()
        }

    private class FakeAistorObjectClient(
        private val throwAfterPut: Boolean = false,
        private val failBeforePut: Boolean = false,
    ) : AistorObjectClient {
        data class Stored(
            val bytes: ByteArray,
            val sha256: String,
        )

        val objects = linkedMapOf<String, Stored>()
        val statCalls = mutableListOf<String>()

        override fun put(
            key: String,
            bytes: ByteArray,
            contentType: String,
            sha256: String,
        ) {
            if (failBeforePut) throw IllegalStateException("connection closed before response")
            objects[key] = Stored(bytes, sha256)
            if (throwAfterPut) throw IllegalStateException("connection closed after write")
        }

        override fun stat(key: String): AistorObjectStatus {
            statCalls += key
            val stored = objects[key] ?: throw IllegalStateException("not found")
            return AistorObjectStatus(stored.bytes.size.toLong(), stored.sha256)
        }

        override fun presignGet(
            key: String,
            expirySeconds: Int,
        ): String {
            check(expirySeconds == 900)
            return "https://media.beanflow.test/$key?signed=true"
        }

        override fun delete(key: String) {
            objects.remove(key)
        }

        override fun bucketExists(): Boolean = true
    }
}

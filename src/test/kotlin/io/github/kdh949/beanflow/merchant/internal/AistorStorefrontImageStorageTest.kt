package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.StorefrontImageTarget
import io.github.kdh949.beanflow.merchant.api.StorefrontImageUpload
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.ExternalDependencyOperation
import io.github.kdh949.beanflow.shared.api.ExternalDependencyOutcome
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.RecordingExternalDependencyTelemetry
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
    private val telemetry = RecordingExternalDependencyTelemetry()
    private val registry = SimpleMeterRegistry()

    @Test
    fun `immutable keys hold normalized original and thumbnail`() {
        val client = FakeAistorObjectClient()
        val storage = storage(client)
        val targetId = UUID.randomUUID()

        val prepared = storage.store(StorefrontImageTarget.STORE, targetId, storage.normalize(StorefrontImageUpload(jpeg(), "image/jpeg")))

        assertThat(prepared.originalKey)
            .matches("stores/$targetId/${prepared.sha256}/[0-9a-f-]{36}/original\\.jpg")
        assertThat(prepared.thumbnailKey)
            .matches("stores/$targetId/${prepared.sha256}/[0-9a-f-]{36}/thumbnail\\.jpg")
        assertThat(client.objects.keys).containsExactlyInAnyOrder(prepared.originalKey, prepared.thumbnailKey)
        assertThat(telemetry.records.map { it.call.operation })
            .containsExactly(ExternalDependencyOperation.PUT, ExternalDependencyOperation.PUT)
    }

    @Test
    fun `a later upload of the same bytes receives a new immutable generation`() {
        val client = FakeAistorObjectClient()
        val storage = storage(client)
        val targetId = UUID.randomUUID()
        val normalized = storage.normalize(StorefrontImageUpload(jpeg(), "image/jpeg"))

        val first = storage.store(StorefrontImageTarget.STORE, targetId, normalized)
        val second = storage.store(StorefrontImageTarget.STORE, targetId, normalized)

        assertThat(first.sha256).isEqualTo(second.sha256)
        assertThat(first.originalKey).isNotEqualTo(second.originalKey)
        assertThat(first.thumbnailKey).isNotEqualTo(second.thumbnailKey)
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
        assertThat(telemetry.records.map { it.call.operation })
            .containsExactly(
                ExternalDependencyOperation.PUT,
                ExternalDependencyOperation.HEAD,
                ExternalDependencyOperation.PUT,
                ExternalDependencyOperation.HEAD,
            )
        assertThat(mediaCount("store_image", "put-confirmed-by-head", "success")).isEqualTo(2.0)
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
        assertThat(telemetry.records.map { it.outcome })
            .containsExactly(ExternalDependencyOutcome.FAILURE, ExternalDependencyOutcome.FAILURE)
        assertThat(mediaCount("store_image", "put", "failure")).isEqualTo(1.0)
    }

    @Test
    fun `campaign banner upload access and deletion preserve provider telemetry and target metrics`() {
        val client = FakeAistorObjectClient()
        val storage = storage(client)
        val prepared =
            storage.store(
                StorefrontImageTarget.CAMPAIGN,
                UUID.randomUUID(),
                storage.normalizeCampaignBanner(StorefrontImageUpload(jpeg(), "image/jpeg")),
            )

        val access = storage.access(prepared.thumbnailKey)
        storage.delete(prepared.originalKey, prepared.thumbnailKey)

        assertThat(prepared.originalKey).startsWith("campaigns/")
        assertThat(access.expiresAt).isEqualTo(now.plusSeconds(900))
        assertThat(client.objects).isEmpty()
        assertThat(telemetry.records.map { it.call.operation })
            .containsExactly(
                ExternalDependencyOperation.PUT,
                ExternalDependencyOperation.PUT,
                ExternalDependencyOperation.PRESIGN,
                ExternalDependencyOperation.DELETE,
            )
        assertThat(mediaCount("campaign_banner", "put", "success")).isEqualTo(2.0)
        assertThat(mediaCount("campaign_banner", "presign", "success")).isEqualTo(1.0)
        assertThat(mediaCount("campaign_banner", "delete", "success")).isEqualTo(1.0)
    }

    @Test
    fun `signed access expires after fifteen minutes without reading the object`() {
        val client = FakeAistorObjectClient()

        val access = storage(client).access("stores/id/hash/thumbnail.jpg")

        assertThat(access.url).isEqualTo("https://media.beanflow.test/stores/id/hash/thumbnail.jpg?signed=true")
        assertThat(access.expiresAt).isEqualTo(now.plusSeconds(900))
        assertThat(client.statCalls).isEmpty()
    }

    @Test
    fun `orphan listing is prefix scoped grace filtered and batch bounded`() {
        val client = FakeAistorObjectClient()
        client.listed += AistorObjectSummary("stores/one/original.jpg", now.minusSeconds(200))
        client.listed += AistorObjectSummary("menus/two/thumbnail.jpg", now.minusSeconds(150))
        client.listed += AistorObjectSummary("other/ignored", now.minusSeconds(300))
        client.listed += AistorObjectSummary("menus/new/thumbnail.jpg", now.minusSeconds(10))

        assertThat(
            storage(client).listOrphanCandidates(
                setOf(StorefrontImageTarget.STORE, StorefrontImageTarget.MENU),
                now.minusSeconds(100),
                1,
            ),
        ).containsExactly("stores/one/original.jpg")
    }

    @Test
    fun `orphan page resumes after the last scanned object and bounds raw object scanning`() {
        val client = FakeAistorObjectClient()
        client.listed += AistorObjectSummary("campaigns/001/referenced.jpg", now.minusSeconds(200))
        client.listed += AistorObjectSummary("campaigns/002/new.jpg", now.minusSeconds(10))
        client.listed += AistorObjectSummary("campaigns/003/orphan.jpg", now.minusSeconds(200))

        val first =
            storage(client).listOrphanCandidatePage(
                StorefrontImageTarget.CAMPAIGN,
                now.minusSeconds(100),
                null,
                2,
            )
        val second =
            storage(client).listOrphanCandidatePage(
                StorefrontImageTarget.CAMPAIGN,
                now.minusSeconds(100),
                first.nextStartAfter,
                2,
            )

        assertThat(first.candidateKeys).containsExactly("campaigns/001/referenced.jpg")
        assertThat(first.nextStartAfter).isEqualTo("campaigns/002/new.jpg")
        assertThat(second.candidateKeys).containsExactly("campaigns/003/orphan.jpg")
        assertThat(second.nextStartAfter).isNull()
        assertThat(client.listStartAfter).containsExactly(null, "campaigns/002/new.jpg")
        assertThat(telemetry.records.map { it.call.operation })
            .containsExactly(ExternalDependencyOperation.LIST, ExternalDependencyOperation.LIST)
        assertThat(mediaCount("campaign_banner", "list-orphan-page", "success")).isEqualTo(2.0)
    }

    @Test
    fun `failed campaign orphan page records failure and does not return an empty success`() {
        val client = FakeAistorObjectClient(failList = true)

        assertThatThrownBy {
            storage(client).listOrphanCandidatePage(StorefrontImageTarget.CAMPAIGN, now, null, 2)
        }.isInstanceOf(DomainFailure::class.java)
            .extracting("code")
            .isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
        assertThat(telemetry.records.map { it.call.operation }).containsExactly(ExternalDependencyOperation.LIST)
        assertThat(telemetry.records.map { it.outcome }).containsExactly(ExternalDependencyOutcome.FAILURE)
        assertThat(mediaCount("campaign_banner", "list-orphan-page", "failure")).isEqualTo(1.0)
        assertThat(registry.get("beanflow.media.availability").gauge().value()).isZero()
    }

    private fun mediaCount(
        target: String,
        operation: String,
        outcome: String,
    ): Double =
        registry
            .get("beanflow.media.operation")
            .tags("target", target, "operation", operation, "outcome", outcome)
            .counter()
            .count()

    private fun storage(client: AistorObjectClient) =
        AistorStorefrontImageStorage(
            StorefrontImageNormalizer(),
            client,
            AistorMediaMetrics(registry),
            Clock.fixed(now, ZoneOffset.UTC),
            telemetry,
        )

    private fun jpeg(): ByteArray =
        ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(320, 280, BufferedImage.TYPE_INT_RGB), "jpg", output)
            output.toByteArray()
        }

    private class FakeAistorObjectClient(
        private val throwAfterPut: Boolean = false,
        private val failBeforePut: Boolean = false,
        private val failList: Boolean = false,
    ) : AistorObjectClient {
        data class Stored(
            val bytes: ByteArray,
            val sha256: String,
        )

        val objects = linkedMapOf<String, Stored>()
        val statCalls = mutableListOf<String>()
        val listed = mutableListOf<AistorObjectSummary>()
        val listStartAfter = mutableListOf<String?>()

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

        override fun list(prefix: String): Sequence<AistorObjectSummary> = listed.asSequence().filter { it.key.startsWith(prefix) }

        override fun list(
            prefix: String,
            startAfter: String?,
            limit: Int,
        ): List<AistorObjectSummary> {
            if (failList) throw IllegalStateException("object listing is unavailable")
            listStartAfter += startAfter
            return listed
                .asSequence()
                .filter { it.key.startsWith(prefix) }
                .filter { startAfter == null || it.key > startAfter }
                .take(limit)
                .toList()
        }

        override fun verifyBucket(): AistorBucketVerification = AistorBucketVerification.AVAILABLE
    }
}

package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.NormalizedStorefrontImageUpload
import io.github.kdh949.beanflow.merchant.api.PreparedStorefrontImage
import io.github.kdh949.beanflow.merchant.api.StorefrontImageAccess
import io.github.kdh949.beanflow.merchant.api.StorefrontImageStorageOperations
import io.github.kdh949.beanflow.merchant.api.StorefrontImageTarget
import io.github.kdh949.beanflow.merchant.api.StorefrontImageUpload
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@Component
internal class AistorStorefrontImageStorage(
    private val normalizer: StorefrontImageNormalizer,
    private val client: AistorObjectClient,
    private val metrics: AistorMediaMetrics,
    private val clock: Clock,
) : StorefrontImageStorageOperations {
    override fun normalize(upload: StorefrontImageUpload): NormalizedStorefrontImageUpload =
        normalizer.normalize(upload.bytes, upload.contentType).let {
            NormalizedStorefrontImageUpload(it.original, it.thumbnail, it.contentType, it.extension, it.sha256)
        }

    override fun normalizeCampaignBanner(upload: StorefrontImageUpload): NormalizedStorefrontImageUpload =
        normalizer.normalizeCampaignBanner(upload.bytes, upload.contentType).let {
            NormalizedStorefrontImageUpload(it.original, it.thumbnail, it.contentType, it.extension, it.sha256)
        }

    override fun store(
        target: StorefrontImageTarget,
        targetId: UUID,
        normalized: NormalizedStorefrontImageUpload,
    ): PreparedStorefrontImage {
        val base = "${target.objectPrefix}/$targetId/${normalized.sha256}/${UUID.randomUUID()}"
        val originalKey = "$base/original.${normalized.extension}"
        val thumbnailKey = "$base/thumbnail.${normalized.extension}"
        putVerified(target, originalKey, normalized.original, normalized.contentType, normalized.sha256)
        putVerified(target, thumbnailKey, normalized.thumbnail, normalized.contentType, normalized.sha256)
        return PreparedStorefrontImage(originalKey, thumbnailKey, normalized.sha256)
    }

    override fun access(thumbnailKey: String): StorefrontImageAccess =
        external("presign", metricTarget(thumbnailKey), providerObserved = false) {
            val issuedAt = clock.instant()
            StorefrontImageAccess(client.presignGet(thumbnailKey, URL_TTL.seconds.toInt()), issuedAt.plus(URL_TTL))
        }

    override fun delete(
        originalKey: String,
        thumbnailKey: String,
    ) {
        external("delete", metricTarget(originalKey)) {
            client.delete(originalKey)
            client.delete(thumbnailKey)
        }
    }

    override fun listOrphanCandidates(
        targets: Set<StorefrontImageTarget>,
        olderThan: java.time.Instant,
        limit: Int,
    ): List<String> {
        require(targets.isNotEmpty())
        require(limit > 0)
        return external("list-orphans", metricTarget(targets)) {
            targets
                .asSequence()
                .sortedBy(StorefrontImageTarget::ordinal)
                .flatMap { target -> client.list("${target.objectPrefix}/") }
                .filter { stored -> stored.lastModifiedAt.isBefore(olderThan) }
                .take(limit)
                .map(AistorObjectSummary::key)
                .toList()
        }
    }

    override fun deleteObject(key: String) {
        external("delete-orphan", metricTarget(key)) { client.delete(key) }
    }

    private fun putVerified(
        target: StorefrontImageTarget,
        key: String,
        bytes: ByteArray,
        contentType: String,
        sha256: String,
    ) {
        try {
            client.put(key, bytes, contentType, sha256)
            metrics.success("put", metricTarget(target))
        } catch (putFailure: Exception) {
            val confirmed =
                try {
                    val status = client.stat(key)
                    status.size == bytes.size.toLong() && status.sha256 == sha256
                } catch (_: Exception) {
                    false
                }
            if (!confirmed) unavailable("put", metricTarget(target), "AIStor image upload outcome is unresolved", putFailure)
            metrics.success("put-confirmed-by-head", metricTarget(target))
        }
    }

    private fun <T> external(
        operation: String,
        target: String,
        providerObserved: Boolean = true,
        block: () -> T,
    ): T =
        try {
            block().also { metrics.success(operation, target, providerObserved) }
        } catch (failure: DomainFailure) {
            throw failure
        } catch (failure: Exception) {
            unavailable(operation, target, "AIStor image operation is unavailable", failure, providerObserved)
        }

    private fun unavailable(
        operation: String,
        target: String,
        message: String,
        cause: Throwable,
        providerObserved: Boolean = true,
    ): Nothing {
        metrics.failure(operation, target, providerObserved)
        throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message).also { it.initCause(cause) }
    }

    private fun metricTarget(target: StorefrontImageTarget): String =
        when (target) {
            StorefrontImageTarget.STORE -> "store_image"
            StorefrontImageTarget.MENU -> "menu_image"
            StorefrontImageTarget.CAMPAIGN -> "campaign_banner"
        }

    private fun metricTarget(targets: Set<StorefrontImageTarget>): String =
        if (targets.size == 1) metricTarget(targets.single()) else "mixed"

    private fun metricTarget(key: String): String =
        StorefrontImageTarget.entries.firstOrNull { key.startsWith("${it.objectPrefix}/") }?.let(::metricTarget) ?: "unknown"

    private companion object {
        val URL_TTL: Duration = Duration.ofMinutes(15)
    }
}

@Component
internal class AistorMediaMetrics(
    meterRegistry: MeterRegistry,
) {
    private val availability = AtomicInteger(1)
    private val registry = meterRegistry

    init {
        registry.gauge("beanflow.media.availability", availability)
    }

    fun success(
        operation: String,
        target: String,
        providerObserved: Boolean = true,
    ) {
        if (providerObserved) availability.set(1)
        registry.counter("beanflow.media.operation", "target", target, "operation", operation, "outcome", "success").increment()
    }

    fun failure(
        operation: String,
        target: String,
        providerObserved: Boolean = true,
    ) {
        if (providerObserved) availability.set(0)
        registry.counter("beanflow.media.operation", "target", target, "operation", operation, "outcome", "failure").increment()
    }

    fun startup(outcome: String) {
        registry.counter("beanflow.media.startup.validation", "outcome", outcome).increment()
    }
}

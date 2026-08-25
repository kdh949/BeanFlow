package io.github.kdh949.beanflow.merchant.internal

import io.minio.BucketExistsArgs
import io.minio.GetPresignedObjectUrlArgs
import io.minio.Http
import io.minio.ListObjectsArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import io.minio.StatObjectArgs
import io.minio.errors.ErrorResponseException
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.TimeUnit

@ConfigurationProperties(prefix = "beanflow.media.aistor")
internal data class AistorMediaProperties(
    val endpoint: String,
    val publicEndpoint: String,
    val accessKey: String,
    val secretKey: String,
    val bucket: String,
    val region: String = "us-east-1",
    val verifyAtStartup: Boolean = true,
) {
    init {
        validateEndpoint(endpoint, "endpoint")
        validateEndpoint(publicEndpoint, "publicEndpoint")
        require(accessKey.isNotBlank()) { "AIStor accessKey must not be blank" }
        require(secretKey.isNotBlank()) { "AIStor secretKey must not be blank" }
        require(bucket.matches(Regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$"))) { "AIStor bucket is invalid" }
        require(region.matches(Regex("^[A-Za-z0-9][A-Za-z0-9-]{0,62}$"))) { "AIStor region is invalid" }
    }

    private fun validateEndpoint(
        raw: String,
        name: String,
    ) {
        val uri = runCatching { URI(raw) }.getOrElse { throw IllegalArgumentException("AIStor $name is invalid", it) }
        require(
            uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null,
        ) {
            "AIStor $name must be an absolute HTTP(S) origin without user information, query or fragment"
        }
    }
}

internal data class AistorObjectStatus(
    val size: Long,
    val sha256: String?,
)

internal data class AistorObjectSummary(
    val key: String,
    val lastModifiedAt: java.time.Instant,
)

internal enum class AistorBucketVerification {
    AVAILABLE,
    MISCONFIGURED,
    UNAVAILABLE,
}

/** Narrow wrapper kept fakeable for ambiguous-PUT and cleanup tests. */
internal interface AistorObjectClient {
    fun put(
        key: String,
        bytes: ByteArray,
        contentType: String,
        sha256: String,
    )

    fun stat(key: String): AistorObjectStatus

    fun presignGet(
        key: String,
        expirySeconds: Int,
    ): String

    fun delete(key: String)

    fun list(prefix: String): Sequence<AistorObjectSummary>

    fun verifyBucket(): AistorBucketVerification
}

internal class MinioAistorObjectClient(
    private val operational: MinioClient,
    private val publicSigner: MinioClient,
    private val bucket: String,
) : AistorObjectClient {
    override fun put(
        key: String,
        bytes: ByteArray,
        contentType: String,
        sha256: String,
    ) {
        operational.putObject(
            PutObjectArgs
                .builder()
                .bucket(bucket)
                .`object`(key)
                .stream(ByteArrayInputStream(bytes), bytes.size.toLong(), -1)
                .contentType(contentType)
                .userMetadata(mapOf(SHA256_METADATA to sha256))
                .build(),
        )
    }

    override fun stat(key: String): AistorObjectStatus {
        val response =
            operational.statObject(
                StatObjectArgs
                    .builder()
                    .bucket(bucket)
                    .`object`(key)
                    .build(),
            )
        return AistorObjectStatus(response.size(), response.userMetadata().getFirst(SHA256_METADATA))
    }

    override fun presignGet(
        key: String,
        expirySeconds: Int,
    ): String =
        publicSigner.getPresignedObjectUrl(
            GetPresignedObjectUrlArgs
                .builder()
                .bucket(bucket)
                .`object`(key)
                .method(Http.Method.GET)
                .expiry(expirySeconds, TimeUnit.SECONDS)
                .build(),
        )

    override fun delete(key: String) {
        operational.removeObject(
            RemoveObjectArgs
                .builder()
                .bucket(bucket)
                .`object`(key)
                .build(),
        )
    }

    override fun list(prefix: String): Sequence<AistorObjectSummary> =
        operational
            .listObjects(
                ListObjectsArgs
                    .builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .recursive(true)
                    .build(),
            ).asSequence()
            .map { result ->
                val item = result.get()
                AistorObjectSummary(item.objectName(), item.lastModified().toInstant())
            }

    override fun verifyBucket(): AistorBucketVerification =
        try {
            if (operational.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                AistorBucketVerification.AVAILABLE
            } else {
                AistorBucketVerification.MISCONFIGURED
            }
        } catch (failure: ErrorResponseException) {
            if (failure.response().code in 400..499) {
                AistorBucketVerification.MISCONFIGURED
            } else {
                AistorBucketVerification.UNAVAILABLE
            }
        } catch (_: Exception) {
            AistorBucketVerification.UNAVAILABLE
        }

    private companion object {
        const val SHA256_METADATA = "sha256"
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AistorMediaProperties::class)
internal class AistorMediaConfiguration {
    @Bean
    fun aistorObjectClient(properties: AistorMediaProperties): AistorObjectClient {
        fun client(endpoint: String): MinioClient =
            MinioClient
                .builder()
                .endpoint(endpoint)
                .credentials(properties.accessKey, properties.secretKey)
                .region(properties.region)
                .build()
                .also { it.setTimeout(CONNECT_TIMEOUT_MS, WRITE_TIMEOUT_MS, READ_TIMEOUT_MS) }
        return MinioAistorObjectClient(client(properties.endpoint), client(properties.publicEndpoint), properties.bucket)
    }

    @Bean
    fun aistorStartupVerifier(
        client: AistorObjectClient,
        properties: AistorMediaProperties,
        metrics: AistorMediaMetrics,
    ): ApplicationRunner =
        ApplicationRunner {
            if (properties.verifyAtStartup) {
                when (client.verifyBucket()) {
                    AistorBucketVerification.AVAILABLE -> {
                        metrics.startup("success")
                    }

                    AistorBucketVerification.MISCONFIGURED -> {
                        metrics.startup("failure")
                        throw IllegalStateException("Configured AIStor credential or bucket is invalid")
                    }

                    AistorBucketVerification.UNAVAILABLE -> {
                        metrics.startup("unavailable")
                        logger.warn("AIStor startup verification is unavailable; media operations remain isolated")
                    }
                }
            }
        }

    private companion object {
        val logger = LoggerFactory.getLogger(AistorMediaConfiguration::class.java)
        const val CONNECT_TIMEOUT_MS = 2_000L
        const val WRITE_TIMEOUT_MS = 10_000L
        const val READ_TIMEOUT_MS = 5_000L
    }
}

package io.github.kdh949.beanflow.merchant.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments

internal class AistorMediaConfigurationTest {
    @Test
    fun `invalid endpoint credential or bucket configuration fails immediately`() {
        assertThatThrownBy { properties(endpoint = "file:///tmp/media") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { properties(accessKey = "") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { properties(bucket = "INVALID_BUCKET") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `startup verification rejects a missing private bucket`() {
        val client =
            object : AistorObjectClient {
                override fun put(
                    key: String,
                    bytes: ByteArray,
                    contentType: String,
                    sha256: String,
                ) = Unit

                override fun stat(key: String) = AistorObjectStatus(0, null)

                override fun presignGet(
                    key: String,
                    expirySeconds: Int,
                ) = "https://media.test/signed"

                override fun delete(key: String) = Unit

                override fun list(prefix: String) = emptySequence<AistorObjectSummary>()

                override fun verifyBucket() = AistorBucketVerification.MISCONFIGURED
            }

        assertThatThrownBy {
            AistorMediaConfiguration()
                .aistorStartupVerifier(client, properties(), AistorMediaMetrics(SimpleMeterRegistry()))
                .run(DefaultApplicationArguments())
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("bucket")
    }

    @Test
    fun `startup verification isolates a temporarily unavailable AIStor`() {
        val client = client(AistorBucketVerification.UNAVAILABLE)
        val registry = SimpleMeterRegistry()

        AistorMediaConfiguration()
            .aistorStartupVerifier(client, properties(), AistorMediaMetrics(registry))
            .run(DefaultApplicationArguments())

        assertThat(
            registry
                .find("beanflow.media.startup.validation")
                .tag("outcome", "unavailable")
                .counter()
                ?.count(),
        ).isEqualTo(1.0)
    }

    private fun client(verification: AistorBucketVerification) =
        object : AistorObjectClient {
            override fun put(
                key: String,
                bytes: ByteArray,
                contentType: String,
                sha256: String,
            ) = Unit

            override fun stat(key: String) = AistorObjectStatus(0, null)

            override fun presignGet(
                key: String,
                expirySeconds: Int,
            ) = "https://media.test/signed"

            override fun delete(key: String) = Unit

            override fun list(prefix: String) = emptySequence<AistorObjectSummary>()

            override fun verifyBucket() = verification
        }

    private fun properties(
        endpoint: String = "http://127.0.0.1:9000",
        accessKey: String = "access-key",
        bucket: String = "beanflow-media",
    ) = AistorMediaProperties(
        endpoint = endpoint,
        publicEndpoint = "https://media.beanflow.test",
        accessKey = accessKey,
        secretKey = "secret-key",
        bucket = bucket,
    )
}

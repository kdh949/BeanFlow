package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class HmacSignedCursorCodecTest {
    @Test
    fun `issues the fixed v1 canonical wire test vector without Base64URL padding`() {
        val codec = codec()

        val token =
            codec.issue(
                scope = scope(),
                sort = listOf("1234567", "123e4567-e89b-12d3-a456-426614174000"),
                expiresAt = Instant.parse("2026-08-01T01:00:00Z"),
            )

        assertThat(token).isEqualTo(
            "v1.test-vector.eyJlbmRwb2ludCI6IkdFVCAvc3RvcmVzL25lYXJieSIsImZpbHRlckhhc2giOiJhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhIiwic29ydCI6WyIxMjM0NTY3IiwiMTIzZTQ1NjctZTg5Yi0xMmQzLWE0NTYtNDI2NjE0MTc0MDAwIl0sImlzc3VlZEF0IjoxNzg1NTQyNDAwLCJleHBpcmVzQXQiOjE3ODU1NDYwMDB9.kSEtHnOhVmStKW12L9Aej2uXBBEL4mqOVB38flzomW0",
        )
        assertThat(token).doesNotContain("=")

        val verified = codec.verify(token, scope())
        assertThat(verified.sort).containsExactly("1234567", "123e4567-e89b-12d3-a456-426614174000")
        assertThat(verified.issuedAt).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"))
        assertThat(verified.expiresAt).isEqualTo(Instant.parse("2026-08-01T01:00:00Z"))
    }

    @Test
    fun `rejects malformed noncanonical and tampered tokens before the sort adapter runs`() {
        val adapterInvoked = booleanArrayOf(false)
        val guardedScope =
            SignedCursorScope(
                endpoint = ENDPOINT,
                filterHash = FILTER_HASH,
                sortAdapter =
                    object : CursorSortAdapter<List<String>> {
                        override fun encode(sort: List<String>): List<String> = sort

                        override fun decode(values: List<String>): List<String>? {
                            adapterInvoked[0] = true
                            return values
                        }
                    },
            )
        val codec = codec()
        val valid =
            codec.issue(
                guardedScope,
                listOf("1234567", "123e4567-e89b-12d3-a456-426614174000"),
                Instant.parse("2026-08-01T01:00:00Z"),
            )
        val whitespacePayload =
            """{ \"endpoint\":\"$ENDPOINT\",\"filterHash\":\"$FILTER_HASH\",""" +
                """\"sort\":[\"1234567\",\"123e4567-e89b-12d3-a456-426614174000\"],""" +
                """\"issuedAt\":1785542400,\"expiresAt\":1785546000}"""

        listOf(
            valid.replaceFirst("v1", "v2"),
            valid.replaceFirst("test-vector", "unknown"),
            valid.dropLast(1) + "x",
            valid.replace(".", ".=", ignoreCase = false),
            signedToken("test-vector", whitespacePayload),
            signedToken("test-vector", rawPayload(extraProperty = ",\"unexpected\":\"value\"")),
            signedToken("test-vector", rawPayload(filterHashJson = "null")),
            signedToken("test-vector", "{\"endpoint\":"),
            "x".repeat(2049),
        ).forEach { token ->
            assertInvalidCursor { codec.verify(token, guardedScope) }
        }

        assertThat(adapterInvoked[0]).isFalse()
    }

    @Test
    fun `rejects endpoint filter sort and expiry scope mismatches`() {
        val codec = codec()
        val token =
            codec.issue(
                scope(),
                listOf("1234567", "123e4567-e89b-12d3-a456-426614174000"),
                Instant.parse("2026-08-01T01:00:00Z"),
            )

        assertInvalidCursor {
            codec.verify(token, scope(endpoint = "GET /point-accounts/transactions"))
        }
        assertInvalidCursor {
            codec.verify(token, scope(filterHash = "b".repeat(64)))
        }
        assertInvalidCursor {
            codec.verify(token, scope(sortSize = 3))
        }
        assertInvalidCursor {
            codec(Instant.parse("2026-08-01T01:00:00Z")).verify(token, scope())
        }
    }

    @Test
    fun `accepts an unexpired retired key only while it remains in the verification ring`() {
        val previous = keyRing(activeKeyId = "previous", keys = listOf("previous" to PREVIOUS_SECRET))
        val currentWithPrevious =
            keyRing(
                activeKeyId = "current",
                keys = listOf("current" to TEST_VECTOR_SECRET, "previous" to PREVIOUS_SECRET),
            )
        val currentOnly = keyRing(activeKeyId = "current", keys = listOf("current" to TEST_VECTOR_SECRET))
        val issuedWithPrevious =
            codec(keyRing = previous).issue(
                scope(),
                listOf("1234567", "123e4567-e89b-12d3-a456-426614174000"),
                Instant.parse("2026-08-01T01:00:00Z"),
            )

        assertThat(codec(keyRing = currentWithPrevious).verify(issuedWithPrevious, scope()).sort)
            .containsExactly("1234567", "123e4567-e89b-12d3-a456-426614174000")
        assertInvalidCursor { codec(keyRing = currentOnly).verify(issuedWithPrevious, scope()) }
    }

    @Test
    fun `rejects a noncanonical UUID sort and lifetimes outside one to twenty four hours`() {
        val codec = codec()

        assertThatThrownBy {
            codec.issue(
                scope(),
                listOf("1234567", "123E4567-E89B-12D3-A456-426614174000"),
                Instant.parse("2026-08-01T01:00:00Z"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            codec.issue(
                scope(),
                listOf("1234567", "123e4567-e89b-12d3-a456-426614174000"),
                Instant.parse("2026-08-01T00:00:00Z"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            codec.issue(
                scope(),
                listOf("1234567", "123e4567-e89b-12d3-a456-426614174000"),
                Instant.parse("2026-08-02T00:00:01Z"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `records only endpoint and closed outcome tags`() {
        val registry = SimpleMeterRegistry()
        val codec = codec(meterRegistry = registry)
        val token =
            codec.issue(
                scope(),
                listOf("1234567", "123e4567-e89b-12d3-a456-426614174000"),
                Instant.parse("2026-08-01T01:00:00Z"),
            )

        codec.verify(token, scope())
        assertInvalidCursor { codec.verify(token.dropLast(1) + "x", scope()) }

        val meters = registry.find("beanflow.pagination.cursor.validation.count").meters()
        assertThat(meters).hasSize(2)
        assertThat(meters.flatMap { meter -> meter.id.tags.map { tag -> tag.key } }.toSet())
            .containsExactlyInAnyOrder("endpoint", "outcome")
        assertThat(meters.flatMap { meter -> meter.id.tags.map { tag -> tag.value } })
            .noneMatch { value -> value.contains("test-vector") || value.contains(FILTER_HASH) || value.contains("1234567") }
    }

    private fun codec(
        now: Instant = Instant.parse("2026-08-01T00:00:00Z"),
        keyRing: CursorHmacKeyRing = keyRing(),
        meterRegistry: SimpleMeterRegistry = SimpleMeterRegistry(),
    ): HmacSignedCursorCodec = HmacSignedCursorCodec(keyRing, Clock.fixed(now, ZoneOffset.UTC), CursorMetrics(meterRegistry))

    private fun scope(
        endpoint: String = ENDPOINT,
        filterHash: String = FILTER_HASH,
        sortSize: Int = 2,
    ): SignedCursorScope<List<String>> =
        SignedCursorScope(
            endpoint = endpoint,
            filterHash = filterHash,
            sortAdapter =
                object : CursorSortAdapter<List<String>> {
                    override fun encode(sort: List<String>): List<String> = sort

                    override fun decode(values: List<String>): List<String>? = values.takeIf { it.size == sortSize }
                },
        )

    private fun keyRing(
        activeKeyId: String = "test-vector",
        keys: List<Pair<String, String>> = listOf("test-vector" to TEST_VECTOR_SECRET),
    ): CursorHmacKeyRing =
        CursorHmacKeyRing.from(
            CursorHmacProperties(
                activeKeyId = activeKeyId,
                keys = keys.map { (id, secret) -> CursorHmacKeyProperties(id, secret) },
            ),
        )

    private fun signedToken(
        keyId: String,
        payload: String,
    ): String {
        val encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        val signature =
            Mac
                .getInstance("HmacSHA256")
                .apply { init(SecretKeySpec(Base64.getUrlDecoder().decode(TEST_VECTOR_SECRET), "HmacSHA256")) }
                .doFinal("v1.$keyId.$encodedPayload".toByteArray())
        return "v1.$keyId.$encodedPayload.${Base64.getUrlEncoder().withoutPadding().encodeToString(signature)}"
    }

    private fun rawPayload(
        filterHashJson: String = "\"$FILTER_HASH\"",
        extraProperty: String = "",
    ): String =
        listOf(
            "{\"endpoint\":\"$ENDPOINT\",",
            "\"filterHash\":$filterHashJson,",
            "\"sort\":[\"1234567\",\"123e4567-e89b-12d3-a456-426614174000\"],",
            "\"issuedAt\":1785542400,\"expiresAt\":1785546000$extraProperty}",
        ).joinToString(separator = "")

    private fun assertInvalidCursor(action: () -> Unit) {
        assertThatThrownBy(action)
            .isInstanceOf(DomainFailure::class.java)
            .extracting { failure -> (failure as DomainFailure).code }
            .isEqualTo(FailureCode.INVALID_REQUEST)
    }

    private companion object {
        const val ENDPOINT = "GET /stores/nearby"
        const val FILTER_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val TEST_VECTOR_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY"
        const val PREVIOUS_SECRET = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA"
    }
}

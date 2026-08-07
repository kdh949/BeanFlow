package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.discovery.api.SearchNearbyStoresCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.SignedCursor
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Coordinate canonicalization, public bounds and the cursor sort tuple, without a database.
 *
 * The codec is replaced by a recording double so the test can compare the canonical filter hash of
 * two textually different but numerically identical requests.
 */
internal class NearbyStoreDiscoveryValidationTest {
    private val codec = RecordingSignedCursorCodec()
    private val validation = NearbyStoreQueryValidation(codec)
    private val now: Instant = Instant.parse("2026-08-06T00:00:00Z")

    @Test
    fun `textually different but numerically identical coordinates share one filter hash`() {
        val plain = prepare(latitude = "37.5", longitude = "127.0")
        val padded = prepare(latitude = "37.5000", longitude = "127.000")

        assertThat(padded.cursorScope.filterHash).isEqualTo(plain.cursorScope.filterHash)
        assertThat(plain.cursorScope.endpoint).isEqualTo("stores-nearby")
        assertThat(plain.cursorScope.filterHash).matches("[0-9a-f]{64}")
    }

    @Test
    fun `signed zero and trailing zero coordinates canonicalize to the same filter hash`() {
        val hashes =
            listOf("0", "-0", "0.0", "-0.0", "0.000")
                .map { zero -> prepare(latitude = zero, longitude = zero).cursorScope.filterHash }

        assertThat(hashes.distinct()).hasSize(1)
    }

    @Test
    fun `a different radius or a swapped coordinate produces a different filter hash`() {
        val base = prepare(latitude = "37.5", longitude = "12.7", radiusMeters = "1000").cursorScope.filterHash

        assertThat(prepare(latitude = "37.5", longitude = "12.7", radiusMeters = "1001").cursorScope.filterHash)
            .isNotEqualTo(base)
        assertThat(prepare(latitude = "12.7", longitude = "37.5", radiusMeters = "1000").cursorScope.filterHash)
            .isNotEqualTo(base)
    }

    @Test
    fun `radius accepts the inclusive contract bounds and rejects everything outside them`() {
        assertThat(prepare(radiusMeters = "1").query.radiusMeters).isOne()
        assertThat(prepare(radiusMeters = "10000").query.radiusMeters).isEqualTo(10_000)

        listOf("10001", "0", "-1", "1.0", " 1", "1 ", "01", "+1", "", "abc", "1".repeat(11))
            .forEach { radius -> assertInvalid(radiusMeters = radius) }
        assertInvalid(radiusMeters = null)
    }

    @Test
    fun `limit defaults to twenty and accepts only the common one to hundred range`() {
        assertThat(prepare(limit = null).limit).isEqualTo(20)
        assertThat(prepare(limit = "1").limit).isOne()
        assertThat(prepare(limit = "100").limit).isEqualTo(100)
        // The repository always fetches one extra row to decide whether a next page exists.
        assertThat(prepare(limit = "100").query.limit).isEqualTo(101)

        listOf("101", "0", "-1", "1.5", "abc", "").forEach { limit -> assertInvalid(limit = limit) }
    }

    @Test
    fun `latitude and longitude accept the inclusive contract range as finite decimals`() {
        listOf("90", "-90", "0", "89.999999").forEach { latitude ->
            assertThat(prepare(latitude = latitude).query.latitude).isEqualTo(BigDecimal(latitude))
        }
        listOf("180", "-180", "0", "-179.999999").forEach { longitude ->
            assertThat(prepare(longitude = longitude).query.longitude).isEqualTo(BigDecimal(longitude))
        }

        listOf("90.1", "-90.1", "91", "NaN", "Infinity", "-Infinity", "1e2", "+1", ".5", "1.", "", "1".repeat(33))
            .forEach { latitude -> assertInvalid(latitude = latitude) }
        listOf("180.1", "-180.1", "181", "NaN", "1E2").forEach { longitude -> assertInvalid(longitude = longitude) }
        assertInvalid(latitude = null)
        assertInvalid(longitude = null)
    }

    @Test
    fun `rejection messages never echo the customer coordinate radius or cursor`() {
        val secretLatitude = "37.123456789"
        val secretLongitude = "127.987654321"
        val secretCursor = "v1.secret-cursor-material"

        val failure =
            assertThatThrownBy {
                validation.prepare(command(latitude = "$secretLatitude$secretLatitude", longitude = secretLongitude))
            }.isInstanceOf(DomainFailure::class.java)
        failure.hasMessageNotContaining(secretLatitude).hasMessageNotContaining(secretLongitude)

        assertThatThrownBy { validation.prepare(command(cursor = secretCursor.padEnd(2049, 'x'))) }
            .isInstanceOf(DomainFailure::class.java)
            .hasMessageNotContaining(secretCursor)
    }

    @Test
    fun `cursor length is bounded before the codec sees the token`() {
        assertInvalid(cursor = "")
        assertInvalid(cursor = "x".repeat(2049))
        assertThat(codec.verifiedTokens).isEmpty()

        prepare(cursor = "x".repeat(2048))
        assertThat(codec.verifiedTokens).hasSize(1)
    }

    @Test
    fun `an invalid coordinate is rejected before any cursor verification`() {
        assertInvalid(latitude = "91", cursor = "any-cursor")

        assertThat(codec.verifiedTokens).isEmpty()
    }

    @Test
    fun `the sort adapter round trips the micrometer and store ID tuple`() {
        val storeId = UUID.fromString("00000000-0000-0000-0000-0000000000ab")
        val adapter = NearbyStoreQueryValidation.SORT_ADAPTER
        val encoded = adapter.encode(NearbyStoreSort(12_345_678, storeId))

        assertThat(encoded).containsExactly("12345678", storeId.toString())
        assertThat(adapter.decode(encoded)).isEqualTo(NearbyStoreSort(12_345_678, storeId))
    }

    @Test
    fun `the sort adapter rejects a tampered or non canonical tuple`() {
        val adapter = NearbyStoreQueryValidation.SORT_ADAPTER
        val storeId = UUID.randomUUID().toString()

        listOf(
            emptyList(),
            listOf("1"),
            listOf("1", storeId, "1"),
            listOf("-1", storeId),
            listOf("01", storeId),
            listOf("1.5", storeId),
            listOf("99999999999999999999", storeId),
            listOf("1", storeId.uppercase()),
            listOf("1", "not-a-uuid"),
            listOf("1", ""),
        ).forEach { values -> assertThat(adapter.decode(values)).describedAs(values.toString()).isNull() }
    }

    private fun prepare(
        latitude: String? = "37.5",
        longitude: String? = "127.0",
        radiusMeters: String? = "1000",
        cursor: String? = null,
        limit: String? = null,
    ): PreparedNearbyStorePage = validation.prepare(command(latitude, longitude, radiusMeters, cursor, limit))

    private fun assertInvalid(
        latitude: String? = "37.5",
        longitude: String? = "127.0",
        radiusMeters: String? = "1000",
        cursor: String? = null,
        limit: String? = null,
    ) {
        assertThatThrownBy { validation.prepare(command(latitude, longitude, radiusMeters, cursor, limit)) }
            .describedAs("latitude=$latitude longitude=$longitude radius=$radiusMeters cursor=$cursor limit=$limit")
            .isInstanceOf(DomainFailure::class.java)
            .extracting { (it as DomainFailure).code }
            .isEqualTo(FailureCode.INVALID_REQUEST)
    }

    private fun command(
        latitude: String? = "37.5",
        longitude: String? = "127.0",
        radiusMeters: String? = "1000",
        cursor: String? = null,
        limit: String? = null,
    ) = SearchNearbyStoresCommand(latitude, longitude, radiusMeters, cursor, limit, now)

    private class RecordingSignedCursorCodec : SignedCursorCodec {
        val verifiedTokens = mutableListOf<String>()

        override fun <T> issue(
            scope: SignedCursorScope<T>,
            sort: T,
            expiresAt: Instant,
        ): String = throw UnsupportedOperationException("Cursor issuing is covered by the PostgreSQL contract test")

        override fun <T> verify(
            token: String,
            scope: SignedCursorScope<T>,
        ): SignedCursor<T> {
            verifiedTokens += token
            val sort =
                scope.sortAdapter.decode(listOf("0", "00000000-0000-0000-0000-000000000001"))
                    ?: error("The nearby sort adapter must decode its own canonical tuple")
            return SignedCursor(sort, Instant.EPOCH, Instant.EPOCH.plusSeconds(1))
        }
    }
}

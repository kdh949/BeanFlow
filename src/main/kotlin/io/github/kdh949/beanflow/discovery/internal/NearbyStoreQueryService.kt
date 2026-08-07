package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.discovery.api.NearbyStorePage
import io.github.kdh949.beanflow.discovery.api.NearbyStoreQueryOperations
import io.github.kdh949.beanflow.discovery.api.NearbyStoreView
import io.github.kdh949.beanflow.discovery.api.SearchNearbyStoresCommand
import io.github.kdh949.beanflow.merchant.api.NearbyStoreProfileCursor
import io.github.kdh949.beanflow.merchant.api.NearbyStoreProfileProjection
import io.github.kdh949.beanflow.merchant.api.NearbyStoreProfileQuery
import io.github.kdh949.beanflow.merchant.api.StoreDiscoveryQueryOperations
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.PersistenceException
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionException
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The stable nearby sort tuple bound into every issued cursor. */
internal data class NearbyStoreSort(
    val distanceMicrometers: Long,
    val storeId: UUID,
)

internal data class PreparedNearbyStorePage(
    val limit: Int,
    val query: NearbyStoreProfileQuery,
    val cursorScope: SignedCursorScope<NearbyStoreSort>,
    val cursorExpiresAt: Instant,
)

@Service
internal class NearbyStoreQueryService(
    private val validation: NearbyStoreQueryValidation,
    private val reads: NearbyStoreReadTransaction,
    private val metrics: NearbyStoreQueryMetrics,
) : NearbyStoreQueryOperations {
    override fun search(command: SearchNearbyStoresCommand): NearbyStorePage {
        val startedAt = System.nanoTime()
        val prepared =
            try {
                validation.prepare(command)
            } catch (failure: DomainFailure) {
                metrics.record(failure.toOutcome(), startedAt)
                throw failure
            }
        return try {
            reads.search(prepared).also { page ->
                metrics.record(NearbyStoreQueryOutcome.SUCCEEDED, startedAt, page.items.size)
            }
        } catch (failure: DomainFailure) {
            metrics.record(failure.toOutcome(), startedAt)
            throw failure
        } catch (failure: TransactionException) {
            metrics.recordSpatialFailure(NearbySpatialFailureReason.TRANSACTION_FAILED)
            metrics.record(NearbyStoreQueryOutcome.DEPENDENCY_UNAVAILABLE, startedAt)
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Nearby store search transaction could not complete",
            ).also { it.initCause(failure) }
        }
    }
}

/**
 * One read-only transaction around the Merchant public Query API.
 *
 * A spatial, extension or database failure becomes an explicit 503. It is never converted into an
 * empty successful page, a cached page or an application distance calculation.
 */
@Component
internal class NearbyStoreReadTransaction(
    private val stores: StoreDiscoveryQueryOperations,
    private val signedCursorCodec: SignedCursorCodec,
    private val metrics: NearbyStoreQueryMetrics,
) {
    @Transactional(readOnly = true)
    fun search(prepared: PreparedNearbyStorePage): NearbyStorePage {
        val fetched =
            try {
                stores.findPickupCapableStoresNear(prepared.query)
            } catch (failure: DataAccessException) {
                spatialUnavailable(failure)
            } catch (failure: PersistenceException) {
                spatialUnavailable(failure)
            }
        val items = fetched.take(prepared.limit).map(NearbyStoreProfileProjection::toView)
        val nextCursor =
            if (fetched.size > prepared.limit) {
                val last = fetched[prepared.limit - 1]
                signedCursorCodec.issue(
                    prepared.cursorScope,
                    NearbyStoreSort(last.distanceMicrometers, last.storeId),
                    prepared.cursorExpiresAt,
                )
            } else {
                null
            }
        return NearbyStorePage(items, nextCursor)
    }

    private fun spatialUnavailable(cause: Throwable): Nothing {
        metrics.recordSpatialFailure(NearbySpatialFailureReason.QUERY_FAILED)
        throw DomainFailure(
            FailureCode.DEPENDENCY_UNAVAILABLE,
            "Nearby store spatial query is unavailable",
        ).also { it.initCause(cause) }
    }
}

internal fun NearbyStoreProfileProjection.toView(): NearbyStoreView =
    NearbyStoreView(
        storeId = storeId,
        name = name,
        distanceMeters = distanceMicrometers / MICROMETERS_PER_METER,
        open = open,
        pickupAvailable = pickupAvailable,
    )

private const val MICROMETERS_PER_METER = 1_000_000L

/**
 * Validates and canonicalizes the public nearby contract before any spatial query runs.
 *
 * Failure messages never contain the customer coordinate, the radius or the cursor, so a rejected
 * request cannot leak precise location through the API error body, an application log or a trace.
 */
@Component
internal class NearbyStoreQueryValidation(
    private val signedCursorCodec: SignedCursorCodec,
) {
    fun prepare(command: SearchNearbyStoresCommand): PreparedNearbyStorePage {
        val limit = limit(command.limit)
        val cursor = cursor(command.cursor)
        val latitude = coordinate(command.latitude, MIN_LATITUDE, MAX_LATITUDE, "Latitude")
        val longitude = coordinate(command.longitude, MIN_LONGITUDE, MAX_LONGITUDE, "Longitude")
        val radiusMeters = radiusMeters(command.radiusMeters)
        val scope =
            SignedCursorScope(
                endpoint = CURSOR_ENDPOINT,
                filterHash = filterHash(latitude, longitude, radiusMeters),
                sortAdapter = SORT_ADAPTER,
            )
        val after = cursor?.let { signedCursorCodec.verify(it, scope).sort }
        return PreparedNearbyStorePage(
            limit = limit,
            query =
                NearbyStoreProfileQuery(
                    latitude = latitude,
                    longitude = longitude,
                    radiusMeters = radiusMeters,
                    after = after?.let { NearbyStoreProfileCursor(it.distanceMicrometers, it.storeId) },
                    // One extra row decides whether a next page exists without a second query.
                    limit = limit + 1,
                ),
            cursorScope = scope,
            cursorExpiresAt = command.now.plus(CURSOR_TTL),
        )
    }

    private fun limit(raw: String?): Int {
        if (raw == null) return DEFAULT_LIMIT
        if (raw.length > MAX_INTEGER_LENGTH || !INTEGER_PATTERN.matches(raw)) invalid("Limit must be an integer between 1 and $MAX_LIMIT")
        val limit = raw.toIntOrNull() ?: invalid("Limit must be an integer between 1 and $MAX_LIMIT")
        if (limit !in 1..MAX_LIMIT) invalid("Limit must be an integer between 1 and $MAX_LIMIT")
        return limit
    }

    private fun cursor(raw: String?): String? {
        if (raw == null) return null
        if (raw.isEmpty() || raw.length > MAX_CURSOR_LENGTH) invalid("Cursor is invalid")
        return raw
    }

    private fun coordinate(
        raw: String?,
        minimum: BigDecimal,
        maximum: BigDecimal,
        name: String,
    ): BigDecimal {
        if (raw == null) invalid("$name is required")
        if (raw.length > MAX_COORDINATE_LENGTH || !DECIMAL_PATTERN.matches(raw)) {
            invalid("$name must be a finite decimal between ${minimum.toPlainString()} and ${maximum.toPlainString()}")
        }
        val value =
            try {
                BigDecimal(raw)
            } catch (_: NumberFormatException) {
                invalid("$name must be a finite decimal between ${minimum.toPlainString()} and ${maximum.toPlainString()}")
            }
        if (value < minimum || value > maximum) {
            invalid("$name must be a finite decimal between ${minimum.toPlainString()} and ${maximum.toPlainString()}")
        }
        return value
    }

    private fun radiusMeters(raw: String?): Int {
        if (raw == null) invalid("Radius in meters is required")
        if (raw.length > MAX_INTEGER_LENGTH || !INTEGER_PATTERN.matches(raw)) {
            invalid("Radius must be an integer between $MIN_RADIUS_METERS and $MAX_RADIUS_METERS meters")
        }
        val radius = raw.toIntOrNull() ?: invalid("Radius must be an integer between $MIN_RADIUS_METERS and $MAX_RADIUS_METERS meters")
        if (radius !in MIN_RADIUS_METERS..MAX_RADIUS_METERS) {
            invalid("Radius must be an integer between $MIN_RADIUS_METERS and $MAX_RADIUS_METERS meters")
        }
        return radius
    }

    /**
     * ADR-070 canonical filter binding. `37.5` and `37.5000` produce the same digest and the raw
     * coordinate text never enters the token, so a cursor cannot be replayed against another
     * radius and cannot be decoded back into the original request text.
     */
    private fun filterHash(
        latitude: BigDecimal,
        longitude: BigDecimal,
        radiusMeters: Int,
    ): String {
        val canonicalLatitude = canonicalDecimal(latitude)
        val canonicalLongitude = canonicalDecimal(longitude)
        val canonicalJson =
            """{"endpoint":"$CURSOR_ENDPOINT","latitude":"$canonicalLatitude",""" +
                """"longitude":"$canonicalLongitude","radiusMeters":$radiusMeters}"""
        return HexFormat
            .of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalJson.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun canonicalDecimal(value: BigDecimal): String {
        val plain = value.stripTrailingZeros().toPlainString()
        val canonical = if (plain == "-0") "0" else plain
        check(DECIMAL_PATTERN.matches(canonical)) { "Canonical coordinate is not a plain decimal" }
        return canonical
    }

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    internal companion object {
        const val CURSOR_ENDPOINT = "stores-nearby"
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
        const val MAX_CURSOR_LENGTH = 2048
        const val MAX_COORDINATE_LENGTH = 32
        const val MAX_INTEGER_LENGTH = 10
        const val MIN_RADIUS_METERS = 1
        const val MAX_RADIUS_METERS = 10_000
        val MIN_LATITUDE: BigDecimal = BigDecimal("-90")
        val MAX_LATITUDE: BigDecimal = BigDecimal("90")
        val MIN_LONGITUDE: BigDecimal = BigDecimal("-180")
        val MAX_LONGITUDE: BigDecimal = BigDecimal("180")
        val CURSOR_TTL: Duration = Duration.ofHours(24)

        /** Plain finite decimals only; exponent notation and a leading `+` are rejected. */
        val DECIMAL_PATTERN = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")
        val INTEGER_PATTERN = Regex("-?(0|[1-9][0-9]*)")
        val UNSIGNED_INTEGER_PATTERN = Regex("0|[1-9][0-9]*")

        val SORT_ADAPTER =
            object : CursorSortAdapter<NearbyStoreSort> {
                override fun encode(sort: NearbyStoreSort): List<String> =
                    listOf(sort.distanceMicrometers.toString(), sort.storeId.toString())

                override fun decode(values: List<String>): NearbyStoreSort? {
                    if (values.size != 2) return null
                    if (!UNSIGNED_INTEGER_PATTERN.matches(values[0])) return null
                    val distanceMicrometers = values[0].toLongOrNull() ?: return null
                    val storeId =
                        try {
                            UUID.fromString(values[1])
                        } catch (_: IllegalArgumentException) {
                            return null
                        }
                    if (distanceMicrometers.toString() != values[0] || storeId.toString() != values[1]) return null
                    return NearbyStoreSort(distanceMicrometers, storeId)
                }
            }
    }
}

internal enum class NearbyStoreQueryOutcome {
    SUCCEEDED,
    INVALID_INPUT,
    DEPENDENCY_UNAVAILABLE,
}

internal enum class NearbySpatialFailureReason {
    QUERY_FAILED,
    TRANSACTION_FAILED,
}

/**
 * Closed outcome and reason vocabularies only. Coordinates, radius, cursor, store IDs and the
 * request URI are never used as metric tags.
 */
@Component
internal class NearbyStoreQueryMetrics(
    private val meterRegistry: MeterRegistry,
) {
    fun record(
        outcome: NearbyStoreQueryOutcome,
        startedAtNanos: Long,
        pageSize: Int? = null,
    ) {
        meterRegistry.counter("beanflow.discovery.nearby.count", "outcome", outcome.name).increment()
        meterRegistry
            .timer("beanflow.discovery.nearby.latency", "outcome", outcome.name)
            .record(System.nanoTime() - startedAtNanos, TimeUnit.NANOSECONDS)
        if (pageSize != null) {
            meterRegistry.summary("beanflow.discovery.nearby.page.size").record(pageSize.toDouble())
        }
    }

    fun recordSpatialFailure(reason: NearbySpatialFailureReason) {
        meterRegistry.counter("beanflow.discovery.spatial.failure", "reason", reason.name).increment()
    }
}

private fun DomainFailure.toOutcome(): NearbyStoreQueryOutcome =
    when (code) {
        FailureCode.INVALID_REQUEST -> NearbyStoreQueryOutcome.INVALID_INPUT
        else -> NearbyStoreQueryOutcome.DEPENDENCY_UNAVAILABLE
    }

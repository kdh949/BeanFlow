package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.ReplaceStoreCustomerDisplayCommand
import io.github.kdh949.beanflow.merchant.api.StoreCustomerDisplayChange
import io.github.kdh949.beanflow.merchant.api.StoreCustomerDisplayOperations
import io.github.kdh949.beanflow.merchant.api.StoreCustomerDisplaySnapshot
import io.github.kdh949.beanflow.merchant.api.StoreOperatingDay
import io.github.kdh949.beanflow.merchant.api.StoreOrderAvailabilityOperations
import io.github.kdh949.beanflow.merchant.api.StoreOrderAvailabilityReason
import io.github.kdh949.beanflow.merchant.api.StoreOrderAvailabilitySnapshot
import io.github.kdh949.beanflow.merchant.api.StoreOrderingWindowShortened
import io.github.kdh949.beanflow.merchant.api.StoreWeeklyOperatingHours
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.PreparedStatement
import java.sql.Time
import java.sql.Timestamp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

@Service
internal class StoreCustomerDisplayService(
    private val stores: StoreJpaRepository,
    private val repository: StoreCustomerDisplayRepository,
    private val events: ApplicationEventPublisher,
) : StoreCustomerDisplayOperations {
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    override fun find(storeId: UUID): StoreCustomerDisplaySnapshot =
        persistence {
            if (!stores.existsById(storeId)) notFound()
            repository.find(storeId) ?: absent(storeId)
        }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun replace(
        command: ReplaceStoreCustomerDisplayCommand,
        now: Instant,
    ): StoreCustomerDisplayChange =
        persistence {
            val normalized = normalize(command)
            stores.findByIdForUpdate(command.storeId) ?: notFound()
            val persisted = repository.find(command.storeId)
            val current = persisted ?: absent(command.storeId)
            if (current.version != command.expectedVersion) stale()
            if (current.sameContent(normalized)) return@persistence StoreCustomerDisplayChange(current, current, false)

            val nextVersion = nextVersion(current.version)
            val replacement = normalized.copy(version = nextVersion)
            if (persisted == null) {
                repository.insert(replacement, now)
            } else {
                repository.update(replacement, current.version, now)
            }
            repository.replaceHours(command.storeId, replacement.operatingHours?.days.orEmpty())
            StoreOperatingWindowPolicy
                .shortening(current, replacement, now)
                ?.let(events::publishEvent)
            StoreCustomerDisplayChange(current, replacement, true)
        }

    private fun normalize(command: ReplaceStoreCustomerDisplayCommand): StoreCustomerDisplaySnapshot {
        if (command.expectedVersion < 0) invalid("Expected version must not be negative")
        val days = normalizeDays(command.timezone, command.operatingDays)
        return StoreCustomerDisplaySnapshot(
            storeId = command.storeId,
            addressLine = text(command.addressLine, MAX_ADDRESS_LENGTH, "Address line"),
            directionsHint = text(command.directionsHint, MAX_DIRECTIONS_LENGTH, "Directions hint"),
            operatingHours = days?.let(::StoreWeeklyOperatingHours),
            version = command.expectedVersion,
        )
    }

    private fun normalizeDays(
        timezone: String?,
        days: List<StoreOperatingDay>?,
    ): List<StoreOperatingDay>? {
        if (days == null) {
            if (timezone != null) invalid("Timezone is only allowed with operating hours")
            return null
        }
        if (timezone != SEOUL_TIMEZONE) invalid("Operating-hours timezone must be Asia/Seoul")
        if (days.size != DayOfWeek.entries.size || days.map(StoreOperatingDay::dayOfWeek).toSet() != DayOfWeek.entries.toSet()) {
            invalid("Operating hours must contain every day of the week exactly once")
        }
        return days
            .map { day ->
                if (day.closed) {
                    if (day.opensAt != null || day.closesAt != null) invalid("Closed days must not contain times")
                } else {
                    val opensAt = day.opensAt ?: invalid("Open days require opensAt")
                    val closesAt = day.closesAt ?: invalid("Open days require closesAt")
                    if (opensAt >= closesAt) invalid("Open days require opensAt before closesAt")
                }
                day
            }.sortedBy { it.dayOfWeek.value }
    }

    private fun text(
        raw: String?,
        maximumLength: Int,
        field: String,
    ): String? {
        if (raw == null) return null
        val normalized = raw.trim()
        val length = normalized.codePointCount(0, normalized.length)
        if (length !in 1..maximumLength || normalized.any(Char::isISOControl)) {
            invalid("$field is invalid")
        }
        return normalized
    }

    private fun nextVersion(current: Long): Long =
        try {
            Math.addExact(current, 1)
        } catch (failure: ArithmeticException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Store display version is exhausted").also {
                it.initCause(failure)
            }
        }

    private fun <T> persistence(block: () -> T): T =
        try {
            block()
        } catch (failure: DomainFailure) {
            throw failure
        } catch (failure: DataAccessException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Store customer display is unavailable").also {
                it.initCause(failure)
            }
        }

    private fun absent(storeId: UUID) = StoreCustomerDisplaySnapshot(storeId, null, null, null, 0)

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    private fun stale(): Nothing = throw DomainFailure(FailureCode.MERCHANT_CONTENT_STALE, "Store display version is stale")

    private fun notFound(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Store was not found")

    private companion object {
        const val SEOUL_TIMEZONE = "Asia/Seoul"
        const val MAX_ADDRESS_LENGTH = 300
        const val MAX_DIRECTIONS_LENGTH = 200
    }
}

@Service
internal class StoreOrderAvailabilityService(
    private val stores: StoreJpaRepository,
    private val displays: StoreCustomerDisplayRepository,
) : StoreOrderAvailabilityOperations {
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    override fun inspect(
        storeId: UUID,
        at: Instant,
    ): StoreOrderAvailabilitySnapshot = availability(storeId, at, lockStore = false)

    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    override fun lockForOrderCommitment(
        storeId: UUID,
        at: Instant,
    ): StoreOrderAvailabilitySnapshot = availability(storeId, at, lockStore = true)

    private fun availability(
        storeId: UUID,
        at: Instant,
        lockStore: Boolean,
    ): StoreOrderAvailabilitySnapshot =
        try {
            val store =
                (if (lockStore) stores.findByIdForShare(storeId) else stores.findById(storeId).orElse(null))
                    ?: throw DomainFailure(
                        FailureCode.RESOURCE_NOT_FOUND,
                        "Store was not found",
                        targetReference = storeId.toString(),
                    )
            StoreOperatingWindowPolicy.evaluate(
                storeId = storeId,
                acceptingOrders = store.acceptingOrders,
                pickupEnabled = store.pickupEnabled,
                orderingPolicyVersion = store.orderingPolicyVersion,
                display = displays.find(storeId) ?: StoreCustomerDisplaySnapshot(storeId, null, null, null, 0),
                at = at,
            )
        } catch (failure: DomainFailure) {
            throw failure
        } catch (failure: DataAccessException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Store order availability is unavailable").also {
                it.initCause(failure)
            }
        }
}

internal object StoreOperatingWindowPolicy {
    private val seoul: ZoneId = ZoneId.of("Asia/Seoul")

    fun evaluate(
        storeId: UUID,
        acceptingOrders: Boolean,
        pickupEnabled: Boolean,
        orderingPolicyVersion: Long,
        display: StoreCustomerDisplaySnapshot,
        at: Instant,
    ): StoreOrderAvailabilitySnapshot {
        val local = at.atZone(seoul)
        val businessDate = local.toLocalDate()
        val operatingHours = display.operatingHours
        if (operatingHours == null) {
            return snapshot(
                storeId,
                StoreOrderAvailabilityReason.STORE_HOURS_NOT_CONFIGURED,
                businessDate,
                null,
                null,
                display.version,
                orderingPolicyVersion,
            )
        }
        val day =
            operatingHours.days.singleOrNull { it.dayOfWeek == local.dayOfWeek }
                ?: throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Store operating hours are incomplete")
        if (day.closed) {
            return snapshot(
                storeId,
                StoreOrderAvailabilityReason.STORE_CLOSED,
                businessDate,
                null,
                null,
                display.version,
                orderingPolicyVersion,
            )
        }
        val opensAt = day.opensAt ?: invalidSource()
        val closesAt = day.closesAt ?: invalidSource()
        if (opensAt >= closesAt) invalidSource()
        val windowOpensAt = businessDate.atTime(opensAt).atZone(seoul).toInstant()
        val windowClosesAt = businessDate.atTime(closesAt).atZone(seoul).toInstant()
        if (at.isBefore(windowOpensAt) || !at.isBefore(windowClosesAt)) {
            return snapshot(
                storeId,
                StoreOrderAvailabilityReason.STORE_CLOSED,
                businessDate,
                windowOpensAt,
                windowClosesAt,
                display.version,
                orderingPolicyVersion,
            )
        }
        val reason =
            if (acceptingOrders && pickupEnabled) {
                StoreOrderAvailabilityReason.AVAILABLE
            } else {
                StoreOrderAvailabilityReason.STORE_NOT_ACCEPTING_ORDERS
            }
        return snapshot(
            storeId,
            reason,
            businessDate,
            windowOpensAt,
            windowClosesAt,
            display.version,
            orderingPolicyVersion,
        )
    }

    fun shortening(
        previous: StoreCustomerDisplaySnapshot,
        current: StoreCustomerDisplaySnapshot,
        changedAt: Instant,
    ): StoreOrderingWindowShortened? {
        val previousClosesAt = activeClosesAt(previous, changedAt) ?: return null
        val currentClosesAt = activeClosesAt(current, changedAt) ?: changedAt
        if (!currentClosesAt.isBefore(previousClosesAt)) return null
        return StoreOrderingWindowShortened(
            storeId = current.storeId,
            previousClosesAt = previousClosesAt,
            shortenedClosesAt = currentClosesAt,
            displayVersion = current.version,
            changedAt = changedAt,
        )
    }

    private fun activeClosesAt(
        display: StoreCustomerDisplaySnapshot,
        at: Instant,
    ): Instant? {
        val local = at.atZone(seoul)
        val day = display.operatingHours?.days?.singleOrNull { it.dayOfWeek == local.dayOfWeek } ?: return null
        if (day.closed) return null
        val opensAt = day.opensAt ?: invalidSource()
        val closesAt = day.closesAt ?: invalidSource()
        if (opensAt >= closesAt) invalidSource()
        val businessDate = local.toLocalDate()
        val opens = businessDate.atTime(opensAt).atZone(seoul).toInstant()
        val closes = businessDate.atTime(closesAt).atZone(seoul).toInstant()
        return closes.takeIf { !at.isBefore(opens) && at.isBefore(closes) }
    }

    private fun snapshot(
        storeId: UUID,
        reason: StoreOrderAvailabilityReason,
        businessDate: LocalDate,
        opensAt: Instant?,
        closesAt: Instant?,
        displayVersion: Long,
        orderingPolicyVersion: Long,
    ) = StoreOrderAvailabilitySnapshot(
        storeId = storeId,
        available = reason == StoreOrderAvailabilityReason.AVAILABLE,
        reason = reason,
        businessDate = businessDate,
        orderingWindowOpensAt = opensAt,
        orderingWindowClosesAt = closesAt,
        displayVersion = displayVersion,
        orderingPolicyVersion = orderingPolicyVersion,
    )

    private fun invalidSource(): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Store operating hours are invalid")
}

private fun StoreCustomerDisplaySnapshot.sameContent(other: StoreCustomerDisplaySnapshot): Boolean =
    addressLine == other.addressLine &&
        directionsHint == other.directionsHint &&
        operatingHours == other.operatingHours

@Repository
internal class StoreCustomerDisplayRepository(
    private val jdbc: JdbcTemplate,
) {
    fun find(storeId: UUID): StoreCustomerDisplaySnapshot? {
        val profile =
            jdbc
                .query(
                    """
                    SELECT store_id, address_line, directions_hint, version
                      FROM merchant_store_customer_display_profile
                     WHERE store_id = ?
                    """.trimIndent(),
                    { resultSet, _ ->
                        StoreCustomerDisplayRow(
                            storeId = resultSet.getObject("store_id", UUID::class.java),
                            addressLine = resultSet.getString("address_line"),
                            directionsHint = resultSet.getString("directions_hint"),
                            version = resultSet.getLong("version"),
                        )
                    },
                    storeId,
                ).singleOrNull() ?: return null
        val hours =
            jdbc.query(
                """
                SELECT day_of_week, closed, opens_at, closes_at
                  FROM merchant_store_operating_hours
                 WHERE store_id = ?
                 ORDER BY day_of_week
                """.trimIndent(),
                { resultSet, _ ->
                    StoreOperatingDay(
                        dayOfWeek = DayOfWeek.of(resultSet.getInt("day_of_week")),
                        closed = resultSet.getBoolean("closed"),
                        opensAt = resultSet.getObject("opens_at", LocalTime::class.java),
                        closesAt = resultSet.getObject("closes_at", LocalTime::class.java),
                    )
                },
                storeId,
            )
        if (hours.isNotEmpty() && hours.size != DayOfWeek.entries.size) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Store operating hours are incomplete")
        }
        return StoreCustomerDisplaySnapshot(
            storeId = profile.storeId,
            addressLine = profile.addressLine,
            directionsHint = profile.directionsHint,
            operatingHours = hours.takeIf(List<*>::isNotEmpty)?.let(::StoreWeeklyOperatingHours),
            version = profile.version,
        )
    }

    fun insert(
        replacement: StoreCustomerDisplaySnapshot,
        now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO merchant_store_customer_display_profile
                (store_id, address_line, directions_hint, version, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            replacement.storeId,
            replacement.addressLine,
            replacement.directionsHint,
            replacement.version,
            Timestamp.from(now),
            Timestamp.from(now),
        )
    }

    fun update(
        replacement: StoreCustomerDisplaySnapshot,
        expectedVersion: Long,
        now: Instant,
    ) {
        val changed =
            jdbc.update(
                """
                UPDATE merchant_store_customer_display_profile
                   SET address_line = ?, directions_hint = ?, version = ?, updated_at = ?
                 WHERE store_id = ? AND version = ?
                """.trimIndent(),
                replacement.addressLine,
                replacement.directionsHint,
                replacement.version,
                Timestamp.from(now),
                replacement.storeId,
                expectedVersion,
            )
        if (changed != 1) throw DomainFailure(FailureCode.MERCHANT_CONTENT_STALE, "Store display version is stale")
    }

    fun replaceHours(
        storeId: UUID,
        days: List<StoreOperatingDay>,
    ) {
        jdbc.update("DELETE FROM merchant_store_operating_hours WHERE store_id = ?", storeId)
        if (days.isEmpty()) return
        jdbc.batchUpdate(
            """
            INSERT INTO merchant_store_operating_hours
                (store_id, day_of_week, closed, opens_at, closes_at)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            object : BatchPreparedStatementSetter {
                override fun getBatchSize(): Int = days.size

                override fun setValues(
                    statement: PreparedStatement,
                    index: Int,
                ) {
                    val day = days[index]
                    statement.setObject(1, storeId)
                    statement.setInt(2, day.dayOfWeek.value)
                    statement.setBoolean(3, day.closed)
                    statement.setTime(4, day.opensAt?.let(Time::valueOf))
                    statement.setTime(5, day.closesAt?.let(Time::valueOf))
                }
            },
        )
    }
}

private data class StoreCustomerDisplayRow(
    val storeId: UUID,
    val addressLine: String?,
    val directionsHint: String?,
    val version: Long,
)

package io.github.kdh949.beanflow.fulfillment.internal

import com.fasterxml.jackson.annotation.JsonAnySetter
import io.github.kdh949.beanflow.identity.api.StoreAccessOperations
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.MerchantActor
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

internal data class CreatePickupSlotRequest(
    val startsAt: Instant,
    val endsAt: Instant,
    @field:Min(0) val capacity: Long,
    @field:NotBlank @field:Size(max = 500) val reason: String,
) {
    @JsonAnySetter
    fun rejectUnknown(
        name: String,
        value: Any?,
    ): Unit = throw IllegalArgumentException("Unknown pickup creation field: $name")
}

internal data class ReplacePickupSlotRequest(
    val startsAt: Instant,
    val endsAt: Instant,
    @field:Min(0) val capacity: Long,
    @field:Min(0) val expectedVersion: Long,
    @field:NotBlank @field:Size(max = 500) val reason: String,
) {
    @JsonAnySetter
    fun rejectUnknown(
        name: String,
        value: Any?,
    ): Unit = throw IllegalArgumentException("Unknown pickup management field: $name")
}

internal data class ManagedPickupSlot(
    val slotId: UUID,
    val storeId: UUID,
    val startsAt: Instant,
    val endsAt: Instant,
    val capacity: Long,
    val reservedCount: Long,
    val confirmedCount: Long,
    val version: Long,
)

internal data class ManagedPickupSlotPage(
    val items: List<ManagedPickupSlot>,
    val nextCursor: String?,
)

internal data class PickupSlotManagementCommand(
    val actorId: UUID,
    val storeId: UUID,
    val slotId: UUID?,
    val key: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val capacity: Long,
    val expectedVersion: Long?,
    val reason: String,
)

@Service
@Transactional
internal class PickupSlotManagementService(
    private val access: StoreAccessOperations,
    private val slots: PickupSlotJpaRepository,
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val audits: AuditRecordOperations,
    private val correlation: CorrelationIdSource,
    private val cursors: SignedCursorCodec,
    private val clock: Clock,
) {
    fun get(
        actorId: UUID,
        storeId: UUID,
        slotId: UUID,
    ): ManagedPickupSlot {
        access.requireStoreAuthoringAccess(
            actorId,
            storeId,
            ROLES,
        )
        return owned(
            storeId,
            slotId,
            false,
        ).snapshot()
    }

    fun list(
        actorId: UUID,
        storeId: UUID,
        from: Instant,
        to: Instant,
        cursor: String?,
        limit: Int,
    ): ManagedPickupSlotPage {
        access.requireStoreAuthoringAccess(
            actorId,
            storeId,
            ROLES,
        )
        if (limit !in 1..100 ||
            !to.isAfter(from)
        ) {
            invalid("Pickup management interval or limit is invalid")
        }
        val scope =
            SignedCursorScope(
                "pickup-slot-management",
                digest(
                    listOf(
                        actorId,
                        storeId,
                        from,
                        to,
                    ),
                ),
                SORT,
            )
        val after =
            cursor?.let {
                cursors
                    .verify(
                        it,
                        scope,
                    ).sort
            }
        val args =
            mutableListOf<Any>(
                storeId,
                Timestamp.from(from),
                Timestamp.from(to),
            )
        val afterSql = if (after == null) "" else " AND (starts_at, id) > (?, ?)"
        if (after != null) {
            args += Timestamp.from(after.startsAt)
            args += after.id
        }
        args += limit + 1
        val fetched =
            jdbc.query(
                "SELECT id, store_id, starts_at, ends_at, capacity, reserved_count, confirmed_count, version " +
                    "FROM fulfillment_pickup_slot WHERE store_id = ? AND starts_at >= ? AND starts_at < ?" +
                    afterSql + " ORDER BY starts_at, id LIMIT ?",
                {
                    rs,
                    _,
                    ->
                    ManagedPickupSlot(
                        rs.getObject(
                            "id",
                            UUID::class.java,
                        ),
                        rs.getObject(
                            "store_id",
                            UUID::class.java,
                        ),
                        rs.getTimestamp("starts_at").toInstant(),
                        rs.getTimestamp("ends_at").toInstant(),
                        rs.getLong("capacity"),
                        rs.getLong("reserved_count"),
                        rs.getLong("confirmed_count"),
                        rs.getLong("version"),
                    )
                },
                *args.toTypedArray(),
            )
        val items = fetched.take(limit)
        val next =
            if (fetched.size > limit) {
                val last = items.last()
                cursors.issue(
                    scope,
                    Sort(
                        last.startsAt,
                        last.slotId,
                    ),
                    clock.instant().plus(Duration.ofMinutes(15)),
                )
            } else {
                null
            }
        return ManagedPickupSlotPage(
            items,
            next,
        )
    }

    fun change(raw: PickupSlotManagementCommand): ManagedPickupSlot {
        val actor =
            access.requireStoreAuthoringAccess(
                raw.actorId,
                raw.storeId,
                ROLES,
            )
        val c =
            raw.copy(
                startsAt = raw.startsAt.truncatedTo(ChronoUnit.MICROS),
                endsAt = raw.endsAt.truncatedTo(ChronoUnit.MICROS),
            )
        validate(c)
        val operation = if (c.slotId == null) "CREATE" else "REPLACE"
        val hash =
            digest(
                listOf(
                    c.storeId,
                    c.slotId,
                    c.startsAt,
                    c.endsAt,
                    c.capacity,
                    c.expectedVersion,
                    c.reason,
                ),
            )
        jdbc.queryForObject(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            Any::class.java,
            "pickup-management:${c.actorId}:$operation:${c.key}",
        )
        jdbc
            .query(
                "SELECT payload_hash, response_json FROM fulfillment_pickup_slot_command " +
                    "WHERE actor_id = ? AND operation = ? AND idempotency_key = ?",
                {
                    rs,
                    _,
                    ->
                    rs.getString("payload_hash") to rs.getString("response_json")
                },
                c.actorId,
                operation,
                c.key,
            ).singleOrNull()
            ?.let {
                if (it.first != hash) {
                    throw DomainFailure(
                        FailureCode.IDEMPOTENCY_KEY_REUSED,
                        "Pickup command key has another payload",
                    )
                }
                return mapper.readValue(
                    it.second,
                    ManagedPickupSlot::class.java,
                )
            }
        var now = clock.instant()
        if (!c.startsAt.isAfter(now)) invalid("Pickup slot must start in the future")
        val slot: PickupSlotEntity
        val previous: ManagedPickupSlot?
        if (c.slotId == null) {
            previous = null
            slot =
                slots.saveAndFlush(
                    PickupSlotEntity(
                        UUID.randomUUID(),
                        c.storeId,
                        c.startsAt,
                        c.endsAt,
                        c.capacity,
                    ),
                )
        } else {
            slot =
                owned(
                    c.storeId,
                    c.slotId,
                    true,
                )
            if (slot.version != c.expectedVersion) conflict("Pickup slot version is stale")
            // 잠금 대기 중 슬롯이 시작될 수 있으므로 owner lock 아래에서 시간을 다시 확인한다.
            now = clock.instant()
            previous = slot.snapshot()
            slot.replaceSchedule(
                c.startsAt,
                c.endsAt,
                c.capacity,
                now,
            )
            slots.flush()
        }
        val result = slot.snapshot()
        val commandId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO fulfillment_pickup_slot_command " +
                "(id, actor_id, operation, idempotency_key, slot_id, payload_hash, response_json, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            commandId,
            c.actorId,
            operation,
            c.key,
            slot.id,
            hash,
            mapper.writeValueAsString(result),
            Timestamp.from(now),
        )
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = c.actorId.toString(),
                    actorType = if (actor.role == StoreActorRole.OWNER) AuditActorType.STORE_OWNER else AuditActorType.STORE_STAFF,
                    category = AuditCategory.ORDER_AND_FULFILLMENT,
                    action = if (c.slotId == null) "PICKUP_SLOT_CREATED" else "PICKUP_SLOT_REPLACED",
                    targetType = "PickupSlot",
                    targetId = slot.id,
                    occurredAt = now,
                    reason = c.reason,
                    beforeSummary = previous?.summary() ?: emptyMap(),
                    afterSummary = result.summary(),
                    correlationId = correlation.currentOrCreate(),
                    sourceReference = "pickup-slot-command:$commandId",
                ),
            ),
        )
        return result
    }

    private fun owned(
        storeId: UUID,
        slotId: UUID,
        lock: Boolean,
    ): PickupSlotEntity {
        val slot = if (lock) slots.findLockedById(slotId) else slots.findById(slotId).orElse(null)
        if (slot == null ||
            slot.storeId != storeId
        ) {
            throw DomainFailure(
                FailureCode.RESOURCE_NOT_FOUND,
                "Pickup slot was not found",
            )
        }
        return slot
    }

    private fun validate(c: PickupSlotManagementCommand) {
        if (c.key.length !in 8..128 ||
            c.key != c.key.trim() ||
            c.key.any(Char::isISOControl) ||

            c.reason.isBlank() ||
            c.reason != c.reason.trim() ||
            c.reason.length > 500 ||
            c.reason.any(Char::isISOControl) ||

            c.capacity < 0 ||
            !c.endsAt.isAfter(c.startsAt) ||

            (c.slotId == null && c.expectedVersion != null) ||
            (
                c.slotId != null && (
                    c.expectedVersion == null ||
                        c.expectedVersion < 0
                )
            )
        ) {
            invalid("Pickup slot management input is invalid")
        }
    }

    private fun PickupSlotEntity.snapshot() =
        ManagedPickupSlot(
            id,
            storeId,
            startsAt,
            endsAt,
            capacity,
            reservedCount,
            confirmedCount,
            version,
        )

    private fun ManagedPickupSlot.summary() =
        mapOf(
            "version" to version.toString(),
            "scheduleDigest" to digest(listOf(startsAt, endsAt, capacity)).chunked(2).joinToString(":"),
        )

    private fun digest(value: Any) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value)))

    private fun invalid(message: String): Nothing =
        throw DomainFailure(
            FailureCode.INVALID_REQUEST,
            message,
        )

    private fun conflict(message: String): Nothing =
        throw DomainFailure(
            FailureCode.RESOURCE_STATE_CONFLICT,
            message,
        )

    private data class Sort(
        val startsAt: Instant,
        val id: UUID,
    )

    private companion object {
        val ROLES =
            setOf(
                StoreActorRole.OWNER,
                StoreActorRole.STAFF,
            )
        val SORT =
            object : CursorSortAdapter<Sort> {
                override fun encode(sort: Sort) =
                    listOf(
                        sort.startsAt.toString(),
                        sort.id.toString(),
                    )

                override fun decode(values: List<String>): Sort? =
                    if (values.size != 2) {
                        null
                    } else {
                        runCatching {
                            Sort(
                                Instant.parse(values[0]),
                                UUID.fromString(values[1]),
                            )
                        }.getOrNull()
                    }
            }
    }
}

@Component
internal class PickupSlotCommandRetention(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {
    @Transactional
    @Scheduled(
        fixedDelayString = "\${beanflow.pickup-slot-command.retention.fixed-delay-ms:3600000}",
        initialDelayString = "\${beanflow.pickup-slot-command.retention.initial-delay-ms:3600000}",
    )
    fun cleanup() {
        jdbc.update(
            "DELETE FROM fulfillment_pickup_slot_command WHERE id IN (SELECT id FROM fulfillment_pickup_slot_command " +
                "WHERE created_at < ? ORDER BY created_at, id LIMIT 100 FOR UPDATE SKIP LOCKED)",
            Timestamp.from(clock.instant().minus(Duration.ofDays(90))),
        )
    }
}

@Validated
@RestController
@RequestMapping("/api/v1/stores/{storeId}/pickup-slot-management")
@PreAuthorize("hasRole('MERCHANT')")
internal class PickupSlotManagementController(
    private val service: PickupSlotManagementService,
) {
    @GetMapping
    fun list(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @RequestParam from: Instant,
        @RequestParam to: Instant,
        @RequestParam(required = false) @Size(
            min = 1,
            max = 2048,
        ) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ) = service.list(
        actor.actorId,
        storeId,
        from,
        to,
        cursor,
        limit,
    )

    @GetMapping("/{slotId}")
    fun get(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @PathVariable slotId: UUID,
    ) = service.get(
        actor.actorId,
        storeId,
        slotId,
    )

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(
            min = 8,
            max = 128,
        ) key: String,
        @Valid @RequestBody request: CreatePickupSlotRequest,
    ) = service.change(
        PickupSlotManagementCommand(
            actor.actorId,
            storeId,
            null,
            key,
            request.startsAt,
            request.endsAt,
            request.capacity,
            null,
            request.reason,
        ),
    )

    @PutMapping("/{slotId}")
    fun replace(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @PathVariable slotId: UUID,
        @RequestHeader("Idempotency-Key") @Size(
            min = 8,
            max = 128,
        ) key: String,
        @Valid @RequestBody request: ReplacePickupSlotRequest,
    ) = service.change(
        PickupSlotManagementCommand(
            actor.actorId,
            storeId,
            slotId,
            key,
            request.startsAt,
            request.endsAt,
            request.capacity,
            request.expectedVersion,
            request.reason,
        ),
    )
}

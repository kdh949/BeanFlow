package io.github.kdh949.beanflow.identity.internal

import com.fasterxml.jackson.annotation.JsonAnySetter
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.merchant.api.StorePolicyScopeOperations
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OperatorActor
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
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

internal data class AddStoreMembershipRequest(
    val accountId: UUID,
    val role: StoreActorRole,
    @field:NotBlank @field:Size(max = 500) val reason: String,
) {
    @JsonAnySetter fun rejectUnknown(
        name: String,
        value: Any?,
    ): Unit = throw IllegalArgumentException("Unknown membership field: $name")
}

internal data class ReplaceStoreMembershipRequest(
    val role: StoreActorRole,
    val status: StoreMembershipStatus,
    @field:Min(0) val expectedVersion: Long,
    @field:NotBlank @field:Size(max = 500) val reason: String,
) {
    @JsonAnySetter fun rejectUnknown(
        name: String,
        value: Any?,
    ): Unit = throw IllegalArgumentException("Unknown membership field: $name")
}

internal data class ManagedStoreMembership(
    val membershipId: UUID,
    val accountId: UUID,
    val storeId: UUID,
    val role: StoreActorRole,
    val status: StoreMembershipStatus,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

internal data class ManagedStoreMembershipPage(
    val items: List<ManagedStoreMembership>,
    val nextCursor: String?,
)

internal data class StoreMembershipCommand(
    val operatorId: UUID,
    val storeId: UUID,
    val accountId: UUID,
    val key: String,
    val role: StoreActorRole,
    val status: StoreMembershipStatus,
    val expectedVersion: Long?,
    val reason: String,
    val now: Instant,
)

@Service
@Transactional
internal class StoreMembershipManagementService(
    private val memberships: StoreMembershipJpaRepository,
    private val accounts: MerchantAccountJpaRepository,
    private val stores: StorePolicyScopeOperations,
    private val grants: OperatorPermissionAuthorization,
    private val audits: AuditRecordOperations,
    private val cursors: SignedCursorCodec,
    private val correlation: CorrelationIdSource,
    private val mapper: ObjectMapper,
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {
    fun get(
        operatorId: UUID,
        storeId: UUID,
        accountId: UUID,
    ): ManagedStoreMembership {
        grants.requireActive(
            operatorId,
            OperatorPermission.STORE_MEMBERSHIP_READ,
        )
        stores.requireExisting(storeId)
        return memberships
            .findByActorIdAndStoreId(
                accountId,
                storeId,
            )?.snapshot() ?: missing()
    }

    fun list(
        operatorId: UUID,
        storeId: UUID,
        cursor: String?,
        limit: Int,
    ): ManagedStoreMembershipPage {
        grants.requireActive(
            operatorId,
            OperatorPermission.STORE_MEMBERSHIP_READ,
        )
        if (limit !in 1..100) invalid()
        stores.requireExisting(storeId)
        val scope =
            SignedCursorScope(
                "operator-store-memberships",
                digest("$operatorId:$storeId".toByteArray()),
                object : CursorSortAdapter<UUID> {
                    override fun encode(sort: UUID) = listOf(sort.toString())

                    override fun decode(values: List<String>): UUID? =
                        values.singleOrNull()?.let {
                            runCatching {
                                UUID.fromString(it)
                            }.getOrNull()
                        }
                },
            )
        val after =
            cursor?.let {
                cursors
                    .verify(
                        it,
                        scope,
                    ).sort
            }
        val args = mutableListOf<Any>(storeId)
        val where =
            if (after != null) {
                args += after
                " AND actor_id > ?"
            } else {
                ""
            }
        args += limit + 1
        val rows =
            jdbc.query(
                "SELECT id, actor_id, store_id, membership_role, status, version, created_at, updated_at " +
                    "FROM identity_store_membership WHERE store_id = ?$where ORDER BY actor_id LIMIT ?",
                ::map,
                *args.toTypedArray(),
            )
        val items = rows.take(limit)
        val next =
            if (rows.size > limit) {
                cursors.issue(
                    scope,
                    items.last().accountId,
                    clock.instant().plus(Duration.ofMinutes(15)),
                )
            } else {
                null
            }
        return ManagedStoreMembershipPage(
            items,
            next,
        )
    }

    fun change(command: StoreMembershipCommand): ManagedStoreMembership {
        val c = command.copy(now = command.now.truncatedTo(ChronoUnit.MICROS))
        grants.requireActive(
            c.operatorId,
            OperatorPermission.STORE_MEMBERSHIP_WRITE,
        )
        if (!validText(
                c.key,
                8,
                128,
            ) ||
            !validText(
                c.reason,
                1,
                500,
            ) ||

            (c.expectedVersion != null && c.expectedVersion < 0) ||

            (c.expectedVersion == null && c.status != StoreMembershipStatus.ACTIVE)
        ) {
            invalid()
        }
        val operation = if (c.expectedVersion == null) "ADD" else "REPLACE"
        val hash =
            digest(
                mapper.writeValueAsBytes(
                    listOf(
                        c.storeId,
                        c.accountId,
                        c.role,
                        c.status,
                        c.expectedVersion,
                        c.reason,
                    ),
                ),
            )
        jdbc.queryForObject(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            Any::class.java,
            "membership-command:${c.operatorId}:$operation:${c.key}",
        )
        jdbc
            .query(
                "SELECT payload_hash, response_json FROM identity_membership_command " +
                    "WHERE actor_id = ? AND operation = ? AND idempotency_key = ?",
                {
                    rs,
                    _,
                    ->
                    rs.getString("payload_hash") to rs.getString("response_json")
                },
                c.operatorId,
                operation,
                c.key,
            ).singleOrNull()
            ?.let {
                if (it.first != hash) {
                    throw DomainFailure(
                        FailureCode.IDEMPOTENCY_KEY_REUSED,
                        "Membership key has another payload",
                    )
                }
                return mapper.readValue(
                    it.second,
                    ManagedStoreMembership::class.java,
                )
            }
        accounts.findLockedById(c.accountId) ?: missing()
        stores.requireExisting(c.storeId)
        val existing =
            memberships.findByActorIdAndStoreIdForUpdate(
                c.accountId,
                c.storeId,
            )
        val before = existing?.snapshot()
        val row =
            if (c.expectedVersion == null) {
                if (existing != null) conflict("Membership already exists; use its version to change or reactivate it")
                StoreMembershipEntity(
                    UUID.randomUUID(),
                    c.accountId,
                    c.storeId,
                    c.role,
                    StoreMembershipStatus.ACTIVE,
                    c.now,
                    c.now,
                )
            } else {
                val target = existing ?: missing()
                if (target.version != c.expectedVersion) conflict("Membership version is stale")
                target.replace(
                    c.role,
                    c.status,
                    c.now,
                )
                target
            }
        memberships.saveAndFlush(row)
        val response = row.snapshot()
        val commandId = UUID.randomUUID()
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = c.operatorId.toString(),
                    actorType = AuditActorType.PLATFORM_OPERATOR,
                    category = AuditCategory.OPERATIONS_POLICY,
                    action = if (operation == "ADD") "STORE_MEMBERSHIP_ADDED" else "STORE_MEMBERSHIP_REPLACED",
                    targetType = "StoreMembership",
                    targetId = row.id,
                    occurredAt = c.now,
                    reason = c.reason,
                    beforeSummary = before?.summary() ?: emptyMap(),
                    afterSummary = response.summary(),
                    correlationId = correlation.currentOrCreate(),
                    sourceReference = "store-membership:$commandId",
                ),
            ),
        )
        jdbc.update(
            "INSERT INTO identity_membership_command(id, actor_id, store_id, operation, idempotency_key, " +
                "payload_hash, response_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            commandId,
            c.operatorId,
            c.storeId,
            operation,
            c.key,
            hash,
            mapper.writeValueAsString(response),
            Timestamp.from(c.now),
        )
        return response
    }

    private fun StoreMembershipEntity.snapshot() =
        ManagedStoreMembership(
            id,
            actorId,
            storeId,
            membershipRole,
            status,
            version,
            createdAt,
            updatedAt,
        )

    private fun ManagedStoreMembership.summary() =
        mapOf(
            "role" to role.name,
            "status" to status.name,
            "version" to version.toString(),
        )

    private fun map(
        rs: ResultSet,
        row: Int,
    ) = ManagedStoreMembership(
        rs.getObject(
            "id",
            UUID::class.java,
        ),
        rs.getObject(
            "actor_id",
            UUID::class.java,
        ),
        rs.getObject(
            "store_id",
            UUID::class.java,
        ),
        StoreActorRole.valueOf(rs.getString("membership_role")),
        StoreMembershipStatus.valueOf(rs.getString("status")),
        rs.getLong("version"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
    )

    private fun digest(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun validText(
        value: String,
        min: Int,
        max: Int,
    ) = value.length in min..max && value.isNotBlank() && value == value.trim() && value.none(Char::isISOControl)

    private fun missing(): Nothing =
        throw DomainFailure(
            FailureCode.RESOURCE_NOT_FOUND,
            "Store account or membership was not found",
        )

    private fun invalid(): Nothing =
        throw DomainFailure(
            FailureCode.INVALID_REQUEST,
            "Membership request is invalid",
        )

    private fun conflict(message: String): Nothing =
        throw DomainFailure(
            FailureCode.RESOURCE_STATE_CONFLICT,
            message,
        )
}

@Validated
@RestController
@RequestMapping("/api/v1/operations/stores/{storeId}/memberships")
@PreAuthorize("hasRole('PLATFORM_OPERATOR')")
internal class StoreMembershipManagementController(
    private val service: StoreMembershipManagementService,
    private val clock: Clock,
) {
    @GetMapping
    fun list(
        actor: OperatorActor,
        @PathVariable storeId: UUID,
        @RequestParam(required = false) @Size(
            min = 1,
            max = 2048,
        ) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ) = service.list(
        actor.actorId,
        storeId,
        cursor,
        limit,
    )

    @GetMapping("/{accountId}")
    fun get(
        actor: OperatorActor,
        @PathVariable storeId: UUID,
        @PathVariable accountId: UUID,
    ) = service.get(
        actor.actorId,
        storeId,
        accountId,
    )

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun add(
        actor: OperatorActor,
        @PathVariable storeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(
            min = 8,
            max = 128,
        ) key: String,
        @Valid @RequestBody request: AddStoreMembershipRequest,
    ) = service.change(
        StoreMembershipCommand(
            actor.actorId,
            storeId,
            request.accountId,
            key,
            request.role,
            StoreMembershipStatus.ACTIVE,
            null,
            request.reason,
            clock.instant(),
        ),
    )

    @PutMapping("/{accountId}")
    fun replace(
        actor: OperatorActor,
        @PathVariable storeId: UUID,
        @PathVariable accountId: UUID,
        @RequestHeader("Idempotency-Key") @Size(
            min = 8,
            max = 128,
        ) key: String,
        @Valid @RequestBody request: ReplaceStoreMembershipRequest,
    ) = service.change(
        StoreMembershipCommand(
            actor.actorId,
            storeId,
            accountId,
            key,
            request.role,
            request.status,
            request.expectedVersion,
            request.reason,
            clock.instant(),
        ),
    )
}

@Component
internal class StoreMembershipCommandRetention(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {
    @Transactional
    @Scheduled(
        fixedDelayString = "\${beanflow.store-membership.retention.fixed-delay-ms:3600000}",
        initialDelayString = "\${beanflow.store-membership.retention.initial-delay-ms:3600000}",
    )
    fun cleanup() {
        jdbc.update(
            "DELETE FROM identity_membership_command WHERE id IN (SELECT id FROM identity_membership_command " +
                "WHERE created_at < ? ORDER BY created_at, id LIMIT 100 FOR UPDATE SKIP LOCKED)",
            Timestamp.from(clock.instant().minus(Duration.ofDays(90))),
        )
    }
}

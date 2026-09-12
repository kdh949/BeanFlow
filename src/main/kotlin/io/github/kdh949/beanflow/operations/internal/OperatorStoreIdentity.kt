package io.github.kdh949.beanflow.operations.internal

import com.fasterxml.jackson.annotation.JsonAnySetter
import io.github.kdh949.beanflow.merchant.api.RegionCatalogQueryOperations
import io.github.kdh949.beanflow.merchant.api.RegionSnapshot
import io.github.kdh949.beanflow.merchant.api.StoreIdentityCommand
import io.github.kdh949.beanflow.merchant.api.StoreIdentityOperations
import io.github.kdh949.beanflow.merchant.api.StoreIdentitySnapshot
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
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
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
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.HexFormat
import java.util.UUID

internal data class CreateStoreIdentityRequest(
    @field:NotBlank @field:Size(max = 200) val name: String,
    @field:DecimalMin("-90") @field:DecimalMax("90") val latitude: Double,
    @field:DecimalMin("-180") @field:DecimalMax("180") val longitude: Double,
    @field:Pattern(regexp = "[0-9]{10}") val regionCode: String,
    @field:NotBlank @field:Size(max = 500) val reason: String,
) {
    @JsonAnySetter fun rejectUnknown(
        name: String,
        value: Any?,
    ): Unit = throw IllegalArgumentException("Unknown store creation field: $name")
}

internal data class ReplaceStoreIdentityRequest(
    @field:NotBlank @field:Size(max = 200) val name: String,
    @field:DecimalMin("-90") @field:DecimalMax("90") val latitude: Double,
    @field:DecimalMin("-180") @field:DecimalMax("180") val longitude: Double,
    @field:Min(0) val expectedVersion: Long,
    @field:NotBlank @field:Size(max = 500) val reason: String,
) {
    @JsonAnySetter fun rejectUnknown(
        name: String,
        value: Any?,
    ): Unit = throw IllegalArgumentException("Unknown store identity field: $name")
}

internal data class OperatorStoreIdentityPage(
    val items: List<StoreIdentitySnapshot>,
    val nextCursor: String?,
)

internal enum class StoreTargetPurpose(
    val permission: OperatorPermission,
) {
    IDENTITY(OperatorPermission.STORE_IDENTITY_READ),
    TERMS(OperatorPermission.STORE_SETTLEMENT_TERMS_READ),
    MEMBERSHIP(OperatorPermission.STORE_MEMBERSHIP_READ),
    BRAND(OperatorPermission.STORE_BRAND_MANAGE),
    POINT_POLICY(OperatorPermission.POINT_ACCRUAL_POLICY_READ),
}

internal data class OperatorStoreTarget(
    val storeId: UUID,
    val name: String,
)

internal data class OperatorStoreTargetPage(
    val items: List<OperatorStoreTarget>,
    val nextCursor: String?,
)

internal data class OperatorStoreRegionPage(
    val items: List<RegionSnapshot>,
    val nextCursor: String?,
)

@Service
@Transactional
internal class OperatorStoreIdentityService(
    private val stores: StoreIdentityOperations,
    private val regions: RegionCatalogQueryOperations,
    private val grants: OperatorPermissionAuthorization,
    private val audits: AuditRecordOperations,
    private val cursors: SignedCursorCodec,
    private val correlation: CorrelationIdSource,
    private val clock: Clock,
) {
    fun get(
        actorId: UUID,
        storeId: UUID,
    ): StoreIdentitySnapshot {
        grants.requireActive(actorId, OperatorPermission.STORE_IDENTITY_READ)
        return stores.get(storeId)
    }

    fun list(
        actorId: UUID,
        query: String?,
        cursor: String?,
        limit: Int,
    ): OperatorStoreIdentityPage {
        grants.requireActive(actorId, OperatorPermission.STORE_IDENTITY_READ)
        return listPage(actorId, query, cursor, limit, "operator-store-identity")
    }

    fun targets(
        actorId: UUID,
        purpose: StoreTargetPurpose,
        query: String?,
        cursor: String?,
        limit: Int,
    ): OperatorStoreTargetPage {
        grants.requireActive(actorId, purpose.permission)
        val page = listPage(actorId, query, cursor, limit, "operator-store-targets:${purpose.name}")
        return OperatorStoreTargetPage(page.items.map { OperatorStoreTarget(it.storeId, it.name) }, page.nextCursor)
    }

    private fun listPage(
        actorId: UUID,
        query: String?,
        cursor: String?,
        limit: Int,
        endpoint: String,
    ): OperatorStoreIdentityPage {
        validateList(query, limit)
        val scope = scope(endpoint, actorId, query, 1)
        val after =
            cursor?.let { cursors.verify(it, scope).sort.single() }?.let {
                runCatching { UUID.fromString(it) }.getOrNull()
                    ?: throw DomainFailure(FailureCode.INVALID_REQUEST, "Store cursor is invalid")
            }
        val rows = stores.list(query, after, limit + 1)
        val items = rows.take(limit)
        return OperatorStoreIdentityPage(
            items,
            if (rows.size >
                limit
            ) {
                cursors.issue(scope, listOf(items.last().storeId.toString()), clock.instant().plus(Duration.ofMinutes(15)))
            } else {
                null
            },
        )
    }

    fun regions(
        actorId: UUID,
        query: String?,
        cursor: String?,
        limit: Int,
    ): OperatorStoreRegionPage {
        grants.requireActive(actorId, OperatorPermission.STORE_IDENTITY_READ)
        validateList(query, limit)
        val scope = scope("operator-store-regions", actorId, query, 2)
        val after = cursor?.let { cursors.verify(it, scope).sort }
        val page = regions.search(query, after?.get(0), after?.get(1), limit)
        val next =
            page.nextFullName?.let {
                cursors.issue(scope, listOf(it, requireNotNull(page.nextCode)), clock.instant().plus(Duration.ofMinutes(15)))
            }
        return OperatorStoreRegionPage(page.regions, next)
    }

    fun change(command: StoreIdentityCommand): StoreIdentitySnapshot {
        grants.requireActive(command.actorId, OperatorPermission.STORE_IDENTITY_WRITE)
        val change = stores.change(command)
        if (!change.replayed) {
            audits.appendAll(
                listOf(
                    AppendAuditRecordCommand(
                        actorId = command.actorId.toString(),
                        actorType = AuditActorType.PLATFORM_OPERATOR,
                        category = AuditCategory.OPERATIONS_POLICY,
                        action =
                            if (command.storeId ==
                                null
                            ) {
                                "STORE_CREATED"
                            } else {
                                "STORE_IDENTITY_REPLACED"
                            },
                        targetType = "Store",
                        targetId = change.snapshot.storeId,
                        occurredAt = command.now,
                        reason = command.reason,
                        beforeSummary = change.previous?.summary() ?: emptyMap(),
                        afterSummary = change.snapshot.summary(),
                        correlationId = correlation.currentOrCreate(),
                        sourceReference = "store-identity:${change.commandId}",
                    ),
                ),
            )
        }
        return change.snapshot
    }

    private fun StoreIdentitySnapshot.summary() =
        mapOf(
            "version" to version.toString(),
            "identityDigest" to
                HexFormat
                    .of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-256").digest(
                            listOf(name, latitude.toString(), longitude.toString(), regionCode).joinToString("\u0000").toByteArray(),
                        ),
                    ).chunked(2)
                    .joinToString(":"),
        )

    private fun validateList(
        query: String?,
        limit: Int,
    ) {
        if (limit !in 1..100 ||
            (query != null && (query.length > 200 || query.any(Char::isISOControl)))
        ) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Store list input is invalid")
        }
    }

    private fun scope(
        endpoint: String,
        actorId: UUID,
        query: String?,
        fields: Int,
    ) = SignedCursorScope(
        endpoint,
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("$actorId|${query?.trim().orEmpty()}".toByteArray())),
        object : CursorSortAdapter<List<String>> {
            override fun encode(sort: List<String>) = sort

            override fun decode(values: List<String>): List<String>? = values.takeIf { it.size == fields }
        },
    )
}

@Validated
@RestController
@RequestMapping("/api/v1/operations")
@PreAuthorize("hasRole('PLATFORM_OPERATOR')")
internal class OperatorStoreIdentityController(
    private val service: OperatorStoreIdentityService,
    private val clock: Clock,
) {
    @GetMapping("/stores")
    fun list(
        actor: OperatorActor,
        @RequestParam(required = false) @Size(max = 200) query: String?,
        @RequestParam(required = false) @Size(min = 1, max = 2048) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ) = service.list(actor.actorId, query, cursor, limit)

    @GetMapping("/store-targets")
    fun targets(
        actor: OperatorActor,
        @RequestParam purpose: StoreTargetPurpose,
        @RequestParam(required = false) @Size(max = 200) query: String?,
        @RequestParam(required = false) @Size(min = 1, max = 2048) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ) = service.targets(actor.actorId, purpose, query, cursor, limit)

    @GetMapping("/store-regions")
    fun regions(
        actor: OperatorActor,
        @RequestParam(required = false) @Size(max = 200) query: String?,
        @RequestParam(required = false) @Size(min = 1, max = 2048) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ) = service.regions(actor.actorId, query, cursor, limit)

    @GetMapping("/stores/{storeId}/identity")
    fun get(
        actor: OperatorActor,
        @PathVariable storeId: UUID,
    ) = service.get(actor.actorId, storeId)

    @PostMapping("/stores")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: OperatorActor,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: CreateStoreIdentityRequest,
    ) = service.change(
        StoreIdentityCommand(
            actor.actorId,
            key,
            null,
            request.name,
            request.latitude,
            request.longitude,
            request.regionCode,
            null,
            request.reason,
            clock.instant(),
        ),
    )

    @PutMapping("/stores/{storeId}/identity")
    fun replace(
        actor: OperatorActor,
        @PathVariable storeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: ReplaceStoreIdentityRequest,
    ) = service.change(
        StoreIdentityCommand(
            actor.actorId,
            key,
            storeId,
            request.name,
            request.latitude,
            request.longitude,
            null,
            request.expectedVersion,
            request.reason,
            clock.instant(),
        ),
    )
}

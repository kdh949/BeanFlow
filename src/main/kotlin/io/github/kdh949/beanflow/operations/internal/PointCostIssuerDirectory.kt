package io.github.kdh949.beanflow.operations.internal

import com.fasterxml.jackson.annotation.JsonAnySetter
import io.github.kdh949.beanflow.merchant.api.BrandStatus
import io.github.kdh949.beanflow.merchant.api.StoreBrandQueryOperations
import io.github.kdh949.beanflow.merchant.api.StoreIdentityOperations
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.operations.api.PointAccrualIssuerType
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.OperatorActor
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCallback
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.ResultSet
import java.sql.Timestamp
import java.text.Normalizer
import java.time.Clock
import java.time.Duration
import java.util.HexFormat
import java.util.Locale
import java.util.UUID

internal enum class PointCostIssuerPurpose { POLICY, ADJUSTMENT }

internal enum class PointCostIssuerSource { STORE_PROFILE, BRAND_PROFILE, REGISTERED_PLATFORM, GLOBAL_POLICY }

internal data class PointCostIssuerResource(
    val issuerType: PointAccrualIssuerType,
    val issuerReference: String,
    val displayName: String,
    val source: PointCostIssuerSource,
    val sourcePolicyVersion: Long? = null,
)

internal data class PointCostIssuerPage(
    val items: List<PointCostIssuerResource>,
    val nextCursor: String?,
    val canRegisterPlatform: Boolean,
)

internal data class RegisterPlatformPointCostOwnerRequest(
    @field:NotBlank @field:Size(max = 200) val name: String,
    @field:NotBlank @field:Size(max = 500) val reason: String,
) {
    @JsonAnySetter fun rejectUnknown(
        name: String,
        value: Any?,
    ): Unit = throw IllegalArgumentException("Unknown platform cost owner field: $name")
}

@Service
@Transactional
internal class PointCostIssuerDirectory(
    private val stores: StoreIdentityOperations,
    private val brands: StoreBrandQueryOperations,
    private val permissions: OperatorPermissionAuthorization,
    private val jdbc: JdbcTemplate,
    private val cursors: SignedCursorCodec,
    private val identifiers: IdentifierSource,
    private val audits: AuditRecordOperations,
    private val correlations: CorrelationIdSource,
    private val clock: Clock,
) {
    fun list(
        actorId: UUID,
        purpose: PointCostIssuerPurpose,
        type: PointAccrualIssuerType,
        query: String?,
        cursor: String?,
        limit: Int,
    ): PointCostIssuerPage {
        permissions.requireActive(
            actorId,
            if (purpose ==
                PointCostIssuerPurpose.POLICY
            ) {
                OperatorPermission.POINT_ACCRUAL_POLICY_WRITE
            } else {
                OperatorPermission.POINT_ADJUSTMENT
            },
        )
        if (limit !in 1..100 || (query != null && (query.length > 200 || query.any(Char::isISOControl)))) invalid()
        val search = query?.trim()?.takeIf { it.isNotEmpty() }
        val scope = scope("$actorId|$purpose|$type|${search.orEmpty()}", if (type == PointAccrualIssuerType.BRAND) 2 else 1)
        val after = cursor?.let { cursors.verify(it, scope).sort }
        val canRegister = permissions.hasActive(actorId, OperatorPermission.POINT_ACCRUAL_POLICY_WRITE)
        val (items, next) =
            when (type) {
                PointAccrualIssuerType.STORE -> {
                    val rows = stores.list(search, after?.single()?.let(UUID::fromString), limit + 1)
                    rows
                        .take(
                            limit,
                        ).map { PointCostIssuerResource(type, it.storeId.toString(), it.name, PointCostIssuerSource.STORE_PROFILE) } to
                        if (rows.size > limit) listOf(rows[limit - 1].storeId.toString()) else null
                }

                PointAccrualIssuerType.BRAND -> {
                    val page = brands.list(after?.get(0), after?.get(1)?.let(UUID::fromString), limit)
                    page.brands
                        .filter { it.status == BrandStatus.ACTIVE && (search == null || it.name.contains(search, ignoreCase = true)) }
                        .map { PointCostIssuerResource(type, it.brandId.toString(), it.name, PointCostIssuerSource.BRAND_PROFILE) } to
                        page.nextNormalizedName?.let { listOf(it, requireNotNull(page.nextBrandId).toString()) }
                }

                PointAccrualIssuerType.PLATFORM -> {
                    val args = mutableListOf<Any>(if (purpose == PointCostIssuerPurpose.ADJUSTMENT) 200 else 240)
                    val filters = mutableListOf("length(issuer_reference) <= ?")
                    if (search != null) {
                        filters += "position(lower(?) in lower(display_name)) > 0"
                        args += search
                    }
                    if (after != null) {
                        filters += "issuer_reference > ?"
                        args += after.single()
                    }
                    args += limit + 1
                    val rows =
                        jdbc.query(
                            """
                            WITH candidate AS (
                                SELECT 'platform:' || id::text AS issuer_reference, display_name, 'REGISTERED_PLATFORM' AS source, NULL::bigint AS policy_version_id
                                FROM operations_platform_point_cost_owner
                                UNION ALL
                                SELECT version.issuer_reference, '공통 정책의 플랫폼 비용 주체' AS display_name, 'GLOBAL_POLICY' AS source, version.policy_version_id
                                FROM operations_point_accrual_policy_head head
                                JOIN operations_point_accrual_policy_version version ON version.policy_version_id = head.policy_version_id
                                WHERE head.scope_type = 'GLOBAL' AND version.issuer_type = 'PLATFORM'
                                  AND NOT EXISTS (SELECT 1 FROM operations_platform_point_cost_owner owner WHERE 'platform:' || owner.id::text = version.issuer_reference)
                            )
                            SELECT * FROM candidate WHERE ${filters.joinToString(" AND ")} ORDER BY issuer_reference LIMIT ?
                            """.trimIndent(),
                            { rs, _ -> platformResource(rs) },
                            *args.toTypedArray(),
                        )
                    rows.take(limit) to if (rows.size > limit) listOf(rows[limit - 1].issuerReference) else null
                }
            }
        return PointCostIssuerPage(items, next?.let { cursors.issue(scope, it, clock.instant().plus(Duration.ofMinutes(15))) }, canRegister)
    }

    fun register(
        actorId: UUID,
        key: String,
        request: RegisterPlatformPointCostOwnerRequest,
    ): PointCostIssuerResource {
        permissions.requireActive(actorId, OperatorPermission.POINT_ACCRUAL_POLICY_WRITE)
        val name = request.name.trim()
        val normalized = Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val reason = request.reason.trim()
        val normalizedKey = key.trim()
        if (name.length !in 1..200 || normalized.length !in 1..200 || reason.length !in 1..500 || normalizedKey.length !in 8..128 ||
            listOf(name, reason, normalizedKey).any { it.any(Char::isISOControl) }
        ) {
            invalid()
        }
        // The grant serializes this actor; the name lock covers different actors registering the same owner.
        lockName(normalized)
        val hash = sha256("${name.length}:$name${reason.length}:$reason")
        val replay =
            jdbc
                .query(
                    "SELECT id, display_name, payload_hash FROM operations_platform_point_cost_owner WHERE actor_id = ? AND idempotency_key = ?",
                    {
                        rs,
                        _,
                        ->
                        rs.getString("payload_hash") to registered(rs.getObject("id", UUID::class.java), rs.getString("display_name"))
                    },
                    actorId,
                    normalizedKey,
                ).singleOrNull()
        if (replay != null) {
            if (replay.first != hash) throw DomainFailure(FailureCode.IDEMPOTENCY_KEY_REUSED, "Platform cost owner key was reused")
            return replay.second
        }
        if (jdbc.queryForObject(
                "SELECT count(*) FROM operations_platform_point_cost_owner WHERE normalized_name = ?",
                Long::class.java,
                normalized,
            ) !=
            0L
        ) {
            throw DomainFailure(FailureCode.RESOURCE_STATE_CONFLICT, "A platform cost owner with this name is already registered")
        }
        val id = identifiers.next()
        val now = clock.instant()
        jdbc.update(
            "INSERT INTO operations_platform_point_cost_owner (id, display_name, normalized_name, actor_id, idempotency_key, payload_hash, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            id,
            name,
            normalized,
            actorId,
            normalizedKey,
            hash,
            Timestamp.from(now),
        )
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = actorId.toString(),
                    actorType = AuditActorType.PLATFORM_OPERATOR,
                    category = AuditCategory.OPERATIONS_POLICY,
                    action = "PLATFORM_POINT_COST_OWNER_REGISTERED",
                    targetType = "PlatformPointCostOwner",
                    targetId = id,
                    occurredAt = now,
                    reason = reason,
                    afterSummary = mapOf("nameDigest" to sha256(name)),
                    correlationId = correlations.currentOrCreate(),
                    sourceReference = "platform-point-cost-owner:$id",
                ),
            ),
        )
        return registered(id, name)
    }

    private fun platformResource(rs: ResultSet) =
        PointCostIssuerResource(
            PointAccrualIssuerType.PLATFORM,
            rs.getString("issuer_reference"),
            rs.getString("display_name"),
            PointCostIssuerSource.valueOf(rs.getString("source")),
            rs.getLong("policy_version_id").let { if (rs.wasNull()) null else it },
        )

    private fun registered(
        id: UUID,
        name: String,
    ) = PointCostIssuerResource(PointAccrualIssuerType.PLATFORM, "platform:$id", name, PointCostIssuerSource.REGISTERED_PLATFORM)

    private fun scope(
        binding: String,
        fields: Int,
    ) = SignedCursorScope(
        "point-cost-issuers",
        sha256(binding),
        object : CursorSortAdapter<List<String>> {
            override fun encode(sort: List<String>) = sort

            override fun decode(values: List<String>) = values.takeIf { it.size == fields }
        },
    )

    private fun lockName(name: String) {
        val value = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest("platform-point-cost-owner:$name".toByteArray())).long
        jdbc.execute(
            "SELECT pg_advisory_xact_lock(?)",
            PreparedStatementCallback<Unit> { statement ->
                statement.setLong(1, value)
                statement.execute()
                Unit
            },
        )
    }

    private fun sha256(value: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    private fun invalid(): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, "Point cost owner input is invalid")
}

@Validated
@RestController
@PreAuthorize("hasRole('PLATFORM_OPERATOR')")
internal class PointCostIssuerController(
    private val service: PointCostIssuerDirectory,
) {
    @GetMapping("/api/v1/operations/point-cost-issuers")
    fun list(
        actor: OperatorActor,
        @RequestParam purpose: PointCostIssuerPurpose,
        @RequestParam type: PointAccrualIssuerType,
        @RequestParam(required = false) @Size(max = 200) query: String?,
        @RequestParam(required = false) @Size(max = 2048) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ): ResponseEntity<PointCostIssuerPage> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(actor.actorId, purpose, type, query, cursor, limit))

    @PostMapping("/api/v1/operations/platform-point-cost-owners")
    fun register(
        actor: OperatorActor,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: RegisterPlatformPointCostOwnerRequest,
    ): ResponseEntity<PointCostIssuerResource> =
        ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.register(actor.actorId, key, request))
}

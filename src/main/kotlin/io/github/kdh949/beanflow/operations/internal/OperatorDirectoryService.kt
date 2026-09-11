package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.OperatorDirectoryOperations
import io.github.kdh949.beanflow.operations.api.OperatorDisplay
import io.github.kdh949.beanflow.operations.api.OperatorDisplayState
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OperatorActor
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

internal enum class OperatorSelectionPurpose {
    CASE_FILTER,
    CASE_ASSIGNMENT,
    ORDER_CANCELLATION,
    PICKUP_RESCHEDULE,
    POST_ACCEPTANCE_RESOLUTION,
    COMPENSATION,
    PROFILE_CHANGE,
}

internal data class OperatorCandidate(
    val operatorId: UUID,
    val loginName: String,
    val observedAt: Instant,
)

internal data class OperatorDirectoryPage(
    val items: List<OperatorCandidate>,
    val nextCursor: String?,
    val missingProfileCount: Long,
)

@Service
@Transactional
internal class OperatorDirectoryService(
    private val jdbc: JdbcTemplate,
    private val permissions: OperatorPermissionAuthorization,
    private val cursors: SignedCursorCodec,
    private val clock: Clock,
) : OperatorDirectoryOperations {
    /** Only the authenticated boundary supplies these signed identity-provider claims. */
    fun observe(
        actorId: UUID,
        loginName: String?,
        issuedAt: Instant?,
    ): OperatorDisplay {
        if (loginName == null) return displays(setOf(actorId)).getValue(actorId)
        if (loginName.isBlank() || loginName.length > 200 || loginName.any(Char::isISOControl) || issuedAt == null) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Operator identity display claims are invalid")
        }
        jdbc.update(
            """
            INSERT INTO operations_operator_login_display (actor_id, login_name, token_issued_at, observed_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (actor_id) DO UPDATE SET login_name = excluded.login_name,
                token_issued_at = excluded.token_issued_at, observed_at = excluded.observed_at
            WHERE operations_operator_login_display.token_issued_at < excluded.token_issued_at
            """.trimIndent(),
            actorId,
            loginName,
            Timestamp.from(issuedAt),
            Timestamp.from(clock.instant()),
        )
        return displays(setOf(actorId)).getValue(actorId)
    }

    override fun displays(actorIds: Set<UUID>): Map<UUID, OperatorDisplay> {
        if (actorIds.isEmpty()) return emptyMap()
        val found =
            actorIds
                .chunked(100)
                .flatMap { ids ->
                    jdbc.query(
                        "SELECT actor_id, login_name, observed_at FROM operations_operator_login_display " +
                            "WHERE actor_id IN (${ids.joinToString(",") { "?" }})",
                        { rs, _ ->
                            rs.getObject("actor_id", UUID::class.java) to
                                OperatorDisplay(
                                    OperatorDisplayState.AVAILABLE,
                                    rs.getString("login_name"),
                                    rs.getTimestamp("observed_at").toInstant(),
                                )
                        },
                        *ids.toTypedArray(),
                    )
                }.toMap()
        return actorIds.associateWith { found[it] ?: OperatorDisplay(OperatorDisplayState.MISSING_PROFILE) }
    }

    fun list(
        actorId: UUID,
        purpose: OperatorSelectionPurpose,
        query: String?,
        cursor: String?,
        limit: Int,
    ): OperatorDirectoryPage {
        if (!permissions.hasActive(actorId, OperatorPermission.SUPPORT_CASE_READ)) {
            permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_ASSIGN)
        }
        if (limit !in 1..100 || (query?.length ?: 0) > 100) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Operator directory filter is invalid")
        }
        val normalized = query?.trim().orEmpty()
        val required = requiredPermissions(purpose)
        val grantParameters = required.joinToString(",") { "?" }
        val stateFilter = if (purpose == OperatorSelectionPurpose.CASE_FILTER) "" else "AND state = 'ACTIVE'"
        val eligible =
            """
            SELECT actor_id FROM operations_operator_permission_grant
            WHERE permission IN ($grantParameters) $stateFilter
            GROUP BY actor_id HAVING count(*) = ?
            """.trimIndent()
        val grantArgs: List<Any> = required.map { it.name } + required.size
        val binding = "$actorId|$purpose|$normalized".toByteArray()
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binding))
        val scope = SignedCursorScope("operator-directory", hash, SORT)
        val after = cursor?.let { cursors.verify(it, scope).sort }
        val conditions = mutableListOf("profile.actor_id IN ($eligible)")
        val args = grantArgs.toMutableList()
        if (normalized.isNotEmpty()) {
            conditions += "profile.login_name ILIKE ? ESCAPE '!'"
            args += "%${normalized.replace("!", "!!").replace("%", "!%").replace("_", "!_")}%"
        }
        if (after != null) {
            conditions += "profile.actor_id > ?"
            args += after
        }
        args += limit + 1
        val rows =
            jdbc.query(
                "SELECT profile.actor_id, profile.login_name, profile.observed_at FROM operations_operator_login_display profile " +
                    "WHERE ${conditions.joinToString(" AND ")} ORDER BY profile.actor_id LIMIT ?",
                { rs, _ ->
                    OperatorCandidate(
                        rs.getObject("actor_id", UUID::class.java),
                        rs.getString("login_name"),
                        rs.getTimestamp("observed_at").toInstant(),
                    )
                },
                *args.toTypedArray(),
            )
        val missing =
            jdbc.queryForObject(
                "SELECT count(*) FROM ($eligible) candidate WHERE NOT EXISTS " +
                    "(SELECT 1 FROM operations_operator_login_display profile WHERE profile.actor_id = candidate.actor_id)",
                Long::class.java,
                *grantArgs.toTypedArray(),
            ) ?: throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Operator directory count is unavailable")
        val page = rows.take(limit)
        val next =
            if (rows.size >
                limit
            ) {
                cursors.issue(scope, page.last().operatorId, clock.instant().plus(Duration.ofMinutes(15)))
            } else {
                null
            }
        return OperatorDirectoryPage(page, next, missing)
    }

    private fun requiredPermissions(purpose: OperatorSelectionPurpose): Set<OperatorPermission> {
        val permissions = mutableSetOf(OperatorPermission.SUPPORT_CASE_WRITE)
        if (purpose == OperatorSelectionPurpose.COMPENSATION) {
            permissions += OperatorPermission.SUPPORT_COMPENSATION_EXECUTE
        } else if (purpose !in setOf(OperatorSelectionPurpose.CASE_ASSIGNMENT, OperatorSelectionPurpose.CASE_FILTER)) {
            permissions += OperatorPermission.SUPPORT_ACTION_EXECUTE
            permissions +=
                when (purpose) {
                    OperatorSelectionPurpose.ORDER_CANCELLATION -> OperatorPermission.SUPPORT_ORDER_CANCEL
                    OperatorSelectionPurpose.PICKUP_RESCHEDULE -> OperatorPermission.SUPPORT_PICKUP_RESCHEDULE
                    OperatorSelectionPurpose.POST_ACCEPTANCE_RESOLUTION -> OperatorPermission.SUPPORT_RESOLUTION_EXECUTE
                    OperatorSelectionPurpose.PROFILE_CHANGE -> OperatorPermission.SUPPORT_PROFILE_R3_REQUEST
                    else -> error("Unexpected operator selection purpose")
                }
        }
        return permissions
    }

    private companion object {
        val SORT =
            object : CursorSortAdapter<UUID> {
                override fun encode(sort: UUID) = listOf(sort.toString())

                override fun decode(values: List<String>): UUID? {
                    val value = values.singleOrNull() ?: return null
                    return runCatching { UUID.fromString(value) }.getOrNull()
                }
            }
    }
}

@Validated
@RestController
@RequestMapping("/api/v1/operations/operator-directory")
@PreAuthorize("hasRole('PLATFORM_OPERATOR')")
internal class OperatorDirectoryController(
    private val directory: OperatorDirectoryService,
) {
    @GetMapping
    fun list(
        actor: OperatorActor,
        @RequestParam(defaultValue = "CASE_ASSIGNMENT") purpose: OperatorSelectionPurpose,
        @RequestParam(required = false) @Size(max = 100) query: String?,
        @RequestParam(required = false) @Size(min = 1, max = 2048) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(directory.list(actor.actorId, purpose, query, cursor, limit))
}

package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.ordering.api.GoodwillCompensationOrderOperations
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.OperatorActor
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import io.github.kdh949.beanflow.support.internal.domain.SupportCaseState
import io.github.kdh949.beanflow.support.internal.domain.SupportInquiryCategory
import io.github.kdh949.beanflow.support.internal.domain.VerificationActionScope
import io.github.kdh949.beanflow.support.internal.domain.VerificationPurpose
import io.github.kdh949.beanflow.support.internal.domain.VerificationState
import io.github.kdh949.beanflow.support.internal.domain.VerificationSubjectType
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

internal data class SupportCompensationIncidentResource(
    val incidentId: UUID,
    val category: SupportInquiryCategory,
    val occurredAt: Instant?,
    val createdAt: Instant,
    val source: String,
    val benefitIssued: Boolean,
)

internal data class SupportCompensationIncidentPage(
    val items: List<SupportCompensationIncidentResource>,
    val nextCursor: String?,
)

internal data class RegisterSupportCompensationIncidentRequest(
    val verificationSessionId: UUID,
    val orderId: UUID?,
    val occurredAt: Instant,
) : StrictSupportRequest

@Repository
internal class SupportCompensationIncidentRegistry(
    private val jdbc: JdbcTemplate,
) {
    /** Immutable registrations cannot be reused for another compensation customer or order. */
    fun requireBinding(
        incidentId: UUID,
        customerId: UUID,
        orderId: UUID?,
    ) {
        val bindings =
            jdbc.query(
                "SELECT customer_id, order_id FROM support_compensation_incident WHERE id = ?",
                { rs, _ -> rs.getObject("customer_id", UUID::class.java) to rs.getObject("order_id", UUID::class.java) },
                incidentId,
            )
        if (bindings.any { it.first != customerId || it.second != orderId }) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Incident belongs to a different customer or order")
        }
    }
}

@Service
@Transactional
internal class SupportCompensationIncidentDirectory(
    private val cases: SupportCaseJpaRepository,
    private val links: SupportCaseSubjectLinkJpaRepository,
    private val sessions: VerificationSessionJpaRepository,
    private val permissions: OperatorPermissionAuthorization,
    private val orders: GoodwillCompensationOrderOperations,
    private val commandLock: SupportCaseCommandLock,
    private val jdbc: JdbcTemplate,
    private val identifiers: IdentifierSource,
    private val audits: AuditRecordOperations,
    private val correlations: CorrelationIdSource,
    private val cursors: SignedCursorCodec,
    private val clock: Clock,
) {
    fun list(
        actorId: UUID,
        caseId: UUID,
        sessionId: UUID,
        orderId: UUID?,
        cursor: String?,
        limit: Int,
        incidentId: UUID?,
    ): SupportCompensationIncidentPage {
        if (limit !in 1..100) invalid()
        authorizeCase(actorId, caseId)
        val customerId = requireContext(actorId, caseId, sessionId, orderId)
        val scope = scope("$actorId|$caseId|$sessionId|$customerId|$orderId|$incidentId")
        val after = cursor?.let { cursors.verify(it, scope).sort }
        val args = mutableListOf<Any?>(customerId, orderId, customerId, orderId)
        val filters = mutableListOf<String>()
        if (after != null) {
            filters += "candidate.id > ?"
            args += after
        }
        if (incidentId != null) {
            filters += "candidate.id = ?"
            args += incidentId
        }
        val afterClause = if (filters.isEmpty()) "" else " WHERE " + filters.joinToString(" AND ")
        args += limit + 1
        val rows =
            jdbc.query(
                """
                WITH legacy AS (
                    SELECT DISTINCT ON (request.incident_id) request.incident_id AS id, support_case.category,
                           NULL::timestamptz AS occurred_at, request.created_at, 'EXISTING_COMPENSATION' AS source
                    FROM support_compensation_request request JOIN support_case ON support_case.id = request.support_case_id
                    WHERE request.customer_id = ? AND request.order_id IS NOT DISTINCT FROM ?::uuid
                      AND NOT EXISTS (SELECT 1 FROM support_compensation_incident registered WHERE registered.id = request.incident_id)
                    ORDER BY request.incident_id, request.created_at, request.id
                ), candidate AS (
                    SELECT id, category, occurred_at, created_at, 'REGISTERED' AS source FROM support_compensation_incident
                    WHERE customer_id = ? AND order_id IS NOT DISTINCT FROM ?::uuid
                    UNION ALL SELECT * FROM legacy
                )
                SELECT candidate.*, EXISTS (SELECT 1 FROM support_compensation_terminal_benefit terminal WHERE terminal.incident_id = candidate.id) AS benefit_issued
                FROM candidate $afterClause ORDER BY candidate.id LIMIT ?
                """.trimIndent(),
                { rs, _ -> resource(rs) },
                *args.toTypedArray(),
            )
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = actorId.toString(),
                    actorType = AuditActorType.PLATFORM_OPERATOR,
                    category = AuditCategory.OPERATIONS_POLICY,
                    action = "SUPPORT_COMPENSATION_INCIDENTS_READ",
                    targetType = "SupportCase",
                    targetId = caseId,
                    occurredAt = clock.instant(),
                    reason = "Compensation incident selection",
                    afterSummary =
                        mapOf("returnedCount" to rows.take(limit).size.toString()),
                    correlationId = correlations.currentOrCreate(),
                    sourceReference = "support-compensation-incidents-read:${identifiers.next()}",
                ),
            ),
        )
        val items = rows.take(limit)
        return SupportCompensationIncidentPage(
            items,
            if (rows.size >
                limit
            ) {
                cursors.issue(scope, items.last().incidentId, clock.instant().plus(Duration.ofMinutes(15)))
            } else {
                null
            },
        )
    }

    fun register(
        actorId: UUID,
        caseId: UUID,
        key: String,
        request: RegisterSupportCompensationIncidentRequest,
    ): SupportCompensationIncidentResource {
        val normalizedKey = key.trim()
        if (normalizedKey.length !in 8..128 || normalizedKey.any(Char::isISOControl)) invalid()
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_COMPENSATION_REQUEST)
        commandLock.lock(caseId, actorId, "REGISTER_COMPENSATION_INCIDENT", normalizedKey)
        val supportCase = authorizeCase(actorId, caseId)
        val hash = sha256("$caseId|${request.verificationSessionId}|${request.orderId}|${request.occurredAt}")
        val replay =
            jdbc
                .query(
                    "SELECT *, 'REGISTERED' AS source, false AS benefit_issued FROM support_compensation_incident WHERE actor_id = ? AND idempotency_key = ?",
                    { rs, _ -> rs.getString("payload_hash") to resource(rs) },
                    actorId,
                    normalizedKey,
                ).singleOrNull()
        if (replay != null) {
            if (replay.first != hash) throw DomainFailure(FailureCode.IDEMPOTENCY_KEY_REUSED, "Incident registration key was reused")
            return replay.second
        }
        val customerId = requireContext(actorId, caseId, request.verificationSessionId, request.orderId)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        if (request.occurredAt.isAfter(now)) invalid()
        val occurredAt = request.occurredAt.truncatedTo(ChronoUnit.MICROS)
        val id = identifiers.next()
        jdbc.update(
            """INSERT INTO support_compensation_incident
            (id, origin_case_id, customer_id, order_id, category, occurred_at, created_at, actor_id, idempotency_key, payload_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            id,
            caseId,
            customerId,
            request.orderId,
            supportCase.category.name,
            Timestamp.from(occurredAt),
            Timestamp.from(now),
            actorId,
            normalizedKey,
            hash,
        )
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = actorId.toString(),
                    actorType = AuditActorType.PLATFORM_OPERATOR,
                    category = AuditCategory.OPERATIONS_POLICY,
                    action = "SUPPORT_COMPENSATION_INCIDENT_REGISTERED",
                    targetType = "SupportCompensationIncident",
                    targetId = id,
                    occurredAt = now,
                    reason = "Distinct compensation incident registration",
                    afterSummary =
                        mapOf("category" to supportCase.category.name),
                    correlationId = correlations.currentOrCreate(),
                    sourceReference = "support-compensation-incident:$id",
                ),
            ),
        )
        return SupportCompensationIncidentResource(id, supportCase.category, occurredAt, now, "REGISTERED", false)
    }

    private fun authorizeCase(
        actorId: UUID,
        caseId: UUID,
    ): SupportCaseEntity {
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_COMPENSATION_REQUEST)
        val supportCase = cases.findLockedById(caseId) ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Case is missing")
        if (supportCase.currentAssigneeId != actorId ||
            supportCase.state !in setOf(SupportCaseState.OPEN, SupportCaseState.IN_PROGRESS, SupportCaseState.WAITING)
        ) {
            denied()
        }
        return supportCase
    }

    private fun requireContext(
        actorId: UUID,
        caseId: UUID,
        sessionId: UUID,
        orderId: UUID?,
    ): UUID {
        val session = sessions.findLockedById(sessionId) ?: denied()
        if (session.actorId != actorId || session.supportCaseId != caseId || session.subjectType != VerificationSubjectType.CUSTOMER ||
            session.state != VerificationState.VERIFIED || session.actionScope != VerificationActionScope.SUPPORT_ACTION ||
            session.purpose != VerificationPurpose.CASE_RESOLUTION || !clock.instant().isBefore(session.expiresAt)
        ) {
            denied()
        }
        val link = links.findByIdAndSupportCaseId(session.subjectLinkId, caseId) ?: denied()
        if (link.unlinkedAt != null || link.subjectType != SupportSubjectType.CUSTOMER || link.subjectId != session.subjectId) denied()
        if (orderId != null) {
            if (!links.existsBySupportCaseIdAndSubjectTypeAndSubjectIdAndRelationshipAndUnlinkedAtIsNull(
                    caseId,
                    SupportSubjectType.ORDER,
                    orderId,
                    SupportSubjectRelationship.RELATED_ORDER,
                )
            ) {
                denied()
            }
            val order = orders.find(orderId) ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Order is missing")
            if (order.customerId != session.subjectId) denied()
        }
        return session.subjectId
    }

    private fun resource(rs: ResultSet) =
        SupportCompensationIncidentResource(
            rs.getObject("id", UUID::class.java),
            SupportInquiryCategory.valueOf(rs.getString("category")),
            rs.getTimestamp("occurred_at")?.toInstant(),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("source"),
            rs.getBoolean("benefit_issued"),
        )

    private fun scope(binding: String) =
        SignedCursorScope(
            "support-compensation-incidents",
            sha256(binding),
            object : CursorSortAdapter<UUID> {
                override fun encode(sort: UUID) = listOf(sort.toString())

                override fun decode(values: List<String>) = UUID.fromString(values.single())
            },
        )

    private fun sha256(value: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    private fun denied(): Nothing =
        throw DomainFailure(FailureCode.ACCESS_DENIED, "Current compensation case and verification access is required")

    private fun invalid(): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, "Incident registration or list input is invalid")
}

@Validated
@RestController
@PreAuthorize("isAuthenticated()")
internal class SupportCompensationIncidentController(
    private val service: SupportCompensationIncidentDirectory,
) {
    @GetMapping("/api/v1/support/cases/{caseId}/compensation-incidents")
    fun list(
        actor: OperatorActor,
        @PathVariable caseId: UUID,
        @RequestParam verificationSessionId: UUID,
        @RequestParam(required = false) orderId: UUID?,
        @RequestParam(required = false) @Size(max = 2048) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
        @RequestParam(required = false) incidentId: UUID?,
    ): ResponseEntity<SupportCompensationIncidentPage> =
        ResponseEntity
            .ok()
            .cacheControl(
                CacheControl.noStore(),
            ).body(service.list(actor.actorId, caseId, verificationSessionId, orderId, cursor, limit, incidentId))

    @PostMapping("/api/v1/support/cases/{caseId}/compensation-incidents")
    fun register(
        actor: OperatorActor,
        @PathVariable caseId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: RegisterSupportCompensationIncidentRequest,
    ): ResponseEntity<SupportCompensationIncidentResource> =
        ResponseEntity
            .status(
                HttpStatus.CREATED,
            ).cacheControl(CacheControl.noStore())
            .body(service.register(actor.actorId, caseId, key, request))
}

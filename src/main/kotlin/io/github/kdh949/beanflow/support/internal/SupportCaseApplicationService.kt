package io.github.kdh949.beanflow.support.internal

import com.fasterxml.jackson.annotation.JsonInclude
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.operations.api.RetentionPolicyCategory
import io.github.kdh949.beanflow.operations.api.RetentionPolicyOperations
import io.github.kdh949.beanflow.operations.api.RetentionPolicyVersionSnapshot
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import io.github.kdh949.beanflow.support.internal.domain.SupportCase
import io.github.kdh949.beanflow.support.internal.domain.SupportCaseMutation
import io.github.kdh949.beanflow.support.internal.domain.SupportCasePriority
import io.github.kdh949.beanflow.support.internal.domain.SupportCaseState
import io.github.kdh949.beanflow.support.internal.domain.SupportContentPolicy
import io.github.kdh949.beanflow.support.internal.domain.SupportInquiryCategory
import io.github.kdh949.beanflow.support.internal.domain.SupportRequesterType
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCallback
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

internal data class CreateSupportCaseCommand(
    val actorId: UUID,
    val idempotencyKey: String,
    val requesterType: SupportRequesterType,
    val requesterReference: String,
    val category: SupportInquiryCategory,
    val priority: SupportCasePriority,
    val externalReference: String?,
    val reason: String,
    val correlationId: String,
)

internal data class AssignSupportCaseCommand(
    val actorId: UUID,
    val caseId: UUID,
    val idempotencyKey: String,
    val assigneeId: UUID,
    val expectedVersion: Long,
    val reason: String,
    val correlationId: String,
)

internal data class TransitionSupportCaseCommand(
    val actorId: UUID,
    val caseId: UUID,
    val idempotencyKey: String,
    val targetState: SupportCaseState,
    val expectedVersion: Long,
    val reason: String,
    val correlationId: String,
)

internal data class AppendSupportInteractionCommand(
    val actorId: UUID,
    val caseId: UUID,
    val idempotencyKey: String,
    val channel: SupportInteractionChannel,
    val direction: SupportInteractionDirection,
    val occurredAt: Instant,
    val redactedSummary: String,
    val correlationId: String,
)

internal data class AppendSupportNoteCommand(
    val actorId: UUID,
    val caseId: UUID,
    val idempotencyKey: String,
    val content: String,
    val reason: String,
    val correlationId: String,
)

internal data class LinkSupportSubjectCommand(
    val actorId: UUID,
    val caseId: UUID,
    val idempotencyKey: String,
    val subjectType: SupportSubjectType,
    val subjectId: UUID,
    val relationship: SupportSubjectRelationship,
    val reason: String,
    val correlationId: String,
)

internal data class UnlinkSupportSubjectCommand(
    val actorId: UUID,
    val caseId: UUID,
    val linkId: UUID,
    val idempotencyKey: String,
    val expectedVersion: Long,
    val reason: String,
    val correlationId: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class SupportCaseResource(
    val caseId: UUID,
    val state: SupportCaseState,
    val priority: SupportCasePriority,
    val assigneeId: UUID,
    val version: Long,
    val openedAt: Instant,
    val closedAt: Instant?,
    val subjectLinks: List<SupportSubjectLinkResource>,
)

internal data class SupportCaseSummaryResource(
    val caseId: UUID,
    val state: SupportCaseState,
    val priority: SupportCasePriority,
    val assigneeId: UUID,
    val version: Long,
    val openedAt: Instant,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class SupportCasePageResource(
    val items: List<SupportCaseSummaryResource>,
    val nextCursor: String?,
)

internal data class SupportCaseAssignmentResource(
    val assignmentId: UUID,
    val assigneeId: UUID,
    val state: SupportCaseState,
    val caseVersion: Long,
    val assignedAt: Instant,
)

internal data class SupportCaseTransitionResource(
    val transitionId: UUID,
    val previousState: SupportCaseState,
    val currentState: SupportCaseState,
    val caseVersion: Long,
    val occurredAt: Instant,
)

internal data class SupportInteractionResource(
    val interactionId: UUID,
    val channel: SupportInteractionChannel,
    val direction: SupportInteractionDirection,
    val summary: String,
    val occurredAt: Instant,
    val recordedAt: Instant,
    val caseVersion: Long,
)

internal data class SupportNoteResource(
    val noteId: UUID,
    val summary: String,
    val createdAt: Instant,
    val caseVersion: Long,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class SupportSubjectLinkResource(
    val linkId: UUID,
    val subjectType: SupportSubjectType,
    val subjectId: UUID,
    val relationship: SupportSubjectRelationship,
    val linkedAt: Instant,
    val caseVersion: Long? = null,
)

internal data class SupportSubjectUnlinkResource(
    val linkId: UUID,
    val unlinkedAt: Instant,
    val caseVersion: Long,
)

@Component
internal class SupportCaseCommandLock(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun lock(
        caseId: UUID?,
        actorId: UUID,
        operation: String,
        idempotencyKey: String,
    ) {
        buildList {
            caseId?.let { add("support-case:$it") }
            add("support-case-idempotency:$actorId:$operation:$idempotencyKey")
        }.map(::lockKey).sorted().forEach { key ->
            jdbcTemplate.execute(
                "SELECT pg_advisory_xact_lock(?)",
                PreparedStatementCallback<Unit> { statement ->
                    statement.setLong(1, key)
                    statement.execute()
                    Unit
                },
            )
        }
    }

    private fun lockKey(source: String): Long =
        ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(source.toByteArray(StandardCharsets.UTF_8)), 0, Long.SIZE_BYTES).long
}

@Service
internal class SupportCaseApplicationService(
    private val cases: SupportCaseJpaRepository,
    private val assignments: SupportCaseAssignmentHistoryJpaRepository,
    private val states: SupportCaseStateHistoryJpaRepository,
    private val interactions: SupportCaseInteractionJpaRepository,
    private val notes: SupportCaseNoteJpaRepository,
    private val subjectLinks: SupportCaseSubjectLinkJpaRepository,
    private val idempotency: SupportCaseIdempotencyJpaRepository,
    private val queryRepository: SupportCaseQueryRepository,
    private val commandLock: SupportCaseCommandLock,
    private val cursors: SignedCursorCodec,
    private val permissions: OperatorPermissionAuthorization,
    private val retentionPolicies: RetentionPolicyOperations,
    private val audits: AuditRecordOperations,
    private val identifiers: IdentifierSource,
    private val objectMapper: ObjectMapper,
    private val commandPayloads: SupportCommandPayloadCanonicalizer,
    private val clock: Clock,
) {
    @Transactional
    fun create(command: CreateSupportCaseCommand): SupportCaseResource =
        persistenceBoundary {
            val normalized = command.normalized()
            permissions.requireActive(normalized.actorId, OperatorPermission.SUPPORT_CASE_WRITE)
            commandLock.lock(null, normalized.actorId, CREATE_CASE, normalized.idempotencyKey)
            replayOrExecute(
                actorId = normalized.actorId,
                operation = CREATE_CASE,
                idempotencyKey = normalized.idempotencyKey,
                payloadHash =
                    commandPayloadHash(
                        CREATE_CASE,
                        listOf(
                            payloadField("actorId", "uuid", normalized.actorId),
                            payloadField("requesterType", "enum", normalized.requesterType),
                            payloadField("requesterReference", "string", normalized.requesterReference),
                            payloadField("category", "enum", normalized.category),
                            payloadField("priority", "enum", normalized.priority),
                            payloadField("externalReference", "string", normalized.externalReference),
                            payloadField("reason", "string", normalized.reason),
                        ),
                    ),
                responseType = SupportCaseResource::class.java,
            ) {
                val now = clock.instant()
                val retention = supportRetention()
                val supportCase =
                    SupportCase.open(
                        id = identifiers.next(),
                        requesterType = normalized.requesterType,
                        requesterReference = normalized.requesterReference,
                        category = normalized.category,
                        priority = normalized.priority,
                        assigneeId = normalized.actorId,
                        reason = normalized.reason,
                        openedAt = now,
                    )
                val entity = supportCase.toEntity(normalized.externalReference, normalized.reason, retention)
                cases.saveAndFlush(entity)
                assignments.saveAndFlush(
                    SupportCaseAssignmentHistoryEntity(
                        id = identifiers.next(),
                        supportCaseId = entity.id,
                        sequence = 0,
                        previousAssigneeId = null,
                        currentAssigneeId = entity.currentAssigneeId,
                        actorId = normalized.actorId,
                        caseVersion = 0,
                        occurredAt = now,
                    ),
                )
                states.saveAndFlush(
                    SupportCaseStateHistoryEntity(
                        id = identifiers.next(),
                        supportCaseId = entity.id,
                        sequence = 0,
                        previousState = null,
                        currentState = SupportCaseState.OPEN,
                        actorId = normalized.actorId,
                        caseVersion = 0,
                        occurredAt = now,
                    ),
                )
                val response = entity.toResource(emptyList())
                audits.appendAll(listOf(entity.audit("SUPPORT_CASE_CREATED", now, normalized.actorId, normalized.correlationId)))
                response
            }
        }

    @Transactional
    fun assign(command: AssignSupportCaseCommand): SupportCaseAssignmentResource =
        persistenceBoundary {
            val normalized = command.normalized()
            permissions.requireActive(normalized.actorId, OperatorPermission.SUPPORT_CASE_ASSIGN)
            permissions.requireActive(normalized.assigneeId, OperatorPermission.SUPPORT_CASE_WRITE)
            commandLock.lock(normalized.caseId, normalized.actorId, ASSIGN_CASE, normalized.idempotencyKey)
            replayOrExecute(
                actorId = normalized.actorId,
                operation = ASSIGN_CASE,
                idempotencyKey = normalized.idempotencyKey,
                payloadHash =
                    commandPayloadHash(
                        ASSIGN_CASE,
                        listOf(
                            payloadField("actorId", "uuid", normalized.actorId),
                            payloadField("caseId", "uuid", normalized.caseId),
                            payloadField("assigneeId", "uuid", normalized.assigneeId),
                            payloadField("expectedVersion", "int64", normalized.expectedVersion),
                            payloadField("reason", "string", normalized.reason),
                        ),
                    ),
                responseType = SupportCaseAssignmentResource::class.java,
            ) {
                val entity = lockedCase(normalized.caseId)
                val aggregate = entity.toAggregate()
                requireVersion(aggregate.version, normalized.expectedVersion)
                val now = clock.instant()
                val change = aggregate.assign(normalized.assigneeId, normalized.actorId, now)
                entity.apply(aggregate)
                cases.saveAndFlush(entity)
                val assignmentId = identifiers.next()
                assignments.saveAndFlush(
                    SupportCaseAssignmentHistoryEntity(
                        id = assignmentId,
                        supportCaseId = entity.id,
                        sequence = assignments.nextSequence(entity.id),
                        previousAssigneeId = change.previousAssigneeId,
                        currentAssigneeId = change.currentAssigneeId,
                        actorId = normalized.actorId,
                        caseVersion = change.caseVersion,
                        occurredAt = now,
                    ),
                )
                audits.appendAll(listOf(entity.audit("SUPPORT_CASE_ASSIGNED", now, normalized.actorId, normalized.correlationId)))
                SupportCaseAssignmentResource(assignmentId, entity.currentAssigneeId, entity.state, entity.version, now)
            }
        }

    @Transactional
    fun transition(command: TransitionSupportCaseCommand): SupportCaseTransitionResource =
        persistenceBoundary {
            val normalized = command.normalized()
            permissions.requireActive(normalized.actorId, OperatorPermission.SUPPORT_CASE_WRITE)
            commandLock.lock(normalized.caseId, normalized.actorId, TRANSITION_CASE, normalized.idempotencyKey)
            replayOrExecute(
                actorId = normalized.actorId,
                operation = TRANSITION_CASE,
                idempotencyKey = normalized.idempotencyKey,
                payloadHash =
                    commandPayloadHash(
                        TRANSITION_CASE,
                        listOf(
                            payloadField("actorId", "uuid", normalized.actorId),
                            payloadField("caseId", "uuid", normalized.caseId),
                            payloadField("targetState", "enum", normalized.targetState),
                            payloadField("expectedVersion", "int64", normalized.expectedVersion),
                            payloadField("reason", "string", normalized.reason),
                        ),
                    ),
                responseType = SupportCaseTransitionResource::class.java,
            ) {
                val entity = lockedCaseAssignedTo(normalized.caseId, normalized.actorId)
                val aggregate = entity.toAggregate()
                requireVersion(aggregate.version, normalized.expectedVersion)
                val now = clock.instant()
                val change = aggregate.transitionTo(normalized.targetState, normalized.actorId, now)
                entity.apply(aggregate)
                cases.saveAndFlush(entity)
                val transitionId = identifiers.next()
                states.saveAndFlush(
                    SupportCaseStateHistoryEntity(
                        id = transitionId,
                        supportCaseId = entity.id,
                        sequence = states.nextSequence(entity.id),
                        previousState = change.previousState,
                        currentState = change.currentState,
                        actorId = normalized.actorId,
                        caseVersion = change.caseVersion,
                        occurredAt = now,
                    ),
                )
                audits.appendAll(listOf(entity.audit("SUPPORT_CASE_STATE_TRANSITIONED", now, normalized.actorId, normalized.correlationId)))
                SupportCaseTransitionResource(transitionId, change.previousState, change.currentState, change.caseVersion, now)
            }
        }

    @Transactional
    fun appendInteraction(command: AppendSupportInteractionCommand): SupportInteractionResource =
        persistenceBoundary {
            val normalized = command.normalized()
            permissions.requireActive(normalized.actorId, OperatorPermission.SUPPORT_CASE_WRITE)
            commandLock.lock(normalized.caseId, normalized.actorId, APPEND_INTERACTION, normalized.idempotencyKey)
            replayOrExecute(
                actorId = normalized.actorId,
                operation = APPEND_INTERACTION,
                idempotencyKey = normalized.idempotencyKey,
                payloadHash =
                    commandPayloadHash(
                        APPEND_INTERACTION,
                        listOf(
                            payloadField("actorId", "uuid", normalized.actorId),
                            payloadField("caseId", "uuid", normalized.caseId),
                            payloadField("channel", "enum", normalized.channel),
                            payloadField("direction", "enum", normalized.direction),
                            payloadField("occurredAt", "instant", normalized.occurredAt),
                            payloadField("redactedSummary", "string", normalized.redactedSummary),
                        ),
                    ),
                responseType = SupportInteractionResource::class.java,
            ) {
                val entity = lockedCaseAssignedTo(normalized.caseId, normalized.actorId)
                val now = clock.instant()
                if (normalized.occurredAt > now) invalid("Interaction time cannot be in the future")
                val aggregate = entity.toAggregate()
                val caseVersion = aggregate.recordMutation(SupportCaseMutation.INTERACTION, now)
                entity.apply(aggregate)
                val retention = supportRetention()
                val interactionId = identifiers.next()
                interactions.saveAndFlush(
                    SupportCaseInteractionEntity(
                        id = interactionId,
                        supportCaseId = entity.id,
                        sequence = interactions.nextSequence(entity.id),
                        channel = normalized.channel,
                        direction = normalized.direction,
                        redactedSummary = normalized.redactedSummary,
                        occurredAt = normalized.occurredAt,
                        recordedAt = now,
                        recordedByActorId = normalized.actorId,
                        retentionPolicyVersionId = retention.policyVersionId,
                    ),
                )
                cases.saveAndFlush(entity)
                audits.appendAll(
                    listOf(entity.audit("SUPPORT_CASE_INTERACTION_APPENDED", now, normalized.actorId, normalized.correlationId)),
                )
                SupportInteractionResource(
                    interactionId,
                    normalized.channel,
                    normalized.direction,
                    "INTERACTION_RECORDED",
                    normalized.occurredAt,
                    now,
                    caseVersion,
                )
            }
        }

    @Transactional
    fun appendNote(command: AppendSupportNoteCommand): SupportNoteResource =
        persistenceBoundary {
            val normalized = command.normalized()
            permissions.requireActive(normalized.actorId, OperatorPermission.SUPPORT_CASE_WRITE)
            commandLock.lock(normalized.caseId, normalized.actorId, APPEND_NOTE, normalized.idempotencyKey)
            replayOrExecute(
                actorId = normalized.actorId,
                operation = APPEND_NOTE,
                idempotencyKey = normalized.idempotencyKey,
                payloadHash =
                    commandPayloadHash(
                        APPEND_NOTE,
                        listOf(
                            payloadField("actorId", "uuid", normalized.actorId),
                            payloadField("caseId", "uuid", normalized.caseId),
                            payloadField("content", "string", normalized.content),
                            payloadField("reason", "string", normalized.reason),
                        ),
                    ),
                responseType = SupportNoteResource::class.java,
            ) {
                val entity = lockedCaseAssignedTo(normalized.caseId, normalized.actorId)
                val now = clock.instant()
                val aggregate = entity.toAggregate()
                val caseVersion = aggregate.recordMutation(SupportCaseMutation.NOTE, now)
                entity.apply(aggregate)
                val retention = supportRetention()
                val noteId = identifiers.next()
                notes.saveAndFlush(
                    SupportCaseNoteEntity(
                        id = noteId,
                        supportCaseId = entity.id,
                        sequence = notes.nextSequence(entity.id),
                        content = normalized.content,
                        reason = normalized.reason,
                        authorId = normalized.actorId,
                        createdAt = now,
                        retentionPolicyVersionId = retention.policyVersionId,
                    ),
                )
                cases.saveAndFlush(entity)
                audits.appendAll(listOf(entity.audit("SUPPORT_CASE_NOTE_APPENDED", now, normalized.actorId, normalized.correlationId)))
                SupportNoteResource(noteId, "NOTE_RECORDED", now, caseVersion)
            }
        }

    @Transactional
    fun linkSubject(command: LinkSupportSubjectCommand): SupportSubjectLinkResource =
        persistenceBoundary {
            val normalized = command.normalized()
            permissions.requireActive(normalized.actorId, OperatorPermission.SUPPORT_CASE_WRITE)
            commandLock.lock(normalized.caseId, normalized.actorId, LINK_SUBJECT, normalized.idempotencyKey)
            replayOrExecute(
                actorId = normalized.actorId,
                operation = LINK_SUBJECT,
                idempotencyKey = normalized.idempotencyKey,
                payloadHash =
                    commandPayloadHash(
                        LINK_SUBJECT,
                        listOf(
                            payloadField("actorId", "uuid", normalized.actorId),
                            payloadField("caseId", "uuid", normalized.caseId),
                            payloadField("subjectType", "enum", normalized.subjectType),
                            payloadField("subjectId", "uuid", normalized.subjectId),
                            payloadField("relationship", "enum", normalized.relationship),
                            payloadField("reason", "string", normalized.reason),
                        ),
                    ),
                responseType = SupportSubjectLinkResource::class.java,
            ) {
                val entity = lockedCaseAssignedTo(normalized.caseId, normalized.actorId)
                if (subjectLinks.existsBySupportCaseIdAndSubjectTypeAndSubjectIdAndRelationshipAndUnlinkedAtIsNull(
                        entity.id,
                        normalized.subjectType,
                        normalized.subjectId,
                        normalized.relationship,
                    )
                ) {
                    conflict("Active SupportCase subject link already exists")
                }
                val now = clock.instant()
                val aggregate = entity.toAggregate()
                val caseVersion = aggregate.recordMutation(SupportCaseMutation.SUBJECT_LINK, now)
                entity.apply(aggregate)
                val link =
                    SupportCaseSubjectLinkEntity(
                        id = identifiers.next(),
                        supportCaseId = entity.id,
                        subjectType = normalized.subjectType,
                        subjectId = normalized.subjectId,
                        relationship = normalized.relationship,
                        linkedByActorId = normalized.actorId,
                        reason = normalized.reason,
                        linkedAt = now,
                    )
                subjectLinks.saveAndFlush(link)
                cases.saveAndFlush(entity)
                audits.appendAll(listOf(entity.audit("SUPPORT_CASE_SUBJECT_LINKED", now, normalized.actorId, normalized.correlationId)))
                SupportSubjectLinkResource(link.id, link.subjectType, link.subjectId, link.relationship, now, caseVersion)
            }
        }

    @Transactional
    fun unlinkSubject(command: UnlinkSupportSubjectCommand): SupportSubjectUnlinkResource =
        persistenceBoundary {
            val normalized = command.normalized()
            permissions.requireActive(normalized.actorId, OperatorPermission.SUPPORT_CASE_WRITE)
            commandLock.lock(normalized.caseId, normalized.actorId, UNLINK_SUBJECT, normalized.idempotencyKey)
            replayOrExecute(
                actorId = normalized.actorId,
                operation = UNLINK_SUBJECT,
                idempotencyKey = normalized.idempotencyKey,
                payloadHash =
                    commandPayloadHash(
                        UNLINK_SUBJECT,
                        listOf(
                            payloadField("actorId", "uuid", normalized.actorId),
                            payloadField("caseId", "uuid", normalized.caseId),
                            payloadField("linkId", "uuid", normalized.linkId),
                            payloadField("expectedVersion", "int64", normalized.expectedVersion),
                            payloadField("reason", "string", normalized.reason),
                        ),
                    ),
                responseType = SupportSubjectUnlinkResource::class.java,
            ) {
                val entity = lockedCaseAssignedTo(normalized.caseId, normalized.actorId)
                val aggregate = entity.toAggregate()
                requireVersion(aggregate.version, normalized.expectedVersion)
                val link = subjectLinks.findByIdAndSupportCaseId(normalized.linkId, entity.id) ?: notFound()
                val now = clock.instant()
                val caseVersion = aggregate.recordMutation(SupportCaseMutation.SUBJECT_LINK, now)
                link.unlink(normalized.actorId, normalized.reason, now, caseVersion)
                entity.apply(aggregate)
                subjectLinks.saveAndFlush(link)
                cases.saveAndFlush(entity)
                audits.appendAll(listOf(entity.audit("SUPPORT_CASE_SUBJECT_UNLINKED", now, normalized.actorId, normalized.correlationId)))
                SupportSubjectUnlinkResource(link.id, now, caseVersion)
            }
        }

    @Transactional
    fun get(
        actorId: UUID,
        caseId: UUID,
    ): SupportCaseResource =
        persistenceBoundary {
            permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
            val entity = cases.findById(caseId).orElseThrow(::notFound)
            entity.toResource(subjectLinks.findBySupportCaseIdAndUnlinkedAtIsNullOrderByLinkedAtAsc(entity.id).map { it.toResource() })
        }

    @Transactional
    fun list(
        actorId: UUID,
        state: SupportCaseState?,
        assigneeId: UUID?,
        cursor: String?,
        limit: Int?,
    ): SupportCasePageResource =
        persistenceBoundary {
            permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
            val normalizedLimit = limit ?: DEFAULT_LIST_LIMIT
            if (normalizedLimit !in 1..MAX_LIST_LIMIT) invalid("SupportCase limit must be between 1 and 100")
            val scope = listCursorScope(state, assigneeId)
            val after = cursor?.let { cursors.verify(it, scope).sort }
            val fetched = queryRepository.findPage(state, assigneeId, after, normalizedLimit + 1)
            val items = fetched.take(normalizedLimit)
            val nextCursor =
                if (fetched.size > normalizedLimit) {
                    val last = items.last()
                    cursors.issue(scope, SupportCaseSort(last.openedAt, last.caseId), clock.instant().plus(CURSOR_TTL))
                } else {
                    null
                }
            SupportCasePageResource(
                items.map { SupportCaseSummaryResource(it.caseId, it.state, it.priority, it.assigneeId, it.version, it.openedAt) },
                nextCursor,
            )
        }

    private fun lockedCase(caseId: UUID): SupportCaseEntity = cases.findLockedById(caseId) ?: notFound()

    private fun lockedCaseAssignedTo(
        caseId: UUID,
        actorId: UUID,
    ): SupportCaseEntity {
        val entity = lockedCase(caseId)
        if (entity.currentAssigneeId != actorId) denied("Current SupportCase assignment is required")
        return entity
    }

    private fun supportRetention(): RetentionPolicyVersionSnapshot = retentionPolicies.current(RetentionPolicyCategory.SUPPORT_CASE)

    private fun listCursorScope(
        state: SupportCaseState?,
        assigneeId: UUID?,
    ): SignedCursorScope<SupportCaseSort> =
        SignedCursorScope(
            endpoint = LIST_CURSOR_ENDPOINT,
            filterHash = hash("$LIST_CURSOR_ENDPOINT|state=${state?.name.orEmpty()}|assigneeId=${assigneeId ?: ""}"),
            sortAdapter = SUPPORT_CASE_SORT_ADAPTER,
        )

    private fun requireVersion(
        actual: Long,
        expected: Long,
    ) {
        if (actual != expected) conflict("SupportCase version is stale")
    }

    private fun <T : Any> replayOrExecute(
        actorId: UUID,
        operation: String,
        idempotencyKey: String,
        payloadHash: String,
        responseType: Class<T>,
        execute: () -> T,
    ): T {
        idempotency.findByActorIdAndOperationAndIdempotencyKey(actorId, operation, idempotencyKey)?.let { existing ->
            if (existing.payloadHash != payloadHash) {
                throw DomainFailure(FailureCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was reused for another SupportCase command")
            }
            return objectMapper.readValue(existing.responseBody, responseType)
        }
        val response = execute()
        val recordedAt = clock.instant()
        idempotency.saveAndFlush(
            SupportCaseIdempotencyEntity(
                id = identifiers.next(),
                actorId = actorId,
                idempotencyKey = idempotencyKey,
                operation = operation,
                payloadHash = payloadHash,
                responseStatus = if (operation == CREATE_CASE) 201 else 200,
                responseBody = objectMapper.writeValueAsString(response),
                createdAt = recordedAt,
                retentionExpiresAt = recordedAt.plus(IDEMPOTENCY_RETENTION),
            ),
        )
        return response
    }

    private fun CreateSupportCaseCommand.normalized(): CreateSupportCaseCommand =
        copy(
            idempotencyKey = idempotencyKey.normalizedIdempotencyKey(),
            requesterReference = requesterReference.normalizedReference("Requester reference", 200),
            externalReference = externalReference?.normalizedReference("External reference", 200),
            reason = normalizedContent { SupportContentPolicy.reason(reason) },
            correlationId = correlationId.normalizedCorrelation(),
        )

    private fun AssignSupportCaseCommand.normalized(): AssignSupportCaseCommand =
        copy(
            idempotencyKey = idempotencyKey.normalizedIdempotencyKey(),
            reason =
                normalizedContent {
                    SupportContentPolicy.reason(reason)
                },
            correlationId = correlationId.normalizedCorrelation(),
        )

    private fun TransitionSupportCaseCommand.normalized(): TransitionSupportCaseCommand =
        copy(
            idempotencyKey = idempotencyKey.normalizedIdempotencyKey(),
            reason =
                normalizedContent {
                    SupportContentPolicy.reason(reason)
                },
            correlationId = correlationId.normalizedCorrelation(),
        )

    private fun AppendSupportInteractionCommand.normalized(): AppendSupportInteractionCommand =
        copy(
            idempotencyKey = idempotencyKey.normalizedIdempotencyKey(),
            redactedSummary =
                normalizedContent {
                    SupportContentPolicy.interactionSummary(redactedSummary)
                },
            correlationId = correlationId.normalizedCorrelation(),
        )

    private fun AppendSupportNoteCommand.normalized(): AppendSupportNoteCommand =
        copy(
            idempotencyKey = idempotencyKey.normalizedIdempotencyKey(),
            content =
                normalizedContent {
                    SupportContentPolicy.note(content)
                },
            reason = normalizedContent { SupportContentPolicy.reason(reason) },
            correlationId = correlationId.normalizedCorrelation(),
        )

    private fun LinkSupportSubjectCommand.normalized(): LinkSupportSubjectCommand =
        copy(
            idempotencyKey = idempotencyKey.normalizedIdempotencyKey(),
            reason =
                normalizedContent {
                    SupportContentPolicy.reason(reason)
                },
            correlationId = correlationId.normalizedCorrelation(),
        )

    private fun UnlinkSupportSubjectCommand.normalized(): UnlinkSupportSubjectCommand =
        copy(
            idempotencyKey = idempotencyKey.normalizedIdempotencyKey(),
            reason =
                normalizedContent {
                    SupportContentPolicy.reason(reason)
                },
            correlationId = correlationId.normalizedCorrelation(),
        )

    private fun String.normalizedIdempotencyKey(): String =
        trim().also { value -> if (value.length !in 8..128 || value.any(Char::isISOControl)) invalid("Idempotency-Key is invalid") }

    private fun String.normalizedReference(
        field: String,
        maxLength: Int,
    ): String =
        trim().also { value ->
            if (value.isEmpty() || value.length > maxLength ||
                value.any(Char::isISOControl)
            ) {
                invalid("$field is invalid")
            }
        }

    private fun String.normalizedCorrelation(): String =
        trim().also { value ->
            if (value.isEmpty() || value.length > 240 ||
                value.any(Char::isISOControl)
            ) {
                invalid("Correlation ID is invalid")
            }
        }

    private fun normalizedContent(block: () -> String): String =
        try {
            block()
        } catch (_: IllegalArgumentException) {
            invalid("Support content is not permitted")
        }

    private fun SupportCase.toEntity(
        externalReference: String?,
        reason: String,
        retention: RetentionPolicyVersionSnapshot,
    ) = SupportCaseEntity(
        id = id,
        externalReference = externalReference,
        requesterType = requesterType,
        requesterReference = requesterReference,
        category = category,
        priority = priority,
        reason = reason,
        state = state,
        currentAssigneeId = assigneeId,
        openedAt = openedAt,
        lastChangedAt = openedAt,
        closedAt = closedAt,
        version = version,
        retentionPolicyVersionId = retention.policyVersionId,
    )

    private fun SupportCaseEntity.toAggregate(): SupportCase =
        SupportCase.reconstitute(
            id = id,
            requesterType = requesterType,
            requesterReference = requesterReference,
            category = category,
            priority = priority,
            openedAt = openedAt,
            assigneeId = currentAssigneeId,
            state = state,
            version = version,
            closedAt = closedAt,
            lastChangedAt = lastChangedAt,
        )

    private fun SupportCaseEntity.apply(aggregate: SupportCase) {
        currentAssigneeId = aggregate.assigneeId
        state = aggregate.state
        version = aggregate.version
        closedAt = aggregate.closedAt
        lastChangedAt = aggregate.latestChangeAt
    }

    private fun SupportCaseEntity.toResource(links: List<SupportSubjectLinkResource>): SupportCaseResource =
        SupportCaseResource(id, state, priority, currentAssigneeId, version, openedAt, closedAt, links)

    private fun SupportCaseSubjectLinkEntity.toResource(): SupportSubjectLinkResource =
        SupportSubjectLinkResource(id, subjectType, subjectId, relationship, linkedAt)

    private fun SupportCaseEntity.audit(
        action: String,
        occurredAt: Instant,
        actorId: UUID,
        correlationId: String,
    ): AppendAuditRecordCommand =
        AppendAuditRecordCommand(
            actorId = actorId.toString(),
            actorType = AuditActorType.PLATFORM_OPERATOR,
            category = AuditCategory.OPERATIONS_POLICY,
            action = action,
            targetType = "SUPPORT_CASE",
            targetId = id,
            occurredAt = occurredAt,
            reason = "SUPPORT_CASE_LIFECYCLE",
            afterSummary = mapOf("event" to action, "state" to state.name, "caseVersion" to version.toString()),
            correlationId = correlationId,
            sourceReference = "support-case:$id:$action:$version",
        )

    private fun hash(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

    private fun commandPayloadHash(
        operation: String,
        fields: List<SupportCommandPayloadField>,
    ): String = hash(commandPayloads.canonical(operation, fields))

    private fun payloadField(
        name: String,
        type: String,
        value: Any?,
    ): SupportCommandPayloadField =
        SupportCommandPayloadField(
            name = name,
            type = type,
            value =
                when (value) {
                    null -> null
                    is Enum<*> -> value.name
                    else -> value.toString()
                },
        )

    private fun <T> persistenceBoundary(block: () -> T): T =
        try {
            block()
        } catch (failure: DomainFailure) {
            throw failure
        } catch (failure: DataAccessException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "SupportCase persistence is unavailable").also { it.initCause(failure) }
        } catch (failure: IllegalArgumentException) {
            throw DomainFailure(
                FailureCode.INVALID_REQUEST,
                "SupportCase input is invalid",
            ).also { it.initCause(failure) }
        } catch (failure: IllegalStateException) {
            throw DomainFailure(
                FailureCode.ORDER_STATE_CONFLICT,
                "SupportCase state does not allow this operation",
            ).also { it.initCause(failure) }
        }

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    private fun denied(message: String): Nothing = throw DomainFailure(FailureCode.ACCESS_DENIED, message)

    private fun notFound(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "SupportCase was not found")

    private fun conflict(message: String): Nothing = throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, message)

    private companion object {
        const val CREATE_CASE = "CREATE_CASE"
        const val ASSIGN_CASE = "ASSIGN_CASE"
        const val TRANSITION_CASE = "TRANSITION_CASE"
        const val APPEND_INTERACTION = "APPEND_INTERACTION"
        const val APPEND_NOTE = "APPEND_NOTE"
        const val LINK_SUBJECT = "LINK_SUBJECT"
        const val UNLINK_SUBJECT = "UNLINK_SUBJECT"
        const val DEFAULT_LIST_LIMIT = 20
        const val MAX_LIST_LIMIT = 100
        const val LIST_CURSOR_ENDPOINT = "support/cases"
        val CURSOR_TTL: Duration = Duration.ofMinutes(15)
        val IDEMPOTENCY_RETENTION: Duration = Duration.ofDays(90)
        val SUPPORT_CASE_SORT_ADAPTER =
            object : CursorSortAdapter<SupportCaseSort> {
                override fun encode(sort: SupportCaseSort): List<String> = listOf(sort.openedAt.toString(), sort.caseId.toString())

                override fun decode(values: List<String>): SupportCaseSort? {
                    if (values.size != 2) return null
                    return try {
                        val openedAt = Instant.parse(values[0])
                        val caseId = UUID.fromString(values[1])
                        if (openedAt.toString() != values[0] || caseId.toString() != values[1]) null else SupportCaseSort(openedAt, caseId)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
            }
    }
}

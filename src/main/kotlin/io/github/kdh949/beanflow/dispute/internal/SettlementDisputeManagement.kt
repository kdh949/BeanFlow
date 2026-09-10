package io.github.kdh949.beanflow.dispute.internal

import com.fasterxml.jackson.annotation.JsonAnySetter
import io.github.kdh949.beanflow.identity.api.StoreAccessOperations
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.MerchantActor
import io.github.kdh949.beanflow.shared.api.OperatorActor
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

internal enum class DisputeDecisionOutcome { ACCEPTED, REJECTED }

internal data class DisputeManagementRequest(
    @field:Min(0) val expectedVersion: Long,
    @field:NotBlank @field:Size(max = 500) val reason: String,
) {
    @JsonAnySetter
    fun rejectUnknown(
        name: String,
        value: Any?,
    ): Unit = throw IllegalArgumentException("Unknown dispute management field: $name")
}

internal data class DisputeDecisionRequest(
    val outcome: DisputeDecisionOutcome,
    @field:Min(0) val expectedVersion: Long,
    @field:NotBlank @field:Size(max = 500) val reason: String,
) {
    @JsonAnySetter
    fun rejectUnknown(
        name: String,
        value: Any?,
    ): Unit = throw IllegalArgumentException("Unknown dispute decision field: $name")
}

internal data class DisputeManagementResponse(
    val disputeId: UUID,
    val storeId: UUID,
    val settlementItemId: UUID,
    val state: SettlementDisputeState,
    val version: Long,
    val expectedAdjustmentKrw: Long,
    val heldAmountKrw: Long,
    val reason: String,
    val evidenceReferences: List<String>,
    val filedAt: Instant,
    val decidedAt: Instant?,
    val settlementAdjustmentId: UUID?,
    val pendingDecision: SettlementDisputeState?,
)

internal data class DisputeManagementCommand(
    val actorId: UUID,
    val storeId: UUID?,
    val disputeId: UUID,
    val operation: String,
    val key: String,
    val expectedVersion: Long,
    val reason: String,
    val correlationId: String,
)

@Service
internal class SettlementDisputeManagementService(
    private val repository: SettlementDisputeJpaRepository,
    private val decisions: SettlementDisputeDecisionService,
    private val grants: OperatorPermissionAuthorization,
    private val access: StoreAccessOperations,
    private val audits: AuditRecordOperations,
    private val locks: SettlementDisputeFilingLock,
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    @Transactional
    fun get(
        actorId: UUID,
        storeId: UUID?,
        disputeId: UUID,
    ): DisputeManagementResponse {
        authorize(actorId, storeId, false)
        val dispute = repository.findById(disputeId).orElseThrow { missing() }
        requireStore(dispute, storeId)
        return dispute.response()
    }

    /** Persist intent before Settlement commits its independently durable Adjustment. */
    fun execute(command: DisputeManagementCommand): DisputeManagementResponse {
        validate(command)
        val digest =
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    mapper.writeValueAsBytes(
                        listOf(command.storeId, command.disputeId, command.operation, command.expectedVersion, command.reason),
                    ),
                ),
            )
        val prepared =
            requireNotNull(
                transaction.execute {
                    authorize(command.actorId, command.storeId, true)
                    locks.lock(command.disputeId, command.actorId, "management:${command.operation}:${command.key}")
                    val existing =
                        jdbc
                            .query(
                                "SELECT id, payload_hash FROM settlement_dispute_management_command WHERE actor_id = ? AND operation = ? AND idempotency_key = ?",
                                { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("payload_hash") },
                                command.actorId,
                                command.operation,
                                command.key,
                            ).singleOrNull()
                    if (existing != null) {
                        if (existing.second !=
                            digest
                        ) {
                            throw DomainFailure(FailureCode.IDEMPOTENCY_KEY_REUSED, "Dispute management key has another payload")
                        }
                        return@execute existing.first
                    }
                    val dispute = locked(command.disputeId)
                    requireStore(dispute, command.storeId)
                    if (dispute.version != command.expectedVersion) conflict("Dispute version is stale")
                    if (command.operation == "REVIEW") {
                        if (dispute.state !in
                            setOf(SettlementDisputeState.FILED, SettlementDisputeState.UNDER_REVIEW)
                        ) {
                            conflict("Dispute is already decided")
                        }
                        decisions.startReview(dispute.id)
                    } else {
                        val outcome = SettlementDisputeState.valueOf(command.operation)
                        if (dispute.state != SettlementDisputeState.UNDER_REVIEW) conflict("Dispute is not under review")
                        if (dispute.decisionIntent != null && dispute.decisionIntent != outcome) conflict("A different decision is pending")
                        dispute.requestDecision(
                            outcome,
                            command.actorId,
                            actorType(command).name,
                            command.reason,
                            clock.instant(),
                            command.correlationId,
                        )
                        repository.flush()
                    }
                    val id = UUID.randomUUID()
                    val now = clock.instant()
                    val response = if (command.operation == "REVIEW") mapper.writeValueAsString(dispute.response()) else null
                    jdbc.update(
                        "INSERT INTO settlement_dispute_management_command(id, actor_id, operation, idempotency_key, dispute_id, payload_hash, response_json, created_at, completed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        id,
                        command.actorId,
                        command.operation,
                        command.key,
                        command.disputeId,
                        digest,
                        response,
                        Timestamp.from(now),
                        if (response ==
                            null
                        ) {
                            null
                        } else {
                            Timestamp.from(now)
                        },
                    )
                    audits.appendAll(
                        listOf(
                            AppendAuditRecordCommand(
                                actorId = command.actorId.toString(),
                                actorType = actorType(command),
                                category = AuditCategory.SETTLEMENT_AND_DISPUTE,
                                action = "SETTLEMENT_DISPUTE_MANAGEMENT_REQUESTED",
                                targetType = "SETTLEMENT_DISPUTE",
                                targetId = dispute.id,
                                occurredAt = now,
                                reason = command.reason,
                                afterSummary =
                                    mapOf(
                                        "operation" to command.operation,
                                        "version" to dispute.version.toString(),
                                    ),
                                correlationId = command.correlationId,
                                sourceReference = "dispute-management:$id",
                            ),
                        ),
                    )
                    id
                },
            )
        return requireNotNull(
            transaction.execute {
                authorize(command.actorId, command.storeId, true)
                locks.lock(command.disputeId, command.actorId, "management:${command.operation}:${command.key}")
                val replay =
                    jdbc.queryForObject(
                        "SELECT response_json FROM settlement_dispute_management_command WHERE id = ? FOR UPDATE",
                        String::class.java,
                        prepared,
                    )
                if (replay != null) {
                    // 최초 응답은 보존하되 판정 commit 이후 실패한 Case 완료는 다시 시도한다.
                    when (command.operation) {
                        "ACCEPTED" -> decisions.accept(command.disputeId, clock.instant())
                        "REJECTED" -> decisions.reject(command.disputeId, clock.instant())
                        "WITHDRAWN" -> decisions.withdraw(command.disputeId, clock.instant())
                    }
                    return@execute mapper.readValue(replay, DisputeManagementResponse::class.java)
                }
                val dispute = locked(command.disputeId)
                requireStore(dispute, command.storeId)
                when (command.operation) {
                    "ACCEPTED" -> decisions.accept(dispute.id, clock.instant())
                    "REJECTED" -> decisions.reject(dispute.id, clock.instant())
                    "WITHDRAWN" -> decisions.withdraw(dispute.id, clock.instant())
                    else -> conflict("Unsupported pending dispute command")
                }
                val response = dispute.response()
                jdbc.update(
                    "UPDATE settlement_dispute_management_command SET response_json = ?, completed_at = ? WHERE id = ?",
                    mapper.writeValueAsString(response),
                    Timestamp.from(clock.instant()),
                    prepared,
                )
                response
            },
        )
    }

    private fun validate(command: DisputeManagementCommand) {
        if (command.key.length !in 8..128 || command.key != command.key.trim() || command.key.any(Char::isISOControl) ||
            command.expectedVersion < 0 || command.reason.isBlank() || command.reason != command.reason.trim() ||
            command.reason.length > 500 ||
            command.reason.any(Char::isISOControl) ||
            command.correlationId.isBlank() || command.correlationId.length > 240 ||
            command.operation !in setOf("REVIEW", "ACCEPTED", "REJECTED", "WITHDRAWN")
        ) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Dispute management input is invalid")
        }
        if ((command.operation == "WITHDRAWN") !=
            (command.storeId != null)
        ) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Dispute command actor scope is invalid")
        }
    }

    private fun authorize(
        actorId: UUID,
        storeId: UUID?,
        write: Boolean,
    ) {
        if (storeId != null) {
            access.requireCatalogAccess(actorId, storeId, setOf(StoreActorRole.OWNER))
        } else {
            grants.requireActive(
                actorId,
                if (write) OperatorPermission.SETTLEMENT_DISPUTE_DECIDE else OperatorPermission.SETTLEMENT_DISPUTE_READ,
            )
        }
    }

    private fun actorType(command: DisputeManagementCommand) =
        if (command.storeId ==
            null
        ) {
            AuditActorType.PLATFORM_OPERATOR
        } else {
            AuditActorType.STORE_OWNER
        }

    private fun requireStore(
        dispute: SettlementDisputeEntity,
        storeId: UUID?,
    ) {
        if (storeId != null &&
            dispute.storeId != storeId
        ) {
            throw missing()
        }
    }

    private fun locked(id: UUID) = repository.findLockedById(id) ?: throw missing()

    private fun missing() = DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "SettlementDispute was not found")

    private fun conflict(message: String): Nothing = throw DomainFailure(FailureCode.RESOURCE_STATE_CONFLICT, message)

    private fun SettlementDisputeEntity.response() =
        DisputeManagementResponse(
            id,
            storeId,
            settlementItemId,
            state,
            version,
            expectedAdjustmentKrw,
            heldAmountKrw,
            reason,
            evidenceReferences,
            filedAt,
            decidedAt,
            settlementAdjustmentId,
            decisionIntent.takeIf {
                state ==
                    SettlementDisputeState.UNDER_REVIEW
            },
        )
}

@Component
internal class SettlementDisputeManagementRetention(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {
    @Transactional
    @Scheduled(
        fixedDelayString = "\${beanflow.dispute-management.retention.fixed-delay-ms:3600000}",
        initialDelayString = "\${beanflow.dispute-management.retention.initial-delay-ms:3600000}",
    )
    fun cleanup() {
        jdbc.update(
            "DELETE FROM settlement_dispute_management_command WHERE id IN (SELECT id FROM settlement_dispute_management_command WHERE completed_at < ? ORDER BY completed_at, id LIMIT 100 FOR UPDATE SKIP LOCKED)",
            Timestamp.from(clock.instant().minus(Duration.ofDays(90))),
        )
    }
}

@Validated
@RestController
@RequestMapping("/api/v1/operations/settlement-disputes")
@PreAuthorize("hasRole('PLATFORM_OPERATOR')")
internal class OperationsDisputeManagementController(
    private val service: SettlementDisputeManagementService,
    private val queries: SettlementDisputeQueryService,
    private val correlation: CorrelationIdSource,
) {
    @GetMapping
    fun list(
        actor: OperatorActor,
        @RequestParam storeId: UUID,
        @RequestParam(required = false) state: SettlementDisputeState?,
        @RequestParam(required = false) @Size(min = 1, max = 2048) cursor: String?,
        @RequestParam(required = false) @Min(1) @Max(100) limit: Int?,
    ) = queries.listOperations(ListStoreDisputesQuery(actor.actorId, storeId, state, cursor, limit))

    @GetMapping("/{disputeId}")
    fun get(
        actor: OperatorActor,
        @PathVariable disputeId: UUID,
    ) = service.get(actor.actorId, null, disputeId)

    @PostMapping("/{disputeId}/reviews")
    fun review(
        actor: OperatorActor,
        @PathVariable disputeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: DisputeManagementRequest,
    ) = service.execute(
        DisputeManagementCommand(
            actor.actorId,
            null,
            disputeId,
            "REVIEW",
            key,
            request.expectedVersion,
            request.reason,
            correlation.currentOrCreate(),
        ),
    )

    @PostMapping("/{disputeId}/decisions")
    fun decide(
        actor: OperatorActor,
        @PathVariable disputeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: DisputeDecisionRequest,
    ) = service.execute(
        DisputeManagementCommand(
            actor.actorId,
            null,
            disputeId,
            request.outcome.name,
            key,
            request.expectedVersion,
            request.reason,
            correlation.currentOrCreate(),
        ),
    )
}

@Validated
@RestController
@RequestMapping("/api/v1/stores/{storeId}/disputes/{disputeId}")
@PreAuthorize("hasRole('MERCHANT')")
internal class OwnerDisputeManagementController(
    private val service: SettlementDisputeManagementService,
    private val correlation: CorrelationIdSource,
) {
    @GetMapping
    fun get(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @PathVariable disputeId: UUID,
    ) = service.get(actor.actorId, storeId, disputeId)

    @PostMapping("/withdrawals")
    fun withdraw(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @PathVariable disputeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: DisputeManagementRequest,
    ) = service.execute(
        DisputeManagementCommand(
            actor.actorId,
            storeId,
            disputeId,
            "WITHDRAWN",
            key,
            request.expectedVersion,
            request.reason,
            correlation.currentOrCreate(),
        ),
    )
}

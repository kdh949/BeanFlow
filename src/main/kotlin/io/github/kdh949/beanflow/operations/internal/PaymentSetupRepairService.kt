package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.CustomerCancellationMissingRefundRepairOperations
import io.github.kdh949.beanflow.operations.api.CustomerCancellationMissingRefundRepairSnapshot
import io.github.kdh949.beanflow.operations.api.InspectCustomerCancellationMissingRefundCommand
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.operations.api.RecreateCustomerCancellationMissingRefundCommand
import io.github.kdh949.beanflow.operations.api.RecreateCustomerCancellationMissingRefundResult
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

internal data class ProposePaymentSetupRepairCommand(
    val actorId: UUID,
    val caseId: UUID,
    val idempotencyKey: String,
    val reason: String,
    val now: Instant,
)

internal data class DecidePaymentSetupRepairCommand(
    val actorId: UUID,
    val proposalId: UUID,
    val decision: RepairProposalDecision,
    val idempotencyKey: String,
    val reason: String,
    val now: Instant,
)

internal sealed interface PaymentSetupRepairDecisionOutcome {
    data class Succeeded(
        val proposal: RepairProposal,
    ) : PaymentSetupRepairDecisionOutcome

    data class Failed(
        val proposal: RepairProposal,
        val code: FailureCode,
        val message: String,
    ) : PaymentSetupRepairDecisionOutcome
}

@Service
internal class PaymentSetupRepairService(
    private val proposals: PaymentSetupRepairProposalJpaRepository,
    private val idempotencies: PaymentSetupRepairIdempotencyJpaRepository,
    private val cases: ReprocessingCaseJpaRepository,
    private val paymentRepair: CustomerCancellationMissingRefundRepairOperations,
    private val authorization: OperatorPermissionAuthorization,
    private val advisoryLock: DatabaseAdvisoryLock,
    private val audits: AuditRecordOperations,
    private val identifiers: IdentifierSource,
    private val correlations: CorrelationIdSource,
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val entityManager: EntityManager,
) {
    @Transactional
    fun propose(command: ProposePaymentSetupRepairCommand): RepairProposal {
        validate(command.idempotencyKey, command.reason)
        authorization.requireActive(command.actorId, OperatorPermission.PAYMENT_CANCELLATION_SETUP_REPAIR)
        advisoryLock.lock(idempotencyLock(command.actorId, PROPOSE, command.idempotencyKey))
        val hash = payloadHash(command.caseId.toString(), command.reason.trim())
        replay(command.actorId, PROPOSE, command.idempotencyKey, hash)?.let { outcome ->
            return when (outcome) {
                is PaymentSetupRepairDecisionOutcome.Succeeded -> outcome.proposal
                is PaymentSetupRepairDecisionOutcome.Failed -> throw DomainFailure(outcome.code, outcome.message)
            }
        }

        advisoryLock.lock("payment-setup-repair:case:${command.caseId}")
        val beanCase = requireOpenSetupCase(command.caseId)
        val owner = parseOwner(beanCase.ownerReference)
        if (!orderMatches(requireOrder(owner, false), owner)) {
            conflict(FailureCode.REPROCESSING_NOT_SAFE, "Setup case no longer matches the cancelled Order")
        }
        val snapshot =
            paymentRepair.inspect(
                InspectCustomerCancellationMissingRefundCommand(owner.orderId, owner.orderVersion),
            )
        if (proposals.findByCaseIdAndState(command.caseId, PENDING) != null) {
            conflict(FailureCode.REPROCESSING_NOT_SAFE, "An active repair proposal already exists for this case")
        }
        val correlationId = correlations.currentOrCreate()
        val entity =
            proposals.saveAndFlush(
                PaymentSetupRepairProposalEntity(
                    id = identifiers.next(),
                    caseId = beanCase.id,
                    caseVersion = beanCase.version,
                    orderId = owner.orderId,
                    cancellationOrderVersion = owner.orderVersion,
                    paymentId = snapshot.paymentId,
                    snapshotId = snapshot.snapshotId,
                    snapshotVersion = snapshot.snapshotVersion,
                    refundId = snapshot.refundId,
                    requestedAmountKrw = snapshot.requestedAmountKrw,
                    refundSourceFingerprint = snapshot.refundSourceFingerprint,
                    providerKeyFingerprint = snapshot.providerKeyFingerprint,
                    action = ACTION,
                    state = PENDING,
                    proposedBy = command.actorId,
                    proposalReason = command.reason.trim(),
                    correlationId = correlationId,
                    createdAt = command.now,
                    expiresAt = command.now.plus(PROPOSAL_TTL),
                    updatedAt = command.now,
                ),
            )
        appendAudit(
            entity,
            command.actorId,
            PROPOSED_ACTION,
            command.reason.trim(),
            TARGET_PROPOSAL,
            entity.id,
            emptyMap(),
            mapOf("state" to entity.state.name, "action" to entity.action.name),
            command.now,
        )
        val response = entity.toView()
        saveIdempotency(command.actorId, PROPOSE, command.idempotencyKey, hash, entity.id, response, null, command.now)
        afterCommit { proposalMetric(PENDING.name.lowercase()) }
        return response
    }

    @Transactional
    fun decide(command: DecidePaymentSetupRepairCommand): PaymentSetupRepairDecisionOutcome {
        validate(command.idempotencyKey, command.reason)
        authorization.requireActive(command.actorId, OperatorPermission.PAYMENT_CANCELLATION_SETUP_REPAIR)
        val observed = proposals.findById(command.proposalId).orElseThrow(::notFound)
        if (observed.proposedBy == command.actorId) {
            meterRegistry
                .counter("beanflow.operations.payment_setup.approval.count", "outcome", "self_decision_denied")
                .increment()
            conflict(
                FailureCode.REPROCESSING_APPROVER_MUST_DIFFER,
                "Repair proposal must be decided by a different active operator",
            )
        }
        entityManager.detach(observed)
        advisoryLock.lock(idempotencyLock(command.actorId, DECIDE, command.idempotencyKey))
        val hash = payloadHash(command.proposalId.toString(), command.decision.name, command.reason.trim())
        replay(command.actorId, DECIDE, command.idempotencyKey, hash)?.let { return it }

        if (command.now >= observed.expiresAt) {
            return expireFromDecision(command, hash)
        }
        if (command.decision == RepairProposalDecision.REJECT) {
            val terminal = transition(command, REJECTED, REJECTED_ACTION)
            saveIdempotency(command.actorId, DECIDE, command.idempotencyKey, hash, terminal.id, terminal.toView(), null, command.now)
            afterCommit { proposalMetric(REJECTED.name.lowercase()) }
            afterCommit {
                approvalMetric("rejected")
                proposalAge(terminal, command.now)
            }
            return PaymentSetupRepairDecisionOutcome.Succeeded(terminal.toView())
        }

        val order = OrderGuard(observed.orderId, observed.cancellationOrderVersion)
        if (!orderMatches(requireOrder(order, true), order)) {
            return terminalFailure(
                command,
                STALE,
                STALE_ACTION,
                FailureCode.REPROCESSING_PROPOSAL_STALE,
                "Repair proposal no longer matches the cancelled Order",
                hash,
            )
        }
        val currentCase = cases.findById(observed.caseId).orElse(null)
        if (!caseMatches(currentCase, observed)) {
            return terminalFailure(
                command,
                STALE,
                STALE_ACTION,
                FailureCode.REPROCESSING_PROPOSAL_STALE,
                "Repair proposal no longer matches the setup case",
                hash,
            )
        }
        currentCase?.let(entityManager::detach)
        val recreated =
            paymentRepair.recreateForLookup(
                RecreateCustomerCancellationMissingRefundCommand(
                    proposalId = observed.id,
                    expected = observed.repairSnapshot(),
                    now = command.now,
                ),
            )
        if (recreated is RecreateCustomerCancellationMissingRefundResult.Stale) {
            return terminalFailure(
                command,
                STALE,
                STALE_ACTION,
                FailureCode.REPROCESSING_PROPOSAL_STALE,
                "Repair proposal safety guards changed: ${recreated.errorCode}",
                hash,
            )
        }

        val lockedProposal = lockProposal(observed.id)
        requirePending(lockedProposal)
        val lockedCase = cases.findLockedById(observed.caseId) ?: rollbackStale("Setup case disappeared during approval")
        entityManager.refresh(lockedCase, LockModeType.PESSIMISTIC_WRITE)
        if (!caseMatches(lockedCase, observed)) {
            rollbackStale("Setup case changed during approval")
        }
        lockedProposal.state = EXECUTED
        lockedProposal.decidedBy = command.actorId
        lockedProposal.decisionReason = command.reason.trim()
        lockedProposal.decidedAt = command.now
        lockedProposal.updatedAt = command.now
        lockedCase.status = ReprocessingCaseStatus.RESOLVED
        lockedCase.resolution = RESOLUTION
        lockedCase.updatedAt = command.now
        appendAudit(
            lockedProposal,
            command.actorId,
            APPROVED_ACTION,
            command.reason.trim(),
            TARGET_PROPOSAL,
            lockedProposal.id,
            mapOf("state" to PENDING.name),
            mapOf("state" to EXECUTED.name),
            command.now,
        )
        appendAudit(
            lockedProposal,
            command.actorId,
            RECREATED_ACTION,
            command.reason.trim(),
            TARGET_REFUND,
            lockedProposal.refundId,
            mapOf("state" to "MISSING"),
            mapOf("state" to "RECONCILING", "nextAction" to "LOOKUP"),
            command.now,
        )
        val response = lockedProposal.toView()
        saveIdempotency(command.actorId, DECIDE, command.idempotencyKey, hash, lockedProposal.id, response, null, command.now)
        afterCommit {
            proposalMetric(EXECUTED.name.lowercase())
            proposalAge(lockedProposal, command.now)
            approvalMetric("executed")
            repairMetric("succeeded", "missing_refund")
            meterRegistry
                .counter("beanflow.operations.payment_setup.repair.lookup.count", "outcome", "scheduled")
                .increment()
        }
        return PaymentSetupRepairDecisionOutcome.Succeeded(response)
    }

    @Transactional
    fun expireDue(
        now: Instant,
        limit: Int,
    ): Int {
        require(limit in 1..100)
        val dueIds = proposals.findDueIds(now, PageRequest.of(0, limit))
        var expired = 0
        val expiredAges = mutableListOf<Double>()
        dueIds.forEach { proposalId ->
            val proposal = lockProposal(proposalId)
            if (proposal.state == PENDING && now >= proposal.expiresAt) {
                expire(proposal, now)
                expiredAges +=
                    Duration
                        .between(proposal.createdAt, now)
                        .seconds
                        .coerceAtLeast(0)
                        .toDouble()
                expired++
            }
        }
        if (expired > 0) {
            afterCommit {
                repeat(expired) { proposalMetric(EXPIRED.name.lowercase()) }
                expiredAges.forEach { age ->
                    meterRegistry
                        .summary("beanflow.operations.payment_setup.proposal.age.seconds", "state", "expired")
                        .record(age)
                }
            }
        }
        return expired
    }

    @Transactional
    fun purgeIdempotencyDue(
        now: Instant,
        limit: Int,
    ): Int {
        require(limit in 1..100)
        val dueIds = idempotencies.findDueIds(now, PageRequest.of(0, limit))
        if (dueIds.isNotEmpty()) idempotencies.deleteAllByIdInBatch(dueIds)
        return dueIds.size
    }

    private fun expireFromDecision(
        command: DecidePaymentSetupRepairCommand,
        hash: String,
    ): PaymentSetupRepairDecisionOutcome.Failed {
        val proposal = lockProposal(command.proposalId)
        requirePending(proposal)
        expire(proposal, command.now)
        val response = proposal.toView()
        saveIdempotency(
            command.actorId,
            DECIDE,
            command.idempotencyKey,
            hash,
            proposal.id,
            response,
            FailureCode.REPROCESSING_PROPOSAL_EXPIRED,
            command.now,
        )
        afterCommit {
            proposalMetric(EXPIRED.name.lowercase())
            proposalAge(proposal, command.now)
            approvalMetric("expired")
            repairMetric("rejected", FailureCode.REPROCESSING_PROPOSAL_EXPIRED.name.lowercase())
        }
        return PaymentSetupRepairDecisionOutcome.Failed(
            response,
            FailureCode.REPROCESSING_PROPOSAL_EXPIRED,
            "Repair proposal has expired",
        )
    }

    private fun expire(
        proposal: PaymentSetupRepairProposalEntity,
        now: Instant,
    ) {
        proposal.state = EXPIRED
        proposal.decidedBy = null
        proposal.decisionReason = SYSTEM_EXPIRY_REASON
        proposal.decidedAt = now
        proposal.updatedAt = now
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = SYSTEM_ACTOR,
                    actorType = AuditActorType.SYSTEM,
                    action = EXPIRED_ACTION,
                    targetType = TARGET_PROPOSAL,
                    targetId = proposal.id,
                    occurredAt = now,
                    reason = SYSTEM_EXPIRY_REASON,
                    beforeSummary = mapOf("state" to PENDING.name),
                    afterSummary = mapOf("state" to EXPIRED.name),
                    correlationId = proposal.correlationId,
                    sourceReference = sourceReference(proposal.id),
                ),
            ),
        )
    }

    private fun terminalFailure(
        command: DecidePaymentSetupRepairCommand,
        state: PaymentSetupRepairProposalState,
        auditAction: String,
        code: FailureCode,
        message: String,
        hash: String,
    ): PaymentSetupRepairDecisionOutcome.Failed {
        val terminal = transition(command, state, auditAction)
        val response = terminal.toView()
        saveIdempotency(command.actorId, DECIDE, command.idempotencyKey, hash, terminal.id, response, code, command.now)
        afterCommit {
            proposalMetric(state.name.lowercase())
            proposalAge(terminal, command.now)
            approvalMetric(state.name.lowercase())
            repairMetric("rejected", code.name.lowercase())
        }
        return PaymentSetupRepairDecisionOutcome.Failed(response, code, message)
    }

    private fun transition(
        command: DecidePaymentSetupRepairCommand,
        state: PaymentSetupRepairProposalState,
        auditAction: String,
    ): PaymentSetupRepairProposalEntity {
        val entity = lockProposal(command.proposalId)
        requirePending(entity)
        entity.state = state
        entity.decidedBy = command.actorId
        entity.decisionReason = command.reason.trim()
        entity.decidedAt = command.now
        entity.updatedAt = command.now
        appendAudit(
            entity,
            command.actorId,
            auditAction,
            command.reason.trim(),
            TARGET_PROPOSAL,
            entity.id,
            mapOf("state" to PENDING.name),
            mapOf("state" to state.name),
            command.now,
        )
        return entity
    }

    private fun requirePending(entity: PaymentSetupRepairProposalEntity) {
        if (entity.state != PENDING) {
            conflict(FailureCode.REPROCESSING_PROPOSAL_STALE, "Repair proposal is already terminal")
        }
    }

    private fun lockProposal(proposalId: UUID): PaymentSetupRepairProposalEntity {
        val proposal = proposals.findLockedById(proposalId) ?: notFound()
        entityManager.refresh(proposal, LockModeType.PESSIMISTIC_WRITE)
        return proposal
    }

    private fun requireOpenSetupCase(caseId: UUID): ReprocessingCaseEntity {
        val beanCase = cases.findById(caseId).orElseThrow(::notFound)
        if (beanCase.caseType != ReprocessingCaseType.PAYMENT_CANCELLATION_SETUP ||
            beanCase.status != ReprocessingCaseStatus.OPEN || beanCase.resolution != null
        ) {
            conflict(FailureCode.REPROCESSING_NOT_SAFE, "Only an open payment cancellation setup case can be repaired")
        }
        return beanCase
    }

    private fun caseMatches(
        beanCase: ReprocessingCaseEntity?,
        proposal: PaymentSetupRepairProposalEntity,
    ): Boolean =
        beanCase != null &&
            beanCase.id == proposal.caseId &&
            beanCase.version == proposal.caseVersion &&
            beanCase.caseType == ReprocessingCaseType.PAYMENT_CANCELLATION_SETUP &&
            beanCase.status == ReprocessingCaseStatus.OPEN &&
            beanCase.resolution == null &&
            parseOwnerOrNull(beanCase.ownerReference) == OrderGuard(proposal.orderId, proposal.cancellationOrderVersion)

    private fun requireOrder(
        expected: OrderGuard,
        lock: Boolean,
    ): OrderGuard? {
        val suffix = if (lock) " for update" else ""
        return jdbcTemplate
            .query(
                "select id, version from ordering_order " +
                    "where id = ? and state = 'CANCELLED' and cancellation_cause = 'CUSTOMER_REQUEST'" + suffix,
                { rs, _ -> OrderGuard(rs.getObject("id", UUID::class.java), rs.getLong("version")) },
                expected.orderId,
            ).singleOrNull()
    }

    private fun orderMatches(
        actual: OrderGuard?,
        expected: OrderGuard,
    ): Boolean = actual == expected

    private fun parseOwner(ownerReference: String): OrderGuard =
        parseOwnerOrNull(ownerReference)
            ?: conflict(FailureCode.REPROCESSING_NOT_SAFE, "Setup case owner reference is invalid")

    private fun parseOwnerOrNull(ownerReference: String): OrderGuard? {
        val match = OWNER_PATTERN.matchEntire(ownerReference) ?: return null
        return try {
            OrderGuard(UUID.fromString(match.groupValues[1]), match.groupValues[2].toLong())
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun replay(
        actorId: UUID,
        operation: PaymentSetupRepairIdempotencyOperation,
        idempotencyKey: String,
        hash: String,
    ): PaymentSetupRepairDecisionOutcome? {
        val existing = idempotencies.findByActorIdAndOperationAndIdempotencyKey(actorId, operation, idempotencyKey) ?: return null
        if (existing.payloadHash != hash) {
            conflict(FailureCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was reused with another repair command")
        }
        val response = objectMapper.readValue(existing.responseJson, RepairProposal::class.java)
        val failure = existing.failureCode?.let(FailureCode::valueOf)
        return if (failure == null) {
            PaymentSetupRepairDecisionOutcome.Succeeded(response)
        } else {
            PaymentSetupRepairDecisionOutcome.Failed(response, failure, replayMessage(failure))
        }
    }

    private fun saveIdempotency(
        actorId: UUID,
        operation: PaymentSetupRepairIdempotencyOperation,
        key: String,
        hash: String,
        proposalId: UUID,
        response: RepairProposal,
        failure: FailureCode?,
        now: Instant,
    ) {
        idempotencies.saveAndFlush(
            PaymentSetupRepairIdempotencyEntity(
                id = identifiers.next(),
                actorId = actorId,
                operation = operation,
                idempotencyKey = key,
                payloadHash = hash,
                proposalId = proposalId,
                responseJson = objectMapper.writeValueAsString(response),
                failureCode = failure?.name,
                createdAt = now,
                retentionExpiresAt = now.plus(IDEMPOTENCY_RETENTION),
            ),
        )
    }

    private fun appendAudit(
        proposal: PaymentSetupRepairProposalEntity,
        actorId: UUID,
        action: String,
        reason: String,
        targetType: String,
        targetId: UUID,
        before: Map<String, String>,
        after: Map<String, String>,
        now: Instant,
    ) {
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = actorId.toString(),
                    actorType = AuditActorType.PLATFORM_OPERATOR,
                    action = action,
                    targetType = targetType,
                    targetId = targetId,
                    occurredAt = now,
                    reason = reason,
                    beforeSummary = before,
                    afterSummary = after,
                    correlationId = proposal.correlationId,
                    sourceReference = sourceReference(proposal.id),
                ),
            ),
        )
    }

    private fun PaymentSetupRepairProposalEntity.repairSnapshot() =
        CustomerCancellationMissingRefundRepairSnapshot(
            orderId = orderId,
            cancellationOrderVersion = cancellationOrderVersion,
            paymentId = paymentId,
            snapshotId = snapshotId,
            snapshotVersion = snapshotVersion,
            refundId = refundId,
            requestedAmountKrw = requestedAmountKrw,
            refundSourceFingerprint = refundSourceFingerprint,
            providerKeyFingerprint = providerKeyFingerprint,
        )

    private fun PaymentSetupRepairProposalEntity.toView() =
        RepairProposal(
            proposalId = id,
            caseId = caseId,
            action = action,
            state = state,
            proposedBy = proposedBy,
            decidedBy = decidedBy,
            createdAt = createdAt,
            expiresAt = expiresAt,
            decidedAt = decidedAt,
            correlationId = correlationId,
        )

    private fun validate(
        key: String,
        reason: String,
    ) {
        if (key.length !in 8..128 || key.any(Char::isISOControl)) {
            invalid("Idempotency-Key must contain between 8 and 128 non-control characters")
        }
        if (reason.trim().length !in 1..500 || reason.any(Char::isISOControl)) {
            invalid("Repair reason must contain between 1 and 500 non-control characters")
        }
    }

    private fun payloadHash(vararg parts: String): String = sha256(parts.joinToString("\u0000"))

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

    private fun idempotencyLock(
        actorId: UUID,
        operation: PaymentSetupRepairIdempotencyOperation,
        key: String,
    ) = "payment-setup-repair:idempotency:$actorId:${operation.name}:${sha256(key)}"

    private fun sourceReference(proposalId: UUID) = "payment-setup-repair:$proposalId"

    private fun replayMessage(code: FailureCode): String =
        when (code) {
            FailureCode.REPROCESSING_PROPOSAL_EXPIRED -> "Repair proposal has expired"
            FailureCode.REPROCESSING_PROPOSAL_STALE -> "Repair proposal is stale"
            else -> "Repair decision failed"
        }

    private fun proposalMetric(state: String) {
        meterRegistry.counter("beanflow.operations.payment_setup.proposal.count", "state", state).increment()
    }

    private fun repairMetric(
        outcome: String,
        reason: String,
    ) {
        meterRegistry.counter("beanflow.operations.payment_setup.repair.count", "outcome", outcome, "reason", reason).increment()
    }

    private fun approvalMetric(outcome: String) {
        meterRegistry.counter("beanflow.operations.payment_setup.approval.count", "outcome", outcome).increment()
    }

    private fun proposalAge(
        proposal: PaymentSetupRepairProposalEntity,
        now: Instant,
    ) {
        meterRegistry
            .summary("beanflow.operations.payment_setup.proposal.age.seconds", "state", proposal.state.name.lowercase())
            .record(
                Duration
                    .between(proposal.createdAt, now)
                    .seconds
                    .coerceAtLeast(0)
                    .toDouble(),
            )
    }

    private fun afterCommit(action: () -> Unit) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = action()
            },
        )
    }

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    private fun notFound(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Repair resource was not found")

    private fun rollbackStale(message: String): Nothing = throw DomainFailure(FailureCode.REPROCESSING_PROPOSAL_STALE, message)

    private fun conflict(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)

    private data class OrderGuard(
        val orderId: UUID,
        val orderVersion: Long,
    )

    private companion object {
        val PROPOSE = PaymentSetupRepairIdempotencyOperation.PROPOSE
        val DECIDE = PaymentSetupRepairIdempotencyOperation.DECIDE
        val ACTION = PaymentSetupRepairAction.RECREATE_MISSING_CANCELLATION_REFUND
        val PENDING = PaymentSetupRepairProposalState.PENDING_APPROVAL
        val EXECUTED = PaymentSetupRepairProposalState.EXECUTED
        val REJECTED = PaymentSetupRepairProposalState.REJECTED
        val EXPIRED = PaymentSetupRepairProposalState.EXPIRED
        val STALE = PaymentSetupRepairProposalState.STALE
        val PROPOSAL_TTL: Duration = Duration.ofMinutes(30)
        val IDEMPOTENCY_RETENTION: Duration = Duration.ofDays(90)
        val OWNER_PATTERN = Regex("^order:([0-9a-fA-F-]{36}):customer-cancellation:([0-9]+):payment-setup$")
        const val RESOLUTION = "MISSING_REFUND_RECREATED_LOOKUP_REQUIRED"
        const val PROPOSED_ACTION = "PAYMENT_CANCELLATION_REPAIR_PROPOSED"
        const val APPROVED_ACTION = "PAYMENT_CANCELLATION_REPAIR_APPROVED_AND_EXECUTED"
        const val REJECTED_ACTION = "PAYMENT_CANCELLATION_REPAIR_REJECTED"
        const val EXPIRED_ACTION = "PAYMENT_CANCELLATION_REPAIR_EXPIRED"
        const val STALE_ACTION = "PAYMENT_CANCELLATION_REPAIR_STALE"
        const val RECREATED_ACTION = "PAYMENT_CANCELLATION_MISSING_REFUND_RECREATED"
        const val TARGET_PROPOSAL = "PAYMENT_SETUP_REPAIR_PROPOSAL"
        const val TARGET_REFUND = "REFUND"
        const val SYSTEM_ACTOR = "SYSTEM"
        const val SYSTEM_EXPIRY_REASON = "PROPOSAL_TTL_EXPIRED"
    }
}

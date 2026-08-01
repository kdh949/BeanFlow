package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.payment.api.ClaimedRefundPointRecovery
import io.github.kdh949.beanflow.payment.api.PointAccrualCompletionEligibility
import io.github.kdh949.beanflow.payment.api.PreparePointAccrualCompletionCommand
import io.github.kdh949.beanflow.payment.api.PreparedRefundPointRecovery
import io.github.kdh949.beanflow.payment.api.RecordPointAccrualNotApplicableCommand
import io.github.kdh949.beanflow.payment.api.RefundPointAccrualSnapshotSource
import io.github.kdh949.beanflow.payment.api.RefundPointAccrualUnit
import io.github.kdh949.beanflow.payment.api.RefundPointRecoveryOperations
import io.github.kdh949.beanflow.payment.api.RefundPointRecoveryResult
import io.github.kdh949.beanflow.payment.api.RefundPointUnitKey
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
internal class RefundPointRecoveryService(
    private val jdbcTemplate: JdbcTemplate,
    private val identifierSource: IdentifierSource,
    private val meterRegistry: MeterRegistry,
    @Value("\${beanflow.payment.point-recovery.claim-lease:PT1M}")
    private val claimLease: Duration,
) : RefundPointRecoveryOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    fun createWork(
        refund: RefundEntity,
        now: Instant,
    ) {
        val existing = workByRefundId(refund.id, lock = false)
        if (existing != null) {
            if (existing.orderId != refund.orderId || existing.refundSucceededAt != now) {
                conflict("Refund point recovery work source changed")
            }
            return
        }
        val outcome = outcome(refund.orderId, lock = false)
        val state = if (outcome?.outcomeState == OUTCOME_NOT_APPLICABLE) STATE_NOT_APPLICABLE else STATE_PENDING
        val nextAttemptAt = if (state == STATE_PENDING) now else null
        jdbcTemplate.update(
            """
            INSERT INTO payment_refund_point_recovery_work (
                id, refund_id, order_id, outcome_order_id, state, refund_succeeded_at,
                attempt_count, next_attempt_at, source_reference, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?)
            """.trimIndent(),
            identifierSource.next(),
            refund.id,
            refund.orderId,
            outcome?.orderId,
            state,
            Timestamp.from(now),
            nextAttemptAt?.let(Timestamp::from),
            "${refund.sourceReference}:earned-points-recovery",
            Timestamp.from(now),
            Timestamp.from(now),
        )
        metric("work_created", state.lowercase())
    }

    @Transactional
    override fun prepareCompletion(command: PreparePointAccrualCompletionCommand): PointAccrualCompletionEligibility {
        validateSnapshot(command.orderId, command.snapshotSchemaVersion, command.snapshotHash, command.units)
        if (unresolvedRefundCount(command.orderId) > 0) {
            dependency("Order completion has an unresolved partial Refund")
        }
        storeOutcome(
            OutcomeInput(
                command.orderId,
                OUTCOME_COMPLETED,
                ORDER_COMPLETED,
                command.completedAt,
                command.completionSourceReference,
                command.aggregateVersion,
                command.snapshotSchemaVersion,
                command.snapshotHash,
                command.processedAt,
            ),
        )
        if (missingRecoveryWorkCount(command.orderId) > 0) {
            dependency("Successful partial Refund recovery work is missing")
        }
        val source =
            SnapshotContext(
                command.orderId,
                command.completedAt,
                command.completionSourceReference,
                command.aggregateVersion,
                command.snapshotSchemaVersion,
                command.snapshotHash,
                command.units,
            )
        worksForOrder(command.orderId, lock = true).forEach { work ->
            when (work.state) {
                STATE_PENDING, STATE_ELIGIBILITY_PROCESSING, STATE_ELIGIBILITY_RETRY -> {
                    classify(work, source, command.processedAt, keepClaim = false)
                }

                STATE_READY, STATE_PROCESSING, STATE_RETRY_SCHEDULED, STATE_SUCCEEDED,
                STATE_EXCLUDED, STATE_NOT_REQUIRED,
                -> {
                    validateClassified(work, source)
                }

                STATE_NOT_APPLICABLE -> {
                    conflict("Completed Order recovery work is already not applicable")
                }

                STATE_MANUAL_REVIEW -> {
                    dependency("Refund point recovery requires manual review")
                }

                else -> {
                    dependency("Refund point recovery work has an unknown state")
                }
            }
        }
        val excluded = excludedUnits(command.orderId)
        metric("completion_prepared", "committed")
        return PointAccrualCompletionEligibility(excluded)
    }

    @Transactional
    override fun recordNotApplicable(command: RecordPointAccrualNotApplicableCommand) {
        if (command.orderState !in TERMINAL_STATES) {
            dependency("Point accrual not-applicable outcome is not terminal")
        }
        storeOutcome(
            OutcomeInput(
                command.orderId,
                OUTCOME_NOT_APPLICABLE,
                command.orderState,
                command.outcomeAt,
                command.sourceReference,
                command.aggregateVersion,
                null,
                null,
                command.outcomeAt,
            ),
        )
        val works = worksForOrder(command.orderId, lock = true)
        if (works.any { it.state !in UNCLASSIFIED_STATES + STATE_NOT_APPLICABLE }) {
            conflict("Point recovery work was classified before a non-completion outcome")
        }
        jdbcTemplate.update(
            """
            UPDATE payment_refund_point_recovery_work
               SET outcome_order_id = ?, state = 'NOT_APPLICABLE', attempt_count = 0,
                   next_attempt_at = NULL, claim_token = NULL, claim_until = NULL,
                   last_failure_code = NULL, updated_at = ?, version = version + 1
             WHERE order_id = ? AND state <> 'NOT_APPLICABLE'
            """.trimIndent(),
            command.orderId,
            Timestamp.from(command.outcomeAt),
            command.orderId,
        )
        metric("not_applicable", command.orderState.lowercase())
    }

    @Transactional
    override fun claimDue(
        now: Instant,
        limit: Int,
    ): List<ClaimedRefundPointRecovery> {
        require(limit in 1..100)
        val ids =
            jdbcTemplate.query(
                """
                SELECT id
                  FROM payment_refund_point_recovery_work
                 WHERE (state IN ('PENDING', 'ELIGIBILITY_RETRY', 'READY', 'RETRY_SCHEDULED')
                            AND next_attempt_at <= ?)
                    OR (state IN ('ELIGIBILITY_PROCESSING', 'PROCESSING') AND claim_until <= ?)
                 ORDER BY next_attempt_at NULLS FIRST, id
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """.trimIndent(),
                { rs, _ -> UUID.fromString(rs.getString(1)) },
                Timestamp.from(now),
                Timestamp.from(now),
                limit,
            )
        return ids.mapNotNull { claim(it, now) }
    }

    @Transactional
    override fun prepareRecovery(
        claim: ClaimedRefundPointRecovery,
        source: RefundPointAccrualSnapshotSource,
        now: Instant,
    ): PreparedRefundPointRecovery? {
        var work = requireClaim(claim)
        if (source.orderId != work.orderId) conflict("Recovery eligibility Order source changed")
        if (source.outcomeAt == null || source.outcomeSourceReference == null || source.aggregateVersion == null) {
            if (source.orderState !in NONTERMINAL_STATES) {
                dependency("Order point accrual timing source is incomplete")
            }
            releaseAwaitingCompletion(work, now)
            metric("eligibility", "awaiting_completion")
            return null
        }
        if (source.orderState != ORDER_COMPLETED) {
            recordNotApplicable(
                RecordPointAccrualNotApplicableCommand(
                    source.orderId,
                    source.orderState,
                    source.outcomeAt,
                    source.outcomeSourceReference,
                    source.aggregateVersion,
                ),
            )
            return null
        }
        val schemaVersion = source.snapshotSchemaVersion ?: dependency("Completed Order snapshot version is missing")
        val snapshotHash = source.snapshotHash ?: dependency("Completed Order snapshot hash is missing")
        validateSnapshot(source.orderId, schemaVersion, snapshotHash, source.units)
        val context =
            SnapshotContext(
                source.orderId,
                source.outcomeAt,
                source.outcomeSourceReference,
                source.aggregateVersion,
                schemaVersion,
                snapshotHash,
                source.units,
            )
        storeOutcome(
            OutcomeInput(
                source.orderId,
                OUTCOME_COMPLETED,
                ORDER_COMPLETED,
                source.outcomeAt,
                source.outcomeSourceReference,
                source.aggregateVersion,
                schemaVersion,
                snapshotHash,
                now,
            ),
        )
        if (work.targetAmountKrw == null) {
            classify(work, context, now, keepClaim = true)
            work = workById(claim.workId, lock = true) ?: dependency("Classified recovery work is missing")
        } else {
            validateClassified(work, context)
        }
        if (work.state in TERMINAL_WORK_STATES) return null
        if (work.state != STATE_PROCESSING || work.claimToken != claim.claimToken) {
            conflict("Refund point recovery claim was not retained")
        }
        return prepared(work, context)
    }

    @Transactional
    override fun recordSuccess(
        claim: ClaimedRefundPointRecovery,
        result: RefundPointRecoveryResult,
        now: Instant,
    ) {
        val work = requireClaim(claim)
        val target = work.targetAmountKrw ?: dependency("Recovery target is missing")
        if (work.state != STATE_PROCESSING ||
            Math.addExact(result.recoveredAmountKrw, result.pendingAmountKrw) != target
        ) {
            dependency("Loyalty recovery result does not tie out")
        }
        jdbcTemplate.update(
            """
            UPDATE payment_refund_point_recovery_work
               SET state = 'SUCCEEDED', recovered_amount_krw = ?, pending_amount_krw = ?,
                   next_attempt_at = NULL, claim_token = NULL, claim_until = NULL,
                   last_failure_code = NULL, updated_at = ?, version = version + 1
             WHERE id = ? AND claim_token = ?
            """.trimIndent(),
            result.recoveredAmountKrw,
            result.pendingAmountKrw,
            Timestamp.from(now),
            claim.workId,
            claim.claimToken,
        )
        metric("recovery", "succeeded")
    }

    @Transactional
    override fun recordFailure(
        claim: ClaimedRefundPointRecovery,
        failure: RuntimeException,
        now: Instant,
    ) {
        val work = requireClaim(claim)
        val failureCode = failureCode(failure)
        if (work.attemptCount >= MAX_ATTEMPTS) {
            jdbcTemplate.update(
                """
                UPDATE payment_refund_point_recovery_work
                   SET state = 'MANUAL_REVIEW', next_attempt_at = NULL,
                       claim_token = NULL, claim_until = NULL, last_failure_code = ?,
                       updated_at = ?, version = version + 1
                 WHERE id = ? AND claim_token = ?
                """.trimIndent(),
                failureCode,
                Timestamp.from(now),
                claim.workId,
                claim.claimToken,
            )
            metric("recovery", "manual_review")
            return
        }
        val retryState = if (work.targetAmountKrw == null) STATE_ELIGIBILITY_RETRY else STATE_RETRY_SCHEDULED
        jdbcTemplate.update(
            """
            UPDATE payment_refund_point_recovery_work
               SET state = ?, next_attempt_at = ?, claim_token = NULL, claim_until = NULL,
                   last_failure_code = ?, updated_at = ?, version = version + 1
             WHERE id = ? AND claim_token = ?
            """.trimIndent(),
            retryState,
            Timestamp.from(now.plus(RETRY_DELAYS[work.attemptCount - 1])),
            failureCode,
            Timestamp.from(now),
            claim.workId,
            claim.claimToken,
        )
        metric("recovery", "retry_scheduled")
    }

    private fun claim(
        workId: UUID,
        now: Instant,
    ): ClaimedRefundPointRecovery? {
        val work = workById(workId, lock = true) ?: return null
        if (work.state in PROCESSING_STATES && work.attemptCount >= MAX_ATTEMPTS) {
            jdbcTemplate.update(
                """
                UPDATE payment_refund_point_recovery_work
                   SET state = 'MANUAL_REVIEW', next_attempt_at = NULL,
                       claim_token = NULL, claim_until = NULL,
                       last_failure_code = 'CLAIM_LEASE_EXPIRED', updated_at = ?, version = version + 1
                 WHERE id = ?
                """.trimIndent(),
                Timestamp.from(now),
                workId,
            )
            metric("claim", "manual_review")
            return null
        }
        val eligibility = work.targetAmountKrw == null
        val nextState = if (eligibility) STATE_ELIGIBILITY_PROCESSING else STATE_PROCESSING
        val nextAttempt = work.attemptCount + 1
        val token = identifierSource.next()
        jdbcTemplate.update(
            """
            UPDATE payment_refund_point_recovery_work
               SET state = ?, attempt_count = ?, next_attempt_at = NULL,
                   claim_token = ?, claim_until = ?, updated_at = ?, version = version + 1
             WHERE id = ?
            """.trimIndent(),
            nextState,
            nextAttempt,
            token,
            Timestamp.from(now.plus(claimLease)),
            Timestamp.from(now),
            workId,
        )
        return ClaimedRefundPointRecovery(work.id, work.refundId, work.orderId, token, nextAttempt, eligibility)
    }

    private fun classify(
        work: WorkRow,
        source: SnapshotContext,
        now: Instant,
        keepClaim: Boolean,
    ) {
        val target = targetAmount(work.refundId, source.units)
        val excluded = !work.refundSucceededAt.isAfter(source.completedAt)
        val state =
            when {
                excluded -> STATE_EXCLUDED
                target == 0L -> STATE_NOT_REQUIRED
                keepClaim -> STATE_PROCESSING
                else -> STATE_READY
            }
        val terminal = state in TERMINAL_WORK_STATES
        jdbcTemplate.update(
            """
            UPDATE payment_refund_point_recovery_work
               SET outcome_order_id = ?, state = ?, target_amount_krw = ?,
                   snapshot_schema_version = ?, snapshot_hash = ?,
                   next_attempt_at = ?, claim_token = ?, claim_until = ?,
                   last_failure_code = NULL, updated_at = ?, version = version + 1
             WHERE id = ?
            """.trimIndent(),
            source.orderId,
            state,
            target,
            source.snapshotSchemaVersion,
            source.snapshotHash,
            if (state == STATE_READY) Timestamp.from(now) else null,
            if (terminal || state == STATE_READY) null else work.claimToken,
            if (terminal || state == STATE_READY) null else work.claimUntil?.let(Timestamp::from),
            Timestamp.from(now),
            work.id,
        )
        metric("eligibility", state.lowercase())
    }

    private fun validateClassified(
        work: WorkRow,
        source: SnapshotContext,
    ) {
        val target = targetAmount(work.refundId, source.units)
        if (work.outcomeOrderId != source.orderId ||
            work.targetAmountKrw != target ||
            work.snapshotSchemaVersion != source.snapshotSchemaVersion ||
            work.snapshotHash != source.snapshotHash
        ) {
            conflict("Refund point recovery snapshot source changed")
        }
        val shouldExclude = !work.refundSucceededAt.isAfter(source.completedAt)
        if (shouldExclude != (work.state == STATE_EXCLUDED)) {
            conflict("Refund point recovery timing classification changed")
        }
    }

    private fun prepared(
        work: WorkRow,
        source: SnapshotContext,
    ): PreparedRefundPointRecovery =
        PreparedRefundPointRecovery(
            work.refundId,
            work.orderId,
            work.refundSucceededAt,
            work.sourceReference,
            source.completedAt,
            source.completionSourceReference,
            source.snapshotSchemaVersion,
            source.snapshotHash,
            work.targetAmountKrw ?: dependency("Recovery target is missing"),
        )

    private fun releaseAwaitingCompletion(
        work: WorkRow,
        now: Instant,
    ) {
        jdbcTemplate.update(
            """
            UPDATE payment_refund_point_recovery_work
               SET state = 'PENDING', attempt_count = 0, next_attempt_at = ?,
                   claim_token = NULL, claim_until = NULL, last_failure_code = NULL,
                   updated_at = ?, version = version + 1
             WHERE id = ? AND claim_token = ?
            """.trimIndent(),
            Timestamp.from(now.plus(AWAITING_COMPLETION_DELAY)),
            Timestamp.from(now),
            work.id,
            work.claimToken,
        )
    }

    private fun targetAmount(
        refundId: UUID,
        units: List<RefundPointAccrualUnit>,
    ): Long {
        val byKey = units.associateBy { RefundPointUnitKey(it.orderLineId, it.unitPosition) }
        val ranges = refundRanges(refundId)
        if (ranges.isEmpty()) dependency("Successful Refund unit allocation is missing")
        var total = 0L
        ranges.forEach { range ->
            val end = Math.addExact(range.firstUnitIndex, range.quantity)
            if (end > Int.MAX_VALUE) dependency("Refund unit allocation is outside the snapshot range")
            for (position in range.firstUnitIndex.toInt() until end.toInt()) {
                val unit =
                    byKey[RefundPointUnitKey(range.orderLineId, position)]
                        ?: dependency("Refund unit is missing from the immutable accrual snapshot")
                total = Math.addExact(total, unit.accruedAmountKrw)
            }
        }
        return total
    }

    private fun excludedUnits(orderId: UUID): Set<RefundPointUnitKey> =
        jdbcTemplate
            .query(
                """
                SELECT allocation.order_line_id, allocation.first_unit_index, allocation.quantity
                  FROM payment_refund_point_recovery_work work
                  JOIN payment_refund_line_allocation allocation ON allocation.refund_id = work.refund_id
                 WHERE work.order_id = ? AND work.state = 'EXCLUDED_BEFORE_ACCRUAL'
                 ORDER BY allocation.order_line_id, allocation.first_unit_index
                """.trimIndent(),
                { rs, _ -> rs.toRange() },
                orderId,
            ).flatMap { range ->
                val end = Math.addExact(range.firstUnitIndex, range.quantity)
                if (end > Int.MAX_VALUE) dependency("Excluded Refund unit range overflowed")
                (range.firstUnitIndex.toInt() until end.toInt()).map { RefundPointUnitKey(range.orderLineId, it) }
            }.toSet()

    private fun validateSnapshot(
        orderId: UUID,
        schemaVersion: Int,
        snapshotHash: String,
        units: List<RefundPointAccrualUnit>,
    ) {
        if (schemaVersion <= 0 || !HASH_PATTERN.matches(snapshotHash) || units.isEmpty() ||
            units.any { it.accruedAmountKrw < 0 } ||
            units.any { it.unitPosition < 0 } ||
            units.any { it.orderLineId == UUID(0, 0) } ||
            units.map { RefundPointUnitKey(it.orderLineId, it.unitPosition) }.toSet().size != units.size ||
            orderId == UUID(0, 0)
        ) {
            dependency("Order point accrual snapshot source is invalid")
        }
    }

    private fun storeOutcome(input: OutcomeInput) {
        val existing = outcome(input.orderId, lock = true)
        if (existing != null) {
            if (existing.outcomeState != input.outcomeState ||
                existing.orderState != input.orderState ||
                existing.outcomeAt != input.outcomeAt ||
                existing.sourceReference != input.sourceReference ||
                existing.aggregateVersion != input.aggregateVersion ||
                existing.snapshotSchemaVersion != input.snapshotSchemaVersion ||
                existing.snapshotHash != input.snapshotHash
            ) {
                conflict("Order point accrual outcome source changed")
            }
            return
        }
        jdbcTemplate.update(
            """
            INSERT INTO payment_order_point_accrual_outcome (
                order_id, outcome_state, order_state, outcome_at, source_reference,
                aggregate_version, snapshot_schema_version, snapshot_hash, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            input.orderId,
            input.outcomeState,
            input.orderState,
            Timestamp.from(input.outcomeAt),
            input.sourceReference,
            input.aggregateVersion,
            input.snapshotSchemaVersion,
            input.snapshotHash,
            Timestamp.from(input.createdAt),
        )
    }

    private fun unresolvedRefundCount(orderId: UUID): Long =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*)
              FROM payment_refund
             WHERE order_id = ? AND reason = 'PARTIAL_REFUND'
               AND state NOT IN ('SUCCEEDED', 'FAILED')
            """.trimIndent(),
            Long::class.java,
            orderId,
        ) ?: 0L

    private fun missingRecoveryWorkCount(orderId: UUID): Long =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*)
              FROM payment_refund refund
              LEFT JOIN payment_refund_point_recovery_work work ON work.refund_id = refund.id
             WHERE refund.order_id = ? AND refund.reason = 'PARTIAL_REFUND'
               AND refund.state = 'SUCCEEDED' AND work.id IS NULL
            """.trimIndent(),
            Long::class.java,
            orderId,
        ) ?: 0L

    private fun refundRanges(refundId: UUID): List<RefundRange> =
        jdbcTemplate.query(
            """
            SELECT order_line_id, first_unit_index, quantity
              FROM payment_refund_line_allocation
             WHERE refund_id = ?
             ORDER BY order_line_id, first_unit_index
            """.trimIndent(),
            { rs, _ -> rs.toRange() },
            refundId,
        )

    private fun ResultSet.toRange() =
        RefundRange(
            UUID.fromString(getString(1)),
            getLong(2),
            getLong(3),
        )

    private fun outcome(
        orderId: UUID,
        lock: Boolean,
    ): OutcomeRow? =
        jdbcTemplate
            .query(
                """
                SELECT order_id, outcome_state, order_state, outcome_at, source_reference,
                       aggregate_version, snapshot_schema_version, snapshot_hash
                  FROM payment_order_point_accrual_outcome
                 WHERE order_id = ?${if (lock) " FOR UPDATE" else ""}
                """.trimIndent(),
                { rs, _ ->
                    OutcomeRow(
                        UUID.fromString(rs.getString(1)),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getTimestamp(4).toInstant(),
                        rs.getString(5),
                        rs.getLong(6),
                        (rs.getObject(7) as? Number)?.toInt(),
                        rs.getString(8),
                    )
                },
                orderId,
            ).singleOrNull()

    private fun worksForOrder(
        orderId: UUID,
        lock: Boolean,
    ): List<WorkRow> =
        jdbcTemplate.query(
            """
            ${WORK_SELECT.trimIndent()}
             WHERE order_id = ?
             ORDER BY refund_succeeded_at, refund_id${if (lock) " FOR UPDATE" else ""}
            """.trimIndent(),
            { rs, _ -> rs.toWork() },
            orderId,
        )

    private fun workByRefundId(
        refundId: UUID,
        lock: Boolean,
    ): WorkRow? = work("refund_id", refundId, lock)

    private fun workById(
        workId: UUID,
        lock: Boolean,
    ): WorkRow? = work("id", workId, lock)

    private fun work(
        column: String,
        id: UUID,
        lock: Boolean,
    ): WorkRow? =
        jdbcTemplate
            .query(
                """
                ${WORK_SELECT.trimIndent()}
                 WHERE $column = ?${if (lock) " FOR UPDATE" else ""}
                """.trimIndent(),
                { rs, _ -> rs.toWork() },
                id,
            ).singleOrNull()

    private fun ResultSet.toWork() =
        WorkRow(
            UUID.fromString(getString(1)),
            UUID.fromString(getString(2)),
            UUID.fromString(getString(3)),
            getString(4)?.let(UUID::fromString),
            getString(5),
            getTimestamp(6).toInstant(),
            (getObject(7) as? Number)?.toLong(),
            (getObject(8) as? Number)?.toLong(),
            (getObject(9) as? Number)?.toLong(),
            (getObject(10) as? Number)?.toInt(),
            getString(11),
            getInt(12),
            getString(13)?.let(UUID::fromString),
            getTimestamp(14)?.toInstant(),
            getString(15),
        )

    private fun requireClaim(claim: ClaimedRefundPointRecovery): WorkRow {
        val work = workById(claim.workId, lock = true) ?: dependency("Refund point recovery work is missing")
        if (work.refundId != claim.refundId || work.orderId != claim.orderId || work.claimToken != claim.claimToken) {
            conflict("Refund point recovery claim was lost")
        }
        return work
    }

    private fun failureCode(failure: RuntimeException): String =
        when (failure) {
            is DomainFailure -> {
                failure.code.name
            }

            else -> {
                failure.javaClass.simpleName
                    .uppercase()
                    .take(80)
            }
        }

    private fun metric(
        operation: String,
        outcome: String,
    ) {
        meterRegistry
            .counter(
                "beanflow.payment.point_recovery.count",
                "operation",
                operation,
                "outcome",
                outcome,
            ).increment()
    }

    private fun dependency(message: String): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)

    private fun conflict(message: String): Nothing = throw DomainFailure(FailureCode.IDEMPOTENCY_KEY_REUSED, message)

    private data class OutcomeInput(
        val orderId: UUID,
        val outcomeState: String,
        val orderState: String,
        val outcomeAt: Instant,
        val sourceReference: String,
        val aggregateVersion: Long,
        val snapshotSchemaVersion: Int?,
        val snapshotHash: String?,
        val createdAt: Instant,
    )

    private data class OutcomeRow(
        val orderId: UUID,
        val outcomeState: String,
        val orderState: String,
        val outcomeAt: Instant,
        val sourceReference: String,
        val aggregateVersion: Long,
        val snapshotSchemaVersion: Int?,
        val snapshotHash: String?,
    )

    private data class SnapshotContext(
        val orderId: UUID,
        val completedAt: Instant,
        val completionSourceReference: String,
        val aggregateVersion: Long,
        val snapshotSchemaVersion: Int,
        val snapshotHash: String,
        val units: List<RefundPointAccrualUnit>,
    )

    private data class WorkRow(
        val id: UUID,
        val refundId: UUID,
        val orderId: UUID,
        val outcomeOrderId: UUID?,
        val state: String,
        val refundSucceededAt: Instant,
        val targetAmountKrw: Long?,
        val recoveredAmountKrw: Long?,
        val pendingAmountKrw: Long?,
        val snapshotSchemaVersion: Int?,
        val snapshotHash: String?,
        val attemptCount: Int,
        val claimToken: UUID?,
        val claimUntil: Instant?,
        val sourceReference: String,
    )

    private data class RefundRange(
        val orderLineId: UUID,
        val firstUnitIndex: Long,
        val quantity: Long,
    )

    private companion object {
        const val OUTCOME_COMPLETED = "COMPLETED"
        const val OUTCOME_NOT_APPLICABLE = "NOT_APPLICABLE"
        const val ORDER_COMPLETED = "COMPLETED"
        const val STATE_PENDING = "PENDING"
        const val STATE_ELIGIBILITY_PROCESSING = "ELIGIBILITY_PROCESSING"
        const val STATE_ELIGIBILITY_RETRY = "ELIGIBILITY_RETRY"
        const val STATE_READY = "READY"
        const val STATE_PROCESSING = "PROCESSING"
        const val STATE_RETRY_SCHEDULED = "RETRY_SCHEDULED"
        const val STATE_SUCCEEDED = "SUCCEEDED"
        const val STATE_EXCLUDED = "EXCLUDED_BEFORE_ACCRUAL"
        const val STATE_NOT_REQUIRED = "NOT_REQUIRED"
        const val STATE_NOT_APPLICABLE = "NOT_APPLICABLE"
        const val STATE_MANUAL_REVIEW = "MANUAL_REVIEW"
        const val MAX_ATTEMPTS = 5
        val HASH_PATTERN = Regex("^[0-9a-f]{64}$")
        val TERMINAL_STATES = setOf("REJECTED", "CANCELLED", "EXPIRED")
        val NONTERMINAL_STATES = setOf("PENDING_PAYMENT", "PAID", "ACCEPTED", "PREPARING", "READY")
        val UNCLASSIFIED_STATES = setOf(STATE_PENDING, STATE_ELIGIBILITY_PROCESSING, STATE_ELIGIBILITY_RETRY)
        val PROCESSING_STATES = setOf(STATE_ELIGIBILITY_PROCESSING, STATE_PROCESSING)
        val TERMINAL_WORK_STATES = setOf(STATE_EXCLUDED, STATE_NOT_REQUIRED, STATE_NOT_APPLICABLE)
        val AWAITING_COMPLETION_DELAY: Duration = Duration.ofSeconds(30)
        val RETRY_DELAYS =
            listOf(
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                Duration.ofMinutes(2),
                Duration.ofMinutes(5),
                Duration.ofMinutes(15),
            )
        val WORK_SELECT =
            """
            SELECT id, refund_id, order_id, outcome_order_id, state, refund_succeeded_at,
                   target_amount_krw, recovered_amount_krw, pending_amount_krw,
                   snapshot_schema_version, snapshot_hash, attempt_count,
                   claim_token, claim_until, source_reference
              FROM payment_refund_point_recovery_work
            """
    }
}

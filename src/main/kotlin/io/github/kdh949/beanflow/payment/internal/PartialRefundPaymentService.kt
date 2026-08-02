package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.payment.api.ClaimedPartialRefundProvider
import io.github.kdh949.beanflow.payment.api.ClaimedPartialRefundRestoration
import io.github.kdh949.beanflow.payment.api.CreatePartialRefundPaymentCommand
import io.github.kdh949.beanflow.payment.api.LockPartialRefundPaymentCommand
import io.github.kdh949.beanflow.payment.api.PartialRefundAuditActorType
import io.github.kdh949.beanflow.payment.api.PartialRefundPaymentLock
import io.github.kdh949.beanflow.payment.api.PartialRefundPaymentOperations
import io.github.kdh949.beanflow.payment.api.PartialRefundProviderCompletion
import io.github.kdh949.beanflow.payment.api.PartialRefundProviderMode
import io.github.kdh949.beanflow.payment.api.PartialRefundProviderOutcome
import io.github.kdh949.beanflow.payment.api.PartialRefundRestorationCommandSnapshot
import io.github.kdh949.beanflow.payment.api.PartialRefundRestorationPolicyMode
import io.github.kdh949.beanflow.payment.api.PartialRefundRestorationSlice
import io.github.kdh949.beanflow.payment.api.PartialRefundSettlementContext
import io.github.kdh949.beanflow.payment.api.PartialRefundStoredResponse
import io.github.kdh949.beanflow.payment.api.PreparedPartialRefundPayment
import io.github.kdh949.beanflow.payment.internal.domain.PaymentApprovalState
import io.github.kdh949.beanflow.payment.internal.domain.PaymentType
import io.github.kdh949.beanflow.payment.internal.domain.RefundState
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
internal class PartialRefundPaymentService(
    private val paymentRepository: PaymentJpaRepository,
    private val refundRepository: RefundJpaRepository,
    private val refundExecution: RejectionRefundService,
    private val successLedger: PartialRefundSuccessLedger,
    private val refundEventProducer: PaymentRefundEventProducer,
    private val responseTransaction: PartialRefundResponseTransaction,
    private val audit: AuditRecordOperations,
    private val correlationIdSource: CorrelationIdSource,
    private val identifierSource: IdentifierSource,
    private val jdbcTemplate: JdbcTemplate,
    private val meterRegistry: MeterRegistry,
    @Value("\${beanflow.payment.refund-restoration.claim-lease:PT1M}")
    private val restorationClaimLease: Duration,
) : PartialRefundPaymentOperations {
    override fun orderId(paymentId: UUID): UUID =
        paymentRepository.findById(paymentId).orElse(null)?.orderId
            ?: fail(FailureCode.RESOURCE_NOT_FOUND, "Payment was not found")

    @Transactional(propagation = Propagation.MANDATORY)
    override fun lock(command: LockPartialRefundPaymentCommand): PartialRefundPaymentLock {
        jdbcTemplate.query(
            "select pg_advisory_xact_lock(hashtextextended(?, 0))",
            { _, _ -> Unit },
            "partial-refund:${command.actorId}:${command.idempotencyKey}",
        )
        replay(command)?.let { return it }
        val payment =
            paymentRepository.findLockedById(command.paymentId)
                ?: fail(FailureCode.RESOURCE_NOT_FOUND, "Payment was not found")
        replay(command)?.let { return it }
        if (payment.type != PaymentType.EXTERNAL || payment.approvalState != PaymentApprovalState.APPROVED) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Payment is not an approved external payment")
        }
        if (refundRepository.findUnresolvedByPaymentId(payment.id).isNotEmpty()) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Payment has an unresolved Refund")
        }
        return PartialRefundPaymentLock.Ready(
            paymentId = payment.id,
            orderId = payment.orderId,
            approvedAmountKrw =
                payment.approvedAmountKrw
                    ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Approved payment amount is missing"),
            succeededRefundAmountKrw = payment.succeededRefundAmountKrw,
            consumedQuantityByOrderLine = successfulLineConsumptionLocked(payment.id),
            consumedPointsByReservationAllocation = successfulPointConsumptionLocked(payment.id),
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun create(command: CreatePartialRefundPaymentCommand): PreparedPartialRefundPayment {
        require(command.lineRequests.isNotEmpty())
        val payment =
            paymentRepository.findLockedById(command.paymentId)
                ?: fail(FailureCode.RESOURCE_NOT_FOUND, "Payment was not found")
        if (payment.orderId != command.orderId || payment.type != PaymentType.EXTERNAL ||
            payment.approvalState != PaymentApprovalState.APPROVED
        ) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Payment is not an approved external payment for this order")
        }
        replay(
            LockPartialRefundPaymentCommand(
                command.paymentId,
                command.actorId,
                command.idempotencyKey,
                command.payloadHash,
            ),
        )?.let { fail(FailureCode.IDEMPOTENCY_REQUEST_IN_PROGRESS, "Refund request already exists") }
        if (refundRepository.findUnresolvedByPaymentId(payment.id).isNotEmpty()) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Payment has an unresolved Refund")
        }
        val cashRequested = command.lineRequests.sumOf { it.cashRefundKrw }
        val pointsRequested = command.lineRequests.sumOf { it.pointsRestorationKrw }
        if (cashRequested + pointsRequested <= 0) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Selected units contain no refundable cash or points")
        }
        if (command.pointRequests.sumOf { it.requestedAmountKrw } != pointsRequested) {
            fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Point request snapshot does not tie out")
        }
        if (Math.addExact(payment.succeededRefundAmountKrw, cashRequested) >
            requireNotNull(payment.approvedAmountKrw)
        ) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Refund request would exceed the approved payment amount")
        }

        val refundId = identifierSource.next()
        val correlationId = correlationIdSource.currentOrCreate()
        val source = "partial-refund:${command.actorId}:${command.idempotencyKey}"
        val refund =
            RefundEntity(
                id = refundId,
                paymentId = payment.id,
                orderId = command.orderId,
                requestedAmountKrw = cashRequested,
                requestedPointsKrw = pointsRequested,
                reason = PARTIAL_REFUND,
                state = RefundState.REQUESTED,
                providerIdempotencyKey = "refund:partial:$refundId",
                sourceReference = source,
                actorId = command.actorId,
                idempotencyKey = command.idempotencyKey,
                payloadHash = command.payloadHash,
                correlationId = correlationId,
                pointRestorationPolicyVersionId = command.policyVersionId,
                pointRestorationPolicyTrigger = "PARTIAL_REFUND",
                pointRestorationPolicyBenefitType = "POINTS",
                pointRestorationPolicyMode = command.policyMode.name,
                pointRestorationPolicyValidityDays = command.compensationValidityDays,
                attemptCount = 0,
                requestAttemptCount = 0,
                lookupAttemptCount = 0,
                nextAttemptAt = command.now,
                createdAt = command.now,
                updatedAt = command.now,
            )
        refundRepository.saveAndFlush(refund)
        insertRequests(refundId, command, command.now)
        audit.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = command.actorId.toString(),
                    actorType = command.auditActorType.toOperationsType(),
                    action = "PARTIAL_REFUND_REQUESTED",
                    targetType = "PAYMENT_REFUND",
                    targetId = refundId,
                    occurredAt = command.now,
                    reason = command.reason,
                    afterSummary =
                        mapOf(
                            "cashRefundRequestedKrw" to cashRequested.toString(),
                            "pointsRestorationRequestedKrw" to pointsRequested.toString(),
                            "lineCount" to command.lineRequests.size.toString(),
                            "policyVersionId" to command.policyVersionId.toString(),
                        ),
                    correlationId = correlationId,
                    sourceReference = "$source:audit:requested",
                ),
            ),
        )
        if (cashRequested == 0L) {
            refund.state = RefundState.SUCCEEDED
            refund.succeededAmountKrw = 0
            refund.nextAttemptAt = null
            refund.updatedAt = command.now
            successLedger.record(refund, payment, command.now)
            refundEventProducer.publishPartial(refund, payment, command.settlementContext, command.now)
        }
        return PreparedPartialRefundPayment(refundId, cashRequested)
    }

    override fun claimProvider(
        refundId: UUID,
        now: Instant,
    ): ClaimedPartialRefundProvider = refundExecution.claimOne(refundId, now).toApi()

    override fun claimDueProviders(
        now: Instant,
        limit: Int,
    ): List<ClaimedPartialRefundProvider> = refundExecution.claimPartialDue(now, limit).map { it.toApi() }

    override fun callProvider(claim: ClaimedPartialRefundProvider): PartialRefundProviderCompletion {
        val internal = claim.toInternal()
        val result =
            try {
                refundExecution.callProvider(internal)
            } catch (_: io.github.kdh949.beanflow.payment.api.ProviderTransportFailure) {
                GatewayRefundResult.Unknown("PROVIDER_CALL_FAILED")
            }
        return PartialRefundProviderCompletion(claim, result.toApi())
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun recordProviderCompletion(
        completion: PartialRefundProviderCompletion,
        settlementContext: PartialRefundSettlementContext,
        now: Instant,
    ) {
        refundExecution.recordPartialResult(
            completion.claim.toInternal(),
            completion.outcome.toInternal(),
            settlementContext,
            now,
        )
    }

    override fun recordOrReplayResponse(
        refundId: UUID,
        now: Instant,
    ): PartialRefundStoredResponse = responseTransaction.recordOrReplay(refundId, now)

    @Transactional
    override fun claimDueRestorations(
        now: Instant,
        limit: Int,
    ): List<ClaimedPartialRefundRestoration> {
        require(limit in 1..100)
        val ids =
            jdbcTemplate.query(
                """
                SELECT id
                  FROM payment_refund_restoration_work
                 WHERE (state IN ('PENDING', 'RETRY_SCHEDULED') AND next_attempt_at <= ?)
                    OR (state = 'PROCESSING' AND claim_until <= ?)
                 ORDER BY next_attempt_at NULLS FIRST, id
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """.trimIndent(),
                { rs, _ -> UUID.fromString(rs.getString(1)) },
                Timestamp.from(now),
                Timestamp.from(now),
                limit,
            )
        return ids.mapNotNull { id -> claimRestoration(id, now) }
    }

    override fun restorationCommand(refundId: UUID): PartialRefundRestorationCommandSnapshot {
        val refund =
            jdbcTemplate
                .query(
                    """
                    SELECT order_id, updated_at, source_reference, correlation_id,
                           point_restoration_policy_version_id,
                           point_restoration_policy_mode,
                           point_restoration_policy_validity_days
                      FROM payment_refund
                     WHERE id = ? AND state = 'SUCCEEDED'
                    """.trimIndent(),
                    { rs, _ ->
                        RefundWorkSource(
                            UUID.fromString(rs.getString(1)),
                            rs.getTimestamp(2).toInstant(),
                            rs.getString(3),
                            rs.getString(4),
                            rs.getLong(5),
                            PartialRefundRestorationPolicyMode.valueOf(rs.getString(6)),
                            rs.getInt(7),
                        )
                    },
                    refundId,
                ).singleOrNull()
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Successful Refund source is missing")
        val slices =
            jdbcTemplate.query(
                """
                SELECT allocation.order_line_id, allocation.point_reservation_allocation_id,
                       allocation.original_point_lot_id,
                       request.issuer_type, request.issuer_reference, allocation.amount_krw
                  FROM payment_refund_point_allocation allocation
                  JOIN payment_refund_point_request request ON request.id = allocation.refund_point_request_id
                 WHERE allocation.refund_id = ?
                 ORDER BY allocation.order_line_id, allocation.point_reservation_allocation_id
                """.trimIndent(),
                { rs, _ ->
                    PartialRefundRestorationSlice(
                        orderLineId = UUID.fromString(rs.getString(1)),
                        pointReservationAllocationId = UUID.fromString(rs.getString(2)),
                        originalPointLotId = UUID.fromString(rs.getString(3)),
                        issuerType =
                            io.github.kdh949.beanflow.payment.api.PartialRefundIssuerType
                                .valueOf(rs.getString(4)),
                        issuerReference = rs.getString(5),
                        amountKrw = rs.getLong(6),
                    )
                },
                refundId,
            )
        if (slices.isEmpty()) fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Refund point allocation is missing")
        return PartialRefundRestorationCommandSnapshot(
            refundId = refundId,
            orderId = refund.orderId,
            refundSucceededAt = refund.succeededAt,
            sourceReference = "${refund.sourceReference}:points-restoration",
            refundSourceReference = refund.sourceReference,
            correlationId = refund.correlationId,
            policyVersionId = refund.policyVersionId,
            policyMode = refund.policyMode,
            compensationValidityDays = refund.validityDays,
            slices = slices,
        )
    }

    @Transactional
    override fun recordRestorationSuccess(
        claim: ClaimedPartialRefundRestoration,
        restoredAmountKrw: Long,
        now: Instant,
    ) {
        requireOwnedClaim(claim)
        val requested =
            jdbcTemplate.queryForObject(
                "select requested_amount_krw from payment_refund_restoration_work where id = ?",
                Long::class.java,
                claim.workId,
            ) ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Restoration work is missing")
        if (restoredAmountKrw != requested) {
            fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Loyalty restoration result does not tie out")
        }
        jdbcTemplate.update(
            """
            UPDATE payment_refund_restoration_work
               SET state = 'SUCCEEDED', restored_amount_krw = ?, next_attempt_at = NULL,
                   claim_token = NULL, claim_until = NULL, last_failure_code = NULL,
                   updated_at = ?, version = version + 1
             WHERE id = ? AND claim_token = ?
            """.trimIndent(),
            restoredAmountKrw,
            Timestamp.from(now),
            claim.workId,
            claim.claimToken,
        )
        restorationMetric("succeeded", "committed")
    }

    @Transactional
    override fun recordRestorationFailure(
        claim: ClaimedPartialRefundRestoration,
        failure: RuntimeException,
        now: Instant,
    ) {
        requireOwnedClaim(claim)
        val code = failureCode(failure)
        val terminalConflict =
            failure is DomainFailure && failure.code in
                setOf(
                    FailureCode.IDEMPOTENCY_KEY_REUSED,
                    FailureCode.ORDER_STATE_CONFLICT,
                    FailureCode.INVALID_REQUEST,
                )
        if (terminalConflict || claim.attemptCount >= MAX_RESTORATION_ATTEMPTS) {
            jdbcTemplate.update(
                """
                UPDATE payment_refund_restoration_work
                   SET state = 'MANUAL_REVIEW', next_attempt_at = NULL,
                       claim_token = NULL, claim_until = NULL, last_failure_code = ?,
                       updated_at = ?, version = version + 1
                 WHERE id = ? AND claim_token = ?
                """.trimIndent(),
                code,
                Timestamp.from(now),
                claim.workId,
                claim.claimToken,
            )
            restorationMetric("manual_review", "failed")
        } else {
            jdbcTemplate.update(
                """
                UPDATE payment_refund_restoration_work
                   SET state = 'RETRY_SCHEDULED', next_attempt_at = ?,
                       claim_token = NULL, claim_until = NULL, last_failure_code = ?,
                       updated_at = ?, version = version + 1
                 WHERE id = ? AND claim_token = ?
                """.trimIndent(),
                Timestamp.from(now.plus(RESTORATION_RETRY_DELAYS[claim.attemptCount - 1])),
                code,
                Timestamp.from(now),
                claim.workId,
                claim.claimToken,
            )
            restorationMetric("retry_scheduled", "failed")
        }
    }

    private fun replay(command: LockPartialRefundPaymentCommand): PartialRefundPaymentLock.Replay? =
        refundRepository.findByActorIdAndIdempotencyKey(command.actorId, command.idempotencyKey)?.let { existing ->
            if (existing.paymentId != command.paymentId || existing.payloadHash != command.payloadHash) {
                fail(FailureCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was reused with another Refund payload")
            }
            PartialRefundPaymentLock.Replay(
                refundId = existing.id,
                response =
                    if (existing.responseStatus != null && existing.responseBody != null) {
                        PartialRefundStoredResponse(requireNotNull(existing.responseStatus), requireNotNull(existing.responseBody))
                    } else {
                        null
                    },
                inProgress =
                    existing.responseStatus == null &&
                        existing.state in setOf(RefundState.PROCESSING, RefundState.RECONCILING),
            )
        }

    private fun insertRequests(
        refundId: UUID,
        command: CreatePartialRefundPaymentCommand,
        now: Instant,
    ) {
        val requestIdByLine = mutableMapOf<UUID, UUID>()
        command.lineRequests.forEach { line ->
            val requestId = identifierSource.next()
            requestIdByLine[line.orderLineId] = requestId
            jdbcTemplate.update(
                """
                INSERT INTO payment_refund_line_request (
                    id, refund_id, order_line_id, line_sequence, first_unit_index, quantity,
                    original_quantity, gross_krw, coupon_attribution_krw,
                    points_restoration_krw, cash_refund_krw, source_reference, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                requestId,
                refundId,
                line.orderLineId,
                line.lineSequence,
                line.firstUnitIndex,
                line.quantity,
                line.originalQuantity,
                line.grossKrw,
                line.couponAttributionKrw,
                line.pointsRestorationKrw,
                line.cashRefundKrw,
                "partial-refund:$refundId:line:${line.orderLineId}:request",
                Timestamp.from(now),
            )
        }
        command.pointRequests.forEach { point ->
            jdbcTemplate.update(
                """
                INSERT INTO payment_refund_point_request (
                    id, refund_id, refund_line_request_id, order_line_id,
                    point_reservation_allocation_id, original_point_lot_id,
                    issuer_type, issuer_reference, requested_amount_krw,
                    source_reference, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                identifierSource.next(),
                refundId,
                requestIdByLine.getValue(point.orderLineId),
                point.orderLineId,
                point.pointReservationAllocationId,
                point.originalPointLotId,
                point.issuerType.name,
                point.issuerReference,
                point.requestedAmountKrw,
                "partial-refund:$refundId:line:${point.orderLineId}:point:${point.pointReservationAllocationId}:request",
                Timestamp.from(now),
            )
        }
    }

    private fun successfulLineConsumptionLocked(paymentId: UUID): Map<UUID, Long> =
        jdbcTemplate
            .query(
                """
                SELECT allocation.order_line_id, allocation.quantity
                  FROM payment_refund_line_allocation allocation
                  JOIN payment_refund refund ON refund.id = allocation.refund_id
                 WHERE refund.payment_id = ?
                 ORDER BY allocation.order_line_id, allocation.id
                 FOR UPDATE OF allocation
                """.trimIndent(),
                { rs, _ -> UUID.fromString(rs.getString(1)) to rs.getLong(2) },
                paymentId,
            ).groupBy({ it.first }, { it.second })
            .mapValues { (_, amounts) -> amounts.sum() }

    private fun successfulPointConsumptionLocked(paymentId: UUID): Map<UUID, Long> =
        jdbcTemplate
            .query(
                """
                SELECT allocation.point_reservation_allocation_id, allocation.amount_krw
                  FROM payment_refund_point_allocation allocation
                  JOIN payment_refund refund ON refund.id = allocation.refund_id
                 WHERE refund.payment_id = ?
                 ORDER BY allocation.point_reservation_allocation_id, allocation.id
                 FOR UPDATE OF allocation
                """.trimIndent(),
                { rs, _ -> UUID.fromString(rs.getString(1)) to rs.getLong(2) },
                paymentId,
            ).groupBy({ it.first }, { it.second })
            .mapValues { (_, amounts) -> amounts.sum() }

    private fun claimRestoration(
        id: UUID,
        now: Instant,
    ): ClaimedPartialRefundRestoration? {
        val row =
            jdbcTemplate
                .query(
                    """
                    SELECT refund_id, attempt_count, COALESCE(next_attempt_at, claim_until, updated_at)
                      FROM payment_refund_restoration_work
                     WHERE id = ?
                     FOR UPDATE
                    """.trimIndent(),
                    { rs, _ -> WorkRow(UUID.fromString(rs.getString(1)), rs.getInt(2), rs.getTimestamp(3).toInstant()) },
                    id,
                ).single()
        if (row.attemptCount >= MAX_RESTORATION_ATTEMPTS) {
            jdbcTemplate.update(
                """
                UPDATE payment_refund_restoration_work
                   SET state = 'MANUAL_REVIEW', next_attempt_at = NULL,
                       claim_token = NULL, claim_until = NULL,
                       last_failure_code = 'CLAIM_LEASE_EXPIRED', updated_at = ?, version = version + 1
                 WHERE id = ?
                """.trimIndent(),
                Timestamp.from(now),
                id,
            )
            restorationMetric("manual_review", "claim_exhausted")
            return null
        }
        val token = identifierSource.next()
        val attempt = row.attemptCount + 1
        jdbcTemplate.update(
            """
            UPDATE payment_refund_restoration_work
               SET state = 'PROCESSING', attempt_count = ?, next_attempt_at = NULL,
                   claim_token = ?, claim_until = ?, updated_at = ?, version = version + 1
             WHERE id = ?
            """.trimIndent(),
            attempt,
            token,
            Timestamp.from(now.plus(restorationClaimLease)),
            Timestamp.from(now),
            id,
        )
        return ClaimedPartialRefundRestoration(id, row.refundId, token, attempt, row.dueAt)
    }

    private fun requireOwnedClaim(claim: ClaimedPartialRefundRestoration) {
        val owned =
            jdbcTemplate.query(
                """
                SELECT claim_token FROM payment_refund_restoration_work
                 WHERE id = ? AND state = 'PROCESSING' AND claim_token = ?
                 FOR UPDATE
                """.trimIndent(),
                { rs, _ -> UUID.fromString(rs.getString(1)) },
                claim.workId,
                claim.claimToken,
            )
        if (owned.size != 1) fail(FailureCode.ORDER_STATE_CONFLICT, "Restoration claim is no longer owned")
    }

    private fun PartialRefundAuditActorType.toOperationsType(): AuditActorType =
        when (this) {
            PartialRefundAuditActorType.STORE_OWNER -> AuditActorType.STORE_OWNER
            PartialRefundAuditActorType.STORE_STAFF -> AuditActorType.STORE_STAFF
            PartialRefundAuditActorType.PLATFORM_OPERATOR -> AuditActorType.PLATFORM_OPERATOR
        }

    private fun ClaimedRefund.toApi() =
        ClaimedPartialRefundProvider(
            refundId = refundId,
            paymentId = paymentId,
            orderId = orderId,
            amountKrw = amountKrw,
            providerIdempotencyKey = providerIdempotencyKey,
            mode = PartialRefundProviderMode.valueOf(mode.name),
            attemptCount = attemptCount,
            claimToken = claimToken,
            dueAt = dueAt,
        )

    private fun ClaimedPartialRefundProvider.toInternal() =
        ClaimedRefund(
            refundId = refundId,
            paymentId = paymentId,
            orderId = orderId,
            reason = PARTIAL_REFUND,
            amountKrw = amountKrw,
            providerIdempotencyKey = providerIdempotencyKey,
            mode =
                io.github.kdh949.beanflow.payment.internal.domain.RefundClaimMode
                    .valueOf(mode.name),
            attemptCount = attemptCount,
            claimToken = claimToken,
            dueAt = dueAt,
        )

    private fun GatewayRefundResult.toApi(): PartialRefundProviderOutcome =
        when (this) {
            is GatewayRefundResult.Succeeded -> PartialRefundProviderOutcome.Succeeded(providerRefundReference)
            is GatewayRefundResult.Failed -> PartialRefundProviderOutcome.Failed(code)
            is GatewayRefundResult.RetryableFailed -> PartialRefundProviderOutcome.RetryableFailed(code)
            is GatewayRefundResult.Unknown -> PartialRefundProviderOutcome.Unknown(code)
        }

    private fun PartialRefundProviderOutcome.toInternal(): GatewayRefundResult =
        when (this) {
            is PartialRefundProviderOutcome.Succeeded -> GatewayRefundResult.Succeeded(providerRefundReference)
            is PartialRefundProviderOutcome.Failed -> GatewayRefundResult.Failed(code)
            is PartialRefundProviderOutcome.RetryableFailed -> GatewayRefundResult.RetryableFailed(code)
            is PartialRefundProviderOutcome.Unknown -> GatewayRefundResult.Unknown(code)
        }

    private fun failureCode(failure: RuntimeException): String =
        when (failure) {
            is DomainFailure -> {
                failure.code.name
            }

            else -> {
                failure.javaClass.simpleName
                    .uppercase()
                    .replace(Regex("[^A-Z0-9_]+"), "_")
                    .take(80)
            }
        }.ifBlank { "UNKNOWN" }

    private fun restorationMetric(
        state: String,
        outcome: String,
    ) {
        meterRegistry
            .counter("beanflow.payment.refund.restoration.count", "state", state, "outcome", outcome)
            .increment()
    }

    private fun fail(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)

    private data class WorkRow(
        val refundId: UUID,
        val attemptCount: Int,
        val dueAt: Instant,
    )

    private data class RefundWorkSource(
        val orderId: UUID,
        val succeededAt: Instant,
        val sourceReference: String,
        val correlationId: String,
        val policyVersionId: Long,
        val policyMode: PartialRefundRestorationPolicyMode,
        val validityDays: Int,
    )

    private companion object {
        const val PARTIAL_REFUND = "PARTIAL_REFUND"
        const val MAX_RESTORATION_ATTEMPTS = 5
        val RESTORATION_RETRY_DELAYS =
            listOf(
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                Duration.ofMinutes(2),
                Duration.ofMinutes(5),
                Duration.ofMinutes(15),
            )
    }
}

@Service
internal class PartialRefundSuccessLedger(
    private val identifierSource: IdentifierSource,
    private val jdbcTemplate: JdbcTemplate,
    private val audit: AuditRecordOperations,
    private val pointRecoveryService: RefundPointRecoveryService,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun record(
        refund: RefundEntity,
        payment: PaymentEntity,
        now: Instant,
    ) {
        val existing =
            jdbcTemplate.queryForObject(
                "select count(*) from payment_refund_line_allocation where refund_id = ?",
                Long::class.java,
                refund.id,
            ) ?: 0
        if (existing > 0) return
        lineRequests(refund.id).forEach { request ->
            jdbcTemplate.update(
                """
                INSERT INTO payment_refund_line_allocation (
                    id, refund_id, refund_line_request_id, order_line_id, first_unit_index,
                    quantity, gross_krw, coupon_attribution_krw, points_restored_krw,
                    cash_refunded_krw, source_reference, succeeded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                identifierSource.next(),
                refund.id,
                request.id,
                request.orderLineId,
                request.firstUnitIndex,
                request.quantity,
                request.grossKrw,
                request.couponKrw,
                request.pointsKrw,
                request.cashKrw,
                "${refund.sourceReference}:line:${request.orderLineId}:succeeded",
                Timestamp.from(now),
            )
        }
        pointRequests(refund.id).forEach { request ->
            jdbcTemplate.update(
                """
                INSERT INTO payment_refund_point_allocation (
                    id, refund_id, refund_point_request_id, order_line_id,
                    point_reservation_allocation_id, original_point_lot_id,
                    amount_krw, source_reference, succeeded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                identifierSource.next(),
                refund.id,
                request.id,
                request.orderLineId,
                request.pointAllocationId,
                request.originalLotId,
                request.amountKrw,
                "${refund.sourceReference}:line:${request.orderLineId}:point:${request.pointAllocationId}:succeeded",
                Timestamp.from(now),
            )
        }
        if (refund.requestedPointsKrw > 0) {
            jdbcTemplate.update(
                """
                INSERT INTO payment_refund_restoration_work (
                    id, refund_id, state, requested_amount_krw, attempt_count,
                    next_attempt_at, source_reference, created_at, updated_at
                ) VALUES (?, ?, 'PENDING', ?, 0, ?, ?, ?, ?)
                """.trimIndent(),
                identifierSource.next(),
                refund.id,
                refund.requestedPointsKrw,
                Timestamp.from(now),
                "${refund.sourceReference}:points-restoration",
                Timestamp.from(now),
                Timestamp.from(now),
            )
        }
        pointRecoveryService.createWork(refund, now)
        audit.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = "SYSTEM",
                    actorType = AuditActorType.SYSTEM,
                    action = "PARTIAL_REFUND_CASH_SUCCEEDED",
                    targetType = "PAYMENT_REFUND",
                    targetId = refund.id,
                    occurredAt = now,
                    reason = "Provider-confirmed partial Refund",
                    beforeSummary = mapOf("succeededRefundAmountKrw" to payment.succeededRefundAmountKrw.toString()),
                    afterSummary =
                        mapOf(
                            "cashRefundedKrw" to refund.requestedAmountKrw.toString(),
                            "pointsRestorationRequestedKrw" to refund.requestedPointsKrw.toString(),
                        ),
                    correlationId = refund.correlationId ?: payment.correlationId,
                    sourceReference = "${refund.sourceReference}:audit:cash-succeeded",
                ),
            ),
        )
    }

    private fun lineRequests(refundId: UUID): List<LineRequestRow> =
        jdbcTemplate.query(
            """
            SELECT id, order_line_id, first_unit_index, quantity, gross_krw,
                   coupon_attribution_krw, points_restoration_krw, cash_refund_krw
              FROM payment_refund_line_request
             WHERE refund_id = ?
             ORDER BY line_sequence, order_line_id
            """.trimIndent(),
            { rs, _ -> rs.toLineRequest() },
            refundId,
        )

    private fun pointRequests(refundId: UUID): List<PointRequestRow> =
        jdbcTemplate.query(
            """
            SELECT id, order_line_id, point_reservation_allocation_id,
                   original_point_lot_id, requested_amount_krw
              FROM payment_refund_point_request
             WHERE refund_id = ?
             ORDER BY order_line_id, point_reservation_allocation_id
            """.trimIndent(),
            { rs, _ ->
                PointRequestRow(
                    UUID.fromString(rs.getString(1)),
                    UUID.fromString(rs.getString(2)),
                    UUID.fromString(rs.getString(3)),
                    UUID.fromString(rs.getString(4)),
                    rs.getLong(5),
                )
            },
            refundId,
        )

    private fun ResultSet.toLineRequest() =
        LineRequestRow(
            UUID.fromString(getString(1)),
            UUID.fromString(getString(2)),
            getLong(3),
            getLong(4),
            getLong(5),
            getLong(6),
            getLong(7),
            getLong(8),
        )

    private data class LineRequestRow(
        val id: UUID,
        val orderLineId: UUID,
        val firstUnitIndex: Long,
        val quantity: Long,
        val grossKrw: Long,
        val couponKrw: Long,
        val pointsKrw: Long,
        val cashKrw: Long,
    )

    private data class PointRequestRow(
        val id: UUID,
        val orderLineId: UUID,
        val pointAllocationId: UUID,
        val originalLotId: UUID,
        val amountKrw: Long,
    )
}

@Service
internal class PartialRefundResponseTransaction(
    private val refundRepository: RefundJpaRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun recordOrReplay(
        refundId: UUID,
        now: Instant,
    ): PartialRefundStoredResponse {
        val refund =
            refundRepository.findLockedById(refundId)
                ?: throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Refund is missing")
        if (refund.responseStatus != null && refund.responseBody != null) {
            return PartialRefundStoredResponse(requireNotNull(refund.responseStatus), requireNotNull(refund.responseBody))
        }
        if (refund.state in setOf(RefundState.PROCESSING, RefundState.RECONCILING)) {
            throw DomainFailure(
                FailureCode.IDEMPOTENCY_REQUEST_IN_PROGRESS,
                "Refund Provider interaction is still in progress",
                retryAfterSeconds = 1,
            )
        }
        val work =
            jdbcTemplate
                .query(
                    """
                    SELECT state, restored_amount_krw, updated_at
                      FROM payment_refund_restoration_work
                     WHERE refund_id = ?
                    """.trimIndent(),
                    { rs, _ ->
                        RestorationProjection(
                            rs.getString(1),
                            (rs.getObject(2) as? Number)?.toLong(),
                            rs.getTimestamp(3).toInstant(),
                        )
                    },
                    refund.id,
                ).singleOrNull()
        val pointsState =
            when {
                refund.requestedPointsKrw == 0L -> "NOT_REQUIRED"
                refund.state != RefundState.SUCCEEDED -> "REQUESTED"
                work == null -> throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Refund restoration work is missing")
                work.state == "SUCCEEDED" -> "SUCCEEDED"
                work.state == "MANUAL_REVIEW" -> "MANUAL_REVIEW"
                else -> "PROCESSING"
            }
        val response =
            linkedMapOf<String, Any>(
                "refundId" to refund.id,
                "paymentId" to refund.paymentId,
                "state" to refund.state.name,
                "cashRefundRequestedKrw" to refund.requestedAmountKrw,
                "pointsRestorationRequestedKrw" to refund.requestedPointsKrw,
                "pointsRestorationState" to pointsState,
                "currency" to "KRW",
                "createdAt" to refund.createdAt,
                "updatedAt" to listOfNotNull(refund.updatedAt, work?.updatedAt).max(),
                "correlationId" to requireNotNull(refund.correlationId),
            )
        if (refund.state == RefundState.SUCCEEDED) response["cashRefundedKrw"] = requireNotNull(refund.succeededAmountKrw)
        if (pointsState == "SUCCEEDED") response["pointsRestoredKrw"] = requireNotNull(work?.restoredAmountKrw)
        val status =
            if (refund.state in
                setOf(
                    RefundState.REQUESTED,
                    RefundState.PROCESSING,
                    RefundState.RETRY_SCHEDULED,
                    RefundState.UNKNOWN,
                    RefundState.RECONCILING,
                )
            ) {
                HttpStatus.ACCEPTED.value()
            } else {
                HttpStatus.CREATED.value()
            }
        val body = objectMapper.writeValueAsString(response)
        refund.responseStatus = status
        refund.responseBody = body
        refund.responseRecordedAt = now
        return PartialRefundStoredResponse(status, body)
    }

    private data class RestorationProjection(
        val state: String,
        val restoredAmountKrw: Long?,
        val updatedAt: Instant,
    )
}

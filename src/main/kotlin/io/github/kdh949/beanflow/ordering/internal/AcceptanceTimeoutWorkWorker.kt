package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.operations.api.AcceptanceTimeoutWorkReprocessingCaseOperations
import io.github.kdh949.beanflow.operations.api.OpenReprocessingCaseCommand
import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.task.TaskExecutor
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal data class ClaimedAcceptanceTimeoutWork(
    val workId: UUID,
    val orderId: UUID,
    val acceptanceDeadlineAt: Instant,
    val sourceReference: String,
    val createdAt: Instant,
    val claimToken: UUID,
    val attemptCount: Int,
)

internal enum class AcceptanceTimeoutSourceOutcome {
    REJECTED,
    NOT_APPLICABLE,
    RETRY,
    SOURCE_CONFLICT,
}

internal data class AcceptanceTimeoutFailureResult(
    val state: AcceptanceTimeoutWorkState,
    val attemptCount: Int,
)

@Service
internal class AcceptanceTimeoutWorkService(
    private val works: AcceptanceTimeoutWorkJpaRepository,
    private val orders: OrderJpaRepository,
    private val reprocessingCases: AcceptanceTimeoutWorkReprocessingCaseOperations,
    private val identifiers: IdentifierSource,
    private val correlations: CorrelationIdSource,
) {
    @Transactional(readOnly = true)
    fun findDueIds(
        now: Instant,
        limit: Int,
    ): List<UUID> = works.findDueIds(now, PageRequest.of(0, limit))

    @Transactional
    fun claim(
        workId: UUID,
        now: Instant,
    ): ClaimedAcceptanceTimeoutWork? {
        val work = works.findLockedById(workId) ?: return null
        val eligible =
            (work.state == AcceptanceTimeoutWorkState.PENDING && work.nextAttemptAt?.let { !now.isBefore(it) } == true) ||
                (work.state == AcceptanceTimeoutWorkState.CLAIMED && work.claimUntil?.let { !now.isBefore(it) } == true)
        if (!eligible) return null
        if (work.attemptCount >= MAX_ATTEMPTS) {
            manualReview(work, CLAIM_LEASE_EXPIRED, now)
            return null
        }
        val token = identifiers.next()
        work.state = AcceptanceTimeoutWorkState.CLAIMED
        work.attemptCount += 1
        work.nextAttemptAt = null
        work.claimToken = token
        work.claimUntil = now.plus(CLAIM_LEASE)
        work.lastFailureCode = null
        work.updatedAt = now
        return work.toClaim(token)
    }

    @Transactional(readOnly = true)
    fun classifySource(claim: ClaimedAcceptanceTimeoutWork): AcceptanceTimeoutSourceOutcome {
        val order = orders.findById(claim.orderId).orElse(null) ?: return AcceptanceTimeoutSourceOutcome.SOURCE_CONFLICT
        if (order.acceptanceDeadlineAt != claim.acceptanceDeadlineAt) {
            return AcceptanceTimeoutSourceOutcome.SOURCE_CONFLICT
        }
        return when (order.state) {
            OrderState.REJECTED -> {
                if (order.rejectionReason == TIMEOUT_REASON &&
                    order.rejectedAt?.let { !it.isBefore(claim.acceptanceDeadlineAt) } == true
                ) {
                    AcceptanceTimeoutSourceOutcome.REJECTED
                } else {
                    AcceptanceTimeoutSourceOutcome.SOURCE_CONFLICT
                }
            }

            OrderState.ACCEPTED,
            OrderState.PREPARING,
            OrderState.READY,
            OrderState.COMPLETED,
            -> {
                if (order.acceptedAt?.isBefore(claim.acceptanceDeadlineAt) == true) {
                    AcceptanceTimeoutSourceOutcome.NOT_APPLICABLE
                } else {
                    AcceptanceTimeoutSourceOutcome.SOURCE_CONFLICT
                }
            }

            OrderState.PAID -> {
                AcceptanceTimeoutSourceOutcome.RETRY
            }

            else -> {
                AcceptanceTimeoutSourceOutcome.SOURCE_CONFLICT
            }
        }
    }

    @Transactional
    fun complete(
        claim: ClaimedAcceptanceTimeoutWork,
        outcome: AcceptanceTimeoutCompletionOutcome,
        now: Instant,
    ): Boolean {
        val work = works.findLockedById(claim.workId) ?: return false
        if (work.state != AcceptanceTimeoutWorkState.CLAIMED || work.claimToken != claim.claimToken) return false
        work.state = AcceptanceTimeoutWorkState.COMPLETED
        work.completionOutcome = outcome
        work.nextAttemptAt = null
        work.claimToken = null
        work.claimUntil = null
        work.lastFailureCode = null
        work.completedAt = now
        work.retentionExpiresAt = now.plus(RETENTION)
        work.updatedAt = now
        return true
    }

    @Transactional
    fun sourceConflict(
        claim: ClaimedAcceptanceTimeoutWork,
        now: Instant,
    ): Boolean {
        val work = works.findLockedById(claim.workId) ?: return false
        if (work.state != AcceptanceTimeoutWorkState.CLAIMED || work.claimToken != claim.claimToken) return false
        manualReview(work, TIMEOUT_SOURCE_CONFLICT, now)
        return true
    }

    @Transactional
    fun recordFailure(
        claim: ClaimedAcceptanceTimeoutWork,
        failure: RuntimeException,
        now: Instant,
    ): AcceptanceTimeoutFailureResult? {
        val work = works.findLockedById(claim.workId) ?: return null
        if (work.state != AcceptanceTimeoutWorkState.CLAIMED || work.claimToken != claim.claimToken) return null
        val failureCode = failureCode(failure)
        if (work.attemptCount >= MAX_ATTEMPTS) {
            manualReview(work, failureCode, now)
        } else {
            work.state = AcceptanceTimeoutWorkState.PENDING
            work.nextAttemptAt = now.plus(RETRY_DELAYS[work.attemptCount - 1])
            work.claimToken = null
            work.claimUntil = null
            work.lastFailureCode = failureCode
            work.updatedAt = now
        }
        return AcceptanceTimeoutFailureResult(work.state, work.attemptCount)
    }

    @Transactional
    fun purgeCompleted(
        now: Instant,
        limit: Int,
    ): Int {
        val ids = works.findRetentionDueIds(now, PageRequest.of(0, limit))
        if (ids.isNotEmpty()) works.deleteAllByIdInBatch(ids)
        return ids.size
    }

    private fun manualReview(
        work: AcceptanceTimeoutWorkEntity,
        reason: String,
        now: Instant,
    ) {
        work.state = AcceptanceTimeoutWorkState.MANUAL_REVIEW
        work.completionOutcome = null
        work.nextAttemptAt = null
        work.claimToken = null
        work.claimUntil = null
        work.lastFailureCode = reason
        work.completedAt = null
        work.retentionExpiresAt = null
        work.updatedAt = now
        reprocessingCases.openAcceptanceTimeoutWorkCase(
            OpenReprocessingCaseCommand(
                ownerReference = work.sourceReference,
                reason = reason,
                correlationId = correlations.currentOrCreate(),
                now = now,
            ),
        )
    }

    private fun AcceptanceTimeoutWorkEntity.toClaim(token: UUID) =
        ClaimedAcceptanceTimeoutWork(
            workId = id,
            orderId = orderId,
            acceptanceDeadlineAt = acceptanceDeadlineAt,
            sourceReference = sourceReference,
            createdAt = createdAt,
            claimToken = token,
            attemptCount = attemptCount,
        )

    private fun failureCode(failure: RuntimeException): String =
        when (failure) {
            is DomainFailure -> failure.code.name
            else -> UNEXPECTED_FAILURE
        }

    private companion object {
        const val MAX_ATTEMPTS = 4
        const val TIMEOUT_REASON = "STORE_ACCEPTANCE_TIMEOUT"
        const val CLAIM_LEASE_EXPIRED = "CLAIM_LEASE_EXPIRED"
        const val TIMEOUT_SOURCE_CONFLICT = "TIMEOUT_SOURCE_CONFLICT"
        const val UNEXPECTED_FAILURE = "UNEXPECTED_FAILURE"
        val CLAIM_LEASE: Duration = Duration.ofMinutes(1)
        val RETENTION: Duration = Duration.ofDays(90)
        val RETRY_DELAYS = listOf(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30))
    }
}

@Component
internal class AcceptanceTimeoutWorkWorker(
    private val workService: AcceptanceTimeoutWorkService,
    private val deadlineService: StoreAcceptanceDeadlineService,
    @Qualifier("applicationTaskExecutor") private val taskExecutor: TaskExecutor,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
    @Value("\${beanflow.acceptance-timeout-work.chunk-size:100}")
    private val chunkSize: Int,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${beanflow.acceptance-timeout-work.fixed-delay-ms:1000}",
        initialDelayString = "\${beanflow.acceptance-timeout-work.initial-delay-ms:60000}",
    )
    fun runScheduled() {
        runOnce()
    }

    @Scheduled(
        fixedDelayString = "\${beanflow.acceptance-timeout-work.retention-fixed-delay-ms:3600000}",
        initialDelayString = "\${beanflow.acceptance-timeout-work.retention-initial-delay-ms:300000}",
    )
    fun runRetentionScheduled() {
        val deleted = workService.purgeCompleted(clock.instant(), chunkSize)
        meterRegistry.counter("beanflow.order.acceptance_timeout.work.retention.deleted").increment(deleted.toDouble())
    }

    fun wake(workId: UUID) {
        try {
            taskExecutor.execute { process(workId) }
        } catch (failure: RuntimeException) {
            meterRegistry
                .counter("beanflow.order.acceptance_timeout.work.count", "state", "pending", "outcome", "wakeup_rejected")
                .increment()
            logger.warn(
                "acceptance_timeout_work state=PENDING outcome=WAKEUP_REJECTED failureType={}",
                failure.javaClass.simpleName,
            )
        }
    }

    fun runOnce(): Int {
        val ids = workService.findDueIds(clock.instant(), chunkSize)
        ids.forEach(::process)
        return ids.size
    }

    internal fun process(workId: UUID) {
        val claim = workService.claim(workId, clock.instant()) ?: return
        count("claimed", "claimed")
        try {
            val outcome = deadlineService.rejectTimedOut(claim.orderId, clock.instant())
            val sourceOutcome =
                if (outcome == StoreAcceptanceDeadlineOutcome.APPLIED) {
                    AcceptanceTimeoutSourceOutcome.REJECTED
                } else {
                    workService.classifySource(claim)
                }
            when (sourceOutcome) {
                AcceptanceTimeoutSourceOutcome.REJECTED -> {
                    complete(claim, AcceptanceTimeoutCompletionOutcome.REJECTED)
                }

                AcceptanceTimeoutSourceOutcome.NOT_APPLICABLE -> {
                    complete(claim, AcceptanceTimeoutCompletionOutcome.NOT_APPLICABLE)
                }

                AcceptanceTimeoutSourceOutcome.RETRY -> {
                    throw DomainFailure(
                        io.github.kdh949.beanflow.shared.api.FailureCode.DEPENDENCY_UNAVAILABLE,
                        "Acceptance timeout source is not terminal yet",
                    )
                }

                AcceptanceTimeoutSourceOutcome.SOURCE_CONFLICT -> {
                    if (workService.sourceConflict(claim, clock.instant())) {
                        count("manual_review", "source_conflict")
                        meterRegistry.counter("beanflow.order.acceptance_timeout.work.manual_review.count").increment()
                    }
                }
            }
        } catch (failure: RuntimeException) {
            try {
                workService.recordFailure(claim, failure, clock.instant())?.let { result ->
                    val outcome = if (result.state == AcceptanceTimeoutWorkState.MANUAL_REVIEW) "manual_review" else "retry_scheduled"
                    count(result.state.name.lowercase(), outcome)
                    if (result.state == AcceptanceTimeoutWorkState.MANUAL_REVIEW) {
                        meterRegistry.counter("beanflow.order.acceptance_timeout.work.manual_review.count").increment()
                    }
                    logger.warn(
                        "acceptance_timeout_work state={} outcome={} attempt={} failureType={}",
                        result.state,
                        outcome.uppercase(),
                        result.attemptCount,
                        failure.javaClass.simpleName,
                    )
                }
            } catch (recordFailure: RuntimeException) {
                count("claimed", "claim_retained")
                logger.error(
                    "acceptance_timeout_work state=CLAIMED outcome=CLAIM_RETAINED attempt={} failureType={}",
                    claim.attemptCount,
                    recordFailure.javaClass.simpleName,
                )
            }
        }
    }

    private fun complete(
        claim: ClaimedAcceptanceTimeoutWork,
        outcome: AcceptanceTimeoutCompletionOutcome,
    ) {
        val now = clock.instant()
        if (workService.complete(claim, outcome, now)) {
            count("completed", outcome.name.lowercase())
            meterRegistry
                .summary("beanflow.order.acceptance_timeout.work.lag", "outcome", outcome.name.lowercase())
                .record(Duration.between(claim.createdAt, now).toMillis().coerceAtLeast(0) / 1000.0)
        }
    }

    private fun count(
        state: String,
        outcome: String,
    ) {
        meterRegistry.counter("beanflow.order.acceptance_timeout.work.count", "state", state, "outcome", outcome).increment()
    }
}

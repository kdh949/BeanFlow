package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicySnapshot
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitType
import io.github.kdh949.beanflow.operations.api.OpenOrderCompensationCaseCommand
import io.github.kdh949.beanflow.operations.api.OrderCompensationBenefitPolicySnapshotView
import io.github.kdh949.beanflow.operations.api.OrderCompensationCaseView
import io.github.kdh949.beanflow.operations.api.OrderCompensationOperations
import io.github.kdh949.beanflow.operations.api.OrderCompensationState
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepState
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepType
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepView
import io.github.kdh949.beanflow.operations.api.OrderCompensationTrigger
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
internal class OrderCompensationService(
    private val caseRepository: OrderCompensationCaseJpaRepository,
    private val policyRepository: OrderCompensationBenefitPolicySnapshotJpaRepository,
    private val stepRepository: OrderCompensationStepJpaRepository,
    private val identifierSource: IdentifierSource,
    private val advisoryLock: DatabaseAdvisoryLock,
    private val meterRegistry: MeterRegistry,
) : OrderCompensationOperations {
    @Transactional
    override fun open(command: OpenOrderCompensationCaseCommand): OrderCompensationCaseView {
        advisoryLock.lock("order-compensation:${command.orderId}")
        caseRepository.findBySourceReference(command.sourceReference)?.let { existing ->
            requireReplayMatches(existing, command)
            return view(existing)
        }
        caseRepository.findByOrderId(command.orderId)?.let {
            conflict("Order compensation case already exists for a different terminal source")
        }
        val beanCase =
            OrderCompensationCaseEntity(
                id = command.caseId,
                orderId = command.orderId,
                terminalOrderVersion = command.terminalOrderVersion,
                customerId = command.customerId,
                storeId = command.storeId,
                eventId = command.eventId,
                trigger = command.trigger,
                sourceReference = command.sourceReference,
                state = OrderCompensationState.PROCESSING,
                correlationId = command.correlationId,
                createdAt = command.now,
                updatedAt = command.now,
            )
        caseRepository.save(beanCase)
        policyRepository.saveAll(
            listOf(
                policyEntity(beanCase.id, ExpiredBenefitType.COUPON, command.couponPolicy),
                policyEntity(beanCase.id, ExpiredBenefitType.POINTS, command.pointsPolicy),
            ),
        )
        val steps =
            OrderCompensationStepType.entries.map { type ->
                OrderCompensationStepEntity(
                    id = identifierSource.next(),
                    caseId = beanCase.id,
                    stepType = type,
                    state = initialState(type, command),
                    attemptCount = 0,
                    lastErrorCode = null,
                    updatedAt = command.now,
                )
            }
        stepRepository.saveAll(steps)
        afterCommit {
            recordCaseMetric(command.trigger, OrderCompensationState.PROCESSING)
            steps.forEach { recordStepMetric(command.trigger, it.stepType, it.state) }
            listOf(ExpiredBenefitType.COUPON, ExpiredBenefitType.POINTS).forEach { benefitType ->
                meterRegistry
                    .counter(
                        "beanflow.order.compensation.policy_snapshot.count",
                        "trigger",
                        command.trigger.name.lowercase(),
                        "benefit_type",
                        benefitType.name.lowercase(),
                    ).increment()
            }
        }
        return view(beanCase)
    }

    @Transactional(readOnly = true)
    override fun findByOrderId(orderId: UUID): OrderCompensationCaseView? = caseRepository.findByOrderId(orderId)?.let(::view)

    @Transactional
    override fun markPublicationManualReview(
        orderId: UUID,
        stepType: OrderCompensationStepType,
        errorCode: String,
        now: Instant,
    ): OrderCompensationCaseView = updateStep(orderId, stepType, OrderCompensationStepState.MANUAL_REVIEW, errorCode, now, false)

    @Transactional
    override fun recordStep(
        orderId: UUID,
        stepType: OrderCompensationStepType,
        stepState: OrderCompensationStepState,
        errorCode: String?,
        now: Instant,
    ): OrderCompensationCaseView = updateStep(orderId, stepType, stepState, errorCode, now, true)

    private fun updateStep(
        orderId: UUID,
        stepType: OrderCompensationStepType,
        stepState: OrderCompensationStepState,
        errorCode: String?,
        now: Instant,
        businessAttempt: Boolean,
    ): OrderCompensationCaseView {
        val found = caseRepository.findByOrderId(orderId) ?: notFound()
        val beanCase = caseRepository.findLockedById(found.id) ?: notFound()
        val step =
            stepRepository.findLocked(beanCase.id, stepType)
                ?: throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Order compensation step is missing")
        if (step.state.isTerminal()) return view(beanCase)
        step.state = stepState
        if (businessAttempt) step.attemptCount++
        step.lastErrorCode = errorCode
        step.updatedAt = now
        beanCase.updatedAt = now
        beanCase.state = deriveState(stepRepository.findAllByCaseIdOrderByStepType(beanCase.id))
        afterCommit {
            recordStepMetric(beanCase.trigger, step.stepType, step.state)
            recordCaseMetric(beanCase.trigger, beanCase.state)
            if (beanCase.state == OrderCompensationState.SUCCEEDED ||
                beanCase.state == OrderCompensationState.MANUAL_REVIEW
            ) {
                meterRegistry
                    .summary(
                        "beanflow.order.compensation.lag",
                        "trigger",
                        beanCase.trigger.name.lowercase(),
                    ).record(
                        Duration
                            .between(beanCase.createdAt, now)
                            .seconds
                            .coerceAtLeast(0)
                            .toDouble(),
                    )
            }
        }
        return view(beanCase)
    }

    private fun requireReplayMatches(
        existing: OrderCompensationCaseEntity,
        command: OpenOrderCompensationCaseCommand,
    ) {
        val policies = policyRepository.findAllByCaseIdOrderByBenefitType(existing.id).associateBy { it.benefitType }
        val matches =
            existing.id == command.caseId &&
                existing.eventId == command.eventId &&
                existing.orderId == command.orderId &&
                existing.terminalOrderVersion == command.terminalOrderVersion &&
                existing.customerId == command.customerId &&
                existing.storeId == command.storeId &&
                existing.trigger == command.trigger &&
                existing.correlationId == command.correlationId &&
                policyMatches(policies[ExpiredBenefitType.COUPON], command.couponPolicy) &&
                policyMatches(policies[ExpiredBenefitType.POINTS], command.pointsPolicy)
        if (!matches) conflict("Order compensation source was replayed with different immutable data")
    }

    private fun policyMatches(
        existing: OrderCompensationBenefitPolicySnapshotEntity?,
        policy: ExpiredBenefitRestorationPolicySnapshot,
    ): Boolean =
        existing != null &&
            existing.policyVersionId == policy.policyVersion &&
            existing.mode == policy.mode &&
            existing.compensationValidityDays == policy.compensationValidityDays

    private fun policyEntity(
        caseId: UUID,
        benefitType: ExpiredBenefitType,
        policy: ExpiredBenefitRestorationPolicySnapshot,
    ) = OrderCompensationBenefitPolicySnapshotEntity(
        caseId = caseId,
        benefitType = benefitType,
        policyVersionId = policy.policyVersion,
        mode = policy.mode,
        compensationValidityDays = policy.compensationValidityDays,
    )

    private fun initialState(
        type: OrderCompensationStepType,
        command: OpenOrderCompensationCaseCommand,
    ): OrderCompensationStepState =
        when (type) {
            OrderCompensationStepType.PAYMENT -> {
                required(command.paymentRequired)
            }

            OrderCompensationStepType.COUPON -> {
                required(command.couponRequired)
            }

            OrderCompensationStepType.POINTS -> {
                required(command.pointsRequired)
            }

            OrderCompensationStepType.CUSTOMER_NOTIFICATION -> {
                OrderCompensationStepState.PROCESSING
            }

            OrderCompensationStepType.PICKUP,
            OrderCompensationStepType.STOCK,
            -> {
                OrderCompensationStepState.PROCESSING
            }
        }

    private fun required(required: Boolean): OrderCompensationStepState =
        if (required) OrderCompensationStepState.PROCESSING else OrderCompensationStepState.NOT_REQUIRED

    private fun deriveState(steps: List<OrderCompensationStepEntity>): OrderCompensationState =
        when {
            steps.any { it.state == OrderCompensationStepState.MANUAL_REVIEW } -> OrderCompensationState.MANUAL_REVIEW
            steps.any { it.state == OrderCompensationStepState.UNKNOWN } -> OrderCompensationState.UNKNOWN
            steps.any { it.state == OrderCompensationStepState.RETRY_SCHEDULED } -> OrderCompensationState.RETRY_SCHEDULED
            steps.all { it.state.isTerminal() } -> OrderCompensationState.SUCCEEDED
            else -> OrderCompensationState.PROCESSING
        }

    private fun OrderCompensationStepState.isTerminal(): Boolean =
        this == OrderCompensationStepState.SUCCEEDED || this == OrderCompensationStepState.NOT_REQUIRED

    private fun view(beanCase: OrderCompensationCaseEntity): OrderCompensationCaseView =
        OrderCompensationCaseView(
            caseId = beanCase.id,
            orderId = beanCase.orderId,
            trigger = beanCase.trigger,
            terminalOrderVersion = beanCase.terminalOrderVersion,
            benefitPolicies =
                policyRepository.findAllByCaseIdOrderByBenefitType(beanCase.id).map {
                    OrderCompensationBenefitPolicySnapshotView(
                        benefitType = it.benefitType,
                        policyVersionId = it.policyVersionId,
                        mode = it.mode,
                        compensationValidityDays = it.compensationValidityDays,
                    )
                },
            state = beanCase.state,
            steps =
                stepRepository.findAllByCaseIdOrderByStepType(beanCase.id).map {
                    OrderCompensationStepView(it.stepType, it.state, it.attemptCount, it.lastErrorCode)
                },
            updatedAt = beanCase.updatedAt,
        )

    private fun notFound(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Order compensation case was not found")

    private fun conflict(message: String): Nothing = throw DomainFailure(FailureCode.COMPENSATION_SOURCE_CONFLICT, message)

    private fun recordCaseMetric(
        trigger: OrderCompensationTrigger,
        state: OrderCompensationState,
    ) {
        meterRegistry
            .counter(
                "beanflow.order.compensation.case.count",
                "trigger",
                trigger.name.lowercase(),
                "state",
                state.name.lowercase(),
            ).increment()
    }

    private fun recordStepMetric(
        trigger: OrderCompensationTrigger,
        type: OrderCompensationStepType,
        state: OrderCompensationStepState,
    ) {
        meterRegistry
            .counter(
                "beanflow.order.compensation.step.count",
                "trigger",
                trigger.name.lowercase(),
                "type",
                type.name.lowercase(),
                "state",
                state.name.lowercase(),
            ).increment()
    }

    private fun afterCommit(action: () -> Unit) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = action()
            },
        )
    }
}

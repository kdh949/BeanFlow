package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.OpenRejectionCompensationCaseCommand
import io.github.kdh949.beanflow.operations.api.RejectionCompensationCaseView
import io.github.kdh949.beanflow.operations.api.RejectionCompensationOperations
import io.github.kdh949.beanflow.operations.api.RejectionCompensationState
import io.github.kdh949.beanflow.operations.api.RejectionCompensationStepState
import io.github.kdh949.beanflow.operations.api.RejectionCompensationStepType
import io.github.kdh949.beanflow.operations.api.RejectionCompensationStepView
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class RejectionCompensationService(
    private val caseRepository: RejectionCompensationCaseJpaRepository,
    private val stepRepository: RejectionCompensationStepJpaRepository,
    private val identifierSource: IdentifierSource,
) : RejectionCompensationOperations {
    @Transactional
    override fun open(command: OpenRejectionCompensationCaseCommand): RejectionCompensationCaseView {
        caseRepository.findBySourceReference(command.sourceReference)?.let {
            return view(it)
        }
        val beanCase =
            RejectionCompensationCaseEntity(
                id = command.caseId,
                orderId = command.orderId,
                customerId = command.customerId,
                storeId = command.storeId,
                eventId = command.eventId,
                sourceReference = command.sourceReference,
                policyVersion = command.policy.policyVersion,
                policyMode = command.policy.mode,
                policyValidityDays = command.policy.compensationValidityDays,
                state = RejectionCompensationState.PROCESSING,
                correlationId = command.correlationId,
                createdAt = command.now,
                updatedAt = command.now,
            )
        caseRepository.save(beanCase)
        stepRepository.saveAll(
            RejectionCompensationStepType.entries.map { type ->
                RejectionCompensationStepEntity(
                    id = identifierSource.next(),
                    caseId = beanCase.id,
                    stepType = type,
                    state = initialState(type, command),
                    attemptCount = 0,
                    lastErrorCode = null,
                    updatedAt = command.now,
                )
            },
        )
        return view(beanCase)
    }

    @Transactional(readOnly = true)
    override fun findByOrderId(orderId: UUID): RejectionCompensationCaseView? = caseRepository.findByOrderId(orderId)?.let(::view)

    @Transactional
    override fun markPublicationManualReview(
        orderId: UUID,
        errorCode: String,
        now: java.time.Instant,
    ): RejectionCompensationCaseView {
        val found =
            caseRepository.findByOrderId(orderId)
                ?: throw DomainFailure(
                    FailureCode.RESOURCE_NOT_FOUND,
                    "Rejection compensation case was not found",
                )
        val beanCase =
            caseRepository.findLockedById(found.id)
                ?: throw DomainFailure(
                    FailureCode.RESOURCE_NOT_FOUND,
                    "Rejection compensation case was not found",
                )
        beanCase.state = RejectionCompensationState.MANUAL_REVIEW
        beanCase.updatedAt = now
        stepRepository
            .findAllByCaseIdOrderByStepType(beanCase.id)
            .filter {
                it.state != RejectionCompensationStepState.SUCCEEDED &&
                    it.state != RejectionCompensationStepState.NOT_REQUIRED
            }.forEach {
                it.state = RejectionCompensationStepState.MANUAL_REVIEW
                it.lastErrorCode = errorCode
                it.updatedAt = now
            }
        return view(beanCase)
    }

    private fun initialState(
        type: RejectionCompensationStepType,
        command: OpenRejectionCompensationCaseCommand,
    ): RejectionCompensationStepState =
        when (type) {
            RejectionCompensationStepType.PAYMENT -> {
                required(command.paymentRequired)
            }

            RejectionCompensationStepType.COUPON -> {
                required(command.couponRequired)
            }

            RejectionCompensationStepType.POINTS -> {
                required(command.pointsRequired)
            }

            else -> {
                RejectionCompensationStepState.PROCESSING
            }
        }

    private fun required(required: Boolean): RejectionCompensationStepState =
        if (required) {
            RejectionCompensationStepState.PROCESSING
        } else {
            RejectionCompensationStepState.NOT_REQUIRED
        }

    private fun view(beanCase: RejectionCompensationCaseEntity): RejectionCompensationCaseView =
        RejectionCompensationCaseView(
            caseId = beanCase.id,
            orderId = beanCase.orderId,
            policyVersion = beanCase.policyVersion,
            state = beanCase.state,
            steps =
                stepRepository.findAllByCaseIdOrderByStepType(beanCase.id).map {
                    RejectionCompensationStepView(
                        type = it.stepType,
                        state = it.state,
                        attemptCount = it.attemptCount,
                        lastErrorCode = it.lastErrorCode,
                    )
                },
            updatedAt = beanCase.updatedAt,
        )
}

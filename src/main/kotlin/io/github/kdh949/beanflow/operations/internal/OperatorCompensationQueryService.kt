package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.CompensationBenefitPolicyReference
import io.github.kdh949.beanflow.operations.api.CompensationStep
import io.github.kdh949.beanflow.operations.api.CompensationSummary
import io.github.kdh949.beanflow.operations.api.DetectPaymentCancellationSetupIssueCommand
import io.github.kdh949.beanflow.operations.api.OperatorCompensationQueryOperations
import io.github.kdh949.beanflow.operations.api.OperatorCompensationView
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.operations.api.OrderCompensationOperations
import io.github.kdh949.beanflow.operations.api.PaymentCancellationSetupIntegrityOperations
import io.github.kdh949.beanflow.operations.api.PaymentSetupIssue
import io.github.kdh949.beanflow.operations.api.ReadOperatorCompensationCommand
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
internal class OperatorCompensationQueryService(
    private val compensationOperations: OrderCompensationOperations,
    private val authorization: OperatorPermissionAuthorization,
    private val auditRecordOperations: AuditRecordOperations,
    private val correlationIdSource: CorrelationIdSource,
    private val identifierSource: IdentifierSource,
    private val setupQueries: PaymentCancellationSetupIntegrityQueryService,
    private val setupIntegrity: PaymentCancellationSetupIntegrityOperations,
) : OperatorCompensationQueryOperations {
    @Transactional
    override fun read(command: ReadOperatorCompensationCommand): OperatorCompensationView {
        val reason = normalizeAccessReason(command.accessReason)
        authorization.requireActive(command.actorId, OperatorPermission.ORDER_COMPENSATION_READ)
        val beanCase =
            compensationOperations.findByOrderId(command.orderId)
                ?: throw DomainFailure(
                    FailureCode.RESOURCE_NOT_FOUND,
                    "Order compensation case was not found",
                )
        val setupAssessment =
            if (beanCase.trigger == io.github.kdh949.beanflow.operations.api.OrderCompensationTrigger.CUSTOMER_CANCELLATION) {
                setupQueries.assess(command.orderId, beanCase.terminalOrderVersion)
            } else {
                null
            }
        val setupDetection =
            setupAssessment?.let {
                setupIntegrity.detect(
                    DetectPaymentCancellationSetupIssueCommand(
                        orderId = it.orderId,
                        cancellationOrderVersion = it.cancellationOrderVersion,
                        missingArtifacts = it.missingArtifacts,
                        invariantViolations = it.invariantViolations,
                        errorCode = it.errorCode,
                        correlationId = it.correlationId,
                        now = command.now,
                    ),
                )
            }
        auditRecordOperations.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = command.actorId.toString(),
                    actorType = AuditActorType.PLATFORM_OPERATOR,
                    category = AuditCategory.FINANCIAL_TRANSACTION,
                    action = "ORDER_COMPENSATION_READ",
                    targetType = "ORDER_COMPENSATION_CASE",
                    targetId = beanCase.caseId,
                    occurredAt = command.now,
                    reason = reason,
                    afterSummary =
                        mapOf(
                            "trigger" to beanCase.trigger.name,
                            "state" to beanCase.state.name,
                            "stepCount" to beanCase.steps.size.toString(),
                        ),
                    correlationId = correlationIdSource.currentOrCreate(),
                    sourceReference = "order-compensation:${beanCase.caseId}:read:${identifierSource.next()}",
                ),
            ),
        )
        return OperatorCompensationView(
            compensation =
                CompensationSummary(
                    caseId = beanCase.caseId,
                    trigger = beanCase.trigger,
                    benefitPolicies =
                        beanCase.benefitPolicies.map {
                            CompensationBenefitPolicyReference(it.benefitType, it.policyVersionId)
                        },
                    state = beanCase.state,
                    steps =
                        beanCase.steps.map {
                            CompensationStep(it.type, it.state, it.attemptCount, it.lastErrorCode)
                        },
                    updatedAt = beanCase.updatedAt,
                ),
            paymentSetupIssue =
                setupAssessment?.let {
                    PaymentSetupIssue(
                        missingArtifacts = it.missingArtifacts,
                        invariantViolations = it.invariantViolations,
                        detectedAt = requireNotNull(setupDetection).detectedAt,
                        lastErrorCode = setupDetection.lastErrorCode,
                    )
                },
            setupReprocessingCaseId = setupDetection?.caseId,
        )
    }

    private fun normalizeAccessReason(raw: String): String {
        val normalized = raw.trim()
        if (normalized.length !in 1..200 || normalized.any(Char::isISOControl)) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "X-Access-Reason is invalid")
        }
        return normalized
    }
}

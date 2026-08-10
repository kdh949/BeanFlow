package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.RetentionClass
import io.github.kdh949.beanflow.operations.api.RetentionDurationBasis
import io.github.kdh949.beanflow.operations.api.RetentionPolicyCategory
import io.github.kdh949.beanflow.operations.api.RetentionPolicyOperations
import io.github.kdh949.beanflow.operations.api.RetentionPolicyVersionSnapshot
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
internal class RetentionPolicyService(
    private val heads: RetentionPolicyHeadJpaRepository,
    private val versions: RetentionPolicyVersionJpaRepository,
) : RetentionPolicyOperations {
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    override fun current(category: RetentionPolicyCategory): RetentionPolicyVersionSnapshot =
        try {
            val head = heads.findLockedByCategory(category) ?: throw unavailable("Retention policy head is missing")
            val version =
                versions.findById(head.policyVersionId).orElseThrow {
                    unavailable("Retention policy version is missing")
                }
            validate(category, version)
            RetentionPolicyVersionSnapshot(
                policyVersionId = version.policyVersionId,
                category = version.category,
                retentionClass = version.retentionClass,
                durationBasis = version.durationBasis,
                durationValue = version.durationValue,
            )
        } catch (failure: DomainFailure) {
            throw failure
        } catch (failure: DataAccessException) {
            throw unavailable("Retention policy could not be resolved", failure)
        }

    private fun validate(
        requestedCategory: RetentionPolicyCategory,
        version: RetentionPolicyVersionEntity,
    ) {
        val expectedClass = EXPECTED_CLASS[requestedCategory]
        val expectedBasis = EXPECTED_BASIS[expectedClass]
        if (version.category != requestedCategory ||
            version.retentionClass != expectedClass ||
            version.durationBasis != expectedBasis ||
            version.durationValue <= 0
        ) {
            throw unavailable("Retention policy shape is invalid")
        }
    }

    private fun unavailable(
        message: String,
        cause: Throwable? = null,
    ): DomainFailure =
        DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message).also { failure ->
            cause?.let(failure::initCause)
        }

    private companion object {
        val EXPECTED_CLASS =
            mapOf(
                RetentionPolicyCategory.FINANCIAL_TRANSACTION to RetentionClass.FINANCIAL_AUDIT,
                RetentionPolicyCategory.ORDER_AND_FULFILLMENT to RetentionClass.FINANCIAL_AUDIT,
                RetentionPolicyCategory.SETTLEMENT_AND_DISPUTE to RetentionClass.FINANCIAL_AUDIT,
                RetentionPolicyCategory.SECURITY_AND_PERMISSION to RetentionClass.FINANCIAL_AUDIT,
                RetentionPolicyCategory.OPERATIONS_POLICY to RetentionClass.FINANCIAL_AUDIT,
                RetentionPolicyCategory.PII_ACCESS to RetentionClass.PII_ACCESS_AUDIT,
                RetentionPolicyCategory.SUPPORT_CASE to RetentionClass.SUPPORT_CASE,
                RetentionPolicyCategory.DELIVERY_CONTACT to RetentionClass.DELIVERY_CONTACT,
                RetentionPolicyCategory.CURRENT_LOCATION to RetentionClass.CURRENT_LOCATION,
                RetentionPolicyCategory.PROVIDER_RAW_WEBHOOK to RetentionClass.PROVIDER_RAW_WEBHOOK,
            )
        val EXPECTED_BASIS =
            mapOf(
                RetentionClass.FINANCIAL_AUDIT to RetentionDurationBasis.SEOUL_CALENDAR_YEARS,
                RetentionClass.PII_ACCESS_AUDIT to RetentionDurationBasis.SEOUL_CALENDAR_YEARS,
                RetentionClass.SUPPORT_CASE to RetentionDurationBasis.SEOUL_CALENDAR_YEARS_FROM_CASE_CLOSE,
                RetentionClass.DELIVERY_CONTACT to RetentionDurationBasis.EXACT_DAYS_FROM_TERMINAL,
                RetentionClass.CURRENT_LOCATION to RetentionDurationBasis.EXACT_HOURS_FROM_EVENT,
                RetentionClass.PROVIDER_RAW_WEBHOOK to RetentionDurationBasis.EXACT_DAYS_FROM_RECEIPT,
            )
    }
}

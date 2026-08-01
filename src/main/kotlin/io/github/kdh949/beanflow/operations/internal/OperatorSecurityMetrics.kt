package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationMode
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationTrigger
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitType
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

internal enum class OperatorSecurityOutcome {
    ACTIVE,
    SUCCEEDED,
    DENIED,
    INVALID_INPUT,
    NOT_FOUND,
    CONFLICT,
    DEPENDENCY_UNAVAILABLE,
}

@Component
internal class OperatorSecurityMetrics(
    private val meterRegistry: MeterRegistry,
) {
    fun authorization(
        permission: OperatorPermission,
        outcome: OperatorSecurityOutcome,
    ) = increment("beanflow.operations.permission.authorization", permission, outcome)

    fun policyRead(outcome: OperatorSecurityOutcome) {
        meterRegistry
            .counter(
                "beanflow.operations.policy.read.count",
                "outcome",
                outcome.name,
            ).increment()
    }

    fun benefitPolicyChange(
        trigger: ExpiredBenefitRestorationTrigger,
        benefitType: ExpiredBenefitType,
        mode: ExpiredBenefitRestorationMode,
        outcome: OperatorSecurityOutcome,
    ) {
        meterRegistry
            .counter(
                "beanflow.operations.benefit_policy.change.count",
                "trigger",
                trigger.name,
                "benefit_type",
                benefitType.name,
                "mode",
                mode.name,
                "outcome",
                outcome.name,
            ).increment()
    }

    fun grant(
        permission: OperatorPermission,
        action: OperatorPermissionBootstrapAction,
        outcome: OperatorSecurityOutcome,
    ) {
        increment("beanflow.operations.permission.grant.revoke.count", permission, outcome)
        meterRegistry
            .counter(
                "beanflow.operations.permission.bootstrap.count",
                "action",
                action.name,
                "outcome",
                outcome.name,
            ).increment()
    }

    private fun increment(
        name: String,
        permission: OperatorPermission,
        outcome: OperatorSecurityOutcome,
    ) {
        meterRegistry
            .counter(
                name,
                "permission",
                permission.name,
                "outcome",
                outcome.name,
            ).increment()
    }
}

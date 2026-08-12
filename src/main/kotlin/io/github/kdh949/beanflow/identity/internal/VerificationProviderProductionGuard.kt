package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.identity.api.VerificationChallengeOperations
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("prod")
internal class VerificationProviderProductionGuard(
    beanFactory: ListableBeanFactory,
) {
    init {
        val providers = beanFactory.getBeansOfType(VerificationChallengeOperations::class.java)
        check(providers.size == 1) {
            "Production requires exactly one configured VerificationChallengeOperations provider"
        }
    }
}

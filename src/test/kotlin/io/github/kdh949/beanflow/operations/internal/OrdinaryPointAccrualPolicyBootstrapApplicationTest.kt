package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.operations.api.RetentionPolicyOperations
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest(
    classes = [
        OrdinaryPointAccrualPolicyBootstrapApplication::class,
        TestcontainersConfiguration::class,
    ],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.autoconfigure.exclude=" +
            ORDINARY_POINT_ACCRUAL_POLICY_BOOTSTRAP_AUTO_CONFIGURATION_EXCLUSIONS,
    ],
)
@ActiveProfiles("ordinary-point-accrual-policy-bootstrap")
internal class OrdinaryPointAccrualPolicyBootstrapApplicationTest
    @Autowired
    constructor(
        private val lifecycle: OrdinaryPointAccrualPolicyBootstrapLifecycle,
        private val retentionPolicies: RetentionPolicyOperations,
    ) {
        @Test
        fun `narrow bootstrap context includes audit retention dependencies`() {
            assertThat(lifecycle).isNotNull
            assertThat(retentionPolicies).isInstanceOf(RetentionPolicyService::class.java)
        }
    }

package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.BeanflowSharedDatabaseTest
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyScopeType
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicySnapshot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@Import(TestcontainersConfiguration::class)
@BeanflowSharedDatabaseTest
@SpringBootTest
internal class OrdinaryPointAccrualPolicyPrecheckTest
    @Autowired
    constructor(
        private val precheck: OrdinaryPointAccrualPolicyPrecheck,
        private val headRepository: OrdinaryPointAccrualPolicyHeadJpaRepository,
    ) {
        @Test
        fun `complete explicit test GLOBAL policy satisfies the normal startup gate`() {
            precheck.run(DefaultApplicationArguments())

            val head =
                headRepository.findById(
                    OrdinaryPointAccrualPolicyHeadId(
                        OrdinaryPointAccrualPolicyScopeType.GLOBAL,
                        OrdinaryPointAccrualPolicySnapshot.GLOBAL_SCOPE_REFERENCE,
                    ),
                )
            assertThat(head).isPresent
        }

        @Test
        fun `missing GLOBAL policy fails closed instead of supplying a default`() {
            val head = headRepository.findAll().single { it.scopeType == OrdinaryPointAccrualPolicyScopeType.GLOBAL }
            headRepository.delete(head)
            headRepository.flush()
            try {
                assertThatThrownBy { precheck.run(DefaultApplicationArguments()) }
                    .isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("GLOBAL ordinary point accrual policy")
            } finally {
                headRepository.saveAndFlush(head)
            }
        }
    }

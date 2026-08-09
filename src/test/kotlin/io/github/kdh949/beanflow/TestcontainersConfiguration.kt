package io.github.kdh949.beanflow

import io.github.kdh949.beanflow.notification.internal.ScriptedTestNotificationProvider
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualExpiryRule
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyScopeType
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicySnapshot
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyState
import io.github.kdh949.beanflow.operations.api.PointAccrualIssuerType
import io.github.kdh949.beanflow.operations.api.PointAccrualRoundingMode
import io.github.kdh949.beanflow.operations.internal.OrdinaryPointAccrualPolicyHeadEntity
import io.github.kdh949.beanflow.operations.internal.OrdinaryPointAccrualPolicyHeadId
import io.github.kdh949.beanflow.operations.internal.OrdinaryPointAccrualPolicyHeadJpaRepository
import io.github.kdh949.beanflow.operations.internal.OrdinaryPointAccrualPolicyVersionEntity
import io.github.kdh949.beanflow.operations.internal.OrdinaryPointAccrualPolicyVersionJpaRepository
import io.github.kdh949.beanflow.payment.internal.PaymentGateway
import io.github.kdh949.beanflow.payment.internal.ScriptedTestPaymentGateway
import io.github.kdh949.beanflow.payment.internal.ScriptedPaymentMethodLifecycleAdapter
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    /**
     * PostgreSQL 17 with PostGIS 3.5. Spatial migrations and the nearby query must never pass on a
     * plain PostgreSQL image or on an application distance fallback, so the shared container pins
     * the extension-bearing image and declares compatibility with the PostgreSQL substitute name.
     */
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer =
        PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"),
        )

    @Bean
    fun testJwtDecoder(): JwtDecoder =
        JwtDecoder {
            throw JwtException("JWT decoding is not used outside explicit security tests")
        }

    @Bean
    internal fun testPaymentGateway(): ScriptedTestPaymentGateway = ScriptedTestPaymentGateway()

    @Bean
    internal fun testPaymentMethodLifecycleAdapter(): ScriptedPaymentMethodLifecycleAdapter =
        ScriptedPaymentMethodLifecycleAdapter()

    @Bean
    internal fun testNotificationProvider(): ScriptedTestNotificationProvider = ScriptedTestNotificationProvider()

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    internal fun explicitTestOrdinaryPointAccrualPolicy(
        versionRepository: OrdinaryPointAccrualPolicyVersionJpaRepository,
        headRepository: OrdinaryPointAccrualPolicyHeadJpaRepository,
        transactionManager: PlatformTransactionManager,
    ): ApplicationRunner =
        ApplicationRunner {
            TransactionTemplate(transactionManager).executeWithoutResult {
                val headId =
                    OrdinaryPointAccrualPolicyHeadId(
                        OrdinaryPointAccrualPolicyScopeType.GLOBAL,
                        OrdinaryPointAccrualPolicySnapshot.GLOBAL_SCOPE_REFERENCE,
                    )
                if (!headRepository.existsById(headId)) {
                    val version =
                        versionRepository.saveAndFlush(
                            OrdinaryPointAccrualPolicyVersionEntity(
                                scopeType = OrdinaryPointAccrualPolicyScopeType.GLOBAL,
                                scopeReference = OrdinaryPointAccrualPolicySnapshot.GLOBAL_SCOPE_REFERENCE,
                                state = OrdinaryPointAccrualPolicyState.OVERRIDE,
                                accrualRateBps = 100,
                                roundingMode = PointAccrualRoundingMode.FLOOR,
                                issuerType = PointAccrualIssuerType.PLATFORM,
                                issuerReference = "test:beanflow-platform",
                                expiryRule = OrdinaryPointAccrualExpiryRule.EXACT_DURATION_FROM_COMPLETION,
                                validityDays = 365,
                                effectiveAt = Instant.parse("2026-08-01T00:00:00Z"),
                                actorType = AuditActorType.SYSTEM,
                                actorReference = "testcontainers-explicit-policy",
                                reason = "Explicit Testcontainers ordinary accrual policy",
                                idempotencyActorId = null,
                                idempotencyKey = null,
                                payloadHash = TEST_POLICY_HASH,
                                sourceReference = "testcontainers:ordinary-point-accrual-policy",
                            ),
                        )
                    headRepository.saveAndFlush(
                        OrdinaryPointAccrualPolicyHeadEntity(
                            scopeType = OrdinaryPointAccrualPolicyScopeType.GLOBAL,
                            scopeReference = OrdinaryPointAccrualPolicySnapshot.GLOBAL_SCOPE_REFERENCE,
                            policyVersionId = version.policyVersionId,
                        ),
                    )
                }
            }
        }

    private companion object {
        const val TEST_POLICY_HASH = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}

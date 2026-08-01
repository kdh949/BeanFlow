package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualExpiryRule
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyScopeType
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicySnapshot
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyState
import io.github.kdh949.beanflow.operations.api.PointAccrualIssuerType
import io.github.kdh949.beanflow.operations.api.PointAccrualRoundingMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class OrdinaryPointAccrualPolicyPersistenceTest
    @Autowired
    constructor(
        private val versionRepository: OrdinaryPointAccrualPolicyVersionJpaRepository,
        private val headRepository: OrdinaryPointAccrualPolicyHeadJpaRepository,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)

        @BeforeEach
        fun removeMutableHeads() {
            headRepository.deleteAllInBatch()
        }

        @Test
        fun `override version head lock history and idempotency preserve the immutable shape`() {
            val actorId = UUID.randomUUID()
            val first = saveGlobal(rateBps = 100, actorId = actorId, key = "policy-key-0001")
            val second = saveGlobal(rateBps = 250, actorId = UUID.randomUUID(), key = "policy-key-0002")
            headRepository.saveAndFlush(
                OrdinaryPointAccrualPolicyHeadEntity(
                    scopeType = OrdinaryPointAccrualPolicyScopeType.GLOBAL,
                    scopeReference = OrdinaryPointAccrualPolicySnapshot.GLOBAL_SCOPE_REFERENCE,
                    policyVersionId = second.policyVersionId,
                ),
            )

            val locked =
                transactions.execute {
                    headRepository.findLocked(
                        OrdinaryPointAccrualPolicyScopeType.GLOBAL,
                        OrdinaryPointAccrualPolicySnapshot.GLOBAL_SCOPE_REFERENCE,
                    )
                }
            val history =
                versionRepository.findHistory(
                    OrdinaryPointAccrualPolicyScopeType.GLOBAL,
                    OrdinaryPointAccrualPolicySnapshot.GLOBAL_SCOPE_REFERENCE,
                    beforePolicyVersionId = Long.MAX_VALUE,
                    PageRequest.of(0, 2),
                )
            val replay = versionRepository.findByIdempotencyActorIdAndIdempotencyKey(actorId, "policy-key-0001")

            assertThat(locked!!.policyVersionId).isEqualTo(second.policyVersionId)
            assertThat(history.map { it.policyVersionId }).containsExactly(second.policyVersionId, first.policyVersionId)
            assertThat(replay!!.toSnapshot()).isEqualTo(first.toSnapshot())
            assertThat(replay.toSnapshot().accrualRateBps).isEqualTo(100)
        }

        @Test
        fun `value-free inherit version remains distinguishable from an override`() {
            val storeId = UUID.randomUUID()
            val inherit =
                versionRepository.saveAndFlush(
                    OrdinaryPointAccrualPolicyVersionEntity(
                        scopeType = OrdinaryPointAccrualPolicyScopeType.STORE,
                        scopeReference = storeId,
                        state = OrdinaryPointAccrualPolicyState.INHERIT_GLOBAL,
                        accrualRateBps = null,
                        roundingMode = null,
                        issuerType = null,
                        issuerReference = null,
                        expiryRule = null,
                        validityDays = null,
                        effectiveAt = Instant.parse("2026-08-01T00:00:00Z"),
                        actorType = AuditActorType.PLATFORM_OPERATOR,
                        actorReference = UUID.randomUUID().toString(),
                        reason = "Return this Store to the global policy",
                        idempotencyActorId = UUID.randomUUID(),
                        idempotencyKey = "inherit-key-0001",
                        payloadHash = HASH_B,
                        sourceReference = "operator:${UUID.randomUUID()}",
                    ),
                )

            val reloaded = versionRepository.findById(inherit.policyVersionId).orElseThrow()

            assertThat(reloaded.state).isEqualTo(OrdinaryPointAccrualPolicyState.INHERIT_GLOBAL)
            assertThat(reloaded.accrualRateBps).isNull()
            assertThat(reloaded.issuerReference).isNull()
        }

        private fun saveGlobal(
            rateBps: Int,
            actorId: UUID,
            key: String,
        ): OrdinaryPointAccrualPolicyVersionEntity =
            versionRepository.saveAndFlush(
                OrdinaryPointAccrualPolicyVersionEntity(
                    scopeType = OrdinaryPointAccrualPolicyScopeType.GLOBAL,
                    scopeReference = OrdinaryPointAccrualPolicySnapshot.GLOBAL_SCOPE_REFERENCE,
                    state = OrdinaryPointAccrualPolicyState.OVERRIDE,
                    accrualRateBps = rateBps,
                    roundingMode = PointAccrualRoundingMode.FLOOR,
                    issuerType = PointAccrualIssuerType.PLATFORM,
                    issuerReference = "beanflow-platform",
                    expiryRule = OrdinaryPointAccrualExpiryRule.EXACT_DURATION_FROM_COMPLETION,
                    validityDays = 365,
                    effectiveAt = Instant.parse("2026-08-01T00:00:00Z"),
                    actorType = AuditActorType.PLATFORM_OPERATOR,
                    actorReference = actorId.toString(),
                    reason = "Persistence mapping proof $rateBps",
                    idempotencyActorId = actorId,
                    idempotencyKey = key,
                    payloadHash = HASH_A,
                    sourceReference = "operator:${UUID.randomUUID()}",
                ),
            )

        private companion object {
            const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        }
    }

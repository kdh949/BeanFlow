package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.merchant.api.StoreSettlementTermsOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("verifies startup, DDL, or committed state across a transaction boundary")
@SpringBootTest
internal class StoreSettlementTermsRepositoryTest
    @Autowired
    constructor(
        private val operations: StoreSettlementTermsOperations,
        private val repository: StoreSettlementTermsJpaRepository,
        private val storeRepository: StoreJpaRepository,
        private val jdbcTemplate: JdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)

        @BeforeEach
        fun cleanDatabase() {
            jdbcTemplate.execute(
                "TRUNCATE TABLE merchant_store_settlement_terms, merchant_store CASCADE",
            )
        }

        @Test
        fun `applicable version changes only at the effective interval boundary`() {
            val storeId = insertStore()
            val boundary = Instant.parse("2026-08-02T00:00:00Z")
            val first = terms(storeId, 250, Instant.parse("2026-01-01T00:00:00Z"), boundary)
            val second = terms(storeId, 375, boundary, null)
            repository.saveAllAndFlush(listOf(first, second))

            val before =
                transactions.execute {
                    operations.findApplicable(storeId, boundary.minusNanos(1_000))
                }
            val at =
                transactions.execute {
                    operations.findApplicable(storeId, boundary)
                }

            assertThat(before!!.termsVersionId).isEqualTo(first.termsVersionId)
            assertThat(before.feeRateBps).isEqualTo(250)
            assertThat(at!!.termsVersionId).isEqualTo(second.termsVersionId)
            assertThat(at.feeRateBps).isEqualTo(375)
        }

        @Test
        fun `missing terms fail without a default fee rate`() {
            val storeId = insertStore()

            assertThatThrownBy {
                transactions.execute {
                    operations.findApplicable(storeId, Instant.parse("2026-08-02T00:00:00Z"))
                }
            }.isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.SETTLEMENT_INPUT_UNAVAILABLE)
            }
        }

        @Test
        fun `overlapping terms and immutable history are rejected by PostgreSQL`() {
            val storeId = insertStore()
            val first =
                terms(
                    storeId,
                    250,
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2027-01-01T00:00:00Z"),
                )
            repository.saveAndFlush(first)

            assertThatThrownBy {
                repository.saveAndFlush(
                    terms(
                        storeId,
                        375,
                        Instant.parse("2026-06-01T00:00:00Z"),
                        null,
                    ),
                )
            }.isInstanceOf(DataIntegrityViolationException::class.java)

            assertThatThrownBy {
                jdbcTemplate.update(
                    "UPDATE merchant_store_settlement_terms SET fee_rate_bps = 999 WHERE terms_version_id = ?",
                    first.termsVersionId,
                )
            }.isInstanceOf(DataIntegrityViolationException::class.java)
        }

        private fun insertStore(): UUID =
            UUID.randomUUID().also {
                storeRepository.saveAndFlush(StoreEntity(it, acceptingOrders = true, pickupEnabled = true))
            }

        private fun terms(
            storeId: UUID,
            feeRateBps: Int,
            effectiveFrom: Instant,
            effectiveTo: Instant?,
        ): StoreSettlementTermsEntity =
            StoreSettlementTermsEntity(
                termsVersionId = UUID.randomUUID(),
                storeId = storeId,
                sourceReference = "merchant-contract:${UUID.randomUUID()}",
                feeRateBps = feeRateBps,
                effectiveFrom = effectiveFrom,
                effectiveTo = effectiveTo,
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            )
    }

package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyOperations
import io.github.kdh949.beanflow.ordering.api.OrderPointAccrualSourceState
import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class OrderPointAccrualSnapshotServiceTest
    @Autowired
    constructor(
        private val orderRepository: OrderJpaRepository,
        private val lineRepository: OrderLineJpaRepository,
        private val snapshotService: OrderPointAccrualSnapshotService,
        private val policyOperations: OrdinaryPointAccrualPolicyOperations,
        private val jdbcTemplate: JdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)

        @Test
        fun `typed read returns the frozen policy hash and exact unit allocation`() {
            val orderId = persistSnapshot()

            val source = snapshotService.read(orderId)

            assertThat(source.sourceState).isEqualTo(OrderPointAccrualSourceState.SNAPSHOTTED)
            assertThat(source.snapshot!!.policy.canonicalPolicyHash).hasSize(64)
            assertThat(source.snapshot.grossAccrualAmountKrw).isEqualTo(2)
            assertThat(source.snapshot.units.map { it.accruedAmountKrw }).containsExactly(1, 1)
            assertThat(source.snapshot.units.sumOf { it.accruedAmountKrw }).isEqualTo(source.snapshot.grossAccrualAmountKrw)
        }

        @Test
        fun `missing source is corruption and never synthesized as legacy or zero bps`() {
            assertThatThrownBy { snapshotService.read(UUID.randomUUID()) }
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
                }
        }

        private fun persistSnapshot(): UUID {
            val orderId = UUID.randomUUID()
            val lineId = UUID.randomUUID()
            val storeId = UUID.randomUUID()
            val now = Instant.parse("2026-08-01T07:00:00Z")
            transactions.executeWithoutResult {
                orderRepository.save(
                    OrderEntity(
                        orderId,
                        UUID.randomUUID(),
                        storeId,
                        UUID.randomUUID(),
                        OrderState.PENDING_PAYMENT,
                        200,
                        0,
                        0,
                        200,
                        reservationExpiresAt = now.plusSeconds(300),
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                lineRepository.save(
                    OrderLineEntity(
                        lineId,
                        orderId,
                        0,
                        UUID.randomUUID(),
                        "Typed snapshot menu",
                        "[]",
                        OptionSelectionSnapshotState.SNAPSHOTTED,
                        emptyList(),
                        "[]",
                        100,
                        2,
                        200,
                        0,
                        0,
                        200,
                    ),
                )
                orderRepository.flush()
                lineRepository.flush()
                val selected = policyOperations.selectForOrder(storeId)
                val calculation =
                    OrderPointAccrualCalculator().calculate(
                        selected.policy,
                        listOf(OrderPointAccrualLineInput(lineId, 0, 100, 2, 200, 0, 0, 200)),
                    )
                snapshotService.save(orderId, 200, selected, calculation, now)
                OrderCreationDatabaseFixture.insertSettlementInputForDirectOrder(
                    jdbcTemplate,
                    orderId,
                    storeId,
                    200,
                    now,
                )
            }
            return orderId
        }
    }

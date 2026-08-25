package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyOperations
import io.github.kdh949.beanflow.ordering.api.OrderPointAccrualSourceState
import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest
internal class OrderPointAccrualSnapshotPersistenceTest
    @Autowired
    constructor(
        private val orderRepository: OrderJpaRepository,
        private val lineRepository: OrderLineJpaRepository,
        private val snapshotService: OrderPointAccrualSnapshotService,
        private val policyOperations: OrdinaryPointAccrualPolicyOperations,
        private val sourceRepository: OrderPointAccrualSourceJpaRepository,
        private val snapshotRepository: OrderPointAccrualSnapshotJpaRepository,
        private val unitRepository: OrderPointAccrualUnitJpaRepository,
        private val jdbcTemplate: JdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)

        @Test
        fun `source header and conceptual units persist atomically and reject mutation`() {
            val fixture = persistSnapshot()

            assertThat(sourceRepository.findById(fixture.orderId).orElseThrow().sourceState)
                .isEqualTo(OrderPointAccrualSourceState.SNAPSHOTTED)
            assertThat(snapshotRepository.findById(fixture.orderId).orElseThrow().grossAccrualAmountKrw).isEqualTo(2)
            assertThat(unitRepository.findAllByOrderIdOrderByLineSequenceAscUnitPositionAsc(fixture.orderId))
                .extracting<Long> { it.accruedAmountKrw }
                .containsExactly(1, 1)
            assertThatThrownBy {
                jdbcTemplate.update(
                    "UPDATE ordering_order_point_accrual_snapshot SET gross_accrual_amount_krw = 1 WHERE order_id = ?",
                    fixture.orderId,
                )
            }.isInstanceOf(DataIntegrityViolationException::class.java)
        }

        private fun persistSnapshot(): Fixture {
            val orderId = UUID.randomUUID()
            val orderLineId = UUID.randomUUID()
            val storeId = UUID.randomUUID()
            val now = Instant.parse("2026-08-01T06:00:00Z")
            transactions.executeWithoutResult {
                OrderCreationDatabaseFixture.registerPublicReference(jdbcTemplate, orderId, now)
                orderRepository.save(
                    OrderEntity(
                        id = orderId,
                        customerId = UUID.randomUUID(),
                        storeId = storeId,
                        pickupSlotId = UUID.randomUUID(),
                        publicReference = OrderCreationDatabaseFixture.registeredReference(orderId),
                        pickupBusinessDate = LocalDate.parse("2026-08-01"),
                        pickupSequence = 1,
                        storeNameSnapshot = "Test Store",
                        pickupWindowStartSnapshot = now.plusSeconds(600),
                        pickupWindowEndSnapshot = now.plusSeconds(1_200),
                        state = OrderState.PENDING_PAYMENT,
                        subtotalKrw = 200,
                        couponDiscountKrw = 0,
                        pointsAppliedKrw = 0,
                        payableKrw = 200,
                        reservationExpiresAt = now.plusSeconds(300),
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                lineRepository.save(
                    OrderLineEntity(
                        orderLineId,
                        orderId,
                        0,
                        UUID.randomUUID(),
                        "Snapshot menu",
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
                        listOf(OrderPointAccrualLineInput(orderLineId, 0, 100, 2, 200, 0, 0, 200)),
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
            return Fixture(orderId)
        }

        private data class Fixture(
            val orderId: UUID,
        )
    }

package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicyOperations
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicySnapshot
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationTrigger
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitType
import io.github.kdh949.beanflow.operations.api.OpenOrderCompensationCaseCommand
import io.github.kdh949.beanflow.operations.api.OrderCompensationOperations
import io.github.kdh949.beanflow.operations.api.OrderCompensationState
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepState
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepType
import io.github.kdh949.beanflow.operations.api.OrderCompensationTrigger
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@SpringBootTest
internal class OrderCompensationPersistenceTest
    @Autowired
    constructor(
        private val operations: OrderCompensationOperations,
        private val policies: ExpiredBenefitRestorationPolicyOperations,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        @BeforeEach
        fun cleanDatabase() {
            jdbcTemplate.execute("TRUNCATE TABLE operations_order_compensation_case CASCADE")
        }

        @Test
        fun `case stores exactly two policies and six steps and exact replay is idempotent`() {
            val command = command()

            val first = operations.open(command)
            val replay = operations.open(command)

            assertThat(replay).isEqualTo(first)
            assertThat(first.trigger).isEqualTo(OrderCompensationTrigger.STORE_REJECTION)
            assertThat(first.benefitPolicies).hasSize(2)
            assertThat(first.steps).hasSize(6)
            assertThat(count("operations_order_compensation_case")).isEqualTo(1)
            assertThat(count("operations_order_compensation_benefit_policy_snapshot")).isEqualTo(2)
            assertThat(count("operations_order_compensation_step")).isEqualTo(6)
        }

        @Test
        fun `same source with different trigger or policy is a conflict`() {
            val command = command()
            operations.open(command)

            val changedTrigger = command.copy(trigger = OrderCompensationTrigger.CUSTOMER_CANCELLATION)
            val changedPolicy =
                command.copy(
                    pointsPolicy =
                        command.pointsPolicy.copy(
                            compensationValidityDays = command.pointsPolicy.compensationValidityDays + 1,
                        ),
                )

            assertConflict { operations.open(changedTrigger) }
            assertConflict { operations.open(changedPolicy) }
            assertThat(count("operations_order_compensation_case")).isEqualTo(1)
        }

        @Test
        fun `customer cancellation foundation keeps the primary notification step pending`() {
            val orderId = UUID.randomUUID()
            val command =
                command(
                    orderId = orderId,
                    trigger = OrderCompensationTrigger.CUSTOMER_CANCELLATION,
                    couponPolicy = current(ExpiredBenefitRestorationTrigger.CUSTOMER_CANCELLATION, ExpiredBenefitType.COUPON),
                    pointsPolicy = current(ExpiredBenefitRestorationTrigger.CUSTOMER_CANCELLATION, ExpiredBenefitType.POINTS),
                    sourceReference = "order:$orderId:customer-cancellation:7",
                )

            val view = operations.open(command)

            assertThat(view.steps).hasSize(6)
            assertThat(view.steps.single { it.type == OrderCompensationStepType.CUSTOMER_NOTIFICATION }.state)
                .isEqualTo(OrderCompensationStepState.PROCESSING)
            assertThat(view.benefitPolicies).hasSize(2)
        }

        @Test
        fun `publication exhaustion changes only one step without a business attempt`() {
            val command = command()
            operations.open(command)

            val view =
                operations.markPublicationManualReview(
                    command.orderId,
                    OrderCompensationStepType.COUPON,
                    "EVENT_PUBLICATION_RETRY_EXHAUSTED",
                    NOW.plusSeconds(1),
                )

            val coupon = view.steps.single { it.type == OrderCompensationStepType.COUPON }
            val pickup = view.steps.single { it.type == OrderCompensationStepType.PICKUP }
            assertThat(coupon.state).isEqualTo(OrderCompensationStepState.MANUAL_REVIEW)
            assertThat(coupon.attemptCount).isZero()
            assertThat(pickup.state).isEqualTo(OrderCompensationStepState.PROCESSING)
            assertThat(view.state).isEqualTo(OrderCompensationState.MANUAL_REVIEW)
        }

        @Test
        fun `succeeded and not required steps are never reopened`() {
            val command = command(paymentRequired = false, couponRequired = false, pointsRequired = false)
            operations.open(command)
            listOf(
                OrderCompensationStepType.PICKUP,
                OrderCompensationStepType.STOCK,
                OrderCompensationStepType.CUSTOMER_NOTIFICATION,
            ).forEachIndexed { index, step ->
                operations.recordStep(
                    command.orderId,
                    step,
                    OrderCompensationStepState.SUCCEEDED,
                    null,
                    NOW.plusSeconds(index.toLong() + 1),
                )
            }

            val completed = requireNotNull(operations.findByOrderId(command.orderId))
            assertThat(completed.state).isEqualTo(OrderCompensationState.SUCCEEDED)
            val replay =
                operations.recordStep(
                    command.orderId,
                    OrderCompensationStepType.PICKUP,
                    OrderCompensationStepState.UNKNOWN,
                    "LATE_FAILURE",
                    NOW.plusSeconds(10),
                )

            val pickup = replay.steps.single { it.type == OrderCompensationStepType.PICKUP }
            assertThat(pickup.state).isEqualTo(OrderCompensationStepState.SUCCEEDED)
            assertThat(pickup.attemptCount).isEqualTo(1)
            assertThat(replay.state).isEqualTo(OrderCompensationState.SUCCEEDED)
        }

        @Test
        fun `concurrent duplicate open converges to one case and two policies`() {
            val command = command()
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            val results =
                (1..2)
                    .map {
                        executor.submit<Result<UUID>> {
                            barrier.await()
                            runCatching { operations.open(command).caseId }
                        }
                    }.map { it.get(10, TimeUnit.SECONDS) }
            executor.shutdown()

            assertThat(results).allMatch { it.isSuccess }
            assertThat(results.map { it.getOrThrow() }).containsOnly(command.caseId)
            assertThat(count("operations_order_compensation_case")).isEqualTo(1)
            assertThat(count("operations_order_compensation_benefit_policy_snapshot")).isEqualTo(2)
            assertThat(count("operations_order_compensation_step")).isEqualTo(6)
        }

        private fun command(
            paymentRequired: Boolean = true,
            couponRequired: Boolean = true,
            pointsRequired: Boolean = true,
            orderId: UUID = UUID.randomUUID(),
            trigger: OrderCompensationTrigger = OrderCompensationTrigger.STORE_REJECTION,
            couponPolicy: ExpiredBenefitRestorationPolicySnapshot =
                current(ExpiredBenefitRestorationTrigger.STORE_REJECTION, ExpiredBenefitType.COUPON),
            pointsPolicy: ExpiredBenefitRestorationPolicySnapshot =
                current(ExpiredBenefitRestorationTrigger.STORE_REJECTION, ExpiredBenefitType.POINTS),
            sourceReference: String = "order:$orderId:rejection:7",
        ): OpenOrderCompensationCaseCommand =
            OpenOrderCompensationCaseCommand(
                caseId = UUID.randomUUID(),
                eventId = UUID.randomUUID(),
                orderId = orderId,
                terminalOrderVersion = 7,
                customerId = UUID.randomUUID(),
                storeId = UUID.randomUUID(),
                trigger = trigger,
                sourceReference = sourceReference,
                couponPolicy = couponPolicy,
                pointsPolicy = pointsPolicy,
                paymentRequired = paymentRequired,
                couponRequired = couponRequired,
                pointsRequired = pointsRequired,
                correlationId = "compensation-test-$orderId",
                now = NOW,
            )

        private fun current(
            trigger: ExpiredBenefitRestorationTrigger,
            benefitType: ExpiredBenefitType,
        ): ExpiredBenefitRestorationPolicySnapshot = policies.current(trigger, benefitType)

        private fun count(table: String): Long =
            requireNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Long::class.java))

        private fun assertConflict(block: () -> Unit) {
            val failure = runCatching(block).exceptionOrNull()
            assertThat(failure).isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.COMPENSATION_SOURCE_CONFLICT)
            }
        }

        private companion object {
            val NOW: Instant = Instant.parse("2026-08-03T10:00:00Z")
        }
    }

package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.api.StoredHttpResponse
import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import io.github.kdh949.beanflow.payment.internal.PaymentMethodApplicationService
import io.github.kdh949.beanflow.payment.internal.RegisterPaymentMethodCommand
import io.github.kdh949.beanflow.payment.internal.ScriptedTestPaymentGateway
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.payment-method.maintenance.initial-delay-ms=3600000",
        "beanflow.payment-method.retention.initial-delay-ms=3600000",
    ],
)
internal class PaymentMethodPaymentConcurrencyIntegrationTest
    @Autowired
    constructor(
        private val createOrders: CreateOrderUseCase,
        private val confirmations: PaymentConfirmationService,
        private val paymentMethods: PaymentMethodApplicationService,
        private val gateway: ScriptedTestPaymentGateway,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        private lateinit var executor: ExecutorService

        @BeforeEach
        fun setUp() {
            OrderCreationDatabaseFixture.clean(jdbcTemplate)
            gateway.reset()
            executor = Executors.newSingleThreadExecutor()
        }

        @AfterEach
        fun tearDown() {
            executor.shutdownNow()
        }

        @Test
        fun `Tx1 winner keeps immutable snapshot usable after D1 deactivates method`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "snapshot-race-order")
            val methodId = register(fixture.customerId, "snapshot-race-register")
            val tokenBefore = token(methodId)
            val block = gateway.blockNextApproval()
            gateway.enqueueApproval(ProviderPaymentResult.Approved("snapshot-race-approval", 1_000, "KRW"))
            val future =
                executor.submit<StoredHttpResponse> {
                    confirmations.confirm(fixture.customerId, orderId, methodId, "snapshot-race-payment")
                }

            try {
                assertThat(block.awaitStarted()).isTrue()
                assertThat(paymentMethods.deactivate(fixture.customerId, methodId, "snapshot-race-delete").status)
                    .isEqualTo(204)
                assertThat(methodStatus(methodId)).isEqualTo("DEACTIVATED")
            } finally {
                block.release()
            }

            assertThat(future.get(5, TimeUnit.SECONDS).status).isEqualTo(200)
            assertThat(
                jdbcTemplate.queryForObject(
                    """
                    SELECT snapshot.token_reference
                      FROM payment_provider_request_snapshot snapshot
                      JOIN payment_payment payment ON payment.id = snapshot.payment_id
                     WHERE payment.order_id = ?
                    """.trimIndent(),
                    String::class.java,
                    orderId,
                ),
            ).isEqualTo(tokenBefore)
        }

        @Test
        fun `D1 winner rejects later Tx1 before Payment idempotency and snapshot creation`() {
            val fixture = OrderCreationFixture()
            val orderId = pendingOrder(fixture, "deactivation-race-order")
            val methodId = register(fixture.customerId, "deactivation-race-register")

            assertThat(paymentMethods.deactivate(fixture.customerId, methodId, "deactivation-race-delete").status)
                .isEqualTo(204)
            assertThatThrownBy {
                confirmations.confirm(fixture.customerId, orderId, methodId, "deactivation-race-payment")
            }.isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.PAYMENT_METHOD_STATE_CONFLICT)
            }

            assertThat(gateway.approvalCalls).hasValue(0)
            assertThat(
                jdbcTemplate.queryForObject("SELECT count(*) FROM payment_payment", Long::class.java),
            ).isZero()
            assertThat(
                jdbcTemplate.queryForObject("SELECT count(*) FROM payment_provider_request_snapshot", Long::class.java),
            ).isZero()
        }

        private fun pendingOrder(
            fixture: OrderCreationFixture,
            key: String,
        ): UUID {
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            assertThat(createOrders.create(key, fixture.command()).status).isEqualTo(201)
            return jdbcTemplate.queryForObject("SELECT id FROM ordering_order", UUID::class.java)!!
        }

        private fun register(
            customerId: UUID,
            key: String,
        ): UUID {
            val body =
                paymentMethods
                    .register(
                        RegisterPaymentMethodCommand(customerId, key, "issued:$key", "Race card"),
                    ).body
            return UUID.fromString(Regex("\"paymentMethodId\":\"([^\"]+)\"").find(body)!!.groupValues[1])
        }

        private fun token(methodId: UUID): String =
            jdbcTemplate.queryForObject(
                "SELECT token_reference FROM payment_method WHERE id = ?",
                String::class.java,
                methodId,
            )!!

        private fun methodStatus(methodId: UUID): String =
            jdbcTemplate.queryForObject("SELECT status FROM payment_method WHERE id = ?", String::class.java, methodId)!!
    }

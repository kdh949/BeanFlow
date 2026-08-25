package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.api.StoredHttpResponse
import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import io.github.kdh949.beanflow.payment.internal.ScriptedTestPaymentGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest(
    properties = [
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.connection-timeout=1000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
    ],
)
internal class PaymentConnectionBoundaryTest
    @Autowired
    constructor(
        private val createOrderUseCase: CreateOrderUseCase,
        private val confirmationService: PaymentConfirmationService,
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
        fun `Provider wait does not retain the only database connection`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            assertThat(createOrderUseCase.create("payment-pool-order", fixture.command()).status).isEqualTo(201)
            val orderId =
                requireNotNull(
                    jdbcTemplate.queryForObject("SELECT id FROM ordering_order", UUID::class.java),
                )
            val methodId = insertPaymentMethod(fixture.customerId)
            val block = gateway.blockNextApproval()
            gateway.enqueueApproval(ProviderPaymentResult.Approved("provider-pool-approval", 1_000, "KRW"))
            val future =
                executor.submit<StoredHttpResponse> {
                    confirmationService.confirm(
                        fixture.customerId,
                        orderId,
                        methodId,
                        "payment-pool-key",
                    )
                }

            try {
                assertThat(block.awaitStarted()).isTrue()
                assertThat(
                    jdbcTemplate.queryForObject("SELECT count(*) FROM ordering_order", Long::class.java),
                ).isEqualTo(1)
            } finally {
                block.release()
            }

            assertThat(future.get(5, TimeUnit.SECONDS).status).isEqualTo(200)
        }

        private fun insertPaymentMethod(customerId: UUID): UUID {
            val id = UUID.randomUUID()
            val now = Timestamp.from(Instant.now())
            jdbcTemplate.update(
                """
                INSERT INTO payment_method (
                    id, customer_id, provider, token_reference, display_alias, card_brand,
                    last_four, status, created_at, updated_at, version
                )
                VALUES (?, ?, 'SCRIPTED', ?, 'Pool test', 'TEST', '4242', 'ACTIVE', ?, ?, 0)
                """.trimIndent(),
                id,
                customerId,
                "test-token:$id",
                now,
                now,
            )
            return id
        }
    }

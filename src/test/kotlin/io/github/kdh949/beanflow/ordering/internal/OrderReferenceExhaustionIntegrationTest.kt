package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean

@Import(TestcontainersConfiguration::class)
@SpringBootTest
internal class OrderReferenceExhaustionIntegrationTest
    @Autowired
    constructor(
        private val orders: CreateOrderUseCase,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        @MockitoBean
        private lateinit var entropy: PublicOrderReferenceEntropy

        @BeforeEach
        fun prepareCollision() {
            OrderCreationDatabaseFixture.clean(jdbcTemplate)
            `when`(entropy.nextIndex(anyInt())).thenReturn(0)
            jdbcTemplate.update(
                "INSERT INTO ordering_public_reference_registry (public_reference, allocated_at) VALUES " +
                    "('BF-2222-2222', now())",
            )
        }

        @Test
        fun `five public reference collisions return 503 and roll back the complete order transaction`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)

            val result = orders.create("reference-exhaustion", fixture.command())

            assertThat(result.status).isEqualTo(503)
            assertThat(result.body).contains("\"code\":\"ORDER_REFERENCE_EXHAUSTED\"")
            assertThat(count("ordering_order")).isZero()
            assertThat(count("ordering_pickup_counter")).isZero()
            assertThat(count("fulfillment_pickup_reservation")).isZero()
            assertThat(count("inventory_stock_reservation")).isZero()
            assertThat(count("ordering_public_reference_registry")).isOne()
        }

        private fun count(table: String): Long =
            requireNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Long::class.java))
    }

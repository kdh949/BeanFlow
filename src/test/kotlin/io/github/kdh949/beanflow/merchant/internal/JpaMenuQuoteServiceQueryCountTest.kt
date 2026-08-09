package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.merchant.api.CurrentMenuLineQuoteResult
import io.github.kdh949.beanflow.merchant.api.MenuQuoteUseCase
import io.github.kdh949.beanflow.merchant.api.QuoteOrderLine
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.SessionFactory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["spring.jpa.properties.hibernate.generate_statistics=true"])
internal class JpaMenuQuoteServiceQueryCountTest
    @Autowired
    constructor(
        private val quoteUseCase: MenuQuoteUseCase,
        private val jdbcTemplate: JdbcTemplate,
        entityManagerFactory: EntityManagerFactory,
    ) {
        private val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics

        @BeforeEach
        fun cleanDatabase() {
            jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    merchant_menu_configuration_requirement,
                    merchant_menu_configuration,
                    merchant_menu_option,
                    merchant_menu,
                    merchant_store
                CASCADE
                """.trimIndent(),
            )
        }

        @Test
        fun `batch quote uses five statements regardless of menu and configuration count`() {
            val small = seed(storeSequence = 1, menuCount = 1, configurationsPerMenu = 1)
            val smallCount = countStatements { quoteUseCase.quoteCurrentBatch(small.storeId, small.lines) }

            cleanDatabase()
            val large = seed(storeSequence = 2, menuCount = 20, configurationsPerMenu = 4)
            val largeCount =
                countStatements {
                    val quotes = quoteUseCase.quoteCurrentBatch(large.storeId, large.lines)
                    assertThat(quotes).allMatch { it is CurrentMenuLineQuoteResult.Available }
                }

            assertThat(smallCount).isEqualTo(5)
            assertThat(largeCount).isEqualTo(5)
        }

        private fun seed(
            storeSequence: Int,
            menuCount: Int,
            configurationsPerMenu: Int,
        ): QuoteFixture {
            val storeId = uuid(storeSequence, 0, 0)
            jdbcTemplate.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled) VALUES (?, true, true)",
                storeId,
            )
            val lines =
                (1..menuCount).map { menuSequence ->
                    val menuId = uuid(storeSequence, menuSequence, 0)
                    val selectedOptionId = uuid(storeSequence, menuSequence, 1)
                    jdbcTemplate.update(
                        "INSERT INTO merchant_menu (id, store_id, name, base_price_krw, available) " +
                            "VALUES (?, ?, ?, 1000, true)",
                        menuId,
                        storeId,
                        "Menu $menuSequence",
                    )
                    jdbcTemplate.update(
                        "INSERT INTO merchant_menu_option (id, menu_id, name, additional_price_krw, available) " +
                            "VALUES (?, ?, 'Selected', 100, true)",
                        selectedOptionId,
                        menuId,
                    )
                    (1..configurationsPerMenu).forEach { configurationSequence ->
                        val configurationId = uuid(storeSequence, menuSequence, 100 + configurationSequence)
                        val optionId =
                            if (configurationSequence == 1) {
                                selectedOptionId
                            } else {
                                uuid(storeSequence, menuSequence, 10 + configurationSequence).also {
                                    jdbcTemplate.update(
                                        "INSERT INTO merchant_menu_option " +
                                            "(id, menu_id, name, additional_price_krw, available) " +
                                            "VALUES (?, ?, ?, 100, true)",
                                        it,
                                        menuId,
                                        "Alternative $configurationSequence",
                                    )
                                }
                            }
                        jdbcTemplate.update(
                            "INSERT INTO merchant_menu_configuration " +
                                "(id, menu_id, normalized_option_key, available) VALUES (?, ?, ?, true)",
                            configurationId,
                            menuId,
                            optionId.toString(),
                        )
                        jdbcTemplate.update(
                            "INSERT INTO merchant_menu_configuration_requirement " +
                                "(id, menu_configuration_id, sellable_unit_id, quantity_per_line_unit) " +
                                "VALUES (?, ?, ?, 1)",
                            uuid(storeSequence, menuSequence, 200 + configurationSequence),
                            configurationId,
                            uuid(storeSequence, menuSequence, 300 + configurationSequence),
                        )
                    }
                    QuoteOrderLine(menuId, listOf(selectedOptionId), 1)
                }
            return QuoteFixture(storeId, lines)
        }

        private fun countStatements(block: () -> Unit): Long {
            statistics.clear()
            block()
            return statistics.prepareStatementCount
        }

        private fun uuid(
            storeSequence: Int,
            menuSequence: Int,
            itemSequence: Int,
        ): UUID = UUID.nameUUIDFromBytes("quote:$storeSequence:$menuSequence:$itemSequence".toByteArray())

        private data class QuoteFixture(
            val storeId: UUID,
            val lines: List<QuoteOrderLine>,
        )
    }

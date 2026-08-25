package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.merchant.api.MenuImageOperations
import io.github.kdh949.beanflow.merchant.api.PreparedStorefrontImage
import io.github.kdh949.beanflow.merchant.api.StorefrontImageReferenceOperations
import io.github.kdh949.beanflow.merchant.api.StorefrontImageStorageOperations
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("verifies concurrent image replacement across transaction boundaries")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class MenuImageConcurrencyIntegrationTest(
    @Autowired private val images: MenuImageOperations,
    @Autowired private val references: StorefrontImageReferenceOperations,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired transactionManager: PlatformTransactionManager,
) {
    @MockitoBean
    private lateinit var storage: StorefrontImageStorageOperations

    private val transactions = TransactionTemplate(transactionManager)

    @BeforeEach
    fun cleanDatabase() {
        jdbc.execute("TRUNCATE TABLE event_publication, merchant_menu, merchant_store CASCADE")
    }

    @Test
    fun `same hash is version stable and concurrent replacements serialize without a lost update`() {
        val storeId = UUID.randomUUID()
        val menuId = UUID.randomUUID()
        seed(storeId, menuId)
        replace(storeId, menuId, prepared('a'), NOW)
        assertThat(version(menuId)).isEqualTo(1)
        assertThat(references.isReferenced(prepared('a').originalKey)).isTrue()
        assertThat(references.isReferenced(prepared('a').thumbnailKey)).isTrue()
        assertThat(references.isReferenced("menus/unreferenced/original.jpg")).isFalse()

        transactions.executeWithoutResult { images.replace(storeId, menuId, prepared('a'), NOW.plusSeconds(1)) }
        assertThat(version(menuId)).isEqualTo(1)

        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures =
                listOf('b', 'c').mapIndexed { index, value ->
                    executor.submit {
                        start.await(5, TimeUnit.SECONDS)
                        replace(storeId, menuId, prepared(value), NOW.plusSeconds((index + 2).toLong()))
                    }
                }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertThat(version(menuId)).isEqualTo(3)
        assertThat(jdbc.queryForObject("SELECT image_sha256 FROM merchant_menu WHERE id = ?", String::class.java, menuId))
            .isIn("b".repeat(64), "c".repeat(64))
    }

    private fun replace(
        storeId: UUID,
        menuId: UUID,
        prepared: PreparedStorefrontImage,
        now: Instant,
    ) {
        transactions.executeWithoutResult { images.replace(storeId, menuId, prepared, now) }
    }

    private fun seed(
        storeId: UUID,
        menuId: UUID,
    ) {
        jdbc.update("INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)", storeId)
        jdbc.update(
            "INSERT INTO merchant_menu (id, store_id, name, base_price_krw, available, version) " +
                "VALUES (?, ?, 'Latte', 5000, true, 0)",
            menuId,
            storeId,
        )
    }

    private fun version(menuId: UUID): Long =
        requireNotNull(jdbc.queryForObject("SELECT version FROM merchant_menu WHERE id = ?", Long::class.java, menuId))

    private fun prepared(value: Char): PreparedStorefrontImage {
        val hash = value.toString().repeat(64)
        return PreparedStorefrontImage("menus/id/$hash/original.jpg", "menus/id/$hash/thumbnail.jpg", hash)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-24T00:00:00Z")
    }
}

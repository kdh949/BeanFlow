package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest
internal class OrderDisplayIdentityAllocatorIntegrationTest
    @Autowired
    constructor(
        private val pickupSequences: PickupSequenceAllocator,
        private val displayIdentities: OrderDisplayIdentityAllocator,
        private val jdbcTemplate: JdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transaction = TransactionTemplate(transactionManager)

        @BeforeEach
        fun clean() {
            jdbcTemplate.execute(
                "TRUNCATE ordering_order, ordering_pickup_counter, ordering_public_reference_registry CASCADE",
            )
        }

        @Test
        fun `same store and business date allocates unique monotonic sequences concurrently`() {
            val storeId = UUID.randomUUID()
            val businessDate = LocalDate.parse("2030-01-01")
            val count = 20
            val barrier = CyclicBarrier(count)
            val pool = Executors.newFixedThreadPool(count)

            val futures =
                (1..count).map {
                    pool.submit<Long> {
                        barrier.await()
                        requireNotNull(transaction.execute { pickupSequences.next(storeId, businessDate) })
                    }
                }
            val allocated = futures.map { it.get(20, TimeUnit.SECONDS) }
            pool.shutdown()

            assertThat(allocated).containsExactlyInAnyOrderElementsOf((1L..count.toLong()).toList())
            assertThat(allocated.distinct()).hasSize(count)
        }

        @Test
        fun `different store or business date starts independently at one`() {
            val firstStore = UUID.randomUUID()
            val secondStore = UUID.randomUUID()
            val firstDate = LocalDate.parse("2030-01-01")
            val secondDate = firstDate.plusDays(1)

            assertThat(transaction.execute { pickupSequences.next(firstStore, firstDate) }).isEqualTo(1)
            assertThat(transaction.execute { pickupSequences.next(firstStore, secondDate) }).isEqualTo(1)
            assertThat(transaction.execute { pickupSequences.next(secondStore, firstDate) }).isEqualTo(1)
        }

        @Test
        fun `committed display identities and ended orders never return a pickup sequence`() {
            val storeId = UUID.randomUUID()
            val pickupStart = Instant.parse("2030-01-01T00:10:00Z")

            val endedIdentity = requireNotNull(transaction.execute { displayIdentities.allocate(storeId, pickupStart) })
            val nextIdentity = requireNotNull(transaction.execute { displayIdentities.allocate(storeId, pickupStart) })

            assertThat(endedIdentity.pickupSequence).isEqualTo(1)
            assertThat(nextIdentity.pickupSequence).isEqualTo(2)
            assertThat(count("ordering_pickup_counter")).isOne()
            assertThat(count("ordering_public_reference_registry")).isEqualTo(2)
        }

        @Test
        fun `Seoul midnight assigns adjacent UTC pickup windows to independent business dates`() {
            val storeId = UUID.randomUUID()

            val beforeMidnight =
                requireNotNull(
                    transaction.execute {
                        displayIdentities.allocate(storeId, Instant.parse("2030-01-01T14:59:59Z"))
                    },
                )
            val atMidnight =
                requireNotNull(
                    transaction.execute {
                        displayIdentities.allocate(storeId, Instant.parse("2030-01-01T15:00:00Z"))
                    },
                )

            assertThat(beforeMidnight.pickupBusinessDate).isEqualTo(LocalDate.parse("2030-01-01"))
            assertThat(atMidnight.pickupBusinessDate).isEqualTo(LocalDate.parse("2030-01-02"))
            assertThat(beforeMidnight.pickupSequence).isOne()
            assertThat(atMidnight.pickupSequence).isOne()
        }

        @Test
        fun `order transaction rollback releases both counter increment and public reference reservation`() {
            assertThatThrownBy {
                transaction.executeWithoutResult {
                    displayIdentities.allocate(UUID.randomUUID(), Instant.parse("2030-01-01T00:10:00Z"))
                    error("rollback")
                }
            }.isInstanceOf(IllegalStateException::class.java)

            assertThat(count("ordering_pickup_counter")).isZero()
            assertThat(count("ordering_public_reference_registry")).isZero()
        }

        @Test
        fun `public reference reservation regenerates after a registry collision`() {
            val collision = "BF-2345-6789"
            val regenerated = "BF-ABCD-EFGH"
            jdbcTemplate.update(
                "INSERT INTO ordering_public_reference_registry (public_reference, allocated_at) VALUES (?, now())",
                collision,
            )
            val registry =
                PublicOrderReferenceRegistry(
                    jdbcTemplate,
                    SequencePublicOrderReferenceGenerator(listOf(collision, regenerated)),
                    SimpleMeterRegistry(),
                )

            val result =
                requireNotNull(
                    transaction.execute { registry.reserve(Instant.parse("2026-08-12T00:00:00Z")) },
                )

            assertThat(result.value).isEqualTo(regenerated)
            assertThat(count("ordering_public_reference_registry")).isEqualTo(2)
        }

        @Test
        fun `public reference reservation retries collisions and fails after five attempts`() {
            val candidates =
                listOf(
                    "BF-2345-6789",
                    "BF-2345-6789",
                    "BF-2345-6789",
                    "BF-2345-6789",
                    "BF-2345-6789",
                    "BF-2345-6789",
                )
            val fixedGenerator = SequencePublicOrderReferenceGenerator(candidates)
            val registry = PublicOrderReferenceRegistry(jdbcTemplate, fixedGenerator, SimpleMeterRegistry())
            jdbcTemplate.update(
                "INSERT INTO ordering_public_reference_registry (public_reference, allocated_at) VALUES (?, now())",
                candidates.first(),
            )

            assertThatThrownBy {
                transaction.executeWithoutResult { registry.reserve(Instant.parse("2026-08-12T00:00:00Z")) }
            }.isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.ORDER_REFERENCE_EXHAUSTED)
            }
            assertThat(count("ordering_public_reference_registry")).isOne()
        }

        private fun count(table: String): Long =
            requireNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Long::class.java))
    }

private class SequencePublicOrderReferenceGenerator(
    private val values: List<String>,
) : PublicOrderReferenceCandidateGenerator {
    private val cursor = AtomicInteger()

    override fun next(): PublicOrderReference = PublicOrderReference.parse(values[cursor.getAndIncrement()])
}

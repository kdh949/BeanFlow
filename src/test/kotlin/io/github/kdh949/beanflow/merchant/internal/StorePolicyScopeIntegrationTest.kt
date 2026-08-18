package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.BeanflowSharedDatabaseTest
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.merchant.api.StorePolicyScopeOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessResourceFailureException
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@BeanflowSharedDatabaseTest
@SpringBootTest
internal class StorePolicyScopeIntegrationTest
    @Autowired
    constructor(
        private val operations: StorePolicyScopeOperations,
        private val storeRepository: StoreJpaRepository,
    ) {
        @BeforeEach
        fun cleanStores() {
            storeRepository.deleteAllInBatch()
        }

        @Test
        fun `authoritative Store boundary distinguishes existing and missing scope`() {
            val storeId = UUID.randomUUID()
            storeRepository.saveAndFlush(StoreEntity(storeId, acceptingOrders = true, pickupEnabled = true))

            assertThatCode { operations.requireExisting(storeId) }.doesNotThrowAnyException()
            assertThatThrownBy { operations.requireExisting(UUID.randomUUID()) }
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.RESOURCE_NOT_FOUND)
                }
        }

        @Test
        fun `Store lookup dependency failure is explicit instead of not-found or success`() {
            val repository = mock(StoreJpaRepository::class.java)
            val storeId = UUID.randomUUID()
            `when`(repository.existsById(storeId)).thenThrow(DataAccessResourceFailureException("unavailable"))

            assertThatThrownBy { JpaStorePolicyScopeService(repository).requireExisting(storeId) }
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
                }
        }

        @Test
        fun `pickup ordering availability is the same condition order creation enforces`() {
            val open = UUID.randomUUID()
            val closed = UUID.randomUUID()
            val pickupDisabled = UUID.randomUUID()
            storeRepository.saveAndFlush(StoreEntity(open, acceptingOrders = true, pickupEnabled = true))
            storeRepository.saveAndFlush(StoreEntity(closed, acceptingOrders = false, pickupEnabled = true))
            storeRepository.saveAndFlush(StoreEntity(pickupDisabled, acceptingOrders = true, pickupEnabled = false))

            assertThat(operations.pickupOrderingAvailable(open)).isTrue()
            assertThat(operations.pickupOrderingAvailable(closed)).isFalse()
            assertThat(operations.pickupOrderingAvailable(pickupDisabled)).isFalse()
            assertThatThrownBy { operations.pickupOrderingAvailable(UUID.randomUUID()) }
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.RESOURCE_NOT_FOUND)
                }
        }

        @Test
        fun `pickup ordering availability never collapses a dependency failure into false`() {
            val repository = mock(StoreJpaRepository::class.java)
            val storeId = UUID.randomUUID()
            `when`(repository.findById(storeId)).thenThrow(DataAccessResourceFailureException("unavailable"))

            assertThatThrownBy { JpaStorePolicyScopeService(repository).pickupOrderingAvailable(storeId) }
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
                }
        }
    }

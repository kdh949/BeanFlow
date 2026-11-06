package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.dao.DataAccessResourceFailureException
import java.util.UUID

internal class CustomerOrderDisplayQueryServiceTest {
    @Test
    fun `database failure stays unavailable rather than missing context`() {
        val repository = Mockito.mock(CustomerOrderDisplayQueryRepository::class.java)
        val customer = UUID.randomUUID()
        val ids = setOf(UUID.randomUUID())
        Mockito.`when`(repository.find(customer, ids)).thenThrow(DataAccessResourceFailureException("synthetic"))
        assertThatThrownBy { CustomerOrderDisplayQueryService(repository).find(customer, ids) }
            .isInstanceOfSatisfying(DomainFailure::class.java) { assertThat(it.code).isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE) }
    }

    @Test
    fun `empty pages do not read orders and oversized batches are rejected`() {
        val repository = Mockito.mock(CustomerOrderDisplayQueryRepository::class.java)
        val service = CustomerOrderDisplayQueryService(repository)
        assertThat(service.find(UUID.randomUUID(), emptySet())).isEmpty()
        assertThatThrownBy { service.find(UUID.randomUUID(), (1..101).map { UUID.randomUUID() }.toSet()) }
            .isInstanceOf(IllegalArgumentException::class.java)
        Mockito.verifyNoInteractions(repository)
    }
}

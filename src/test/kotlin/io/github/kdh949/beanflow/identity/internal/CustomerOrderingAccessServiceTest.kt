package io.github.kdh949.beanflow.identity.internal

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import java.time.Clock
import java.util.UUID

internal class CustomerOrderingAccessServiceTest {
    @Test
    fun `disabled demo does not query identity during order creation`() {
        val accounts = mock(CustomerAccountJpaRepository::class.java)
        val service = CustomerOrderingAccessService(accounts, Clock.systemUTC(), false)

        service.requireStore(UUID.randomUUID(), UUID.randomUUID())

        verifyNoInteractions(accounts)
    }
}

package io.github.kdh949.beanflow.demo.internal

import io.github.kdh949.beanflow.shared.api.BrowserSessionCookies
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.core.env.Environment

internal class DemoControllerTest {
    @Test
    fun `all demo failures include the shared correlation id`() {
        val correlations = mock(CorrelationIdSource::class.java)
        `when`(correlations.currentOrCreate()).thenReturn("correlation-test")
        val controller =
            DemoController(
                mock(DemoWorkspaceService::class.java),
                mock(BrowserSessionCookies::class.java),
                mock(Environment::class.java),
                correlations,
            )

        listOf(409, 410, 429, 503).forEach { status ->
            val response = controller.failure(DemoFailure(status, "DEMO_TEST", "failed"))

            assertThat(response.statusCode.value()).isEqualTo(status)
            assertThat(response.body).isEqualTo(DemoErrorResponse("DEMO_TEST", "failed", "correlation-test"))
        }
    }
}

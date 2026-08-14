package io.github.kdh949.beanflow.shared.internal

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

class AuthenticationPathCoverageValidatorTest {
    private val validator =
        AuthenticationPathCoverageValidator(
            AuthenticationPathRegistry(),
            mock(RequestMappingHandlerMapping::class.java),
        )

    @Test
    fun `startup validation rejects an unassigned controller path`() {
        assertThatThrownBy {
            validator.validate(listOf("/api/v1/orders", "/api/v1/not-registered"))
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("/api/v1/not-registered")
    }
}

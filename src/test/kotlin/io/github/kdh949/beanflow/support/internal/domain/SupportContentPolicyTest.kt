package io.github.kdh949.beanflow.support.internal.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SupportContentPolicyTest {
    @Test
    fun `note content rejects secret and high risk pii forms without echoing the input`() {
        listOf(
            "password=secret-value",
            "OTP 123456",
            "Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature",
            "4111 1111 1111 1111",
            "customer stated card 4111-1111-1111-1111 during the call",
            "customer stated card 4012‑8888‑8888‑1881 during the call",
            "primary 4242 4242 4242 4242 and backup 4111 1111 1111 1111",
            "CVV: 123",
            "CVC=1234",
            "security code 987",
            "123-456-789012",
            "서울특별시 강남구 테헤란로 12",
        ).forEach { unsafe ->
            assertThatThrownBy { SupportContentPolicy.note(unsafe) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Support content is not permitted")
        }
    }

    @Test
    fun `safe note and redacted interaction content are normalized`() {
        assertThat(SupportContentPolicy.note("  CUSTOMER_REQUESTED_PICKUP_STATUS  "))
            .isEqualTo("CUSTOMER_REQUESTED_PICKUP_STATUS")
        assertThat(SupportContentPolicy.interactionSummary("  CUSTOMER_CONTACTED_US  "))
            .isEqualTo("CUSTOMER_CONTACTED_US")
    }
}

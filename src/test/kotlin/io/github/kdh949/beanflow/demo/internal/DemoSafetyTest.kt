package io.github.kdh949.beanflow.demo.internal

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

internal class DemoSafetyTest {
    private fun run(
        enabled: Boolean,
        vararg profiles: String,
        active: Int = 20,
    ) {
        val environment = MockEnvironment().apply { setActiveProfiles(*profiles) }
        DemoConfiguration().demoSafety(DemoSettings(enabled, active, 200, 5), environment).afterSingletonsInstantiated()
    }

    @Test fun `disabled leaves ordinary production configuration unchanged`() {
        assertThatCode { run(false, "prod") }.doesNotThrowAnyException()
    }

    @Test fun `explicit sandbox combinations are accepted`() {
        assertThatCode { run(true, "local", "local-demo") }.doesNotThrowAnyException()
        assertThatCode { run(true, "local", "toss-sandbox", "portfolio") }.doesNotThrowAnyException()
    }

    @Test fun `production performance missing sandbox and oversized quota are rejected`() {
        listOf("prod", "perf", "toss-perf").forEach { forbidden ->
            assertThatThrownBy { run(true, "local", "toss-sandbox", forbidden) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { run(true, "local") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { run(true, "toss-sandbox") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { run(true, "local", "local-demo", active = 21) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}

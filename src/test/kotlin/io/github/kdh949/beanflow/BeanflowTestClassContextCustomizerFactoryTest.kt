package io.github.kdh949.beanflow

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

internal class BeanflowTestClassContextCustomizerFactoryTest {
    private val factory = BeanflowTestClassContextCustomizerFactory()

    @Test
    fun `shared database tests reuse the Spring context cache key`() {
        assertThat(factory.customizerFor(SharedTest::class.java)).isNull()
    }

    @Test
    fun `isolated tests add their class identity to the cache key`() {
        assertThat(factory.customizerFor(IsolatedTest::class.java)).isNotNull
    }

    @Test
    fun `isolated tests must record a reason`() {
        assertThatIllegalStateException()
            .isThrownBy { factory.customizerFor(MissingReasonTest::class.java) }
            .withMessageContaining("must explain why")
    }

    @Test
    fun `a Spring test cannot be shared and isolated at once`() {
        assertThatIllegalStateException()
            .isThrownBy { factory.customizerFor(ConflictingTest::class.java) }
            .withMessageContaining("exactly one")
    }

    @Test
    fun `raw Spring tests keep legacy isolation until classification completes`() {
        assertThat(factory.customizerFor(LegacyTest::class.java)).isNotNull
    }

    private fun BeanflowTestClassContextCustomizerFactory.customizerFor(testClass: Class<*>) =
        createContextCustomizer(testClass, emptyList())

    @SpringBootTest
    @BeanflowSharedDatabaseTest
    private class SharedTest

    @SpringBootTest
    @BeanflowIsolatedSpringContext("uses a committed transaction from another connection")
    private class IsolatedTest

    @SpringBootTest
    @BeanflowIsolatedSpringContext("")
    private class MissingReasonTest

    @SpringBootTest
    @BeanflowSharedDatabaseTest
    @BeanflowIsolatedSpringContext("conflicting declaration")
    private class ConflictingTest

    @SpringBootTest
    private class LegacyTest
}

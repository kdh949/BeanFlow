package io.github.kdh949.beanflow

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import java.time.Clock

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("verifies startup, DDL, or committed state across a transaction boundary")
@SpringBootTest
class ApplicationContextTests(
    @Autowired private val applicationContext: ApplicationContext,
) {
    @Test
    fun `postgresql backed application starts with an injected clock`() {
        assertThat(applicationContext.getBean(Clock::class.java)).isNotNull
        assertThat(applicationContext.beanDefinitionNames)
            .noneMatch { it.contains("inMemory", ignoreCase = true) }
    }
}

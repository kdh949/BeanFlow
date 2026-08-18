package io.github.kdh949.beanflow

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("verifies startup, DDL, or committed state across a transaction boundary")
@SpringBootTest
class BeanflowApplicationTests {
    @Test
    fun contextLoads() {
    }
}

package io.github.kdh949.beanflow

import io.github.kdh949.beanflow.notification.internal.ScriptedTestNotificationProvider
import io.github.kdh949.beanflow.payment.internal.PaymentGateway
import io.github.kdh949.beanflow.payment.internal.ScriptedTestPaymentGateway
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:17.6"))

    @Bean
    fun testJwtDecoder(): JwtDecoder =
        JwtDecoder {
            throw JwtException("JWT decoding is not used outside explicit security tests")
        }

    @Bean
    internal fun testPaymentGateway(): ScriptedTestPaymentGateway = ScriptedTestPaymentGateway()

    @Bean
    internal fun testNotificationProvider(): ScriptedTestNotificationProvider = ScriptedTestNotificationProvider()
}

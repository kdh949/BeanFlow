package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal class CustomerCredentialSecurityTest {
    private val security = CustomerPasswordSecurity(SimpleMeterRegistry())

    @Test
    fun `login ID canonicalizes only trim and ASCII uppercase`() {
        assertThat(security.validateLoginId("  Demo.User-1  ")).isEqualTo("demo.user-1")
        listOf("a1234", "a.b_c-d9", "a${"b".repeat(30)}z").forEach { valid ->
            assertThat(security.validateLoginId(valid)).isEqualTo(valid)
        }
        listOf("abcd", "a${"b".repeat(31)}z", "_abcde", "abcde_", "abc 한글", "ＡＢＣＤＥ").forEach { invalid ->
            assertThatThrownBy { security.validateLoginId(invalid) }.isInstanceOf(DomainFailure::class.java)
        }
    }

    @Test
    fun `password policy preserves Unicode and whitespace bytes and uses exact Argon2id parameters`() {
        val password = "  서로다른 비밀번호 12345  "
        security.validateRegistrationPassword("customer1", password)
        val hash = security.encode(password)
        assertThat(hash).startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$")
        assertThat(security.matches(password, hash)).isTrue()
        assertThat(security.matches(password.trim(), hash)).isFalse()
        assertThat(hash).doesNotContain(password)
    }

    @Test
    fun `missing or short HMAC keys fail rather than falling back`() {
        val configuration = CustomerCredentialSecurityConfiguration()
        assertThatThrownBy { configuration.authenticationScopeHmac(CustomerAuthenticationProperties()) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy {
            configuration.authenticationScopeHmac(CustomerAuthenticationProperties(attemptHmacKeyBase64Url = "c2hvcnQ"))
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `missing HMAC property fails an application context`() {
        ApplicationContextRunner()
            .withBean(CustomerAuthenticationProperties::class.java, { CustomerAuthenticationProperties() })
            .withBean(
                AuthenticationScopeHmac::class.java,
                { CustomerCredentialSecurityConfiguration().authenticationScopeHmac(CustomerAuthenticationProperties()) },
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("beanflow.authentication.attempt-hmac-key-base64-url is required")
            }
    }

    @Test
    fun `stored password hash must be the exact accepted PHC contract`() {
        security.validateStoredHash(security.dummyHash)
        assertThatThrownBy { security.validateStoredHash("{bcrypt}not-argon") }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `source IP trusts forwarding only from configured proxy networks`() {
        val resolver = CustomerSourceIpResolver(listOf("10.0.0.0/8", "2001:db8::/32"))
        assertThat(resolver.resolve("203.0.113.5", "198.51.100.2")).isEqualTo("203.0.113.5")
        assertThat(resolver.resolve("10.0.0.8", "198.51.100.2, 10.0.0.8")).isEqualTo("198.51.100.2")
    }

    @Test
    fun `HMAC scopes are isolated by actor and raw input is never returned`() {
        val hmac = AuthenticationScopeHmac(ByteArray(32) { 7 })
        val customer = hmac.loginId(LoginAttemptActorType.CUSTOMER, "same.user")
        val merchant = hmac.loginId(LoginAttemptActorType.MERCHANT, "same.user")

        assertThat(customer).matches("^[0-9a-f]{64}$").doesNotContain("same.user")
        assertThat(merchant).matches("^[0-9a-f]{64}$").isNotEqualTo(customer)
    }

    @Test
    fun `retention failure is rethrown and never creates an authentication fallback`() {
        val now = Instant.parse("2026-08-13T00:00:00Z")
        val repository = mock(LoginAttemptRepository::class.java)
        val registry = SimpleMeterRegistry()
        `when`(repository.deleteExpired(now.minusSeconds(24 * 60 * 60), 100))
            .thenThrow(IllegalStateException("injected retention failure"))

        val worker = LoginAttemptRetentionWorker(repository, Clock.fixed(now, ZoneOffset.UTC), registry)

        assertThatThrownBy(worker::deleteExpired)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("injected retention failure")
        assertThat(
            registry
                .get("beanflow.identity.login_attempt.retention")
                .tag("outcome", "failed")
                .counter()
                .count(),
        ).isEqualTo(1.0)
    }
}

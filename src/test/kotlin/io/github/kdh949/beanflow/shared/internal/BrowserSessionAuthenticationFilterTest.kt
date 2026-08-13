package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.shared.api.BrowserActorLoader
import io.github.kdh949.beanflow.shared.api.BrowserActorType
import io.github.kdh949.beanflow.shared.api.BrowserAuthenticationInvalid
import io.github.kdh949.beanflow.shared.api.CurrentActor
import io.github.kdh949.beanflow.shared.api.CustomerActor
import io.github.kdh949.beanflow.shared.api.MerchantAccountState
import io.github.kdh949.beanflow.shared.api.MerchantActor
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.core.context.SecurityContextHolder
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class BrowserSessionAuthenticationFilterTest {
    private val now = Instant.parse("2026-08-13T00:00:00Z")
    private val actorId = UUID.randomUUID()
    private val metrics = AuthenticationMetrics(SimpleMeterRegistry())
    private val errorWriter = SecurityErrorResponseWriter(JsonMapper.builder().build(), { "test-correlation" }, metrics)

    @AfterEach
    fun clearSecurityContext() = SecurityContextHolder.clearContext()

    @Test
    fun `valid minimal session loads current account actor on every request`() {
        val loadCalled = AtomicBoolean()
        val filter =
            filter(
                loader(BrowserActorType.CUSTOMER) { id, version ->
                    assertThat(id).isEqualTo(actorId)
                    assertThat(version).isEqualTo(3)
                    loadCalled.set(true)
                    CustomerActor(id)
                },
            )
        val response = MockHttpServletResponse()
        val continued = AtomicBoolean()

        filter.doFilter(request(session(now.minusSeconds(60), 3)), response) { _, _ -> continued.set(true) }

        assertThat(loadCalled).isTrue()
        assertThat(continued).isTrue()
        assertThat(SecurityContextHolder.getContext().authentication?.principal).isEqualTo(CustomerActor(actorId))
    }

    @Test
    fun `absolute expiry is inclusive and invalidates the session`() {
        val response = MockHttpServletResponse()
        val filter =
            filter(
                BrowserActorType.MERCHANT,
                loader(BrowserActorType.MERCHANT) { id, _ -> MerchantActor(id, MerchantAccountState.ACTIVE) },
            )

        filter.doFilter(request(session(now.minusSeconds(12 * 60 * 60), 1)), response) { _, _ -> error("must not continue") }

        assertThat(response.status).isEqualTo(401)
        assertThat(response.contentAsString).contains("Browser session expired")
    }

    @Test
    fun `credential version rejection is 401 while loader or store failures are 503`() {
        val invalidResponse = MockHttpServletResponse()
        filter(loader(BrowserActorType.CUSTOMER) { _, _ -> throw BrowserAuthenticationInvalid("credential changed") })
            .doFilter(request(session(now, 1)), invalidResponse) { _, _ -> error("must not continue") }
        assertThat(invalidResponse.status).isEqualTo(401)

        val noLoaderResponse = MockHttpServletResponse()
        filter().doFilter(request(session(now, 1)), noLoaderResponse) { _, _ -> error("must not continue") }
        assertThat(noLoaderResponse.status).isEqualTo(503)

        val failedRequest = mock(jakarta.servlet.http.HttpServletRequest::class.java)
        `when`(failedRequest.getSession(false)).thenThrow(IllegalStateException("store down"))
        val storeFailureResponse = MockHttpServletResponse()
        filter().doFilter(failedRequest, storeFailureResponse) { _, _ -> error("must not continue") }
        assertThat(storeFailureResponse.status).isEqualTo(503)
    }

    @Test
    fun `loader actor type mismatch is forbidden`() {
        val response = MockHttpServletResponse()
        filter(loader(BrowserActorType.CUSTOMER) { id, _ -> MerchantActor(id, MerchantAccountState.ACTIVE) })
            .doFilter(request(session(now, 1)), response) { _, _ -> error("must not continue") }
        assertThat(response.status).isEqualTo(403)
    }

    private fun filter(vararg loaders: BrowserActorLoader) = filter(BrowserActorType.CUSTOMER, *loaders)

    private fun filter(
        actorType: BrowserActorType,
        vararg loaders: BrowserActorLoader,
    ) = BrowserSessionAuthenticationFilter(
        actorType,
        loaders.toList(),
        Clock.fixed(now, ZoneOffset.UTC),
        errorWriter,
        metrics,
    )

    private fun request(session: MockHttpSession): MockHttpServletRequest = MockHttpServletRequest().apply { setSession(session) }

    private fun session(
        authenticatedAt: Instant,
        credentialVersion: Long,
    ): MockHttpSession =
        MockHttpSession().apply {
            setAttribute(ACTOR_ID_ATTRIBUTE, actorId.toString())
            setAttribute(AUTHENTICATED_AT_ATTRIBUTE, authenticatedAt.toEpochMilli())
            setAttribute(CREDENTIAL_VERSION_ATTRIBUTE, credentialVersion)
        }

    private fun loader(
        type: BrowserActorType,
        load: (UUID, Long) -> CurrentActor,
    ): BrowserActorLoader =
        object : BrowserActorLoader {
            override val actorType = type

            override fun load(
                actorId: UUID,
                credentialVersion: Long,
            ): CurrentActor = load(actorId, credentialVersion)
        }
}

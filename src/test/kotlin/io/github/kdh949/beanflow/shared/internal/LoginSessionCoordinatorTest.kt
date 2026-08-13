package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.shared.api.BrowserActorType
import io.github.kdh949.beanflow.shared.api.BrowserSessionIdentity
import io.github.kdh949.beanflow.shared.api.CreateLoginSession
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class LoginSessionCoordinatorTest {
    @Test
    fun `customer login rotates current id and evicts oldest by authenticated time then id`() {
        val store = InMemoryBrowserSessionStore()
        val actorId = UUID.randomUUID()
        val principal = principalName(BrowserActorType.CUSTOMER, actorId.toString())
        listOf("session-b", "session-a", "session-c", "session-d", "session-e").forEachIndexed { index, id ->
            store.seed(principal, id, if (index < 2) 100 else 100L + index)
        }
        store.seed(principal, "current-session", 999)
        val coordinator = coordinator(store)

        val result =
            coordinator.create(
                CreateLoginSession(BrowserActorType.CUSTOMER, actorId, 1_000, 7, "current-session"),
            )

        assertThat(result.sessionId).isNotEqualTo("current-session")
        assertThat(result.evictedSessionIds).containsExactly("current-session", "session-a")
        assertThat(store.sessions(principal)).hasSize(5)
        assertThat(store.lastIdleTimeout).isEqualTo(Duration.ofDays(7))
    }

    @Test
    fun `merchant cap is three and equal timestamps evict by session id`() {
        val store = InMemoryBrowserSessionStore()
        val actorId = UUID.randomUUID()
        val principal = principalName(BrowserActorType.MERCHANT, actorId.toString())
        store.seed(principal, "b", 100)
        store.seed(principal, "a", 100)
        store.seed(principal, "c", 101)

        val result =
            coordinator(store).create(
                CreateLoginSession(BrowserActorType.MERCHANT, actorId, 200, 1),
            )

        assertThat(result.evictedSessionIds).containsExactly("a")
        assertThat(store.sessions(principal)).hasSize(3)
        assertThat(store.lastIdleTimeout).isEqualTo(Duration.ofMinutes(30))
    }

    @Test
    fun `store save or delete failure is explicit dependency unavailable`() {
        val actorId = UUID.randomUUID()
        val command = CreateLoginSession(BrowserActorType.MERCHANT, actorId, 200, 1)
        val saveFailure = InMemoryBrowserSessionStore(failSave = true)
        assertDependencyFailure { coordinator(saveFailure).create(command) }

        val deleteFailure = InMemoryBrowserSessionStore(failDelete = true)
        deleteFailure.seed(principalName(BrowserActorType.MERCHANT, actorId.toString()), "old", 100)
        assertDependencyFailure {
            coordinator(deleteFailure).create(command.copy(currentSessionId = "old"))
        }
    }

    @Test
    fun `absolute expiry uses inclusive policy boundary`() {
        val authenticatedAt = Instant.parse("2026-08-01T00:00:00Z")
        assertThat(
            isAbsolutelyExpired(BrowserActorType.MERCHANT, authenticatedAt.toEpochMilli(), authenticatedAt.plus(Duration.ofHours(12))),
        ).isTrue()
        assertThat(
            isAbsolutelyExpired(
                BrowserActorType.CUSTOMER,
                authenticatedAt.toEpochMilli(),
                authenticatedAt.plus(Duration.ofDays(30)).minusMillis(1),
            ),
        ).isFalse()
    }

    private fun assertDependencyFailure(block: () -> Unit) {
        assertThatThrownBy(block)
            .isInstanceOfSatisfying(DomainFailure::class.java) { failure ->
                assertThat(failure.code).isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
            }
    }

    private fun coordinator(store: BrowserSessionStore): PostgresLoginSessionCoordinator =
        PostgresLoginSessionCoordinator(store, AuthenticationMetrics(SimpleMeterRegistry()))
}

private class InMemoryBrowserSessionStore(
    private val failSave: Boolean = false,
    private val failDelete: Boolean = false,
) : BrowserSessionStore {
    private val records = linkedMapOf<String, MutableList<StoredBrowserSession>>()
    private var sequence = 0
    var lastIdleTimeout: Duration? = null
        private set

    override fun findByPrincipal(principalName: String): List<StoredBrowserSession> = records[principalName].orEmpty().toList()

    override fun create(
        principalName: String,
        identity: BrowserSessionIdentity,
        idleTimeout: Duration,
    ): String {
        if (failSave) error("injected save failure")
        lastIdleTimeout = idleTimeout
        val id = "new-${++sequence}"
        seed(principalName, id, identity.authenticatedAtEpochMilli)
        return id
    }

    override fun delete(sessionId: String) {
        if (failDelete) error("injected delete failure")
        records.values.forEach { sessions -> sessions.removeIf { it.sessionId == sessionId } }
    }

    fun seed(
        principalName: String,
        sessionId: String,
        authenticatedAt: Long,
    ) {
        records.computeIfAbsent(principalName) { mutableListOf() } += StoredBrowserSession(sessionId, authenticatedAt)
    }

    fun sessions(principalName: String): List<StoredBrowserSession> = records[principalName].orEmpty()
}

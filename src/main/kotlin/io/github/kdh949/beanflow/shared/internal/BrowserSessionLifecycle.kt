package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.shared.api.BrowserActorType
import io.github.kdh949.beanflow.shared.api.BrowserSessionIdentity
import io.github.kdh949.beanflow.shared.api.BrowserSessionLifecycle
import io.github.kdh949.beanflow.shared.api.CreateLoginSession
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.LoginSessionCoordinator
import io.github.kdh949.beanflow.shared.api.LoginSessionHandle
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.Instant

internal data class StoredBrowserSession(
    val sessionId: String,
    val authenticatedAtEpochMilli: Long,
)

internal interface BrowserSessionStore {
    fun findByPrincipal(principalName: String): List<StoredBrowserSession>

    fun create(
        principalName: String,
        identity: BrowserSessionIdentity,
        idleTimeout: Duration,
    ): String

    fun delete(sessionId: String)
}

@Component
internal class JdbcBrowserSessionStore(
    repository: FindByIndexNameSessionRepository<*>,
) : BrowserSessionStore {
    @Suppress("UNCHECKED_CAST")
    private val sessions = repository as FindByIndexNameSessionRepository<Session>

    override fun findByPrincipal(principalName: String): List<StoredBrowserSession> =
        sessions.findByPrincipalName(principalName).values.mapNotNull { session ->
            val authenticatedAt = session.getAttribute<Long>(AUTHENTICATED_AT_ATTRIBUTE) ?: return@mapNotNull null
            StoredBrowserSession(session.id, authenticatedAt)
        }

    override fun create(
        principalName: String,
        identity: BrowserSessionIdentity,
        idleTimeout: Duration,
    ): String {
        val session = sessions.createSession()
        session.maxInactiveInterval = idleTimeout
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, principalName)
        session.setAttribute(ACTOR_ID_ATTRIBUTE, identity.actorId.toString())
        session.setAttribute(AUTHENTICATED_AT_ATTRIBUTE, identity.authenticatedAtEpochMilli)
        session.setAttribute(CREDENTIAL_VERSION_ATTRIBUTE, identity.credentialVersion)
        sessions.save(session)
        return session.id
    }

    override fun delete(sessionId: String) = sessions.deleteById(sessionId)
}

@Component
internal class PostgresLoginSessionCoordinator(
    private val store: BrowserSessionStore,
    private val metrics: AuthenticationMetrics,
) : LoginSessionCoordinator,
    BrowserSessionLifecycle {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun create(command: CreateLoginSession): LoginSessionHandle =
        dependencyBoundary(command.actorType.name, "create browser session") {
            require(command.credentialVersion >= 0) { "credentialVersion must not be negative" }
            require(command.authenticatedAtEpochMilli >= 0) { "authenticatedAt must not be negative" }
            val policy = BrowserSessionPolicy.forActor(command.actorType)
            val principal = principalName(command.actorType, command.actorId.toString())
            val evicted = mutableListOf<String>()
            command.currentSessionId?.let { current ->
                store.delete(current)
                evicted += current
            }
            val sessions =
                store
                    .findByPrincipal(principal)
                    .filterNot { it.sessionId == command.currentSessionId }
                    .sortedWith(compareBy(StoredBrowserSession::authenticatedAtEpochMilli, StoredBrowserSession::sessionId))
            sessions.take((sessions.size - policy.maximumConcurrentSessions + 1).coerceAtLeast(0)).forEach { oldest ->
                store.delete(oldest.sessionId)
                evicted += oldest.sessionId
            }
            val newSessionId =
                store.create(
                    principal,
                    BrowserSessionIdentity(
                        command.actorType,
                        command.actorId,
                        command.authenticatedAtEpochMilli,
                        command.credentialVersion,
                    ),
                    policy.idleTimeout,
                )
            check(newSessionId != command.currentSessionId) { "Session rotation must issue a new session ID" }
            afterCommit {
                metrics.sessionLifecycle(command.actorType.name, "create", "success")
                if (evicted.isNotEmpty()) {
                    metrics.sessionLifecycle(command.actorType.name, "evict", "success", evicted.size)
                }
            }
            LoginSessionHandle(newSessionId, evicted)
        }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun logout(sessionId: String) {
        dependencyBoundary("UNKNOWN", "delete browser session") { store.delete(sessionId) }
        afterCommit { metrics.sessionLifecycle("UNKNOWN", "logout", "success") }
    }

    private fun <T> dependencyBoundary(
        actorType: String,
        operation: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (failure: DomainFailure) {
            throw failure
        } catch (_: RuntimeException) {
            metrics.sessionStoreError(actorType, operation.replace(' ', '_'))
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Failed to $operation")
        }

    private fun afterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = action()
            },
        )
    }
}

internal data class BrowserSessionPolicy(
    val idleTimeout: Duration,
    val absoluteTimeout: Duration,
    val maximumConcurrentSessions: Int,
) {
    companion object {
        fun forActor(actorType: BrowserActorType): BrowserSessionPolicy =
            when (actorType) {
                BrowserActorType.CUSTOMER -> BrowserSessionPolicy(Duration.ofDays(7), Duration.ofDays(30), 5)
                BrowserActorType.MERCHANT -> BrowserSessionPolicy(Duration.ofMinutes(30), Duration.ofHours(12), 3)
            }
    }
}

internal const val ACTOR_ID_ATTRIBUTE = "beanflow.actorId"
internal const val AUTHENTICATED_AT_ATTRIBUTE = "beanflow.authenticatedAt"
internal const val CREDENTIAL_VERSION_ATTRIBUTE = "beanflow.credentialVersion"

internal fun principalName(
    actorType: BrowserActorType,
    actorId: String,
): String = "${actorType.name}:$actorId"

internal fun isAbsolutelyExpired(
    actorType: BrowserActorType,
    authenticatedAtEpochMilli: Long,
    now: Instant,
): Boolean = !now.isBefore(Instant.ofEpochMilli(authenticatedAtEpochMilli).plus(BrowserSessionPolicy.forActor(actorType).absoluteTimeout))

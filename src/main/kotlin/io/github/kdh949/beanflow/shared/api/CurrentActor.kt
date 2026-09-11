package io.github.kdh949.beanflow.shared.api

import java.io.Serializable
import java.time.Instant
import java.util.UUID

sealed interface CurrentActor : Serializable {
    val actorId: UUID
}

data class CustomerActor(
    override val actorId: UUID,
) : CurrentActor

enum class MerchantAccountState {
    INITIAL_PASSWORD,
    ACTIVE,
    EXPIRED,
}

data class MerchantActor(
    override val actorId: UUID,
    val accountState: MerchantAccountState,
) : CurrentActor

data class OperatorActor(
    override val actorId: UUID,
    val roles: Set<String>,
    val loginIdentity: OperatorLoginIdentity? = null,
) : CurrentActor {
    override fun toString(): String = "OperatorActor(actorId=$actorId, roles=$roles, loginIdentity=<redacted>)"
}

/** Identity-provider login label observed only after JWT verification. */
data class OperatorLoginIdentity(
    val loginName: String,
    val tokenIssuedAt: Instant,
) : Serializable {
    override fun toString(): String = "OperatorLoginIdentity(<redacted>)"
}

enum class BrowserActorType {
    CUSTOMER,
    MERCHANT,
}

data class BrowserSessionIdentity(
    val actorType: BrowserActorType,
    val actorId: UUID,
    val authenticatedAtEpochMilli: Long,
    val credentialVersion: Long,
) : Serializable

data class CreateLoginSession(
    val actorType: BrowserActorType,
    val actorId: UUID,
    val authenticatedAtEpochMilli: Long,
    val credentialVersion: Long,
    val currentSessionId: String? = null,
)

data class LoginSessionHandle(
    val sessionId: String,
    val evictedSessionIds: List<String>,
)

interface LoginSessionCoordinator {
    fun create(command: CreateLoginSession): LoginSessionHandle
}

interface BrowserSessionLifecycle {
    fun logout(sessionId: String)
}

interface BrowserActorLoader {
    val actorType: BrowserActorType

    fun load(
        actorId: UUID,
        credentialVersion: Long,
    ): CurrentActor
}

class BrowserAuthenticationInvalid(
    override val message: String,
) : RuntimeException(message)

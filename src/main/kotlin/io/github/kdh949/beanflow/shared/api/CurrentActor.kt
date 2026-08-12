package io.github.kdh949.beanflow.shared.api

import java.util.UUID

sealed interface CurrentActor {
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
) : CurrentActor

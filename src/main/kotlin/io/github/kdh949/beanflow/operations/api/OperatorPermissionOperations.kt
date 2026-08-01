package io.github.kdh949.beanflow.operations.api

import java.util.UUID

enum class OperatorPermission {
    EXPIRED_BENEFIT_POLICY_READ,
    EXPIRED_BENEFIT_POLICY_WRITE,
    POINT_ACCOUNT_READ,
    POINT_ADJUSTMENT,
    POINT_ACCRUAL_POLICY_READ,
    POINT_ACCRUAL_POLICY_WRITE,
}

interface OperatorPermissionAuthorization {
    /**
     * Locks and validates the persistent grant in the caller's local transaction.
     */
    fun requireActive(
        actorId: UUID,
        permission: OperatorPermission,
    )
}

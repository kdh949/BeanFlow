package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualExpiryRule
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyScopeType
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyState
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyVersionView
import io.github.kdh949.beanflow.operations.api.PointAccrualIssuerType
import io.github.kdh949.beanflow.operations.api.PointAccrualRoundingMode
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

internal data class StorePolicyHeadSort(
    val policyVersionId: Long,
    val storeId: UUID,
)

@Repository
internal class OrdinaryPointAccrualPolicyQueryPersistence(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findHistory(
        scopeType: OrdinaryPointAccrualPolicyScopeType,
        scopeReference: UUID,
        beforePolicyVersionId: Long,
        limit: Int,
    ): List<OrdinaryPointAccrualPolicyVersionView> =
        jdbcTemplate.query(
            """
            SELECT policy_version_id, scope_type, scope_reference, state,
                   accrual_rate_bps, rounding_mode, issuer_type, issuer_reference,
                   expiry_rule, validity_days, effective_at, actor_type, actor_reference, reason
              FROM operations_point_accrual_policy_version
             WHERE scope_type = ?
               AND scope_reference = ?
               AND policy_version_id < ?
             ORDER BY policy_version_id DESC
             LIMIT ?
            """.trimIndent(),
            ::mapVersion,
            scopeType.name,
            scopeReference,
            beforePolicyVersionId,
            limit,
        )

    fun findStoreHeads(
        state: OrdinaryPointAccrualPolicyState?,
        before: StorePolicyHeadSort,
        limit: Int,
    ): List<OrdinaryPointAccrualPolicyVersionView> {
        val statePredicate = if (state == null) "" else "AND policy.state = ?"
        val arguments =
            buildList<Any> {
                if (state != null) add(state.name)
                add(before.policyVersionId)
                add(before.policyVersionId)
                add(before.storeId)
                add(limit)
            }.toTypedArray()
        return jdbcTemplate.query(
            """
            SELECT policy.policy_version_id, policy.scope_type, policy.scope_reference, policy.state,
                   policy.accrual_rate_bps, policy.rounding_mode, policy.issuer_type, policy.issuer_reference,
                   policy.expiry_rule, policy.validity_days, policy.effective_at,
                   policy.actor_type, policy.actor_reference, policy.reason
              FROM operations_point_accrual_policy_head head
              JOIN operations_point_accrual_policy_version policy
                ON policy.policy_version_id = head.policy_version_id
               AND policy.scope_type = head.scope_type
               AND policy.scope_reference = head.scope_reference
             WHERE head.scope_type = 'STORE'
               $statePredicate
               AND (policy.policy_version_id < ?
                    OR (policy.policy_version_id = ? AND policy.scope_reference < ?))
             ORDER BY policy.policy_version_id DESC, policy.scope_reference DESC
             LIMIT ?
            """.trimIndent(),
            ::mapVersion,
            *arguments,
        )
    }

    private fun mapVersion(
        resultSet: ResultSet,
        rowNumber: Int,
    ): OrdinaryPointAccrualPolicyVersionView =
        OrdinaryPointAccrualPolicyVersionView(
            policyVersionId = resultSet.getLong("policy_version_id"),
            scopeType = OrdinaryPointAccrualPolicyScopeType.valueOf(resultSet.getString("scope_type")),
            scopeReference = resultSet.getObject("scope_reference", UUID::class.java),
            state = OrdinaryPointAccrualPolicyState.valueOf(resultSet.getString("state")),
            accrualRateBps = resultSet.getObject("accrual_rate_bps", Int::class.javaObjectType),
            roundingMode = resultSet.getString("rounding_mode")?.let(PointAccrualRoundingMode::valueOf),
            issuerType = resultSet.getString("issuer_type")?.let(PointAccrualIssuerType::valueOf),
            issuerReference = resultSet.getString("issuer_reference"),
            expiryRule = resultSet.getString("expiry_rule")?.let(OrdinaryPointAccrualExpiryRule::valueOf),
            validityDays = resultSet.getObject("validity_days", Int::class.javaObjectType),
            effectiveAt = resultSet.getTimestamp("effective_at").toInstant(),
            actorType = AuditActorType.valueOf(resultSet.getString("actor_type")),
            actorReference = resultSet.getString("actor_reference"),
            reason = resultSet.getString("reason"),
        )
}

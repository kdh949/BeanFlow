package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.loyalty.api.PointAdjustmentCustomerLabel
import io.github.kdh949.beanflow.loyalty.api.PointAdjustmentCustomerLabelQuery
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.PersonalDataMasker
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

@Service
internal class MaskedCustomerAccountLabelAdapter(
    private val jdbc: JdbcTemplate,
) : PointAdjustmentCustomerLabelQuery {
    override fun find(customerId: UUID): PointAdjustmentCustomerLabel? =
        jdbc
            .query(
                "SELECT id, login_id, display_name FROM identity_customer_account WHERE id = ?",
                { row, _ ->
                    val label =
                        try {
                            PersonalDataMasker.maskDisplayLabel(row.getString("display_name"))
                        } catch (_: DomainFailure) {
                            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Customer display projection is invalid")
                        }
                    PointAdjustmentCustomerLabel(row.getObject("id", UUID::class.java), row.getString("login_id").take(1) + "***", label)
                },
                customerId,
            ).singleOrNull()
}

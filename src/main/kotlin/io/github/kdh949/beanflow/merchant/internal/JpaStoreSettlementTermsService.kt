package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.StoreSettlementTermsOperations
import io.github.kdh949.beanflow.merchant.api.StoreSettlementTermsSnapshot
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
internal class JpaStoreSettlementTermsService(
    private val repository: StoreSettlementTermsJpaRepository,
) : StoreSettlementTermsOperations {
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    override fun findApplicable(
        storeId: UUID,
        effectiveAt: Instant,
    ): StoreSettlementTermsSnapshot {
        val applicable = repository.findApplicable(storeId, effectiveAt)
        if (applicable.size != 1) {
            throw DomainFailure(
                FailureCode.SETTLEMENT_INPUT_UNAVAILABLE,
                "Exactly one applicable store settlement terms version is required",
            )
        }
        return applicable.single().let {
            StoreSettlementTermsSnapshot(
                termsVersionId = it.termsVersionId,
                storeId = it.storeId,
                sourceReference = it.sourceReference,
                feeRateBps = it.feeRateBps,
                effectiveFrom = it.effectiveFrom,
                effectiveTo = it.effectiveTo,
            )
        }
    }
}

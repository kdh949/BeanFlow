package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicySnapshot
import io.github.kdh949.beanflow.operations.api.SelectedOrdinaryPointAccrualPolicy
import io.github.kdh949.beanflow.ordering.api.OrderPointAccrualSnapshot
import io.github.kdh949.beanflow.ordering.api.OrderPointAccrualSnapshotOperations
import io.github.kdh949.beanflow.ordering.api.OrderPointAccrualSource
import io.github.kdh949.beanflow.ordering.api.OrderPointAccrualSourceState
import io.github.kdh949.beanflow.ordering.api.OrderPointAccrualUnitSnapshot
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.UUID

@Service
internal class OrderPointAccrualSnapshotService(
    private val sourceRepository: OrderPointAccrualSourceJpaRepository,
    private val snapshotRepository: OrderPointAccrualSnapshotJpaRepository,
    private val unitRepository: OrderPointAccrualUnitJpaRepository,
    private val meterRegistry: MeterRegistry,
) : OrderPointAccrualSnapshotOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    fun save(
        orderId: UUID,
        orderPayableKrw: Long,
        selected: SelectedOrdinaryPointAccrualPolicy,
        calculation: OrderPointAccrualCalculation,
        createdAt: Instant,
    ) {
        try {
            sourceRepository.save(
                OrderPointAccrualSourceEntity(orderId, OrderPointAccrualSourceState.SNAPSHOTTED, createdAt),
            )
            val policy = selected.policy
            snapshotRepository.save(
                OrderPointAccrualSnapshotEntity(
                    orderId = orderId,
                    policyVersionId = policy.policyVersionId,
                    selectedScopeType = policy.scopeType,
                    selectedScopeReference = policy.scopeReference,
                    selectionSource = selected.selectionSource,
                    accrualRateBps = policy.accrualRateBps,
                    roundingMode = policy.roundingMode,
                    issuerType = policy.issuerType,
                    issuerReference = policy.issuerReference,
                    expiryRule = policy.expiryRule,
                    validityDays = policy.validityDays,
                    canonicalPolicyHash = policy.canonicalPolicyHash,
                    orderPayableKrw = orderPayableKrw,
                    grossAccrualAmountKrw = calculation.grossAccrualAmountKrw,
                    createdAt = createdAt,
                ),
            )
            unitRepository.saveAll(
                calculation.units.map {
                    OrderPointAccrualUnitEntity(
                        orderId,
                        it.orderLineId,
                        it.lineSequence,
                        it.unitPosition,
                        it.cashPayableKrw,
                        it.accruedAmountKrw,
                        createdAt,
                    )
                },
            )
            unitRepository.flush()
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        metric(
                            OrderPointAccrualSourceState.SNAPSHOTTED,
                            if (status == TransactionSynchronization.STATUS_COMMITTED) "SAVED" else "ROLLED_BACK",
                        )
                    }
                },
            )
        } catch (failure: DataAccessException) {
            metric(OrderPointAccrualSourceState.SNAPSHOTTED, "DEPENDENCY_UNAVAILABLE")
            dependency("Order point accrual snapshot could not be persisted", failure)
        }
    }

    @Transactional(readOnly = true)
    override fun read(orderId: UUID): OrderPointAccrualSource =
        try {
            val source =
                sourceRepository.findById(orderId).orElseThrow {
                    dependency("Order point accrual source is missing")
                }
            if (source.sourceState == OrderPointAccrualSourceState.LEGACY_NOT_APPLICABLE) {
                metric(source.sourceState, "READ")
                return OrderPointAccrualSource(orderId, source.sourceState, null)
            }
            val header =
                snapshotRepository.findById(orderId).orElseThrow {
                    dependency("Snapshotted Order point accrual header is missing")
                }
            val units = unitRepository.findAllByOrderIdOrderByLineSequenceAscUnitPositionAsc(orderId)
            val policy =
                OrdinaryPointAccrualPolicySnapshot(
                    header.policyVersionId,
                    header.selectedScopeType,
                    header.selectedScopeReference,
                    header.accrualRateBps,
                    header.roundingMode,
                    header.issuerType,
                    header.issuerReference,
                    header.expiryRule,
                    header.validityDays,
                    header.canonicalPolicyHash,
                )
            val unitSnapshots =
                units.map {
                    OrderPointAccrualUnitSnapshot(
                        it.orderLineId,
                        it.lineSequence,
                        it.unitPosition,
                        it.cashPayableKrw,
                        it.accruedAmountKrw,
                    )
                }
            validate(header, unitSnapshots)
            metric(source.sourceState, "READ")
            OrderPointAccrualSource(
                orderId,
                source.sourceState,
                OrderPointAccrualSnapshot(
                    orderId,
                    policy,
                    header.selectionSource,
                    header.orderPayableKrw,
                    header.grossAccrualAmountKrw,
                    header.snapshotSchemaVersion,
                    header.createdAt,
                    unitSnapshots,
                ),
            )
        } catch (failure: DomainFailure) {
            metric(OrderPointAccrualSourceState.SNAPSHOTTED, "INVALID")
            throw failure
        } catch (failure: DataAccessException) {
            metric(OrderPointAccrualSourceState.SNAPSHOTTED, "DEPENDENCY_UNAVAILABLE")
            dependency("Order point accrual snapshot could not be read", failure)
        }

    private fun validate(
        header: OrderPointAccrualSnapshotEntity,
        units: List<OrderPointAccrualUnitSnapshot>,
    ) {
        if (header.sourceState != OrderPointAccrualSourceState.SNAPSHOTTED || units.isEmpty()) {
            dependency("Order point accrual snapshot is incomplete")
        }
        val cash = exactSum(units.map { it.cashPayableKrw })
        val accrued = exactSum(units.map { it.accruedAmountKrw })
        if (cash != header.orderPayableKrw || accrued != header.grossAccrualAmountKrw) {
            dependency("Order point accrual snapshot unit allocation is inconsistent")
        }
    }

    private fun exactSum(values: Iterable<Long>): Long =
        try {
            values.fold(0L, Math::addExact)
        } catch (failure: ArithmeticException) {
            dependency("Order point accrual snapshot amount overflowed", failure)
        }

    private fun metric(
        sourceState: OrderPointAccrualSourceState,
        outcome: String,
    ) {
        meterRegistry
            .counter(
                "beanflow.order.point_accrual_snapshot.count",
                "source_state",
                sourceState.name,
                "outcome",
                outcome,
            ).increment()
    }

    private fun dependency(
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message).also { cause?.let(it::initCause) }
}

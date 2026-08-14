package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.identity.api.StoreAccessOperations
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.operations.api.OrderCompensationOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.persistence.PersistenceException
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionException
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
internal class StoreOrderBoardQueryService(
    private val repository: StoreOrderBoardQueryRepository,
    private val projector: StoreOrderBoardProjector,
    private val paging: StoreOrderBoardPaging,
    private val etags: StoreOrderBoardEtagGenerator,
    private val storeAccess: StoreAccessOperations,
    private val compensations: OrderCompensationOperations,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(
        actorId: UUID,
        storeId: UUID,
        lane: StoreOrderBoardLane?,
        ifNoneMatch: String?,
    ): ResponseEntity<StoreOrderBoardResponse> =
        observed(LIST) {
            requireAccess(actorId, storeId)
            val now = clock.instant()
            val snapshot = repository.findExecutableBoard(storeId, lane)
            val overflow =
                snapshot.overflow.map { boundary ->
                    StoreOrderBoardOverflowResponse(
                        lane = boundary.lane,
                        overflowCount = boundary.overflowCount,
                        nextCursor = paging.issueCursor(storeId, boundary.lane, paging.sortFor(boundary.lane, boundary.boundary), now),
                    )
                }
            val body = projector.board(snapshot.rows, now, overflow)
            recordRows(LIST, snapshot.rows.orders.size)
            overflow.forEach { entry -> recordOverflow(entry) }
            val etag = etags.generate(body)
            if (StoreOrderBoardConditionalRequest.matches(ifNoneMatch, etag)) {
                meterRegistry.counter("beanflow.store.order.board.response", "outcome", "not_modified").increment()
                ResponseEntity.status(304).header(HttpHeaders.ETAG, etag).build()
            } else {
                meterRegistry.counter("beanflow.store.order.board.response", "outcome", "ok").increment()
                ResponseEntity.ok().header(HttpHeaders.ETAG, etag).body(body)
            }
        }

    @Transactional(readOnly = true)
    fun overflow(
        actorId: UUID,
        storeId: UUID,
        lane: StoreOrderBoardLane,
        cursor: String,
    ): StoreOrderBoardOverflowPageResponse =
        observed(OVERFLOW) {
            requireAccess(actorId, storeId)
            val now = clock.instant()
            val prepared = paging.prepareOverflowPage(storeId, lane, cursor)
            val page = repository.findOverflowPage(storeId, prepared.lane, prepared.after)
            val items = projector.overflowPage(page.rows, now)
            recordRows(OVERFLOW, items.size)
            StoreOrderBoardOverflowPageResponse(
                lane = prepared.lane,
                items = items,
                nextCursor =
                    page.nextBoundary?.let { boundary ->
                        paging.issueCursor(storeId, prepared.lane, paging.sortFor(prepared.lane, boundary), now)
                    },
            )
        }

    @Transactional(readOnly = true)
    fun detail(
        actorId: UUID,
        storeId: UUID,
        rawReference: String,
    ): StoreOrderBoardItemResponse =
        observed(DETAIL) {
            requireAccess(actorId, storeId)
            val reference = PublicOrderReference.parse(rawReference)
            val rows = repository.findByReferenceAndStoreId(reference.value, storeId)
            if (rows == null) {
                if (repository.existsByReference(reference.value)) accessDenied()
                notFound()
            }
            val orderId = rows.orders.single().orderId
            val compensation = compensations.findByOrderId(orderId)?.let { StoreCompensationSummary(it.trigger, it.state, it.updatedAt) }
            projector.detail(rows, compensation, clock.instant())
        }

    private fun requireAccess(
        actorId: UUID,
        storeId: UUID,
    ) {
        storeAccess.requireOrderManagementAccess(actorId, storeId, ROLES)
    }

    private fun <T> observed(
        operation: String,
        action: () -> T,
    ): T {
        val sample = Timer.start(meterRegistry)
        return try {
            action()
        } catch (failure: DomainFailure) {
            throw failure
        } catch (failure: StoreOrderBoardEtagFailure) {
            dependency(failure)
        } catch (failure: DataAccessException) {
            dependency(failure)
        } catch (failure: PersistenceException) {
            dependency(failure)
        } catch (failure: TransactionException) {
            dependency(failure)
        } finally {
            sample.stop(meterRegistry.timer("beanflow.store.order.board.query.duration", "operation", operation))
        }
    }

    private fun dependency(cause: RuntimeException): Nothing =
        throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Store order board dependency is unavailable").also {
            it.initCause(cause)
        }

    private fun accessDenied(): Nothing = throw DomainFailure(FailureCode.ACCESS_DENIED, "Order is outside the requested store scope")

    private fun notFound(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Order was not found")

    private fun recordRows(
        operation: String,
        count: Int,
    ) {
        DistributionSummary
            .builder("beanflow.store.order.board.rows")
            .tag("operation", operation)
            .register(meterRegistry)
            .record(count.toDouble())
    }

    private fun recordOverflow(entry: StoreOrderBoardOverflowResponse) {
        DistributionSummary
            .builder("beanflow.store.order.board.overflow.count")
            .tag("lane", entry.lane.name)
            .register(meterRegistry)
            .record(entry.overflowCount.toDouble())
    }

    private companion object {
        const val LIST = "list"
        const val OVERFLOW = "overflow"
        const val DETAIL = "detail"
        val ROLES = setOf(StoreActorRole.OWNER, StoreActorRole.STAFF)
    }
}

package io.github.kdh949.beanflow.demo.internal

import io.github.kdh949.beanflow.identity.api.DemoIdentityOperations
import io.github.kdh949.beanflow.loyalty.api.DemoPointProvisioning
import io.github.kdh949.beanflow.merchant.api.DemoStoreProvisioning
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.ordering.api.DemoOrderOperations
import io.github.kdh949.beanflow.ordering.api.DemoOrderSnapshot
import io.github.kdh949.beanflow.shared.api.BrowserActorType
import io.github.kdh949.beanflow.shared.api.BrowserSessionLifecycle
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.CreateLoginSession
import io.github.kdh949.beanflow.shared.api.LoginSessionCoordinator
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

internal class DemoFailure(
    val status: Int,
    val code: String,
    message: String,
) : RuntimeException(message)

internal data class PresentedDemoSessions(
    val customer: String?,
    val merchant: String?,
) {
    override fun toString() = "PresentedDemoSessions(<redacted>)"
}

internal data class DemoSessionView(
    val workspaceId: UUID,
    val status: String,
    val mode: String,
    val storeId: UUID,
    val storeName: String,
    val expiresAt: Instant,
    val order: DemoOrderSnapshot?,
)

@Service
internal class DemoWorkspaceLifecycleService(
    private val repository: DemoWorkspaceRepository,
    private val identities: DemoIdentityOperations,
    private val stores: DemoStoreProvisioning,
    private val sessions: BrowserSessionLifecycle,
    private val audits: AuditRecordOperations,
    private val correlation: CorrelationIdSource,
    private val clock: Clock,
    private val metrics: MeterRegistry,
) {
    @Transactional(readOnly = true)
    fun expiredWorkspaceIds(): List<UUID> = repository.expiredIds(clock.instant())

    @Transactional
    fun expire(workspaceId: UUID): Boolean {
        val now = clock.instant()
        val workspace = repository.findById(workspaceId, lock = true) ?: return false
        if (workspace.endedAt != null || workspace.expiresAt.isAfter(now)) return false
        close(workspace, now)
        return true
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun close(
        workspace: DemoWorkspace,
        now: Instant,
    ) {
        identities.end(workspace.customerId, workspace.merchantId, now)
        stores.close(workspace.storeId, now)
        sessions.logout(workspace.customerSessionId)
        sessions.logout(workspace.merchantSessionId)
        repository.end(workspace.id, now)
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = "demo-workspace",
                    actorType = AuditActorType.SYSTEM,
                    category = AuditCategory.SECURITY_AND_PERMISSION,
                    action = "DEMO_WORKSPACE_ENDED",
                    targetType = "DEMO_WORKSPACE",
                    targetId = workspace.id,
                    occurredAt = now,
                    reason = "방문자 전용 체험 공간 수명주기",
                    correlationId = correlation.currentOrCreate(),
                    sourceReference = "demo:${workspace.id}:DEMO_WORKSPACE_ENDED:$now:",
                ),
            ),
        )
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    metrics.counter("beanflow.demo.workspace", "outcome", "ended").increment()
                }
            },
        )
    }
}

@Service
@ConditionalOnProperty(name = ["beanflow.demo.enabled"], havingValue = "true")
internal class DemoWorkspaceService(
    private val repository: DemoWorkspaceRepository,
    private val settings: DemoSettings,
    private val identities: DemoIdentityOperations,
    private val stores: DemoStoreProvisioning,
    private val points: DemoPointProvisioning,
    private val orders: DemoOrderOperations,
    private val sessions: LoginSessionCoordinator,
    private val workspaceLifecycle: DemoWorkspaceLifecycleService,
    private val audits: AuditRecordOperations,
    private val correlation: CorrelationIdSource,
    private val clock: Clock,
    private val metrics: MeterRegistry,
) {
    @Transactional
    fun start(
        hash: String,
        key: String,
        mode: String,
        presented: PresentedDemoSessions,
    ): DemoWorkspace {
        validKey(key)
        if (mode !in setOf("GUIDED", "DIRECT")) throw DemoFailure(400, "DEMO_INVALID_REQUEST", "Unknown demo mode")
        repository.lockAdmission()
        val previous = repository.latest(hash, true)
        protectExistingSession(previous, presented)
        repository.replay(hash, key)?.let {
            if (it.mode != mode) throw DemoFailure(409, "IDEMPOTENCY_KEY_REUSED", "Start key has another mode")
            if (!it.active(clock.instant())) expired()
            return it
        }
        val now = clock.instant()
        if (previous?.active(now) == true) throw DemoFailure(409, "DEMO_ALREADY_ACTIVE", "Resume or end the current workspace")
        val day =
            now
                .atZone(ZoneId.of("Asia/Seoul"))
                .toLocalDate()
                .atStartOfDay(ZoneId.of("Asia/Seoul"))
                .toInstant()
        if (repository.activeCount(now) >= settings.maxActive || repository.dailyCount(day) >= settings.maxDaily ||
            repository.dailyCount(day, hash) >= settings.maxBrowserDaily
        ) {
            metrics.counter("beanflow.demo.workspace", "outcome", "quota").increment()
            throw DemoFailure(429, "DEMO_QUOTA_REACHED", "Demo workspace quota reached")
        }
        if (previous != null && previous.endedAt == null) workspaceLifecycle.close(previous, now)
        val id = UUID.randomUUID()
        val customerId = UUID.randomUUID()
        val merchantId = UUID.randomUUID()
        val expiresAt = now.plusSeconds(settings.lifetimeSeconds)
        val store = stores.create(id, now)
        identities.provision(customerId, merchantId, store.storeId, now, expiresAt)
        val customerSession = sessions.create(CreateLoginSession(BrowserActorType.CUSTOMER, customerId, now.toEpochMilli(), 0))
        val merchantSession = sessions.create(CreateLoginSession(BrowserActorType.MERCHANT, merchantId, now.toEpochMilli(), 0))
        var workspace =
            DemoWorkspace(
                id,
                hash,
                key,
                mode,
                customerId,
                merchantId,
                store.storeId,
                store.sampleMenuId,
                customerSession.sessionId,
                merchantSession.sessionId,
                null,
                now,
                expiresAt,
                null,
            )
        repository.insert(workspace)
        if (mode == "GUIDED") {
            val order = newSample(workspace, "initial-sample", now)
            workspace = workspace.copy(orderReference = order.orderReference)
        }
        audit(workspace, "DEMO_WORKSPACE_CREATED", now)
        committedMetric("created")
        return workspace
    }

    @Transactional
    fun current(hash: String): DemoSessionView? = repository.latest(hash)?.let { view(it) }

    @Transactional
    fun resume(
        hash: String,
        presented: PresentedDemoSessions,
    ): DemoWorkspace {
        val w = active(hash)
        protectExistingSession(w, presented)
        // A live workspace can restore its original sessions. Logout ends the workspace through the demo API.
        return w
    }

    @Transactional
    fun sample(
        hash: String,
        key: String,
        presented: PresentedDemoSessions,
    ): DemoSessionView {
        validKey(key)
        val w = active(hash)
        protectExistingSession(w, presented)
        replay(w, key, "SAMPLE", "")?.let { return view(w.copy(orderReference = it)) }
        w.orderReference?.let { reference ->
            if (orders.inspect(w.customerId, w.storeId, reference).status !in TERMINAL) {
                throw DemoFailure(409, "DEMO_ORDER_ACTIVE", "Finish the active order before creating another sample")
            }
        }
        if (repository.commandCount(w.id) >= 5) throw DemoFailure(429, "DEMO_SAMPLE_LIMIT", "Sample order limit reached")
        val order = newSample(w, key, clock.instant())
        return view(w.copy(orderReference = order.orderReference))
    }

    @Transactional
    fun track(
        hash: String,
        key: String,
        reference: String,
        presented: PresentedDemoSessions,
    ): DemoSessionView {
        validKey(key)
        val w = active(hash)
        protectExistingSession(w, presented)
        replay(w, key, "TRACK", reference)?.let { return view(w.copy(orderReference = it)) }
        val order = orders.inspect(w.customerId, w.storeId, reference)
        repository.track(w.id, order.orderReference)
        repository.record(w.id, key, "TRACK", reference, order.orderReference)
        return view(w.copy(orderReference = order.orderReference))
    }

    @Transactional
    fun end(
        hash: String,
        presented: PresentedDemoSessions,
    ): DemoWorkspace? {
        val w = repository.latest(hash, true) ?: return null
        protectExistingSession(w, presented)
        if (w.endedAt == null) workspaceLifecycle.close(w, clock.instant())
        return w
    }

    @Transactional
    fun representation(w: DemoWorkspace): DemoSessionView = view(w)

    private fun newSample(
        w: DemoWorkspace,
        key: String,
        now: Instant,
    ): DemoOrderSnapshot {
        points.grantSample(w.customerId, w.storeId, "demo:${w.id}:$key", w.expiresAt.plusSeconds(3600), now)
        val order = orders.createSample(w.customerId, w.storeId, w.menuId)
        repository.track(w.id, order.orderReference)
        repository.record(w.id, key, "SAMPLE", "", order.orderReference)
        audit(w, "DEMO_SAMPLE_CREATED", now, order.orderReference)
        return order
    }

    private fun active(hash: String): DemoWorkspace {
        val w = repository.latest(hash, true) ?: throw DemoFailure(401, "DEMO_SESSION_NOT_FOUND", "Start a demo workspace")
        if (!w.active(clock.instant())) expired()
        return w
    }

    private fun view(w: DemoWorkspace): DemoSessionView {
        val status =
            if (!clock.instant().isBefore(w.expiresAt)) {
                "EXPIRED"
            } else if (w.endedAt != null) {
                "ENDED"
            } else {
                "ACTIVE"
            }
        return DemoSessionView(
            w.id,
            status,
            w.mode,
            w.storeId,
            "BeanFlow 체험점",
            w.expiresAt,
            if (status == "ACTIVE") w.orderReference?.let { orders.inspect(w.customerId, w.storeId, it) } else null,
        )
    }

    private fun replay(
        w: DemoWorkspace,
        key: String,
        operation: String,
        payload: String,
    ): String? =
        repository.command(w.id, key)?.let {
            if (it[0] != operation || it[1] != payload) throw DemoFailure(409, "IDEMPOTENCY_KEY_REUSED", "Command key has another payload")
            it[2]
        }

    private fun committedMetric(outcome: String) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    metrics.counter("beanflow.demo.workspace", "outcome", outcome).increment()
                }
            },
        )
    }

    private fun audit(
        w: DemoWorkspace,
        action: String,
        now: Instant,
        detail: String = "",
    ) {
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = "demo-workspace",
                    actorType = AuditActorType.SYSTEM,
                    category = AuditCategory.SECURITY_AND_PERMISSION,
                    action = action,
                    targetType = "DEMO_WORKSPACE",
                    targetId = w.id,
                    occurredAt = now,
                    reason = "방문자 전용 체험 공간 수명주기",
                    correlationId = correlation.currentOrCreate(),
                    sourceReference = "demo:${w.id}:$action:$now:$detail",
                ),
            ),
        )
    }

    private fun protectExistingSession(
        w: DemoWorkspace?,
        presented: PresentedDemoSessions,
    ) {
        if ((presented.customer != null && presented.customer != w?.customerSessionId) ||
            (presented.merchant != null && presented.merchant != w?.merchantSessionId)
        ) {
            throw DemoFailure(409, "DEMO_SESSION_CONFLICT", "Sign out of the existing account before starting a demo")
        }
    }

    private fun validKey(key: String) {
        if (!key.matches(Regex("[A-Za-z0-9_.:-]{8,128}"))) throw DemoFailure(400, "DEMO_INVALID_REQUEST", "Invalid request key")
    }

    private fun expired(): Nothing = throw DemoFailure(410, "DEMO_EXPIRED", "Demo access has expired")

    companion object {
        private val TERMINAL = setOf("COMPLETED", "REJECTED", "CANCELLED", "EXPIRED")
    }
}

@Component
internal class DemoExpiryWorker(
    private val lifecycle: DemoWorkspaceLifecycleService,
    private val metrics: MeterRegistry,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 60000, initialDelayString = "\${beanflow.demo.expiry-initial-delay-ms:60000}")
    fun run() {
        try {
            lifecycle.expiredWorkspaceIds().forEach { workspaceId ->
                try {
                    lifecycle.expire(workspaceId)
                } catch (failure: RuntimeException) {
                    logger.error("Visitor demo expiry failed; workspaceId={}", workspaceId, failure)
                    failureMetric()
                }
            }
        } catch (failure: RuntimeException) {
            logger.error("Visitor demo expiry candidate scan failed", failure)
            failureMetric()
        }
    }

    private fun failureMetric() {
        metrics.counter("beanflow.demo.workspace", "outcome", "expiry_failed").increment()
    }
}

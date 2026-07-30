package io.github.kdh949.beanflow.notification.internal

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

internal class ScriptedTestNotificationProvider : NotificationProvider {
    private val results = ConcurrentLinkedQueue<NotificationProviderResult>()
    val calls = AtomicInteger()
    val requests = ConcurrentLinkedQueue<NotificationProviderRequest>()

    fun reset() {
        results.clear()
        requests.clear()
        calls.set(0)
    }

    fun enqueue(vararg scripted: NotificationProviderResult) {
        results.addAll(scripted)
    }

    override fun send(request: NotificationProviderRequest): NotificationProviderResult {
        calls.incrementAndGet()
        requests += request
        return results.poll() ?: NotificationProviderResult.Unknown("TEST_UNSCRIPTED")
    }
}

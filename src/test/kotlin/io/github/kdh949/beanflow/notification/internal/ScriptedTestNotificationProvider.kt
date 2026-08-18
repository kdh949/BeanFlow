package io.github.kdh949.beanflow.notification.internal

import io.github.kdh949.beanflow.ResettableTestDouble
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

internal class ScriptedTestNotificationProvider :
    NotificationProvider,
    ResettableTestDouble {
    private val results = ConcurrentLinkedQueue<NotificationProviderResult>()
    val calls = AtomicInteger()
    val requests = ConcurrentLinkedQueue<NotificationProviderRequest>()

    override fun reset() {
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

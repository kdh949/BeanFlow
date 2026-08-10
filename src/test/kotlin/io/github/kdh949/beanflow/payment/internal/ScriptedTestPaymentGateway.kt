package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import io.github.kdh949.beanflow.payment.api.ProviderTransportFailure
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class ScriptedTestPaymentGateway : PaymentGateway {
    private val approvals = ConcurrentLinkedQueue<ProviderPaymentResult>()
    private val oneTimeConfirmations = ConcurrentLinkedQueue<ProviderPaymentResult>()
    private val approvalFailures = ConcurrentLinkedQueue<ProviderTransportFailure>()
    private val lookups = ConcurrentLinkedQueue<ProviderPaymentResult>()
    private val voids = ConcurrentLinkedQueue<GatewayRecoveryResult>()
    private val refunds = ConcurrentLinkedQueue<GatewayRecoveryResult>()
    private val rejectionRefunds = ConcurrentLinkedQueue<GatewayRefundResult>()
    private val rejectionRefundLookups = ConcurrentLinkedQueue<GatewayRefundResult>()
    val approvalCalls = AtomicInteger()
    val oneTimeConfirmationCalls = AtomicInteger()
    val lookupCalls = AtomicInteger()
    val voidCalls = AtomicInteger()
    val refundCalls = AtomicInteger()
    val rejectionRefundCalls = AtomicInteger()
    val rejectionRefundLookupCalls = AtomicInteger()
    val lastRejectionRefundLookupAmountKrw = AtomicReference<Long?>()
    private val nextApprovalBlock = AtomicReference<ApprovalBlock?>()
    private val nextRefundBlock = AtomicReference<ApprovalBlock?>()

    fun reset() {
        approvals.clear()
        oneTimeConfirmations.clear()
        approvalFailures.clear()
        lookups.clear()
        voids.clear()
        refunds.clear()
        rejectionRefunds.clear()
        rejectionRefundLookups.clear()
        approvalCalls.set(0)
        oneTimeConfirmationCalls.set(0)
        lookupCalls.set(0)
        voidCalls.set(0)
        refundCalls.set(0)
        rejectionRefundCalls.set(0)
        rejectionRefundLookupCalls.set(0)
        lastRejectionRefundLookupAmountKrw.set(null)
        nextApprovalBlock.set(null)
        nextRefundBlock.set(null)
    }

    fun enqueueApproval(vararg results: ProviderPaymentResult) {
        approvals.addAll(results)
    }

    fun enqueueOneTimeConfirmation(vararg results: ProviderPaymentResult) {
        oneTimeConfirmations.addAll(results)
    }

    fun enqueueApprovalFailure(message: String) {
        approvalFailures += ProviderTransportFailure(message)
    }

    fun enqueueLookup(vararg results: ProviderPaymentResult) {
        lookups.addAll(results)
    }

    fun enqueueVoid(vararg results: GatewayRecoveryResult) {
        voids.addAll(results)
    }

    fun enqueueRefund(vararg results: GatewayRecoveryResult) {
        refunds.addAll(results)
    }

    fun enqueueRejectionRefund(vararg results: GatewayRefundResult) {
        rejectionRefunds.addAll(results)
    }

    fun enqueueRejectionRefundLookup(vararg results: GatewayRefundResult) {
        rejectionRefundLookups.addAll(results)
    }

    fun blockNextApproval(): ApprovalBlock = ApprovalBlock().also(nextApprovalBlock::set)

    fun blockNextRefund(): ApprovalBlock = ApprovalBlock().also(nextRefundBlock::set)

    override fun approve(request: GatewayApprovalRequest): ProviderPaymentResult {
        approvalCalls.incrementAndGet()
        nextApprovalBlock.getAndSet(null)?.awaitRelease()
        approvalFailures.poll()?.let { throw it }
        return approvals.poll() ?: ProviderPaymentResult.Unknown("TEST_UNSCRIPTED")
    }

    override fun confirmOneTime(request: GatewayOneTimeConfirmationRequest): ProviderPaymentResult {
        oneTimeConfirmationCalls.incrementAndGet()
        nextApprovalBlock.getAndSet(null)?.awaitRelease()
        approvalFailures.poll()?.let { throw it }
        return oneTimeConfirmations.poll() ?: ProviderPaymentResult.Unknown("TEST_UNSCRIPTED")
    }

    override fun lookup(request: GatewayLookupRequest): ProviderPaymentResult {
        lookupCalls.incrementAndGet()
        return lookups.poll() ?: ProviderPaymentResult.Unknown("TEST_UNSCRIPTED")
    }

    override fun void(
        request: GatewayLookupRequest,
        providerIdempotencyKey: String,
    ): GatewayRecoveryResult {
        voidCalls.incrementAndGet()
        return voids.poll() ?: GatewayRecoveryResult.Unknown("TEST_UNSCRIPTED")
    }

    override fun refund(
        request: GatewayLookupRequest,
        amountKrw: Long,
        providerIdempotencyKey: String,
    ): GatewayRecoveryResult {
        refundCalls.incrementAndGet()
        return refunds.poll() ?: GatewayRecoveryResult.Unknown("TEST_UNSCRIPTED")
    }

    override fun requestRefund(
        request: GatewayLookupRequest,
        amountKrw: Long,
        providerIdempotencyKey: String,
    ): GatewayRefundResult {
        rejectionRefundCalls.incrementAndGet()
        nextRefundBlock.getAndSet(null)?.awaitRelease()
        return rejectionRefunds.poll() ?: GatewayRefundResult.Unknown("TEST_UNSCRIPTED")
    }

    override fun lookupRefund(
        request: GatewayLookupRequest,
        amountKrw: Long,
        providerIdempotencyKey: String,
    ): GatewayRefundResult {
        rejectionRefundLookupCalls.incrementAndGet()
        lastRejectionRefundLookupAmountKrw.set(amountKrw)
        return rejectionRefundLookups.poll() ?: GatewayRefundResult.Unknown("TEST_UNSCRIPTED")
    }

    internal class ApprovalBlock {
        private val started = CountDownLatch(1)
        private val release = CountDownLatch(1)

        fun awaitStarted(): Boolean = started.await(5, TimeUnit.SECONDS)

        fun release() {
            release.countDown()
        }

        internal fun awaitRelease() {
            started.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Timed out waiting to release Provider approval" }
        }
    }
}

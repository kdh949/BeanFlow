package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.api.ReservationExpiryUseCase
import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import io.github.kdh949.beanflow.payment.api.PaymentReconciliationOperations
import io.github.kdh949.beanflow.payment.internal.ScriptedTestPaymentGateway
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
	properties = [
		"beanflow.reservation-expiry.initial-delay-ms=3600000",
		"beanflow.audit-retention.initial-delay-ms=3600000",
		"beanflow.payment.reconciliation.initial-delay-ms=3600000",
	],
)
internal class PaymentConfirmationIntegrationTest @Autowired constructor(
	private val createOrderUseCase: CreateOrderUseCase,
	private val expiryUseCase: ReservationExpiryUseCase,
	private val confirmationService: PaymentConfirmationService,
	private val reconciliationWorker: PaymentReconciliationWorker,
	private val reconciliationOperations: PaymentReconciliationOperations,
	private val gateway: ScriptedTestPaymentGateway,
	private val jdbcTemplate: JdbcTemplate,
) {

	@BeforeEach
	fun setUp() {
		OrderCreationDatabaseFixture.clean(jdbcTemplate)
		gateway.reset()
	}

	@Test
	fun `approval confirms all required owners and order in one transaction`() {
		val fixture = OrderCreationFixture()
		val orderId = pendingOrder(fixture, "payment-approved-order")
		val paymentMethodId = insertPaymentMethod(fixture.customerId)
		gateway.enqueueApproval(
			ProviderPaymentResult.Approved("provider-approved-1", 1_000, "KRW"),
		)

		val response = confirmationService.confirm(
			fixture.customerId,
			orderId,
			paymentMethodId,
			"payment-approved-key",
		)

		assertThat(response.status).isEqualTo(200)
		assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("PAID")
		assertThat(
			value<String>("SELECT state FROM fulfillment_pickup_reservation WHERE order_id = ?", orderId),
		).isEqualTo("CONFIRMED")
		assertThat(
			value<String>("SELECT state FROM inventory_stock_reservation WHERE order_id = ?", orderId),
		).isEqualTo("CONFIRMED")
		assertThat(
			value<String>("SELECT approval_state FROM payment_payment WHERE order_id = ?", orderId),
		).isEqualTo("APPROVED")
		assertThat(gateway.approvalCalls.get()).isEqualTo(1)

		val replay = confirmationService.confirm(
			fixture.customerId,
			orderId,
			paymentMethodId,
			"payment-approved-key",
		)
		assertThat(replay.status).isEqualTo(200)
		assertThat(replay.replay).isTrue()
		assertThat(gateway.approvalCalls.get()).isEqualTo(1)
	}

	@Test
	fun `explicit decline cancels order and releases reservations`() {
		val fixture = OrderCreationFixture()
		val orderId = pendingOrder(fixture, "payment-declined-order")
		val paymentMethodId = insertPaymentMethod(fixture.customerId)
		gateway.enqueueApproval(ProviderPaymentResult.Declined("DO_NOT_HONOR"))

		val response = confirmationService.confirm(
			fixture.customerId,
			orderId,
			paymentMethodId,
			"payment-declined-key",
		)

		assertThat(response.status).isEqualTo(422)
		assertThat(response.body).contains(FailureCode.PAYMENT_DECLINED.name)
		assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("CANCELLED")
		assertThat(
			value<String>("SELECT state FROM fulfillment_pickup_reservation WHERE order_id = ?", orderId),
		).isEqualTo("RELEASED")
		assertThat(
			value<String>("SELECT state FROM inventory_stock_reservation WHERE order_id = ?", orderId),
		).isEqualTo("RELEASED")
		assertThat(
			value<String>("SELECT approval_state FROM payment_payment WHERE order_id = ?", orderId),
		).isEqualTo("FAILED")
	}

	@Test
	fun `unknown response is replayed without a second approval and lookup can complete it`() {
		val fixture = OrderCreationFixture()
		val orderId = pendingOrder(fixture, "payment-unknown-order")
		val paymentMethodId = insertPaymentMethod(fixture.customerId)
		gateway.enqueueApproval(ProviderPaymentResult.Unknown("TIMEOUT"))

		val first = confirmationService.confirm(
			fixture.customerId,
			orderId,
			paymentMethodId,
			"payment-unknown-key",
		)
		val replay = confirmationService.confirm(
			fixture.customerId,
			orderId,
			paymentMethodId,
			"payment-unknown-key",
		)

		assertThat(first.status).isEqualTo(202)
		assertThat(replay.status).isEqualTo(202)
		assertThat(replay.replay).isTrue()
		assertThat(gateway.approvalCalls.get()).isEqualTo(1)

		jdbcTemplate.update(
			"UPDATE payment_reconciliation SET next_attempt_at = ? WHERE payment_id = " +
				"(SELECT id FROM payment_payment WHERE order_id = ?)",
			Timestamp.from(Instant.now().minusSeconds(1)),
			orderId,
		)
		gateway.enqueueLookup(
			ProviderPaymentResult.Approved("provider-approved-after-lookup", 1_000, "KRW"),
		)

		assertThat(reconciliationWorker.runOnce()).isEqualTo(1)
		assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("PAID")
		assertThat(gateway.lookupCalls.get()).isEqualTo(1)
	}

	@Test
	fun `unknown replay at the lease boundary materializes expiry without a new approval`() {
		val fixture = OrderCreationFixture()
		val orderId = pendingOrder(fixture, "payment-unknown-expiry-order")
		val paymentMethodId = insertPaymentMethod(fixture.customerId)
		gateway.enqueueApproval(ProviderPaymentResult.Unknown("TIMEOUT"))
		confirmationService.confirm(
			fixture.customerId,
			orderId,
			paymentMethodId,
			"payment-unknown-expiry-key",
		)
		makeOrderAndApprovalLookupDue(orderId)

		val replay = confirmationService.confirm(
			fixture.customerId,
			orderId,
			paymentMethodId,
			"payment-unknown-expiry-key",
		)

		assertThat(replay.status).isEqualTo(202)
		assertThat(replay.replay).isTrue()
		assertThat(gateway.approvalCalls.get()).isEqualTo(1)
		assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("EXPIRED")
	}

	@Test
	fun `same key with another payment method is rejected before another Provider call`() {
		val fixture = OrderCreationFixture()
		val orderId = pendingOrder(fixture, "payment-key-conflict-order")
		val firstMethodId = insertPaymentMethod(fixture.customerId)
		val otherMethodId = insertPaymentMethod(fixture.customerId)
		gateway.enqueueApproval(ProviderPaymentResult.Unknown("TIMEOUT"))
		confirmationService.confirm(fixture.customerId, orderId, firstMethodId, "payment-conflict-key")

		assertThatThrownBy {
			confirmationService.confirm(
				fixture.customerId,
				orderId,
				otherMethodId,
				"payment-conflict-key",
			)
		}
			.isInstanceOfSatisfying(DomainFailure::class.java) {
				assertThat(it.code).isEqualTo(FailureCode.IDEMPOTENCY_KEY_REUSED)
			}
		assertThat(gateway.approvalCalls.get()).isEqualTo(1)
	}

	@Test
	fun `late approval never revives an expired order and is voided once`() {
		val fixture = OrderCreationFixture()
		val orderId = pendingOrder(fixture, "payment-late-order")
		val paymentMethodId = insertPaymentMethod(fixture.customerId)
		gateway.enqueueApproval(ProviderPaymentResult.Unknown("RESPONSE_LOST"))
		assertThat(
			confirmationService.confirm(
				fixture.customerId,
				orderId,
				paymentMethodId,
				"payment-late-key",
			).status,
		).isEqualTo(202)
		makeOrderAndApprovalLookupDue(orderId)
		gateway.enqueueLookup(
			ProviderPaymentResult.Approved("provider-late-approval", 1_000, "KRW"),
		)

		assertThat(reconciliationWorker.runOnce()).isEqualTo(1)
		assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("EXPIRED")
		assertThat(
			value<String>("SELECT approval_state FROM payment_payment WHERE order_id = ?", orderId),
		).isEqualTo("RECONCILING")

		gateway.enqueueVoid(io.github.kdh949.beanflow.payment.internal.GatewayRecoveryResult.Succeeded)
		assertThat(reconciliationWorker.runOnce()).isEqualTo(1)
		assertThat(gateway.voidCalls.get()).isEqualTo(1)
		assertThat(
			value<String>(
				"SELECT status FROM payment_reconciliation WHERE kind = 'LATE_VOID' " +
					"AND payment_id = (SELECT id FROM payment_payment WHERE order_id = ?)",
				orderId,
			),
		).isEqualTo("SUCCEEDED")
		assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("EXPIRED")
	}

	@Test
	fun `five unknown lookups create one manual review case and stop automatic calls`() {
		val fixture = OrderCreationFixture()
		val orderId = pendingOrder(fixture, "payment-manual-order")
		val paymentMethodId = insertPaymentMethod(fixture.customerId)
		gateway.enqueueApproval(ProviderPaymentResult.Unknown("TIMEOUT"))
		confirmationService.confirm(fixture.customerId, orderId, paymentMethodId, "payment-manual-key")

		repeat(5) { attempt ->
			makeApprovalLookupDue(orderId)
			gateway.enqueueLookup(ProviderPaymentResult.Unknown("LOOKUP_UNKNOWN_${attempt + 1}"))
			assertThat(reconciliationWorker.runOnce()).isEqualTo(1)
		}

		assertThat(gateway.lookupCalls.get()).isEqualTo(5)
		assertThat(
			value<String>("SELECT approval_state FROM payment_payment WHERE order_id = ?", orderId),
		).isEqualTo("MANUAL_REVIEW")
		assertThat(
			value<Long>(
				"SELECT count(*) FROM operations_reprocessing_case WHERE owner_reference = " +
					"(SELECT source_reference FROM payment_reconciliation WHERE kind = 'APPROVAL_LOOKUP' " +
					"AND payment_id = (SELECT id FROM payment_payment WHERE order_id = ?))",
				orderId,
			),
		).isEqualTo(1)
		assertThat(reconciliationWorker.runOnce()).isZero()
		assertThat(gateway.lookupCalls.get()).isEqualTo(5)
	}

	@Test
	fun `explicitly unavailable late void falls forward to one full refund`() {
		val fixture = OrderCreationFixture()
		val orderId = pendingOrder(fixture, "payment-late-refund-order")
		val paymentMethodId = insertPaymentMethod(fixture.customerId)
		gateway.enqueueApproval(ProviderPaymentResult.Unknown("RESPONSE_LOST"))
		confirmationService.confirm(
			fixture.customerId,
			orderId,
			paymentMethodId,
			"payment-late-refund-key",
		)
		makeOrderAndApprovalLookupDue(orderId)
		gateway.enqueueLookup(
			ProviderPaymentResult.Approved("provider-late-refund", 1_000, "KRW"),
		)
		assertThat(reconciliationWorker.runOnce()).isEqualTo(1)

		gateway.enqueueVoid(io.github.kdh949.beanflow.payment.internal.GatewayRecoveryResult.Unavailable)
		assertThat(reconciliationWorker.runOnce()).isEqualTo(1)
		gateway.enqueueRefund(io.github.kdh949.beanflow.payment.internal.GatewayRecoveryResult.Succeeded)
		assertThat(reconciliationWorker.runOnce()).isEqualTo(1)

		assertThat(gateway.voidCalls.get()).isEqualTo(1)
		assertThat(gateway.refundCalls.get()).isEqualTo(1)
		assertThat(
			value<String>(
				"SELECT status FROM payment_reconciliation WHERE kind = 'LATE_REFUND' " +
					"AND payment_id = (SELECT id FROM payment_payment WHERE order_id = ?)",
				orderId,
			),
		).isEqualTo("SUCCEEDED")
		assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("EXPIRED")
	}

	@Test
	fun `owner failure rolls back approval state order and earlier confirmations`() {
		val fixture = OrderCreationFixture()
		val orderId = pendingOrder(fixture, "payment-owner-fault-order")
		val paymentMethodId = insertPaymentMethod(fixture.customerId)
		jdbcTemplate.update("DELETE FROM inventory_stock_reservation WHERE order_id = ?", orderId)
		gateway.enqueueApproval(
			ProviderPaymentResult.Approved("provider-owner-fault", 1_000, "KRW"),
		)

		assertThatThrownBy {
			confirmationService.confirm(
				fixture.customerId,
				orderId,
				paymentMethodId,
				"payment-owner-fault-key",
			)
		}.isInstanceOf(DomainFailure::class.java)

		assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId))
			.isEqualTo("PENDING_PAYMENT")
		assertThat(
			value<String>("SELECT state FROM fulfillment_pickup_reservation WHERE order_id = ?", orderId),
		).isEqualTo("RESERVED")
		assertThat(
			value<String>("SELECT approval_state FROM payment_payment WHERE order_id = ?", orderId),
		).isEqualTo("APPROVING")
	}

	@Test
	fun `payment method ownership is enforced before Provider call`() {
		val fixture = OrderCreationFixture()
		val orderId = pendingOrder(fixture, "payment-method-owner-order")
		val paymentMethodId = insertPaymentMethod(UUID.randomUUID())

		assertThatThrownBy {
			confirmationService.confirm(
				fixture.customerId,
				orderId,
				paymentMethodId,
				"payment-method-owner-key",
			)
		}
			.isInstanceOfSatisfying(DomainFailure::class.java) {
				assertThat(it.code).isEqualTo(FailureCode.ACCESS_DENIED)
			}
		assertThat(gateway.approvalCalls.get()).isZero()
		assertThat(value<Long>("SELECT count(*) FROM payment_payment")).isZero()
	}

	@Test
	fun `revoked payment method is rejected before Provider call`() {
		val fixture = OrderCreationFixture()
		val orderId = pendingOrder(fixture, "payment-method-revoked-order")
		val paymentMethodId = insertPaymentMethod(fixture.customerId, "REVOKED")

		assertThatThrownBy {
			confirmationService.confirm(
				fixture.customerId,
				orderId,
				paymentMethodId,
				"payment-method-revoked-key",
			)
		}
			.isInstanceOfSatisfying(DomainFailure::class.java) {
				assertThat(it.code).isEqualTo(FailureCode.ORDER_STATE_CONFLICT)
			}
		assertThat(gateway.approvalCalls.get()).isZero()
	}

	@Test
	fun `Provider transport failure becomes UNKNOWN and keeps lookup recovery`() {
		val fixture = OrderCreationFixture()
		val orderId = pendingOrder(fixture, "payment-transport-order")
		val paymentMethodId = insertPaymentMethod(fixture.customerId)
		gateway.enqueueApprovalFailure("connection reset")

		val response = confirmationService.confirm(
			fixture.customerId,
			orderId,
			paymentMethodId,
			"payment-transport-key",
		)

		assertThat(response.status).isEqualTo(202)
		assertThat(response.body).contains("\"approvalState\":\"UNKNOWN\"")
		assertThat(
			value<String>("SELECT status FROM payment_reconciliation WHERE payment_id = " +
				"(SELECT id FROM payment_payment WHERE order_id = ?)", orderId),
		).isEqualTo("SCHEDULED")
	}

	@Test
	fun `concurrent requests with the same key execute one Provider approval`() {
		val fixture = OrderCreationFixture()
		val orderId = pendingOrder(fixture, "payment-concurrent-order")
		val paymentMethodId = insertPaymentMethod(fixture.customerId)
		val block = gateway.blockNextApproval()
		gateway.enqueueApproval(
			ProviderPaymentResult.Approved("provider-concurrent-approval", 1_000, "KRW"),
		)
		val executor = Executors.newSingleThreadExecutor()
		val first = executor.submit<io.github.kdh949.beanflow.ordering.api.StoredHttpResponse> {
			confirmationService.confirm(
				fixture.customerId,
				orderId,
				paymentMethodId,
				"payment-concurrent-key",
			)
		}

		try {
			assertThat(block.awaitStarted()).isTrue()
			val concurrent = confirmationService.confirm(
				fixture.customerId,
				orderId,
				paymentMethodId,
				"payment-concurrent-key",
			)
			assertThat(concurrent.status).isEqualTo(409)
			assertThat(concurrent.body).contains(FailureCode.IDEMPOTENCY_REQUEST_IN_PROGRESS.name)
		} finally {
			block.release()
			executor.shutdown()
		}

		assertThat(first.get(5, TimeUnit.SECONDS).status).isEqualTo(200)
		assertThat(gateway.approvalCalls.get()).isEqualTo(1)
	}

	@Test
	fun `amount mismatch never pays the order and consumes the bounded lookup schedule`() {
		val fixture = OrderCreationFixture()
		val orderId = pendingOrder(fixture, "payment-mismatch-order")
		val paymentMethodId = insertPaymentMethod(fixture.customerId)
		gateway.enqueueApproval(
			ProviderPaymentResult.Approved("provider-mismatch", 999, "KRW"),
		)

		val response = confirmationService.confirm(
			fixture.customerId,
			orderId,
			paymentMethodId,
			"payment-mismatch-key",
		)
		assertThat(response.status).isEqualTo(202)
		assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId))
			.isEqualTo("PENDING_PAYMENT")

		repeat(5) {
			makeApprovalLookupDue(orderId)
			gateway.enqueueLookup(
				ProviderPaymentResult.Approved("provider-mismatch", 999, "KRW"),
			)
			assertThat(reconciliationWorker.runOnce()).isEqualTo(1)
		}

		assertThat(
			value<String>("SELECT approval_state FROM payment_payment WHERE order_id = ?", orderId),
		).isEqualTo("MANUAL_REVIEW")
		assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId))
			.isEqualTo("PENDING_PAYMENT")
	}

	@Test
	fun `expired claim lease is reclaimed after a worker stops`() {
		val fixture = OrderCreationFixture()
		val orderId = pendingOrder(fixture, "payment-claim-restart-order")
		val paymentMethodId = insertPaymentMethod(fixture.customerId)
		gateway.enqueueApproval(ProviderPaymentResult.Unknown("TIMEOUT"))
		confirmationService.confirm(
			fixture.customerId,
			orderId,
			paymentMethodId,
			"payment-claim-restart-key",
		)
		makeApprovalLookupDue(orderId)

		val abandoned = reconciliationOperations.claimDue(Instant.now(), 1)
		assertThat(abandoned).hasSize(1)
		assertThat(gateway.lookupCalls.get()).isZero()
		jdbcTemplate.update(
			"UPDATE payment_reconciliation SET claim_until = ? WHERE id = ?",
			Timestamp.from(Instant.now().minusSeconds(1)),
			abandoned.single().workId,
		)
		gateway.enqueueLookup(
			ProviderPaymentResult.Approved("provider-reclaimed", 1_000, "KRW"),
		)

		assertThat(reconciliationWorker.runOnce()).isEqualTo(1)
		assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("PAID")
		assertThat(gateway.lookupCalls.get()).isEqualTo(1)
	}

	@Test
	fun `approval lookup racing the exact lease boundary cannot revive the order`() {
		val fixture = OrderCreationFixture()
		val orderId = pendingOrder(fixture, "payment-expiry-race-order")
		val paymentMethodId = insertPaymentMethod(fixture.customerId)
		gateway.enqueueApproval(ProviderPaymentResult.Unknown("TIMEOUT"))
		confirmationService.confirm(
			fixture.customerId,
			orderId,
			paymentMethodId,
			"payment-expiry-race-key",
		)
		makeOrderAndApprovalLookupDue(orderId)
		gateway.enqueueLookup(
			ProviderPaymentResult.Approved("provider-expiry-race", 1_000, "KRW"),
		)
		val barrier = CyclicBarrier(2)
		val executor = Executors.newFixedThreadPool(2)
		val now = Instant.now()
		val expiry = executor.submit {
			barrier.await()
			expiryUseCase.expireIfDue(orderId, now)
		}
		val payment = executor.submit<Int> {
			barrier.await()
			reconciliationWorker.runOnce()
		}

		try {
			expiry.get(10, TimeUnit.SECONDS)
			assertThat(payment.get(10, TimeUnit.SECONDS)).isEqualTo(1)
		} finally {
			executor.shutdownNow()
		}

		assertThat(value<String>("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("EXPIRED")
		assertThat(
			value<String>("SELECT approval_state FROM payment_payment WHERE order_id = ?", orderId),
		).isEqualTo("RECONCILING")
		assertThat(
			value<Long>(
				"SELECT count(*) FROM payment_reconciliation WHERE kind = 'LATE_VOID' " +
					"AND payment_id = (SELECT id FROM payment_payment WHERE order_id = ?)",
				orderId,
			),
		).isEqualTo(1)
	}

	private fun pendingOrder(fixture: OrderCreationFixture, key: String): UUID {
		OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
		assertThat(createOrderUseCase.create(key, fixture.command()).status).isEqualTo(201)
		return value("SELECT id FROM ordering_order")
	}

	private fun insertPaymentMethod(customerId: UUID, status: String = "ACTIVE"): UUID {
		val id = UUID.randomUUID()
		val now = Timestamp.from(Instant.now())
		jdbcTemplate.update(
			"""
			INSERT INTO payment_method (
			    id, customer_id, provider, token_reference, display_alias, card_brand,
			    last_four, status, created_at, updated_at, version
			)
			VALUES (?, ?, 'SCRIPTED', ?, 'Test method', 'TEST', '4242', ?, ?, ?, 0)
			""".trimIndent(),
			id,
			customerId,
			"test-token:$id",
			status,
			now,
			now,
		)
		return id
	}

	private fun makeOrderAndApprovalLookupDue(orderId: UUID) {
		val dueAt = Timestamp.from(Instant.now().minusSeconds(1))
		jdbcTemplate.update("UPDATE ordering_order SET reservation_expires_at = ? WHERE id = ?", dueAt, orderId)
		jdbcTemplate.update(
			"UPDATE fulfillment_pickup_reservation SET expires_at = ? WHERE order_id = ?",
			dueAt,
			orderId,
		)
		jdbcTemplate.update(
			"UPDATE inventory_stock_reservation SET expires_at = ? WHERE order_id = ?",
			dueAt,
			orderId,
		)
		makeApprovalLookupDue(orderId)
	}

	private fun makeApprovalLookupDue(orderId: UUID) {
		jdbcTemplate.update(
			"UPDATE payment_reconciliation SET next_attempt_at = ? WHERE kind = 'APPROVAL_LOOKUP' " +
				"AND payment_id = (SELECT id FROM payment_payment WHERE order_id = ?)",
			Timestamp.from(Instant.now().minusSeconds(1)),
			orderId,
		)
	}

	private inline fun <reified T : Any> value(sql: String, vararg args: Any): T =
		requireNotNull(jdbcTemplate.queryForObject(sql, T::class.java, *args))
}

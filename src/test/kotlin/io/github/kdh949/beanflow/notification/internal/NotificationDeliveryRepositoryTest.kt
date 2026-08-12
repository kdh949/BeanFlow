package io.github.kdh949.beanflow.notification.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.eventing.api.BenefitRestorationPolicySnapshotV1
import io.github.kdh949.beanflow.eventing.api.CustomerCancellationRefundDelayedV1
import io.github.kdh949.beanflow.eventing.api.CustomerCancellationRefundSucceededV1
import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import io.github.kdh949.beanflow.eventing.api.OrderRejectionActorType
import io.github.kdh949.beanflow.notification.api.ProfileNotificationOwnerType
import io.github.kdh949.beanflow.notification.api.RequestCustomerCancellationAcceptedNotificationCommand
import io.github.kdh949.beanflow.notification.api.RequestProfileChangeNotificationCommand
import io.github.kdh949.beanflow.notification.internal.domain.NotificationDeliveryState
import io.github.kdh949.beanflow.notification.internal.domain.NotificationLogicalChannel
import io.github.kdh949.beanflow.notification.internal.domain.NotificationRecipientType
import io.github.kdh949.beanflow.notification.internal.domain.NotificationTemplate
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationMode
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicySnapshot
import io.github.kdh949.beanflow.operations.api.OpenOrderCompensationCaseCommand
import io.github.kdh949.beanflow.operations.api.OrderCompensationOperations
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepState
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepType
import io.github.kdh949.beanflow.operations.api.OrderCompensationTrigger
import io.github.kdh949.beanflow.shared.api.ProfileNotificationChannel
import io.github.kdh949.beanflow.shared.api.ProfileNotificationTargetKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.refund.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
    ],
)
internal class NotificationDeliveryRepositoryTest
    @Autowired
    constructor(
        private val service: NotificationDeliveryService,
        private val repository: NotificationDeliveryJpaRepository,
        private val compensationOperations: OrderCompensationOperations,
        private val provider: ScriptedTestNotificationProvider,
        private val jdbcTemplate: JdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)

        @BeforeEach
        fun cleanDatabase() {
            jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    notification_delivery,
                    operations_reprocessing_case,
                    operations_order_compensation_step,
                    operations_order_compensation_case
                CASCADE
                """.trimIndent(),
            )
            provider.reset()
        }

        @Test
        fun `rejection notification is unique and recovers unknown result with same provider key`() {
            val event = rejectionEvent()
            openCompensationCase(event)
            service.requestRejection(event)
            service.requestRejection(event)

            provider.enqueue(NotificationProviderResult.Unknown("ACK_LOST"))
            val firstClaim = service.claimDue(NOW, 10).single()
            service.recordResult(firstClaim, service.callProvider(firstClaim), NOW)

            val retrying = repository.findAll().single()
            val unknownStep =
                compensationOperations
                    .findByOrderId(event.orderId)!!
                    .steps
                    .single { it.type == OrderCompensationStepType.CUSTOMER_NOTIFICATION }
            assertThat(retrying.state).isEqualTo(NotificationDeliveryState.RETRY_SCHEDULED)
            assertThat(retrying.nextAttemptAt).isEqualTo(NOW.plusSeconds(60))
            assertThat(unknownStep.state).isEqualTo(OrderCompensationStepState.UNKNOWN)

            provider.enqueue(NotificationProviderResult.Acknowledged("provider-delivery-1"))
            val retryClaim = service.claimDue(NOW.plusSeconds(60), 10).single()
            service.recordResult(
                retryClaim,
                service.callProvider(retryClaim),
                NOW.plusSeconds(60),
            )

            val succeeded = repository.findAll().single()
            val succeededStep =
                compensationOperations
                    .findByOrderId(event.orderId)!!
                    .steps
                    .single { it.type == OrderCompensationStepType.CUSTOMER_NOTIFICATION }
            assertThat(succeeded.state).isEqualTo(NotificationDeliveryState.SUCCEEDED)
            assertThat(succeeded.recipientType).isEqualTo(NotificationRecipientType.CUSTOMER)
            assertThat(succeeded.logicalChannel).isEqualTo(NotificationLogicalChannel.CUSTOMER_APP)
            assertThat(succeededStep.state).isEqualTo(OrderCompensationStepState.SUCCEEDED)
            assertThat(provider.requests.map { it.providerIdempotencyKey }.distinct()).hasSize(1)
            assertThat(repository.count()).isEqualTo(1)
        }

        @Test
        fun `four failed attempts create manual review case and stop automatic delivery`() {
            val event = rejectionEvent()
            openCompensationCase(event)
            service.requestRejection(event)
            var now = NOW

            repeat(4) {
                val claim = service.claimDue(now, 10).single()
                service.recordResult(claim, NotificationProviderResult.Failed("PROVIDER_DOWN"), now)
                now = repository.findAll().single().nextAttemptAt ?: now
            }

            val delivery = repository.findAll().single()
            val step =
                compensationOperations
                    .findByOrderId(event.orderId)!!
                    .steps
                    .single { it.type == OrderCompensationStepType.CUSTOMER_NOTIFICATION }
            assertThat(delivery.state).isEqualTo(NotificationDeliveryState.MANUAL_REVIEW)
            assertThat(step.state).isEqualTo(OrderCompensationStepState.MANUAL_REVIEW)
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM operations_reprocessing_case " +
                        "WHERE case_type = 'NOTIFICATION_DELIVERY'",
                    Long::class.java,
                ),
            ).isEqualTo(1)
            assertThat(service.claimDue(now.plusSeconds(1), 10)).isEmpty()
        }

        @Test
        fun `customer cancellation acceptance stores one pending delivery without reason detail or provider call`() {
            val orderId = UUID.randomUUID()
            val eventId = UUID.randomUUID()
            val command =
                RequestCustomerCancellationAcceptedNotificationCommand(
                    eventId = eventId,
                    orderId = orderId,
                    customerId = UUID.randomUUID(),
                    storeId = UUID.randomUUID(),
                    orderAggregateVersion = 7,
                    cancelledAt = NOW,
                    correlationId = "customer-cancellation-$orderId",
                )

            val first = transactions.execute { service.requestAccepted(command) }
            val replay = transactions.execute { service.requestAccepted(command) }

            assertThat(first).isEqualTo(replay)
            val delivery = repository.findAll().single()
            assertThat(delivery.state).isEqualTo(NotificationDeliveryState.PENDING)
            assertThat(delivery.template).isEqualTo(NotificationTemplate.ORDER_CANCELLATION_ACCEPTED)
            assertThat(delivery.payloadJson).contains(orderId.toString(), "cancelledAt")
            assertThat(delivery.payloadJson).doesNotContain("reason", "detail")
            assertThat(provider.requests).isEmpty()
        }

        @Test
        fun `customer cancellation terminal delivery deduplicates by logical source across event ids`() {
            val orderId = UUID.randomUUID()
            val customerId = UUID.randomUUID()
            val first = cancellationSucceededEvent(orderId, customerId, UUID.randomUUID(), 7_000)
            val replay = cancellationSucceededEvent(orderId, customerId, UUID.randomUUID(), 7_000)

            service.requestCustomerCancellationRefundSucceeded(first)
            service.requestCustomerCancellationRefundSucceeded(replay)

            val delivery = repository.findAll().single()
            assertThat(delivery.logicalSource).isEqualTo("order:$orderId:customer-cancellation:11:refund-succeeded")
            assertThat(delivery.template).isEqualTo(NotificationTemplate.CUSTOMER_CANCELLATION_REFUND_SUCCEEDED)
            assertThat(delivery.payloadJson).contains("\"refundAmountKrw\":7000", "\"locale\":\"ko-KR\"")
            assertThat(delivery.payloadJson).doesNotContain("refundId", "paymentId", "provider", "attempt")
        }

        @Test
        fun `customer cancellation delayed then succeeded creates two independent logical deliveries`() {
            val orderId = UUID.randomUUID()
            val customerId = UUID.randomUUID()

            service.requestCustomerCancellationRefundDelayed(
                cancellationDelayedEvent(orderId, customerId, UUID.randomUUID(), 7_000),
            )
            service.requestCustomerCancellationRefundSucceeded(
                cancellationSucceededEvent(orderId, customerId, UUID.randomUUID(), 7_000),
            )

            val deliveries = repository.findAll()
            assertThat(deliveries).hasSize(2)
            assertThat(deliveries.map { it.template })
                .containsExactlyInAnyOrder(
                    NotificationTemplate.CUSTOMER_CANCELLATION_REFUND_DELAYED,
                    NotificationTemplate.CUSTOMER_CANCELLATION_REFUND_SUCCEEDED,
                )
            assertThat(deliveries.map { it.logicalSource })
                .containsExactlyInAnyOrder(
                    "order:$orderId:customer-cancellation:11:refund-delayed",
                    "order:$orderId:customer-cancellation:11:refund-succeeded",
                )
        }

        @Test
        fun `same cancellation terminal logical source with conflicting payload fails closed`() {
            val orderId = UUID.randomUUID()
            val customerId = UUID.randomUUID()
            service.requestCustomerCancellationRefundDelayed(
                cancellationDelayedEvent(orderId, customerId, UUID.randomUUID(), 7_000),
            )

            assertThatThrownBy {
                service.requestCustomerCancellationRefundDelayed(
                    cancellationDelayedEvent(orderId, customerId, UUID.randomUUID(), 6_000),
                )
            }.isInstanceOfSatisfying(io.github.kdh949.beanflow.shared.api.DomainFailure::class.java) {
                assertThat(it.message).contains("NOTIFICATION_SOURCE_CONFLICT")
            }
            assertThat(repository.count()).isEqualTo(1)
        }

        @Test
        fun `profile change logical source rejoins across request correlations when semantic payload is unchanged`() {
            val profileChangeId = UUID.randomUUID()
            val targetId = UUID.randomUUID()
            val first =
                RequestProfileChangeNotificationCommand(
                    profileChangeId,
                    ProfileNotificationOwnerType.CUSTOMER,
                    targetId,
                    ProfileNotificationTargetKind.OLD,
                    ProfileNotificationChannel.PHONE,
                    "CUSTOMER_PRIMARY_PHONE",
                    NOW,
                    "first-http-correlation",
                )
            val retry = first.copy(correlationId = "retry-http-correlation")

            val accepted = service.requestProfileChange(first)
            val rejoined = service.requestProfileChange(retry)

            assertThat(rejoined.deliveryId).isEqualTo(accepted.deliveryId)
            assertThat(repository.count()).isOne()
            assertThat(repository.findAll().single().correlationId).isEqualTo("first-http-correlation")
        }

        private fun cancellationSucceededEvent(
            orderId: UUID,
            customerId: UUID,
            eventId: UUID,
            amountKrw: Long,
        ) = CustomerCancellationRefundSucceededV1(
            envelope = cancellationEnvelope(orderId, eventId, "CustomerCancellationRefundSucceededV1", "succeeded"),
            orderId = orderId,
            customerId = customerId,
            orderAggregateVersion = 11,
            refundAmountKrw = amountKrw,
            outcomeAt = NOW,
        )

        private fun cancellationDelayedEvent(
            orderId: UUID,
            customerId: UUID,
            eventId: UUID,
            amountKrw: Long,
        ) = CustomerCancellationRefundDelayedV1(
            envelope = cancellationEnvelope(orderId, eventId, "CustomerCancellationRefundDelayedV1", "delayed"),
            orderId = orderId,
            customerId = customerId,
            orderAggregateVersion = 11,
            refundAmountKrw = amountKrw,
            outcomeAt = NOW,
        )

        private fun cancellationEnvelope(
            orderId: UUID,
            eventId: UUID,
            eventType: String,
            outcome: String,
        ): EventEnvelope =
            EventEnvelope(
                eventId = eventId,
                eventType = eventType,
                aggregateId = UUID.randomUUID(),
                aggregateVersion = 1,
                occurredAt = NOW,
                payloadVersion = 1,
                correlationId = "customer-cancellation-$orderId",
                causationId = "refund:${UUID.randomUUID()}:customer-cancellation:$outcome",
            )

        private fun rejectionEvent(): OrderRejectedV1 {
            val eventId = UUID.randomUUID()
            val orderId = UUID.randomUUID()
            return OrderRejectedV1(
                envelope =
                    EventEnvelope(
                        eventId = eventId,
                        eventType = "OrderRejectedV1",
                        aggregateId = orderId,
                        aggregateVersion = 1,
                        occurredAt = NOW,
                        payloadVersion = 1,
                        correlationId = "correlation-$orderId",
                        causationId = "store-command",
                    ),
                orderId = orderId,
                customerId = UUID.randomUUID(),
                storeId = UUID.randomUUID(),
                actorId = UUID.randomUUID().toString(),
                actorType = OrderRejectionActorType.STORE_STAFF,
                reason = "OUT_OF_STOCK",
                rejectedAt = NOW,
                couponPolicy = eventPolicy(),
                pointsPolicy = eventPolicy(),
                paymentRequired = false,
                couponRequired = false,
                pointsRequired = false,
            )
        }

        private fun openCompensationCase(event: OrderRejectedV1) {
            compensationOperations.open(
                OpenOrderCompensationCaseCommand(
                    caseId = UUID.randomUUID(),
                    eventId = event.envelope.eventId,
                    orderId = event.orderId,
                    terminalOrderVersion = event.envelope.aggregateVersion,
                    customerId = event.customerId,
                    storeId = event.storeId,
                    trigger = OrderCompensationTrigger.STORE_REJECTION,
                    sourceReference = "event:${event.envelope.eventId}:rejection-case",
                    couponPolicy =
                        ExpiredBenefitRestorationPolicySnapshot(
                            policyVersion = 1,
                            mode = ExpiredBenefitRestorationMode.COMPENSATE_WITH_NEW_ISSUANCE,
                            compensationValidityDays = 30,
                            effectiveAt = NOW,
                            updatedBy = UUID(0, 0),
                            reason = "INITIAL_DEFAULT",
                        ),
                    pointsPolicy =
                        ExpiredBenefitRestorationPolicySnapshot(
                            policyVersion = 2,
                            mode = ExpiredBenefitRestorationMode.COMPENSATE_WITH_NEW_ISSUANCE,
                            compensationValidityDays = 30,
                            effectiveAt = NOW,
                            updatedBy = UUID(0, 0),
                            reason = "INITIAL_DEFAULT",
                        ),
                    paymentRequired = false,
                    couponRequired = false,
                    pointsRequired = false,
                    correlationId = event.envelope.correlationId,
                    now = NOW,
                ),
            )
        }

        private fun eventPolicy() =
            BenefitRestorationPolicySnapshotV1(
                policyVersionId = 1,
                mode = ExpiredBenefitRestorationMode.COMPENSATE_WITH_NEW_ISSUANCE.name,
                compensationValidityDays = 30,
            )

        private companion object {
            val NOW: Instant = Instant.parse("2026-07-30T11:00:00Z")
        }
    }

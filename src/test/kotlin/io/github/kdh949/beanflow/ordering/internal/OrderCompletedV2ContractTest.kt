package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.OrderCompletedV2Contract
import io.github.kdh949.beanflow.ordering.api.OrderSettlementInputSnapshot
import io.github.kdh949.beanflow.ordering.api.SettlementCouponCostBearer
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal class OrderCompletedV2ContractTest {
    private val factory = OrderCompletedV2Factory()
    private val objectMapper = ObjectMapper()

    @Test
    fun `factory maps only immutable snapshot and approved payment into the V2 contract fixture`() {
        val fixture = fixture()

        val event =
            factory.create(
                fixture.order,
                fixture.payment,
                fixture.snapshot,
                fixture.envelope,
            )
        val expected =
            requireNotNull(javaClass.getResourceAsStream("/contracts/order-completed-v2.json")).use {
                objectMapper.readTree(it)
            }

        assertThat(objectMapper.readTree(objectMapper.writeValueAsBytes(event))).isEqualTo(expected)
        assertThat(event.settlementDate).isEqualTo(LocalDate.parse("2026-08-02"))
        assertThat(event.completionSource).isEqualTo("order:${fixture.order.orderId}:completed:5")
    }

    @Test
    fun `payment approval amount must equal the immutable fee basis`() {
        val fixture = fixture()

        assertThatThrownBy {
            factory.create(
                fixture.order,
                fixture.payment.copy(approvedAmountKrw = fixture.payment.approvedAmountKrw - 1),
                fixture.snapshot,
                fixture.envelope,
            )
        }.isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.SETTLEMENT_INPUT_UNAVAILABLE)
        }
    }

    @Test
    fun `negative net settlement input is rejected before V2 mapping`() {
        val fixture = fixture()

        assertThatThrownBy {
            factory.create(
                fixture.order,
                fixture.payment,
                fixture.snapshot.copy(netSettlementKrw = -1),
                fixture.envelope,
            )
        }.isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.SETTLEMENT_INPUT_UNAVAILABLE)
        }
    }

    @Test
    fun `benefit only approval with zero payable remains a valid completion input`() {
        val fixture = fixture()
        val snapshot =
            fixture.snapshot.copy(
                couponReservationId = null,
                couponCampaignId = null,
                couponCampaignVersion = null,
                couponCostBearer = null,
                couponPlatformShareBps = null,
                couponStoreShareBps = null,
                couponDiscountKrw = 0,
                platformCouponCostKrw = 0,
                couponCostKrw = 0,
                pointReservationId = UUID.fromString("77777777-7777-7777-7777-777777777777"),
                pointAllocationHash = "b".repeat(64),
                pointsAppliedKrw = 1_000,
                pointCostKrw = 0,
                feeBaseKrw = 0,
                feeKrw = 0,
                benefitCostKrw = 0,
                netSettlementKrw = 1_000,
                canonicalSnapshotHash = "c".repeat(64),
            )

        val event =
            factory.create(
                fixture.order,
                fixture.payment.copy(approvedAmountKrw = 0),
                snapshot,
                fixture.envelope,
            )

        assertThat(event.feeKrw).isZero()
        assertThat(event.benefitCostKrw).isZero()
        assertThat(event.netSettlementKrw).isEqualTo(1_000)
    }

    @Test
    fun `validator rejects an envelope type or payload version mismatch`() {
        val fixture = fixture()
        val event = factory.create(fixture.order, fixture.payment, fixture.snapshot, fixture.envelope)

        assertThatThrownBy {
            OrderCompletedV2Contract.validate(
                event.copy(
                    envelope =
                        event.envelope.copy(
                            eventType = "OrderCompletedV1",
                            payloadVersion = 1,
                        ),
                ),
            )
        }.isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.SETTLEMENT_INPUT_UNAVAILABLE)
        }
    }

    @Test
    fun `delayed mapping remains unchanged when current owner values change outside the frozen input`() {
        val fixture = fixture()
        val before = factory.create(fixture.order, fixture.payment, fixture.snapshot, fixture.envelope)
        val ignoredCurrentTermsRate = 900
        val ignoredCurrentCampaignStoreShare = 1_000
        val ignoredNewStoreIssuerReference = UUID.randomUUID().toString()

        val after = factory.create(fixture.order, fixture.payment, fixture.snapshot, fixture.envelope)

        assertThat(ignoredCurrentTermsRate).isNotEqualTo(fixture.snapshot.feeRateBps)
        assertThat(ignoredCurrentCampaignStoreShare).isNotEqualTo(fixture.snapshot.couponStoreShareBps)
        assertThat(ignoredNewStoreIssuerReference).isNotBlank()
        assertThat(after).isEqualTo(before)
    }

    private fun fixture(): ContractFixture {
        val orderId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val customerId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val storeId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val createdAt = Instant.parse("2026-08-01T15:00:00Z")
        val completedAt = Instant.parse("2026-08-01T15:30:00Z")
        return ContractFixture(
            order = CompletedOrderFact(orderId, customerId, storeId, completedAt, 5),
            payment =
                ApprovedPaymentSettlementFact(
                    orderId = orderId,
                    approvedAmountKrw = 500,
                    currency = "KRW",
                    approvedAt = Instant.parse("2026-08-01T15:10:00Z"),
                    approvalSource = "payment:approved:fixture",
                ),
            snapshot =
                OrderSettlementInputSnapshot(
                    orderId = orderId,
                    storeId = storeId,
                    storeSettlementTermsVersionId = UUID.fromString("44444444-4444-4444-4444-444444444444"),
                    storeSettlementTermsSourceReference = "merchant:terms:fixture-v1",
                    couponReservationId = UUID.fromString("55555555-5555-5555-5555-555555555555"),
                    couponCampaignId = UUID.fromString("66666666-6666-6666-6666-666666666666"),
                    couponCampaignVersion = 7,
                    couponCostBearer = SettlementCouponCostBearer.STORE,
                    couponPlatformShareBps = 0,
                    couponStoreShareBps = 10_000,
                    couponDiscountKrw = 200,
                    platformCouponCostKrw = 0,
                    couponCostKrw = 200,
                    pointReservationId = UUID.fromString("77777777-7777-7777-7777-777777777777"),
                    pointAllocationHash = "a".repeat(64),
                    pointsAppliedKrw = 300,
                    pointCostKrw = 150,
                    grossPaidKrw = 1_000,
                    feeBaseKrw = 500,
                    feeRateBps = 500,
                    feeKrw = 25,
                    benefitCostKrw = 350,
                    netSettlementKrw = 625,
                    currency = "KRW",
                    snapshotSchemaVersion = 1,
                    canonicalSnapshotHash = "b".repeat(64),
                    createdAt = createdAt,
                ),
            envelope =
                OrderCompletedV2EnvelopeInput(
                    eventId = UUID.fromString("88888888-8888-8888-8888-888888888888"),
                    correlationId = "correlation:fixture",
                    causationId = "store-order-command:fixture",
                ),
        )
    }

    private data class ContractFixture(
        val order: CompletedOrderFact,
        val payment: ApprovedPaymentSettlementFact,
        val snapshot: OrderSettlementInputSnapshot,
        val envelope: OrderCompletedV2EnvelopeInput,
    )
}

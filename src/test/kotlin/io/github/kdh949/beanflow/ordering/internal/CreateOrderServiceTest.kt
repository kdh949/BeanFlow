package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.api.OrderQuoteCommand
import io.github.kdh949.beanflow.ordering.api.OrderQuoteUseCase
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("invokes REQUIRES_NEW order idempotency registration")
@SpringBootTest
internal class CreateOrderServiceTest
    @Autowired
    constructor(
        private val createOrderUseCase: CreateOrderUseCase,
        private val orderQuoteUseCase: OrderQuoteUseCase,
        private val jdbcTemplate: JdbcTemplate,
        private val meterRegistry: MeterRegistry,
        private val orderCreationRetention: OrderCreationIdempotencyRetentionService,
    ) {
        @BeforeEach
        fun cleanDatabase() = OrderCreationDatabaseFixture.clean(jdbcTemplate)

        @Test
        fun `order and all required reservations commit together`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)

            val response = createOrderUseCase.create("success-key-0001", quotedCommand(fixture))

            assertThat(response.status).isEqualTo(201)
            assertThat(response.body).contains("\"state\":\"PENDING_PAYMENT\"")
            assertThat(response.body).contains("\"reservationExpiresAt\"")
            assertThat(
                response.body,
            ).containsPattern(
                "\\\"publicReference\\\":\\\"BF-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}\\\"",
            )
            assertThat(response.body).contains("\"pickupNumber\":\"A-1\"")
            assertThat(response.body).contains("\"storeName\":\"BeanFlow Test Store\"")
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isEqualTo(1)
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "fulfillment_pickup_reservation")).isEqualTo(1)
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "inventory_stock_reservation")).isEqualTo(1)
            val optionSnapshot =
                jdbcTemplate.queryForMap(
                    "SELECT option_selection_snapshot_state, normalized_option_ids_json::text " +
                        "FROM ordering_order_line",
                )
            assertThat(optionSnapshot["option_selection_snapshot_state"]).isEqualTo("SNAPSHOTTED")
            assertThat(optionSnapshot["normalized_option_ids_json"]).isEqualTo("[]")
            val displayIdentity =
                jdbcTemplate.queryForMap(
                    """
                    SELECT public_reference, pickup_business_date, pickup_sequence,
                           store_name_snapshot, pickup_window_start_snapshot, pickup_window_end_snapshot
                      FROM ordering_order
                    """.trimIndent(),
                )
            assertThat(displayIdentity["public_reference"].toString()).matches(
                "^BF-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$",
            )
            assertThat(displayIdentity["pickup_business_date"].toString()).isEqualTo("2030-01-01")
            assertThat(displayIdentity["pickup_sequence"]).isEqualTo(1L)
            assertThat(displayIdentity["store_name_snapshot"]).isEqualTo("BeanFlow Test Store")
        }

        @Test
        fun `missing verified store display profile rolls back the entire order transaction`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, includeDisplayProfile = false)

            val response = createOrderUseCase.create("missing-profile-01", fixture.command(expectedQuoteFingerprint = "0".repeat(64)))

            assertThat(response.status).isEqualTo(503)
            assertThat(response.body).contains("\"code\":\"DEPENDENCY_UNAVAILABLE\"")
            assertNoOrderOrReservation()
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_pickup_counter")).isZero()
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_public_reference_registry")).isZero()
        }

        @Test
        fun `terminal order creation idempotency is retained until the exact ninety day boundary`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)

            createOrderUseCase.create("retention-key-01", quotedCommand(fixture))
            val retentionExpiresAt =
                requireNotNull(
                    jdbcTemplate.queryForObject(
                        "SELECT retention_expires_at FROM ordering_idempotency_record",
                        java.time.Instant::class.java,
                    ),
                )

            // PostgreSQL timestamptz stores microseconds, so the representable instant before the boundary is -1µs.
            assertThat(orderCreationRetention.purgeDue(retentionExpiresAt.minusNanos(1_000), 100).deletedCount).isZero()
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_idempotency_record")).isOne()
            assertThat(orderCreationRetention.purgeDue(retentionExpiresAt, 100).deletedCount).isOne()
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_idempotency_record")).isZero()
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isOne()
        }

        @Test
        fun `stock failure rolls back earlier pickup reservation`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, stockAvailable = 0)

            val response = createOrderUseCase.create("stock-fail-0001", fixture.command(expectedQuoteFingerprint = "0".repeat(64)))

            assertThat(response.status).isEqualTo(409)
            assertThat(response.body).contains("\"code\":\"STOCK_NOT_AVAILABLE\"")
            assertNoOrderOrReservation()
        }

        @Test
        fun `confirmed domain failure is stored and replayed exactly`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, stockAvailable = 0)
            val command = fixture.command(expectedQuoteFingerprint = "0".repeat(64))

            val first = createOrderUseCase.create("failure-replay-01", command)
            val replay = createOrderUseCase.create("failure-replay-01", command)

            assertThat(first.status).isEqualTo(409)
            assertThat(replay.status).isEqualTo(first.status)
            assertThat(replay.body).isEqualTo(first.body)
            assertThat(replay.replay).isTrue()
            assertNoOrderOrReservation()
        }

        @Test
        fun `coupon failure rolls back pickup and stock reservations`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)

            val response =
                createOrderUseCase.create(
                    "coupon-fail-001",
                    fixture.command(couponIssuanceId = UUID.randomUUID(), expectedQuoteFingerprint = "0".repeat(64)),
                )

            assertThat(response.status).isEqualTo(409)
            assertThat(response.body).contains("\"code\":\"COUPON_NOT_AVAILABLE\"")
            assertNoOrderOrReservation()
        }

        @Test
        fun `point failure rolls back pickup and stock reservations`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)

            val response =
                createOrderUseCase.create(
                    "points-fail-001",
                    fixture.command(pointsToUseKrw = 1, expectedQuoteFingerprint = "0".repeat(64)),
                )

            assertThat(response.status).isEqualTo(409)
            assertThat(response.body).contains("\"code\":\"POINT_BALANCE_INSUFFICIENT\"")
            assertNoOrderOrReservation()
        }

        @Test
        fun `same key and payload replays the exact first response`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val command = quotedCommand(fixture)

            val first = createOrderUseCase.create("replay-key-0001", command)
            val replay = createOrderUseCase.create("replay-key-0001", command)

            assertThat(replay.status).isEqualTo(201)
            assertThat(replay.body).isEqualTo(first.body)
            assertThat(replay.replay).isTrue()
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isEqualTo(1)
        }

        @Test
        fun `creation and idempotency outcomes are recorded without identifier tags`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val successBefore = counter("beanflow.order.creation.attempts", "outcome", "success")
            val replayBefore = counter("beanflow.order.creation.attempts", "outcome", "replay")
            val idempotencyBefore = counter("beanflow.order.idempotency.events", "outcome", "replay")

            val command = quotedCommand(fixture)
            createOrderUseCase.create("metric-replay-01", command)
            createOrderUseCase.create("metric-replay-01", command)

            assertThat(counter("beanflow.order.creation.attempts", "outcome", "success") - successBefore)
                .isEqualTo(1.0)
            assertThat(counter("beanflow.order.creation.attempts", "outcome", "replay") - replayBefore)
                .isEqualTo(1.0)
            assertThat(counter("beanflow.order.idempotency.events", "outcome", "replay") - idempotencyBefore)
                .isEqualTo(1.0)
            assertThat(
                meterRegistry
                    .find("beanflow.order.creation.attempts")
                    .meters()
                    .flatMap { it.id.tags }
                    .map { it.key }
                    .distinct(),
            ).containsOnly("outcome")
        }

        @Test
        fun `reservation conflict identifies the owner without order identifiers`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, stockAvailable = 0)
            val before = counter("beanflow.order.reservation.conflicts", "resource", "stock")

            createOrderUseCase.create(
                "metric-stock-001",
                fixture.command(expectedQuoteFingerprint = "0".repeat(64)),
            )

            assertThat(counter("beanflow.order.reservation.conflicts", "resource", "stock") - before)
                .isEqualTo(1.0)
        }

        @Test
        fun `same key with different payload is rejected without another owner reservation`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)

            val first = createOrderUseCase.create("reused-key-001", quotedCommand(fixture, quantity = 1))
            val conflict =
                createOrderUseCase.create(
                    "reused-key-001",
                    fixture.command(quantity = 2, expectedQuoteFingerprint = "0".repeat(64)),
                )

            assertThat(first.status).isEqualTo(201)
            assertThat(conflict.status).isEqualTo(409)
            assertThat(conflict.body).contains("\"code\":\"IDEMPOTENCY_KEY_REUSED\"")
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isEqualTo(1)
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "fulfillment_pickup_reservation")).isEqualTo(1)
        }

        @Test
        fun `manual review is distinct from processing for direct order creation`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val command = fixture.command(expectedQuoteFingerprint = "0".repeat(64))
            val key = "direct-manual-review"
            jdbcTemplate.update(
                """
                INSERT INTO ordering_idempotency_record (
                    id, actor_id, operation, idempotency_key, payload_hash, status,
                    intended_order_id, started_at, manual_review_reason,
                    manual_review_started_at, intended_order_exists
                ) VALUES (?, ?, 'CREATE_ORDER', ?, ?, 'MANUAL_REVIEW', ?, ?,
                    'ORDER_NOT_FOUND', ?, false)
                """.trimIndent(),
                UUID.randomUUID(),
                fixture.customerId,
                key,
                CanonicalOrderPayload.hash(command),
                UUID.randomUUID(),
                Timestamp.from(Instant.parse("2026-08-09T00:00:00Z")),
                Timestamp.from(Instant.parse("2026-08-09T00:05:00Z")),
            )

            val response = createOrderUseCase.create(key, command)

            assertThat(response.status).isEqualTo(409)
            assertThat(response.body).contains("\"code\":\"IDEMPOTENCY_MANUAL_REVIEW_REQUIRED\"")
            assertThat(response.retryAfterSeconds).isNull()
            assertNoOrderOrReservation()
        }

        private fun assertNoOrderOrReservation() {
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isZero()
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "fulfillment_pickup_reservation")).isZero()
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "inventory_stock_reservation")).isZero()
        }

        private fun quotedCommand(
            fixture: OrderCreationFixture,
            pointsToUseKrw: Long = 0,
            couponIssuanceId: UUID? = null,
            quantity: Long = 1,
        ): io.github.kdh949.beanflow.ordering.api.CreateOrderCommand {
            val command = fixture.command(pointsToUseKrw, couponIssuanceId, quantity)
            val quote =
                orderQuoteUseCase.quote(
                    OrderQuoteCommand(
                        customerId = command.customerId,
                        storeId = command.storeId,
                        pickupSlotId = command.pickupSlotId,
                        lines = command.lines,
                        couponIssuanceId = command.couponIssuanceId,
                        pointsToUseKrw = command.pointsToUseKrw,
                    ),
                )
            return command.copy(expectedQuoteFingerprint = quote.quoteFingerprint)
        }

        private fun counter(
            name: String,
            tag: String,
            value: String,
        ): Double =
            meterRegistry
                .find(name)
                .tag(tag, value)
                .counter()
                ?.count() ?: 0.0
    }

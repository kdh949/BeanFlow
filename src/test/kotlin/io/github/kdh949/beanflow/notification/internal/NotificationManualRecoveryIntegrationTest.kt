package io.github.kdh949.beanflow.notification.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.OrderReadyV1
import io.github.kdh949.beanflow.operations.api.ManualRecoveryAcceptance
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies real notification retry claims, provider identity, manual recovery HTTP and committed failures")
@SpringBootTest(properties = ["beanflow.notification.initial-delay-ms=3600000"])
internal class NotificationManualRecoveryIntegrationTest(
    @Autowired private val management: NotificationManualRecoveryService,
    @Autowired private val deliveryService: NotificationDeliveryService,
    @Autowired private val repository: NotificationDeliveryJpaRepository,
    @Autowired private val worker: NotificationDeliveryWorker,
    @Autowired private val provider: ScriptedTestNotificationProvider,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val mvc: MockMvc,
    @Autowired private val mapper: ObjectMapper,
) {
    @BeforeEach fun clean() {
        jdbc.execute(
            "TRUNCATE notification_delivery, notification_inbox_item, operations_reprocessing_case, " +
                "operations_operator_permission_grant, operations_audit_record CASCADE",
        )
        provider.reset()
    }

    @Test fun `HTTP accepts one retry and the original delivery key reaches real worker success`() {
        val c = command()
        val before = repository.findById(c.deliveryId).orElseThrow()
        val request =
            RetryNotificationDeliveryRequest(
                c.expectedVersion,
                c.expectedCaseVersion,
                c.reason,
            )
        val response =
            mvc
                .perform(
                    post("${path(c)}/retries")
                        .with(jwt(c.actorId))
                        .header(
                            "Idempotency-Key",
                            c.key,
                        ).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)),
                ).andExpect(status().isAccepted)
                .andExpect(jsonPath("$.recoveryCase.status").value("RUNNING"))
                .andReturn()
                .response.contentAsString
        val accepted =
            mapper.readValue(
                response,
                ManualRecoveryAcceptance::class.java,
            )
        assertThat(provider.calls.get()).isEqualTo(4)
        provider.enqueue(NotificationProviderResult.Acknowledged("manual-provider-success"))
        assertThat(worker.runOnce()).isOne()
        val after = repository.findById(c.deliveryId).orElseThrow()
        assertThat(after.providerIdempotencyKey).isEqualTo(before.providerIdempotencyKey)
        assertThat(after.payloadJson).isEqualTo(before.payloadJson)
        assertThat(after.attemptCount).isEqualTo(5)
        assertThat(
            provider.requests
                .map {
                    it.providerIdempotencyKey
                }.toSet(),
        ).hasSize(1)
        mvc
            .perform(get(path(c)).with(jwt(c.actorId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state").value("SUCCEEDED"))
            .andExpect(jsonPath("$.recoveryCase.status").value("RESOLVED"))
        assertThat(management.retry(c)).isEqualTo(accepted)
        assertThat(worker.runOnce()).isZero()
        assertThat(repository.count()).isOne()
    }

    @Test fun `failed manual attempt returns to review without resetting history or automatically retrying again`() {
        val c = command()
        management.retry(c)
        provider.enqueue(NotificationProviderResult.Unknown("ACK_LOST"))
        worker.runOnce()
        val failed =
            management.get(
                c.actorId,
                c.deliveryId,
            )
        assertThat(failed.state).isEqualTo("MANUAL_REVIEW")
        assertThat(failed.attemptCount).isEqualTo(5)
        assertThat(failed.recoveryCase!!.status).isEqualTo("MANUAL_REVIEW")
        assertThat(worker.runOnce()).isZero()
        failure(FailureCode.RESOURCE_STATE_CONFLICT) {
            management.retry(c.copy(key = "notification-stale-case"))
        }
        val retry =
            c.copy(
                key = "notification-second-retry",
                expectedVersion = failed.version,
                expectedCaseVersion = failed.recoveryCase.version,
                now = Instant.now(),
            )
        management.retry(retry)
        provider.enqueue(NotificationProviderResult.Acknowledged("manual-next-success"))
        worker.runOnce()
        assertThat(
            management
                .get(
                    c.actorId,
                    c.deliveryId,
                ).attemptCount,
        ).isEqualTo(6)
        assertThat(
            management
                .get(
                    c.actorId,
                    c.deliveryId,
                ).recoveryCase!!
                .status,
        ).isEqualTo("RESOLVED")
    }

    @Test fun `audit failure rolls back budget case and command and current grants protect replay`() {
        val c = command()
        val before =
            management.get(
                c.actorId,
                c.deliveryId,
            )
        jdbc.execute(
            "ALTER TABLE operations_audit_record ADD CONSTRAINT test_notification_recovery_audit " +
                "CHECK (action <> 'NOTIFICATION_RETRY_REQUESTED')",
        )
        try {
            assertThatThrownBy {
                management.retry(c)
            }.isInstanceOf(RuntimeException::class.java)
        } finally {
            jdbc.execute("ALTER TABLE operations_audit_record DROP CONSTRAINT test_notification_recovery_audit")
        }
        assertThat(
            management.get(
                c.actorId,
                c.deliveryId,
            ),
        ).isEqualTo(before)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification_manual_recovery_command",
                Long::class.java,
            ),
        ).isZero()
        management.retry(c)
        failure(FailureCode.IDEMPOTENCY_KEY_REUSED) {
            management.retry(c.copy(reason = "다른 사유"))
        }
        jdbc.update(
            "UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now() WHERE actor_id = ?",
            c.actorId,
        )
        failure(FailureCode.ACCESS_DENIED) {
            management.retry(c)
        }
    }

    @Test fun `expired manual claim returns to review and concurrent requests reserve only one attempt`() {
        val c = command()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val barrier = CyclicBarrier(2)
            val futures =
                (0..1).map { index ->
                    executor.submit<Boolean> {
                        barrier.await(
                            5,
                            TimeUnit.SECONDS,
                        )
                        try {
                            management.retry(c.copy(key = "notification-race-$index"))
                            true
                        } catch (failure: DomainFailure) {
                            assertThat(failure.code).isEqualTo(FailureCode.RESOURCE_STATE_CONFLICT)
                            false
                        }
                    }
                }
            assertThat(
                futures.map {
                    it.get(
                        20,
                        TimeUnit.SECONDS,
                    )
                },
            ).containsExactlyInAnyOrder(
                true,
                false,
            )
        } finally {
            executor.shutdownNow()
            assertThat(
                executor.awaitTermination(
                    30,
                    TimeUnit.SECONDS,
                ),
            ).isTrue()
        }
        val now = Instant.now()
        assertThat(
            deliveryService.claimDue(
                now,
                10,
            ),
        ).hasSize(1)
        assertThat(
            deliveryService.claimDue(
                now.plusSeconds(120),
                10,
            ),
        ).isEmpty()
        val view =
            management.get(
                c.actorId,
                c.deliveryId,
            )
        assertThat(view.lastFailureCode).isEqualTo("CLAIM_LEASE_EXPIRED")
        assertThat(view.recoveryCase!!.status).isEqualTo("MANUAL_REVIEW")
    }

    @Test fun `HTTP permissions strict input and actor scoped case cursor are enforced`() {
        val c = command()
        val other = command()
        val page =
            management.list(
                c.actorId,
                null,
                1,
            )
        assertThat(page.nextCursor).isNotNull()
        assertThat(
            management
                .list(
                    c.actorId,
                    page.nextCursor,
                    1,
                ).items,
        ).hasSize(1)
        assertThatThrownBy {
            management.list(
                other.actorId,
                page.nextCursor,
                1,
            )
        }.isInstanceOf(DomainFailure::class.java)
        mvc.perform(get(path(c))).andExpect(status().isUnauthorized)
        mvc.perform(get(path(c)).with(jwt(UUID.randomUUID()))).andExpect(status().isForbidden)
        mvc
            .perform(
                post("${path(c)}/retries")
                    .with(jwt(c.actorId))
                    .header(
                        "Idempotency-Key",
                        c.key,
                    ).contentType(
                        MediaType.APPLICATION_JSON,
                    ).content("""{"expectedVersion":${c.expectedVersion},"expectedCaseVersion":0,"reason":"확인","payload":"변경"}"""),
            ).andExpect(status().isBadRequest)
    }

    private fun command(): RetryNotificationCommand {
        val actor = UUID.randomUUID()
        listOf(
            "NOTIFICATION_RECOVERY_READ",
            "NOTIFICATION_RECOVERY_RETRY",
        ).forEach { grant ->
            jdbc.update(
                "INSERT INTO operations_operator_permission_grant(actor_id, permission, state, granted_at, " +
                    "version, audit_source_reference) VALUES (?, ?, 'ACTIVE', now(), 1, ?)",
                actor,
                grant,
                "notification:$actor:$grant",
            )
        }
        val start = Instant.now().minusSeconds(3600)
        val order = UUID.randomUUID()
        val event =
            OrderReadyV1(
                EventEnvelope(
                    UUID.randomUUID(),
                    "OrderReadyV1",
                    order,
                    1,
                    start,
                    1,
                    "manual-notification-test",
                    "manual-notification-test",
                ),
                order,
                UUID.randomUUID(),
                UUID.randomUUID(),
                start,
            )
        deliveryService.requestReady(event)
        val id =
            repository
                .findByEventIdAndRecipientIdAndLogicalChannel(
                    event.envelope.eventId,
                    event.customerId,
                    io.github.kdh949.beanflow.notification.internal.domain.NotificationLogicalChannel.CUSTOMER_APP,
                )!!
                .id
        repeat(4) {
            val due = repository.findById(id).orElseThrow().nextAttemptAt!!
            val claim =
                deliveryService
                    .claimDue(
                        due,
                        10,
                    ).single()
            provider.enqueue(NotificationProviderResult.Unknown("PROVIDER_UNAVAILABLE"))
            deliveryService.recordResult(
                claim,
                deliveryService.callProvider(claim),
                due,
            )
        }
        val view =
            management.get(
                actor,
                id,
            )
        return RetryNotificationCommand(
            actor,
            id,
            "notification-manual-key",
            view.version,
            view.recoveryCase!!.version,
            "전송 장애 조치 확인",
            Instant.now(),
        )
    }

    private fun path(c: RetryNotificationCommand) = "/api/v1/operations/notification-delivery-recoveries/${c.deliveryId}"

    private fun jwt(actor: UUID) =
        jwt()
            .jwt {
                it.subject(actor.toString())
            }.authorities(SimpleGrantedAuthority("ROLE_PLATFORM_OPERATOR"))

    private fun failure(
        code: FailureCode,
        action: () -> Unit,
    ) {
        assertThatThrownBy(action).isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(code)
        }
    }
}

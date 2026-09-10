package io.github.kdh949.beanflow.ordering.internal

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
import org.springframework.context.ApplicationEventPublisher
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(
    TestcontainersConfiguration::class,
    PublicationFailureTestConfiguration::class,
)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies manual HTTP acceptance and actual Modulith resubmission across transaction and thread boundaries")
@SpringBootTest(
    properties = [
        "beanflow.event-publication.initial-delay-ms=3600000",

        "beanflow.notification.initial-delay-ms=3600000",

        "beanflow.publication-manual-recovery.initial-delay-ms=3600000",

    ],
)
internal class PublicationManualRecoveryIntegrationTest(
    @Autowired private val management: PublicationManualRecoveryService,
    @Autowired private val handoff: EventPublicationManualReviewService,
    @Autowired private val worker: EventPublicationRecoveryWorker,
    @Autowired private val results: PublicationManualResultWorker,
    @Autowired private val listener: FailingReadyPublicationListener,
    @Autowired private val publisher: ApplicationEventPublisher,
    @Autowired private val clock: PublicationRecoveryTestClock,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val mvc: MockMvc,
    @Autowired private val mapper: ObjectMapper,
    @Autowired transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)

    @BeforeEach fun clean() {
        jdbc.execute(
            "TRUNCATE event_publication, notification_delivery, notification_inbox_item, operations_reprocessing_case, " +
                "operations_operator_permission_grant, operations_audit_record CASCADE",
        )
        listener.reset()
        clock.reset()
    }

    @Test fun `HTTP acceptance preserves the source and registry completes only the selected publication`() {
        val c = command()
        val before = source(c.publicationId)
        val body =
            mvc
                .perform(
                    post("${path(c)}/retries")
                        .with(jwt(c.actorId))
                        .header(
                            "Idempotency-Key",
                            c.key,
                        ).contentType(MediaType.APPLICATION_JSON)
                        .content(
                            mapper.writeValueAsString(
                                RetryPublicationRequest(
                                    c.expectedCaseVersion,
                                    c.reason,
                                ),
                            ),
                        ),
                ).andExpect(status().isAccepted)
                .andExpect(jsonPath("$.recoveryCase.status").value("RUNNING"))
                .andReturn()
                .response.contentAsString
        val accepted =
            mapper.readValue(
                body,
                ManualRecoveryAcceptance::class.java,
            )
        assertThat(listener.callCount()).isOne()
        assertThat(source(c.publicationId)).isEqualTo(before)
        listener.allowSuccess()
        worker.runOnce()
        await {
            management
                .get(
                    c.actorId,
                    c.publicationId,
                ).completedAt != null
        }
        results.runOnce()
        val after =
            management.get(
                c.actorId,
                c.publicationId,
            )
        assertThat(after.recoveryCase!!.status).isEqualTo("RESOLVED")
        assertThat(after.attemptCount).isEqualTo(7)
        assertThat(source(c.publicationId)).isEqualTo(before)
        assertThat(management.retry(c)).isEqualTo(accepted)
        assertThat(listener.callCount()).isEqualTo(2)
        mvc
            .perform(get(path(c)).with(jwt(c.actorId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.recoverable").value(false))
    }

    @Test fun `one failed manual resubmission returns to review and needs a new explicit request`() {
        val c = command()
        management.retry(c)
        worker.runOnce()
        await {
            val view =
                management.get(
                    c.actorId,
                    c.publicationId,
                )
            view.status == "FAILED" && view.attemptCount == 7
        }
        results.runOnce()
        val failed =
            management.get(
                c.actorId,
                c.publicationId,
            )
        assertThat(failed.recoveryCase!!.status).isEqualTo("MANUAL_REVIEW")
        worker.runOnce()
        assertThat(listener.callCount()).isEqualTo(2)
        failure(FailureCode.RESOURCE_STATE_CONFLICT) {
            management.retry(c.copy(key = "publication-stale-key"))
        }
        management.retry(
            c.copy(
                key = "publication-second-key",
                expectedCaseVersion = failed.recoveryCase.version,
            ),
        )
        listener.allowSuccess()
        worker.runOnce()
        await {
            management
                .get(
                    c.actorId,
                    c.publicationId,
                ).completedAt != null
        }
        results.runOnce()
        assertThat(
            management
                .get(
                    c.actorId,
                    c.publicationId,
                ).attemptCount,
        ).isEqualTo(8)
        assertThat(
            management
                .get(
                    c.actorId,
                    c.publicationId,
                ).recoveryCase!!
                .status,
        ).isEqualTo("RESOLVED")
    }

    @Test fun `audit failure rolls back request and case and revoked grants deny stored response replay`() {
        val c = command()
        val before =
            management.get(
                c.actorId,
                c.publicationId,
            )
        jdbc.execute(
            "ALTER TABLE operations_audit_record ADD CONSTRAINT test_publication_recovery_audit " +
                "CHECK (action <> 'PUBLICATION_RETRY_REQUESTED')",
        )
        try {
            assertThatThrownBy {
                management.retry(c)
            }.isInstanceOf(RuntimeException::class.java)
        } finally {
            jdbc.execute("ALTER TABLE operations_audit_record DROP CONSTRAINT test_publication_recovery_audit")
        }
        assertThat(
            management.get(
                c.actorId,
                c.publicationId,
            ),
        ).isEqualTo(before)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ordering_manual_publication_recovery",
                Long::class.java,
            ),
        ).isZero()
        management.retry(c)
        failure(FailureCode.IDEMPOTENCY_KEY_REUSED) {
            management.retry(c.copy(reason = "다른 사유"))
        }
        jdbc.update(
            "UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now() " +
                "WHERE actor_id = ? AND permission = 'EVENT_PUBLICATION_RECOVERY_RETRY'",
            c.actorId,
        )
        failure(FailureCode.ACCESS_DENIED) {
            management.retry(c)
        }
        assertThat(listener.callCount()).isOne()
    }

    @Test fun `unsupported listener strict HTTP input and actor scoped cursors fail closed`() {
        val c = command()
        val other = command()
        val page =
            management.list(
                c.actorId,
                null,
                1,
            )
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
        jdbc.update(
            "UPDATE event_publication SET listener_id = 'beanflow.analytics.reserved' WHERE id = ?",
            c.publicationId,
        )
        assertThat(
            management
                .get(
                    c.actorId,
                    c.publicationId,
                ).recoverable,
        ).isFalse()
        failure(FailureCode.RESOURCE_STATE_CONFLICT) {
            management.retry(c)
        }
        jdbc.update(
            "UPDATE event_publication SET listener_id = 'missing-listener' WHERE id = ?",
            c.publicationId,
        )
        failure(FailureCode.RESOURCE_STATE_CONFLICT) {
            management.retry(c)
        }
        mvc.perform(get(path(c))).andExpect(status().isUnauthorized)
        mvc.perform(get(path(c)).with(jwt(UUID.randomUUID()))).andExpect(status().isForbidden)
        mvc
            .perform(
                post("${path(other)}/retries")
                    .with(jwt(other.actorId))
                    .header(
                        "Idempotency-Key",
                        other.key,
                    ).contentType(MediaType.APPLICATION_JSON)
                    .content("""{"expectedCaseVersion":0,"reason":"확인","listenerId":"변경"}"""),
            ).andExpect(status().isBadRequest)
    }

    @Test fun `concurrent acceptance is unique and uncertain execution does not block known result reconciliation`() {
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
                            management.retry(c.copy(key = "publication-race-$index"))
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
        jdbc.update(
            "UPDATE event_publication SET status = 'PROCESSING', completion_attempts = 7 WHERE id = ?",
            c.publicationId,
        )
        results.runOnce()
        assertThat(
            management
                .get(
                    c.actorId,
                    c.publicationId,
                ).recoveryCase!!
                .status,
        ).isEqualTo("RUNNING")
        val other = command()
        val accepted = management.retry(other)
        jdbc.update(
            "UPDATE event_publication SET status = 'FAILED', completion_attempts = 7 WHERE id = ?",
            other.publicationId,
        )
        assertThat(management.pending()).containsExactly(accepted.commandId)
        results.runOnce()
        assertThat(
            management
                .get(
                    other.actorId,
                    other.publicationId,
                ).recoveryCase!!
                .status,
        ).isEqualTo("MANUAL_REVIEW")
        assertThat(
            management
                .get(
                    c.actorId,
                    c.publicationId,
                ).recoveryCase!!
                .status,
        ).isEqualTo("RUNNING")
    }

    @Test fun `corrupt persisted payload is unavailable and leaves case and request unchanged`() {
        val c = command()
        jdbc.update(
            "UPDATE event_publication SET serialized_event = '{}' WHERE id = ?",
            c.publicationId,
        )
        failure(FailureCode.DEPENDENCY_UNAVAILABLE) {
            management.retry(c)
        }
        assertThat(
            management
                .get(
                    c.actorId,
                    c.publicationId,
                ).recoveryCase!!
                .status,
        ).isEqualTo("MANUAL_REVIEW")
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ordering_manual_publication_recovery",
                Long::class.java,
            ),
        ).isZero()
    }

    @Test fun `one result persistence failure is retried without blocking another known result`() {
        val first = command()
        val second = command()
        val firstAccepted = management.retry(first)
        management.retry(second)
        jdbc.update(
            "UPDATE event_publication SET completion_attempts = 7 WHERE id IN (?, ?)",
            first.publicationId,
            second.publicationId,
        )
        val protectedCase = firstAccepted.recoveryCase.caseId
        jdbc.execute(
            "ALTER TABLE operations_reprocessing_case ADD CONSTRAINT test_recovery_result " +
                "CHECK (id <> '$protectedCase'::uuid OR status <> 'MANUAL_REVIEW')",
        )
        try {
            assertThatThrownBy {
                results.runOnce()
            }.isInstanceOf(IllegalStateException::class.java)
            assertThat(
                management
                    .get(
                        first.actorId,
                        first.publicationId,
                    ).recoveryCase!!
                    .status,
            ).isEqualTo("RUNNING")
            assertThat(
                management
                    .get(
                        second.actorId,
                        second.publicationId,
                    ).recoveryCase!!
                    .status,
            ).isEqualTo("MANUAL_REVIEW")
        } finally {
            jdbc.execute("ALTER TABLE operations_reprocessing_case DROP CONSTRAINT test_recovery_result")
        }
        results.runOnce()
        assertThat(
            management
                .get(
                    first.actorId,
                    first.publicationId,
                ).recoveryCase!!
                .status,
        ).isEqualTo("MANUAL_REVIEW")
    }

    private fun command(): RetryPublicationCommand {
        val actor = UUID.randomUUID()
        listOf(
            "EVENT_PUBLICATION_RECOVERY_READ",
            "EVENT_PUBLICATION_RECOVERY_RETRY",
        ).forEach { grant ->
            jdbc.update(
                "INSERT INTO operations_operator_permission_grant(actor_id, permission, state, granted_at, " +
                    "version, audit_source_reference) VALUES (?, ?, 'ACTIVE', ?, 1, ?)",
                actor,
                grant,
                Timestamp.from(clock.instant().minusSeconds(1)),
                "publication:$actor:$grant",
            )
        }
        val order = UUID.randomUUID()
        val event =
            OrderReadyV1(
                EventEnvelope(
                    UUID.randomUUID(),
                    "OrderReadyV1",
                    order,
                    1,
                    clock.instant(),
                    1,
                    "manual-publication-test",
                    "manual-publication-test",
                ),
                order,
                UUID.randomUUID(),
                UUID.randomUUID(),
                clock.instant(),
            )
        tx.executeWithoutResult {
            publisher.publishEvent(event)
        }
        val sql =
            "SELECT id FROM event_publication WHERE serialized_event::jsonb ->> 'orderId' = ? " +
                "AND listener_id LIKE '%FailingReadyPublicationListener%' AND status = 'FAILED'"
        await {
            jdbc
                .query(
                    sql,
                    {
                        rs,
                        _,
                        ->
                        rs.getObject(
                            "id",
                            UUID::class.java,
                        )
                    },
                    order.toString(),
                ).size == 1
        }
        await {
            jdbc.queryForObject(
                "SELECT count(*) FROM event_publication WHERE serialized_event::jsonb ->> 'orderId' = ? " +
                    "AND completion_date IS NULL",
                Long::class.java,
                order.toString(),
            ) == 1L
        }
        val id =
            jdbc
                .query(
                    sql,
                    {
                        rs,
                        _,
                        ->
                        rs.getObject(
                            "id",
                            UUID::class.java,
                        )
                    },
                    order.toString(),
                ).single()
        jdbc.update(
            "UPDATE event_publication SET completion_attempts = 6 WHERE id = ?",
            id,
        )
        handoff.transition(
            id,
            clock.instant(),
        )
        val view =
            management.get(
                actor,
                id,
            )
        return RetryPublicationCommand(
            actor,
            id,
            "publication-manual-key",
            view.recoveryCase!!.version,
            "소비자 장애 조치 확인",
            clock.instant(),
        )
    }

    private fun source(id: UUID) =
        jdbc.queryForMap(
            "SELECT id, listener_id, serialized_event, event_type FROM event_publication WHERE id = ?",
            id,
        )

    private fun path(c: RetryPublicationCommand) = "/api/v1/operations/event-publication-recoveries/${c.publicationId}"

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

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertThat(condition()).isTrue()
    }
}

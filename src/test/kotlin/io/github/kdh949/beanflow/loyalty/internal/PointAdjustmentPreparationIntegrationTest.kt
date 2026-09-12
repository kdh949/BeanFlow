package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.loyalty.api.ApplyPointAdjustmentCommand
import io.github.kdh949.beanflow.loyalty.api.PointAdjustmentIssuer
import io.github.kdh949.beanflow.loyalty.api.PointAdjustmentOperations
import io.github.kdh949.beanflow.loyalty.api.PointIssuerType
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies durable preparation and concurrent financial commits")
@SpringBootTest(properties = ["beanflow.loyalty-point-adjustment-retention.initial-delay-ms=3600000"])
internal class PointAdjustmentPreparationIntegrationTest
    @Autowired
    constructor(
        private val preparations: PointAdjustmentPreparationService,
        private val adjustments: PointAdjustmentOperations,
        private val records: PointAdjustmentPreparationRepository,
        private val retention: PointAdjustmentIdempotencyRetentionService,
        private val jdbc: JdbcTemplate,
        private val mvc: MockMvc,
        private val clock: Clock,
    ) {
        private val actor = UUID.fromString("20000000-0000-0000-0000-000000000170")
        private lateinit var account: UUID
        private lateinit var customer: UUID
        private lateinit var request: PointAdjustmentRequest

        @BeforeEach fun setup() {
            jdbc.execute(
                """
                TRUNCATE loyalty_point_account, identity_customer_account,
                operations_operator_permission_grant, operations_audit_record, event_publication CASCADE
                """.trimIndent(),
            )
            customer = UUID.randomUUID()
            account = UUID.randomUUID()
            jdbc.update(
                """
                INSERT INTO identity_customer_account(id, login_id, password_hash, credential_version,
                display_name, state, created_at, updated_at, version) VALUES (?, 'prepared_customer',
                'test-hash', 0, '김민수', 'ACTIVE', now(), now(), 0)
                """.trimIndent(),
                customer,
            )
            jdbc.update(
                """
                INSERT INTO loyalty_point_account(id, customer_id, available_points_krw,
                reserved_points_krw, recovery_pending_krw, version) VALUES (?, ?, 0, 0, 0, 0)
                """.trimIndent(),
                account,
                customer,
            )
            listOf("POINT_ADJUSTMENT", "POINT_ACCOUNT_READ", "CUSTOMER_ACCOUNT_SEARCH").forEach { grant(actor, it) }
            request =
                PointAdjustmentRequest(
                    125,
                    PointAdjustmentIssuerRequest(PointIssuerType.STORE, "store:audited-42"),
                    clock.instant().plusSeconds(3600),
                    "Verified correction",
                    listOf("evidence:ticket-42"),
                )
        }

        @Test fun `reentry and retention preserve the first result`() {
            val first = preparations.prepare(actor, account, request)
            assertThat(preparations.prepare(actor, account, request).preparationId).isEqualTo(first.preparationId)
            assertThat(preparations.current(actor).preparation).isEqualTo(first)
            assertThat(first.customer.maskedDisplayName).isEqualTo("김*수")
            assertThat(balance()).isZero()
            val result = adjustments.adjust(command(first))
            val restored = preparations.current(actor).preparation!!
            assertThat(restored.state).isEqualTo(PointAdjustmentPreparationState.APPLIED)
            assertThat(restored.result).isEqualTo(result)
            assertThat(restored.canExecute).isFalse()
            retention.purgeDue(clock.instant().plus(Duration.ofDays(91)), 100)
            assertThat(records.findOpen(actor)).isNotNull()
            assertThat(adjustments.adjust(command(first).copy(now = clock.instant().plus(Duration.ofDays(91))))).isEqualTo(result)
            assertThat(balance()).isEqualTo(125)
            assertThat(count("loyalty_point_transaction")).isOne()
            preparations.dismiss(actor, first.preparationId, PointAdjustmentPreparationState.APPLIED)
            preparations.dismiss(actor, first.preparationId, PointAdjustmentPreparationState.APPLIED)
            assertThat(preparations.current(actor).preparation).isNull()
            retention.purgeDue(clock.instant().plus(Duration.ofDays(91)), 100)
            assertThat(records.find(actor, first.preparationId)).isNull()
        }

        @Test fun `other key and changed payload cannot bypass an unacknowledged preparation`() {
            val prepared = preparations.prepare(actor, account, request)
            failure(
                FailureCode.RESOURCE_STATE_CONFLICT,
            ) { adjustments.adjust(command(prepared).copy(idempotencyKey = "different-command-key")) }
            failure(FailureCode.IDEMPOTENCY_KEY_REUSED) { adjustments.adjust(command(prepared).copy(amountKrw = 126)) }
            failure(FailureCode.RESOURCE_STATE_CONFLICT) { preparations.prepare(actor, account, request.copy(amountKrw = 126)) }
            assertThat(balance()).isZero()
            assertThat(count("loyalty_point_adjustment_preparation")).isOne()
        }

        @Test fun `cancel prevents late original execution and records actual cancelled state`() {
            val prepared = preparations.prepare(actor, account, request)
            preparations.dismiss(actor, prepared.preparationId, PointAdjustmentPreparationState.PREPARED)
            preparations.dismiss(actor, prepared.preparationId, PointAdjustmentPreparationState.PREPARED)
            failure(FailureCode.RESOURCE_STATE_CONFLICT) { adjustments.adjust(command(prepared)) }
            assertThat(balance()).isZero()
            assertThat(
                jdbc.queryForObject(
                    """
                    SELECT after_summary::text FROM operations_audit_record WHERE action =
                    'POINT_ADJUSTMENT_PREPARATION_DISMISSED'
                    """.trimIndent(),
                    String::class.java,
                ),
            ).contains("CANCELLED")
            assertThat(preparations.prepare(actor, account, request).preparationId).isNotEqualTo(prepared.preparationId)
        }

        @Test fun `cancel and execution serialize without silent acknowledgement`() {
            val prepared = preparations.prepare(actor, account, request)
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val applying =
                    executor.submit<Boolean> {
                        barrier.await(10, TimeUnit.SECONDS)
                        try {
                            adjustments.adjust(command(prepared))
                            true
                        } catch (failure: DomainFailure) {
                            assertThat(failure.code).isEqualTo(FailureCode.RESOURCE_STATE_CONFLICT)
                            false
                        }
                    }
                val cancelling =
                    executor.submit<Boolean> {
                        barrier.await(10, TimeUnit.SECONDS)
                        try {
                            preparations.dismiss(actor, prepared.preparationId, PointAdjustmentPreparationState.PREPARED)
                            true
                        } catch (failure: DomainFailure) {
                            assertThat(failure.code).isEqualTo(FailureCode.RESOURCE_STATE_CONFLICT)
                            false
                        }
                    }
                assertThat(
                    listOf(applying.get(15, TimeUnit.SECONDS), cancelling.get(15, TimeUnit.SECONDS)),
                ).containsExactlyInAnyOrder(true, false)
                val record = records.find(actor, prepared.preparationId)!!
                if (record.state == PointAdjustmentPreparationState.APPLIED) {
                    assertThat(record.dismissedAt).isNull()
                    assertThat(balance()).isEqualTo(125)
                } else {
                    assertThat(record.state).isEqualTo(PointAdjustmentPreparationState.CANCELLED)
                    assertThat(balance()).isZero()
                }
            } finally {
                executor.shutdownNow()
            }
        }

        @Test fun `two tabs preparing different accounts serialize at actor grant`() {
            val other = UUID.randomUUID()
            jdbc.update(
                """
                INSERT INTO loyalty_point_account(id, customer_id, available_points_krw,
                reserved_points_krw, recovery_pending_krw, version) VALUES (?, ?, 0, 0, 0, 0)
                """.trimIndent(),
                other,
                UUID.randomUUID(),
            )
            // Both accounts have a valid owner projection; the second actor-owned preparation still conflicts.
            jdbc.update(
                """
                INSERT INTO identity_customer_account(id, login_id, password_hash, credential_version,
                display_name, state, created_at, updated_at, version) SELECT customer_id,
                'second_customer', 'test-hash', 0, '이수진', 'ACTIVE', now(), now(), 0 FROM
                loyalty_point_account WHERE id = ?
                """.trimIndent(),
                other,
            )
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val futures =
                    listOf(account, other).map { id ->
                        executor.submit<Boolean> {
                            barrier.await(10, TimeUnit.SECONDS)
                            try {
                                preparations.prepare(actor, id, request)
                                true
                            } catch (failure: DomainFailure) {
                                assertThat(failure.code).isEqualTo(FailureCode.RESOURCE_STATE_CONFLICT)
                                false
                            }
                        }
                    }
                assertThat(futures.map { it.get(15, TimeUnit.SECONDS) }).containsExactlyInAnyOrder(true, false)
                assertThat(count("loyalty_point_adjustment_preparation")).isOne()
            } finally {
                executor.shutdownNow()
            }
        }

        @Test fun `recovery is actor scoped and rechecks execution permission`() {
            val prepared = preparations.prepare(actor, account, request)
            val other = UUID.randomUUID()
            grant(other, "POINT_ACCOUNT_READ")
            grant(other, "POINT_ADJUSTMENT")
            failure(FailureCode.ACCESS_DENIED) { adjustments.adjust(command(prepared).copy(actorId = other)) }
            assertThat(preparations.current(other).preparation).isNull()
            failure(
                FailureCode.RESOURCE_NOT_FOUND,
            ) { preparations.dismiss(other, prepared.preparationId, PointAdjustmentPreparationState.PREPARED) }
            jdbc.update(
                """
                UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now(),
                version = version + 1 WHERE actor_id = ? AND permission = 'POINT_ADJUSTMENT'
                """.trimIndent(),
                actor,
            )
            assertThat(preparations.current(actor).preparation!!.canExecute).isFalse()
            failure(FailureCode.ACCESS_DENIED) { adjustments.adjust(command(prepared)) }
            preparations.dismiss(actor, prepared.preparationId, PointAdjustmentPreparationState.PREPARED)
            assertThat(balance()).isZero()
        }

        @Test fun `preparation persistence failure rolls back financial effects`() {
            val prepared = preparations.prepare(actor, account, request)
            jdbc.execute(
                """
                CREATE FUNCTION test_reject_preparation_apply() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected failure'; END; $$
                """.trimIndent(),
            )
            jdbc.execute(
                """
                CREATE TRIGGER test_preparation_apply_failure BEFORE UPDATE ON
                loyalty_point_adjustment_preparation FOR EACH ROW EXECUTE FUNCTION
                test_reject_preparation_apply()
                """.trimIndent(),
            )
            try {
                failure(FailureCode.DEPENDENCY_UNAVAILABLE) { adjustments.adjust(command(prepared)) }
            } finally {
                jdbc.execute("DROP TRIGGER test_preparation_apply_failure ON loyalty_point_adjustment_preparation")
                jdbc.execute("DROP FUNCTION test_reject_preparation_apply()")
            }
            assertThat(records.findOpen(actor)!!.state).isEqualTo(PointAdjustmentPreparationState.PREPARED)
            assertThat(balance()).isZero()
            assertThat(count("loyalty_point_transaction")).isZero()
            assertThat(count("loyalty_point_adjustment_command_idempotency")).isZero()
            assertThat(
                jdbc.queryForObject(
                    """
                    SELECT count(*) FROM operations_audit_record WHERE action =
                    'POINT_ADJUSTMENT_APPLIED'
                    """.trimIndent(),
                    Long::class.java,
                ),
            ).isZero()
            assertThat(count("event_publication")).isZero()
        }

        @Test fun `HTTP recovery masks customer uses no store and enforces search purpose grant`() {
            val prepared = preparations.prepare(actor, account, request)
            mvc
                .perform(get("/api/v1/operations/point-adjustment-preparations/current").with(operator()))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.preparation.preparationId").value(prepared.preparationId.toString()))
                .andExpect(jsonPath("$.preparation.customer.maskedLoginId").value("p***"))
            jdbc.update(
                """
                UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now(),
                version = version + 1 WHERE actor_id = ? AND permission = 'CUSTOMER_ACCOUNT_SEARCH'
                """.trimIndent(),
                actor,
            )
            mvc.perform(get("/api/v1/operations/point-adjustment-preparations/current").with(operator())).andExpect(status().isForbidden)
            mvc
                .perform(
                    post(
                        "/api/v1/operations/point-adjustment-preparations",
                    ).with(operator()).contentType(MediaType.APPLICATION_JSON).content(
                        """
                        {"accountId":"$account",
                          "request":{"amountKrw":-1,"reason":"verified","evidenceReferences":["evidence:1"]}}
                        """.trimIndent(),
                    ),
                ).andExpect(status().isForbidden)
        }

        private fun command(view: PointAdjustmentPreparationView) =
            ApplyPointAdjustmentCommand(
                actor,
                view.accountId,
                view.preparationId.toString(),
                view.request.amountKrw,
                view.request.issuer
                    ?.let {
                        PointAdjustmentIssuer(it.issuerType, it.issuerReference)
                    },
                view.request.expiresAt,
                view.request.reason,
                view.request.evidenceReferences,
                "point-preparation-test",
                clock.instant(),
            )

        private fun grant(
            id: UUID,
            permission: String,
        ) {
            jdbc.update(
                """
                INSERT INTO operations_operator_permission_grant(actor_id, permission, state,
                granted_at, version, audit_source_reference) VALUES (?, ?, 'ACTIVE', now(), 1, ?)
                """.trimIndent(),
                id,
                permission,
                "test:preparation:$id:$permission",
            )
        }

        private fun operator() =
            jwt()
                .jwt {
                    it.subject(actor.toString()).claim("roles", listOf("PLATFORM_OPERATOR"))
                }.authorities(SimpleGrantedAuthority("ROLE_PLATFORM_OPERATOR"))

        private fun balance() =
            jdbc.queryForObject("SELECT available_points_krw FROM loyalty_point_account WHERE id = ?", Long::class.java, account)!!

        private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!

        private fun failure(
            code: FailureCode,
            block: () -> Unit,
        ) {
            assertThatThrownBy(block).isInstanceOfSatisfying(DomainFailure::class.java) { assertThat(it.code).isEqualTo(code) }
        }
    }

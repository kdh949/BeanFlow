package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.payment.api.PaymentMethodProviderNotificationResult
import io.github.kdh949.beanflow.payment.api.VerifiedPaymentMethodProviderNotification
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

@SpringBootTest(
    properties = [
        "beanflow.payment-method.maintenance.initial-delay-ms=3600000",
        "beanflow.payment-method.retention.initial-delay-ms=3600000",
    ],
)
@Import(TestcontainersConfiguration::class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
internal class PaymentMethodProviderNotificationIntegrationTest(
    private val registration: PaymentMethodApplicationService,
    private val notifications: PaymentMethodProviderNotificationService,
    private val maintenance: PaymentMethodLifecycleMaintenance,
    private val adapter: ScriptedPaymentMethodLifecycleAdapter,
    private val jdbcTemplate: JdbcTemplate,
) {
    @BeforeEach
    fun clean() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_reject_payment_method_update ON payment_method")
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_reject_payment_method_update()")
        jdbcTemplate.execute(
            """
            TRUNCATE TABLE
                operations_audit_record,
                payment_method_default_command,
                payment_method_deactivation,
                payment_method_registration,
                payment_provider_notification_inbox,
                payment_provider_request_snapshot,
                payment_payment,
                payment_method
            CASCADE
            """.trimIndent(),
        )
        adapter.reset()
    }

    @Test
    fun `verified deletion maps active method and terminal replay has no additional transition`() {
        val methodId = register("notification-map-key", "issued:notification-map")
        val token = token(methodId)
        val notification = notification("notification-1", token)

        val first = notifications.accept(notification)
        val version = methodVersion(methodId)
        val replay = notifications.accept(notification)

        assertThat(first).isEqualTo(PaymentMethodProviderNotificationResult.MAPPED)
        assertThat(replay).isEqualTo(PaymentMethodProviderNotificationResult.DUPLICATE_TERMINAL)
        assertThat(methodStatus(methodId)).isEqualTo("DEACTIVATED")
        assertThat(methodVersion(methodId)).isEqualTo(version)
        assertThat(
            jdbcTemplate.queryForMap(
                """
                SELECT status, closed_reason, token_fingerprint, retention_expires_at
                  FROM payment_provider_notification_inbox
                 WHERE notification_id = 'notification-1'
                """.trimIndent(),
            ),
        ).containsEntry("status", "PROCESSED")
            .containsEntry("closed_reason", "PAYMENT_METHOD_DEACTIVATED")
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns " +
                    "WHERE table_name = 'payment_provider_notification_inbox' " +
                    "AND column_name IN ('token_reference', 'raw_token', 'raw_payload', 'provider_customer_reference')",
                Long::class.java,
            ),
        ).isZero()
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT token_fingerprint FROM payment_provider_notification_inbox WHERE notification_id = 'notification-1'",
                String::class.java,
            ),
        ).doesNotContain(token)
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM operations_audit_record
                 WHERE action = 'PAYMENT_METHOD_PROVIDER_DEACTIVATED'
                   AND (before_summary LIKE ? OR after_summary LIKE ? OR source_reference LIKE ? OR reason LIKE ?)
                """.trimIndent(),
                Long::class.java,
                "%$token%",
                "%$token%",
                "%$token%",
                "%$token%",
            ),
        ).isZero()
    }

    @Test
    fun `zero or ambiguous binding closes inbox to manual review without guessing owner`() {
        val missing = notifications.accept(notification("notification-missing", "missing-token"))
        val methodId = register("notification-ambiguous-key", "issued:notification-ambiguous")
        val token = token(methodId)
        insertTossMethod(UUID.randomUUID(), token)

        val ambiguous = notifications.accept(notification("notification-ambiguous", token))

        assertThat(missing).isEqualTo(PaymentMethodProviderNotificationResult.MANUAL_REVIEW)
        assertThat(ambiguous).isEqualTo(PaymentMethodProviderNotificationResult.MANUAL_REVIEW)
        assertThat(inboxReason("notification-missing")).isEqualTo("TOKEN_BINDING_NOT_FOUND")
        assertThat(inboxReason("notification-ambiguous")).isEqualTo("TOKEN_BINDING_AMBIGUOUS")
        assertThat(methodStatus(methodId)).isEqualTo("ACTIVE")
    }

    @Test
    fun `W2 persistence failure is not acknowledged and replay uses raw token again`() {
        val methodId = register("notification-failure-key", "issued:notification-failure")
        val token = token(methodId)
        val notification = notification("notification-failure", token)
        jdbcTemplate.execute(
            """
            CREATE FUNCTION test_reject_payment_method_update()
            RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
                RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'test payment method update rejection';
            END
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TRIGGER test_reject_payment_method_update
            BEFORE UPDATE ON payment_method
            FOR EACH ROW EXECUTE FUNCTION test_reject_payment_method_update()
            """.trimIndent(),
        )

        assertThatThrownBy { notifications.accept(notification) }.isInstanceOf(DataAccessException::class.java)
        assertThat(inboxStatus("notification-failure")).isEqualTo("ACCEPTED")
        assertThat(methodStatus(methodId)).isEqualTo("ACTIVE")

        jdbcTemplate.execute("DROP TRIGGER test_reject_payment_method_update ON payment_method")
        jdbcTemplate.execute("DROP FUNCTION test_reject_payment_method_update()")
        assertThat(notifications.accept(notification)).isEqualTo(PaymentMethodProviderNotificationResult.MAPPED)
        assertThat(methodStatus(methodId)).isEqualTo("DEACTIVATED")
    }

    @Test
    fun `verified deletion resolves an unknown deactivation ledger to stored 204`() {
        val customerId = UUID.randomUUID()
        val methodId =
            methodId(
                registration
                    .register(
                        RegisterPaymentMethodCommand(
                            customerId,
                            "notification-resolve-register",
                            "deactivate-unknown:notification-resolve",
                            "Resolve card",
                        ),
                    ).body,
            )
        val deleteKey = "notification-resolve-delete"
        assertThat(registration.deactivate(customerId, methodId, deleteKey).status).isEqualTo(202)
        assertThat(adapter.deactivationCalls).hasValue(1)

        assertThat(notifications.accept(notification("notification-resolve", token(methodId))))
            .isEqualTo(PaymentMethodProviderNotificationResult.MAPPED)
        val replay = registration.deactivate(customerId, methodId, deleteKey)

        assertThat(replay.status).isEqualTo(204)
        assertThat(replay.body).isEmpty()
        assertThat(adapter.deactivationCalls).hasValue(1)
        assertThat(methodStatus(methodId)).isEqualTo("DEACTIVATED")
        assertThat(
            jdbcTemplate.queryForMap(
                """
                SELECT status, first_response_status, first_response_body,
                       unknown_at, manual_review_at, retention_expires_at
                  FROM payment_method_deactivation
                 WHERE payment_method_id = ?
                """.trimIndent(),
                methodId,
            ),
        ).containsEntry("status", "COMPLETED")
            .containsEntry("first_response_status", 204)
            .containsEntry("first_response_body", "")
            .containsEntry("unknown_at", null)
            .containsEntry("manual_review_at", null)
    }

    @Test
    fun `deadline moves unknown deactivation to manual review without another Provider delete`() {
        val customerId = UUID.randomUUID()
        val methodId =
            methodId(
                registration
                    .register(
                        RegisterPaymentMethodCommand(
                            customerId,
                            "deadline-register-key",
                            "deactivate-unknown:deadline",
                            "Deadline card",
                        ),
                    ).body,
            )
        assertThat(registration.deactivate(customerId, methodId, "deadline-delete-key").status).isEqualTo(202)
        assertThat(adapter.deactivationCalls).hasValue(1)
        jdbcTemplate.update(
            """
            UPDATE payment_method_deactivation
               SET unknown_at = now() - interval '97 hours',
                   manual_review_at = now() - interval '1 hour'
             WHERE payment_method_id = ?
            """.trimIndent(),
            methodId,
        )

        maintenance.runDeadline()

        assertThat(methodStatus(methodId)).isEqualTo("MANUAL_REVIEW")
        assertThat(inboxCount()).isZero()
        assertThat(adapter.deactivationCalls).hasValue(1)
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT first_response_body FROM payment_method_deactivation WHERE payment_method_id = ?",
                String::class.java,
                methodId,
            ),
        ).contains("DEACTIVATION_DELAYED")
    }

    @Test
    fun `retention cleanup removes only due terminal ledgers`() {
        val customerId = UUID.randomUUID()
        val terminalId =
            methodId(
                registration
                    .register(
                        RegisterPaymentMethodCommand(customerId, "retention-terminal-key", "issued:terminal", "Terminal"),
                    ).body,
            )
        registration.register(
            RegisterPaymentMethodCommand(customerId, "retention-unknown-key", "unknown:retained", "Retained"),
        )
        val oldTerminal = Instant.now().minus(Duration.ofDays(91))
        jdbcTemplate.update(
            """
            UPDATE payment_method_registration
               SET terminal_at = ?, retention_expires_at = ?
             WHERE intended_payment_method_id = ?
            """.trimIndent(),
            Timestamp.from(oldTerminal),
            Timestamp.from(oldTerminal.plus(Duration.ofDays(90))),
            terminalId,
        )

        assertThat(maintenance.cleanupTerminal(Instant.now())).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForObject("SELECT count(*) FROM payment_method_registration", Long::class.java),
        ).isOne()
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM payment_method_registration",
                String::class.java,
            ),
        ).isEqualTo("REGISTRATION_UNKNOWN")
    }

    private fun register(
        key: String,
        authKey: String,
    ): UUID =
        methodId(
            registration
                .register(
                    RegisterPaymentMethodCommand(UUID.randomUUID(), key, authKey, "Notification card"),
                ).body,
        )

    private fun notification(
        id: String,
        token: String,
    ) = VerifiedPaymentMethodProviderNotification(
        provider = "TOSS_PAYMENTS",
        notificationId = id,
        notificationType = "BILLING_DELETED",
        tokenReference = token,
        occurredAt = Instant.now(),
    )

    private fun insertTossMethod(
        customerId: UUID,
        token: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO payment_method (
                id, customer_id, provider, token_reference, provider_customer_reference,
                display_alias, card_brand, last_four, is_default, status, created_at, updated_at
            ) VALUES (?, ?, 'TOSS_PAYMENTS', ?, ?, 'Other', 'VISA', '4242', false, 'ACTIVE', now(), now())
            """.trimIndent(),
            UUID.randomUUID(),
            customerId,
            token,
            "bf_${UUID.randomUUID().toString().replace("-", "").padEnd(43, 'a').take(43)}",
        )
    }

    private fun methodId(body: String): UUID = UUID.fromString(Regex("\"paymentMethodId\":\"([^\"]+)\"").find(body)!!.groupValues[1])

    private fun token(methodId: UUID): String =
        jdbcTemplate.queryForObject(
            "SELECT token_reference FROM payment_method WHERE id = ?",
            String::class.java,
            methodId,
        )!!

    private fun methodStatus(methodId: UUID): String =
        jdbcTemplate.queryForObject("SELECT status FROM payment_method WHERE id = ?", String::class.java, methodId)!!

    private fun methodVersion(methodId: UUID): Long =
        jdbcTemplate.queryForObject("SELECT version FROM payment_method WHERE id = ?", Long::class.java, methodId)!!

    private fun inboxStatus(notificationId: String): String =
        jdbcTemplate.queryForObject(
            "SELECT status FROM payment_provider_notification_inbox WHERE notification_id = ?",
            String::class.java,
            notificationId,
        )!!

    private fun inboxReason(notificationId: String): String =
        jdbcTemplate.queryForObject(
            "SELECT closed_reason FROM payment_provider_notification_inbox WHERE notification_id = ?",
            String::class.java,
            notificationId,
        )!!

    private fun inboxCount(): Long =
        jdbcTemplate.queryForObject("SELECT count(*) FROM payment_provider_notification_inbox", Long::class.java)!!
}

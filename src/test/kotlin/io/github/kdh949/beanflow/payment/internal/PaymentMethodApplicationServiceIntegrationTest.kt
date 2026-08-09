package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import java.util.UUID

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
internal class PaymentMethodApplicationServiceIntegrationTest(
    private val service: PaymentMethodApplicationService,
    private val adapter: ScriptedPaymentMethodLifecycleAdapter,
    private val jdbcTemplate: JdbcTemplate,
) {
    @BeforeEach
    fun clean() {
        jdbcTemplate.execute(
            """
            TRUNCATE TABLE
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
    fun `registration commits once and same key replay stores no raw authorization key`() {
        val customerId = UUID.randomUUID()
        val authKey = "issued:one-time-secret"
        val command = command(customerId, "register-key-1", authKey, "  Main card  ")

        val first = service.register(command)
        val replay = service.register(command)

        assertThat(first.status).isEqualTo(201)
        assertThat(replay).isEqualTo(first)
        assertThat(adapter.registrationCalls).hasValue(1)
        assertThat(adapter.observedActiveTransaction).isFalse()
        assertThat(first.body)
            .contains("\"provider\":\"TOSS_PAYMENTS\"", "\"displayAlias\":\"Main card\"")
            .doesNotContain(authKey, "tokenReference", "providerCustomerReference")
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM payment_method_registration
                 WHERE authorization_key_hash = repeat('0', 64)
                    OR first_response_body LIKE ?
                    OR display_alias LIKE ?
                """.trimIndent(),
                Long::class.java,
                "%$authKey%",
                "%$authKey%",
            ),
        ).isZero()
    }

    @Test
    fun `idempotency payload reuse and cross key authorization reuse stop before provider`() {
        val customerId = UUID.randomUUID()
        val authKey = "issued:single-use"
        assertThat(service.register(command(customerId, "register-key-2", authKey, "Card A")).status).isEqualTo(201)

        val reusedKey = service.register(command(customerId, "register-key-2", authKey, "Card B"))
        val reusedAuthorization = service.register(command(customerId, "register-key-3", authKey, "Card A"))

        assertThat(reusedKey.status).isEqualTo(409)
        assertThat(reusedKey.body).contains("IDEMPOTENCY_KEY_REUSED")
        assertThat(reusedAuthorization.status).isEqualTo(409)
        assertThat(reusedAuthorization.body).contains("PAYMENT_METHOD_AUTHORIZATION_REUSED")
        assertThat(adapter.registrationCalls).hasValue(1)
    }

    @Test
    fun `unknown and rejection are explicit and unknown replay never resends auth key`() {
        val customerId = UUID.randomUUID()
        val unknownCommand = command(customerId, "register-key-4", "unknown:one-time", "Unknown card")

        val unknown = service.register(unknownCommand)
        val unknownReplay = service.register(unknownCommand)
        val rejected =
            service.register(command(customerId, "register-key-5", "rejected:one-time", "Rejected card"))

        assertThat(unknown.status).isEqualTo(202)
        assertThat(unknownReplay).isEqualTo(unknown)
        assertThat(rejected.status).isEqualTo(422)
        assertThat(rejected.body).contains("PAYMENT_METHOD_REGISTRATION_REJECTED")
        assertThat(adapter.registrationCalls).hasValue(2)
        assertThat(status("payment_method_registration", "idempotency_key", "register-key-4"))
            .isEqualTo("REGISTRATION_UNKNOWN")
    }

    @Test
    fun `default replay does not overwrite a newer preference`() {
        val customerId = UUID.randomUUID()
        val first = service.register(command(customerId, "register-key-6", "issued:first", "First"))
        val second = service.register(command(customerId, "register-key-7", "issued:second", "Second"))
        val firstId = paymentMethodId(first.body)
        val secondId = paymentMethodId(second.body)

        val firstDefault = service.setDefault(customerId, firstId, "default-key-1")
        val secondDefault = service.setDefault(customerId, secondId, "default-key-2")
        val staleReplay = service.setDefault(customerId, firstId, "default-key-1")

        assertThat(firstDefault.status).isEqualTo(200)
        assertThat(secondDefault.status).isEqualTo(200)
        assertThat(staleReplay).isEqualTo(firstDefault)
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT id FROM payment_method WHERE customer_id = ? AND is_default = true",
                UUID::class.java,
                customerId,
            ),
        ).isEqualTo(secondId)
    }

    @Test
    fun `deactivation confirms once or remains explicit unknown without a second delete`() {
        val customerId = UUID.randomUUID()
        val confirmedId =
            paymentMethodId(
                service.register(command(customerId, "register-key-8", "issued:confirmed", "Confirmed")).body,
            )
        val unknownId =
            paymentMethodId(
                service.register(
                    command(customerId, "register-key-9", "deactivate-unknown:pending", "Pending"),
                ).body,
            )

        val confirmed = service.deactivate(customerId, confirmedId, "deactivate-key-1")
        val confirmedReplay = service.deactivate(customerId, confirmedId, "deactivate-key-1")
        val unknown = service.deactivate(customerId, unknownId, "deactivate-key-2")
        val unknownReplay = service.deactivate(customerId, unknownId, "deactivate-key-2")

        assertThat(confirmed.status).isEqualTo(204)
        assertThat(confirmedReplay).isEqualTo(confirmed)
        assertThat(unknown.status).isEqualTo(202)
        assertThat(unknownReplay).isEqualTo(unknown)
        assertThat(adapter.deactivationCalls).hasValue(2)
        assertThat(methodStatus(confirmedId)).isEqualTo("DEACTIVATED")
        assertThat(methodStatus(unknownId)).isEqualTo("DEACTIVATION_UNKNOWN")
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT extract(epoch FROM (manual_review_at - unknown_at))::bigint
                  FROM payment_method_deactivation WHERE payment_method_id = ?
                """.trimIndent(),
                Long::class.java,
                unknownId,
            ),
        ).isEqualTo(96L * 60L * 60L)
    }

    private fun command(
        customerId: UUID,
        key: String,
        authKey: String,
        alias: String,
    ) = RegisterPaymentMethodCommand(customerId, key, authKey, alias)

    private fun paymentMethodId(body: String): UUID =
        UUID.fromString(Regex("\"paymentMethodId\":\"([^\"]+)\"").find(body)!!.groupValues[1])

    private fun methodStatus(methodId: UUID): String =
        jdbcTemplate.queryForObject("SELECT status FROM payment_method WHERE id = ?", String::class.java, methodId)!!

    private fun status(
        table: String,
        keyColumn: String,
        key: String,
    ): String = jdbcTemplate.queryForObject("SELECT status FROM $table WHERE $keyColumn = ?", String::class.java, key)!!
}

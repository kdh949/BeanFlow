package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
internal class PaymentMethodApplicationServiceIntegrationTest(
    private val service: PaymentMethodApplicationService,
    private val adapter: ScriptedPaymentMethodLifecycleAdapter,
    private val transactions: PaymentMethodLifecycleTransactions,
    private val maintenance: PaymentMethodLifecycleMaintenance,
    private val jdbcTemplate: JdbcTemplate,
) {
    @BeforeEach
    fun clean() {
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
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM operations_audit_record
                 WHERE before_summary LIKE ? OR after_summary LIKE ?
                    OR source_reference LIKE ? OR reason LIKE ?
                """.trimIndent(),
                Long::class.java,
                "%$authKey%",
                "%$authKey%",
                "%$authKey%",
                "%$authKey%",
            ),
        ).isZero()
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM operations_audit_record WHERE action LIKE 'PAYMENT_METHOD_%'",
                Long::class.java,
            ),
        ).isOne()
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
                service
                    .register(
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

    @Test
    fun `startup recovery closes claims interrupted by process loss without another provider call`() {
        val registration =
            transactions.prepareRegistration(
                normalized(UUID.randomUUID(), "recovery-register-key", 'a', 'b', "Recovery"),
            ) as RegistrationPreparation.Claimable
        transactions.claimRegistration(registration.registrationId)

        val customerId = UUID.randomUUID()
        val methodId =
            paymentMethodId(
                service.register(command(customerId, "recovery-method-key", "issued:recovery", "Recovery method")).body,
            )
        val deactivation =
            transactions.prepareDeactivation(customerId, methodId, "recovery-deactivate-key") as
                DeactivationPreparation.Claimable
        transactions.claimDeactivation(deactivation.deactivationId)
        val registrationCallsBeforeRecovery = adapter.registrationCalls.get()
        val deactivationCallsBeforeRecovery = adapter.deactivationCalls.get()

        maintenance.recoverInterruptedClaims()

        assertThat(status("payment_method_registration", "id", registration.registrationId))
            .isEqualTo("REGISTRATION_UNKNOWN")
        assertThat(status("payment_method_deactivation", "id", deactivation.deactivationId))
            .isEqualTo("DEACTIVATION_UNKNOWN")
        assertThat(methodStatus(methodId)).isEqualTo("DEACTIVATION_UNKNOWN")
        assertThat(adapter.registrationCalls).hasValue(registrationCallsBeforeRecovery)
        assertThat(adapter.deactivationCalls).hasValue(deactivationCallsBeforeRecovery)
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT extract(epoch FROM (manual_review_at - unknown_at))::bigint
                  FROM payment_method_deactivation WHERE id = ?
                """.trimIndent(),
                Long::class.java,
                deactivation.deactivationId,
            ),
        ).isEqualTo(96L * 60L * 60L)
    }

    @Test
    fun `issued result converges to an exact active binding with stored 200 response`() {
        val customerId = UUID.randomUUID()
        val preparation =
            transactions.prepareRegistration(
                normalized(customerId, "exact-binding-key", 'c', 'd', "Exact card"),
            ) as RegistrationPreparation.Claimable
        val claim = checkNotNull(transactions.claimRegistration(preparation.registrationId))
        val existingId = UUID.randomUUID()
        insertMethod(
            id = existingId,
            customerId = customerId,
            tokenReference = "exact-binding-token",
            providerCustomerReference = claim.providerCustomerReference,
            alias = "Exact card",
            brand = "VISA",
            lastFour = "4242",
        )

        val response =
            transactions.completeRegistration(
                claim,
                io.github.kdh949.beanflow.payment.api.PaymentMethodRegistrationProviderResult.Issued(
                    tokenReference = "exact-binding-token",
                    cardBrand = "VISA",
                    lastFour = "4242",
                ),
            )

        assertThat(response.status).isEqualTo(200)
        assertThat(paymentMethodId(response.body)).isEqualTo(existingId)
        assertThat(status("payment_method_registration", "id", preparation.registrationId))
            .isEqualTo("COMPLETED")
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT first_response_status FROM payment_method_registration WHERE id = ?",
                Int::class.java,
                preparation.registrationId,
            ),
        ).isEqualTo(200)
    }

    @Test
    fun `concurrent cross owner issued results produce one binding and one conflict`() {
        val first =
            transactions.prepareRegistration(
                normalized(UUID.randomUUID(), "cross-owner-first", 'e', 'f', "First owner"),
            ) as RegistrationPreparation.Claimable
        val second =
            transactions.prepareRegistration(
                normalized(UUID.randomUUID(), "cross-owner-second", '1', '2', "Second owner"),
            ) as RegistrationPreparation.Claimable
        val firstClaim = checkNotNull(transactions.claimRegistration(first.registrationId))
        val secondClaim = checkNotNull(transactions.claimRegistration(second.registrationId))
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)

        fun complete(claim: RegistrationClaim) =
            CompletableFuture.supplyAsync {
                ready.countDown()
                start.await()
                transactions.completeRegistration(
                    claim,
                    io.github.kdh949.beanflow.payment.api.PaymentMethodRegistrationProviderResult.Issued(
                        tokenReference = "cross-owner-shared-token",
                        cardBrand = "VISA",
                        lastFour = "4242",
                    ),
                )
            }

        val firstResult = complete(firstClaim)
        val secondResult = complete(secondClaim)
        ready.await()
        start.countDown()

        assertThat(listOf(firstResult.join().status, secondResult.join().status)).containsExactlyInAnyOrder(201, 409)
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_method WHERE token_reference = 'cross-owner-shared-token'",
                Long::class.java,
            ),
        ).isOne()
        assertThat(
            jdbcTemplate.queryForList(
                "SELECT status FROM payment_method_registration WHERE id IN (?, ?)",
                String::class.java,
                first.registrationId,
                second.registrationId,
            ),
        ).containsExactlyInAnyOrder("COMPLETED", "MANUAL_REVIEW")
    }

    @Test
    fun `concurrent default changes leave exactly one active default`() {
        val customerId = UUID.randomUUID()
        val firstId = paymentMethodId(service.register(command(customerId, "concurrent-register-1", "issued:default-1", "One")).body)
        val secondId = paymentMethodId(service.register(command(customerId, "concurrent-register-2", "issued:default-2", "Two")).body)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)

        fun changeDefault(
            methodId: UUID,
            key: String,
        ) = CompletableFuture.supplyAsync {
            ready.countDown()
            start.await()
            service.setDefault(customerId, methodId, key)
        }

        val firstResult = changeDefault(firstId, "concurrent-default-1")
        val secondResult = changeDefault(secondId, "concurrent-default-2")
        ready.await()
        start.countDown()

        assertThat(firstResult.join().status).isEqualTo(200)
        assertThat(secondResult.join().status).isEqualTo(200)
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_method WHERE customer_id = ? AND is_default = true AND status = 'ACTIVE'",
                Long::class.java,
                customerId,
            ),
        ).isOne()
    }

    private fun command(
        customerId: UUID,
        key: String,
        authKey: String,
        alias: String,
    ) = RegisterPaymentMethodCommand(customerId, key, authKey, alias)

    private fun paymentMethodId(body: String): UUID = UUID.fromString(Regex("\"paymentMethodId\":\"([^\"]+)\"").find(body)!!.groupValues[1])

    private fun methodStatus(methodId: UUID): String =
        jdbcTemplate.queryForObject("SELECT status FROM payment_method WHERE id = ?", String::class.java, methodId)!!

    private fun normalized(
        customerId: UUID,
        key: String,
        authorizationHashCharacter: Char,
        payloadHashCharacter: Char,
        alias: String,
    ) = NormalizedRegistrationCommand(
        customerId = customerId,
        idempotencyKey = key,
        authorizationKeyHash = authorizationHashCharacter.toString().repeat(64),
        payloadHash = payloadHashCharacter.toString().repeat(64),
        displayAlias = alias,
    )

    private fun insertMethod(
        id: UUID,
        customerId: UUID,
        tokenReference: String,
        providerCustomerReference: String,
        alias: String,
        brand: String,
        lastFour: String,
    ) {
        val now = Timestamp.from(Instant.now())
        jdbcTemplate.update(
            """
            INSERT INTO payment_method (
                id, customer_id, provider, token_reference, provider_customer_reference,
                display_alias, card_brand, last_four, is_default, status, created_at, updated_at, version
            ) VALUES (?, ?, 'TOSS_PAYMENTS', ?, ?, ?, ?, ?, false, 'ACTIVE', ?, ?, 0)
            """.trimIndent(),
            id,
            customerId,
            tokenReference,
            providerCustomerReference,
            alias,
            brand,
            lastFour,
            now,
            now,
        )
    }

    private fun status(
        table: String,
        keyColumn: String,
        key: Any,
    ): String = jdbcTemplate.queryForObject("SELECT status FROM $table WHERE $keyColumn = ?", String::class.java, key)!!
}

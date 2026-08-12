package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.delivery.api.ExternalCourierSupportProfileChangeOperations
import io.github.kdh949.beanflow.delivery.api.PrepareCourierPayoutReferenceChange
import io.github.kdh949.beanflow.identity.api.CustomerSupportProfileChangeOperations
import io.github.kdh949.beanflow.identity.api.PrepareCustomerPrimaryPhoneChange
import io.github.kdh949.beanflow.merchant.api.PrepareStorePublicProfileCorrection
import io.github.kdh949.beanflow.merchant.api.StoreSupportProfileChangeOperations
import io.github.kdh949.beanflow.shared.api.BlindIndex
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.EncryptedPersonalData
import io.github.kdh949.beanflow.shared.api.NormalizedExactSearchValue
import io.github.kdh949.beanflow.shared.api.KeyedBlindIndexPort
import io.github.kdh949.beanflow.shared.api.PersonalDataCryptoPort
import io.github.kdh949.beanflow.shared.api.PersonalDataEncryptionContext
import io.github.kdh949.beanflow.shared.api.PersonalDataField
import io.github.kdh949.beanflow.shared.api.ProfileNotificationTargetKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@Import(TestcontainersConfiguration::class, OwnerProfileChangeTestConfiguration::class)
@SpringBootTest(
    properties = [
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
        "beanflow.support-case-idempotency.retention.initial-delay-ms=3600000",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class OwnerProfileChangeIntegrationTest
    @Autowired
    constructor(
        private val customers: CustomerSupportProfileChangeOperations,
        private val stores: StoreSupportProfileChangeOperations,
        private val couriers: ExternalCourierSupportProfileChangeOperations,
        private val jdbcTemplate: JdbcTemplate,
        private val transactionTemplate: TransactionTemplate,
        private val crypto: RecordingProfileCrypto,
    ) {
        @BeforeEach
        fun resetAndSeed() {
            jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    identity_customer_support_profile,
                    merchant_store_support_profile,
                    delivery_external_courier_support_profile,
                    merchant_store
                CASCADE
                """.trimIndent(),
            )
            crypto.reset()
            seedCustomer()
            seedStore()
            seedCourier()
        }

        @Test
        fun `customer primary phone write is versioned idempotent and emits old and new snapshots`() {
            val changeId = UUID.fromString("82000000-0000-0000-0000-000000000011")
            val prepared =
                customers.preparePrimaryPhone(
                    PrepareCustomerPrimaryPhoneChange(changeId, CUSTOMER_ID, 0, "010-5555-6666"),
                )

            val result = transactionTemplate.execute { customers.apply(prepared) }!!
            val replay = transactionTemplate.execute { customers.apply(prepared) }!!

            assertThat(result.currentVersion).isEqualTo(1)
            assertThat(result.notificationTargets.map { it.kind })
                .containsExactly(ProfileNotificationTargetKind.OLD, ProfileNotificationTargetKind.NEW)
            assertThat(replay.ownerChangeId).isEqualTo(result.ownerChangeId)
            assertThat(replay.currentVersion).isEqualTo(result.currentVersion)
            assertThat(replay.notificationTargets).containsExactlyElementsOf(result.notificationTargets)
            assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM identity_customer_support_profile WHERE customer_id = ?",
                Long::class.java,
                CUSTOMER_ID,
            )).isEqualTo(1)
            assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM identity_customer_support_profile_exact_index WHERE customer_id = ? AND criterion_type = 'PHONE'",
                Int::class.java,
                CUSTOMER_ID,
            )).isOne()
            assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM identity_customer_profile_change_history WHERE support_profile_change_id = ?",
                Int::class.java,
                changeId,
            )).isOne()
            assertThat(databaseContains("010-5555-6666")).isFalse()

            val stale = customers.preparePrimaryPhone(
                PrepareCustomerPrimaryPhoneChange(UUID.randomUUID(), CUSTOMER_ID, 0, "010-7777-8888"),
            )
            assertThatThrownBy { transactionTemplate.execute { customers.apply(stale) } }
                .isInstanceOf(DomainFailure::class.java)
        }

        @Test
        fun `store public phone change emits old and new and resolves with its exact AAD field`() {
            val changeId = UUID.fromString("82000000-0000-0000-0000-000000000012")
            val prepared =
                stores.preparePublicProfile(
                    PrepareStorePublicProfileCorrection(changeId, STORE_ID, 0, null, "010-5555-6666", null, null),
                )

            val result = transactionTemplate.execute { stores.apply(prepared) }!!
            val newTarget = result.notificationTargets.single { it.kind == ProfileNotificationTargetKind.NEW }
            stores.resolveNotificationTarget(newTarget.targetId)

            assertThat(result.notificationTargets.map { it.kind })
                .containsExactly(ProfileNotificationTargetKind.OLD, ProfileNotificationTargetKind.NEW)
            assertThat(crypto.decryptContexts.single().field).isEqualTo(PersonalDataField.PUBLIC_PHONE)
        }

        @Test
        fun `external courier payout reference is owner-written without raw support storage`() {
            val changeId = UUID.fromString("82000000-0000-0000-0000-000000000013")
            val rawReference = "payout:reference:9001"
            val prepared =
                couriers.preparePayoutReference(
                    PrepareCourierPayoutReferenceChange(changeId, COURIER_ID, 0, rawReference),
                )

            val result = transactionTemplate.execute { couriers.apply(prepared) }!!

            assertThat(result.currentVersion).isEqualTo(1)
            assertThat(result.maskedAfter).isEqualTo("***9001")
            assertThat(result.notificationTargets).hasSize(1)
            assertThat(result.notificationTargets.single().kind).isEqualTo(ProfileNotificationTargetKind.CURRENT)
            assertThat(databaseContains(rawReference)).isFalse()
        }

        private fun databaseContains(raw: String): Boolean =
            jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM identity_customer_support_profile WHERE row_to_json(identity_customer_support_profile)::text LIKE ?
                    UNION ALL
                    SELECT 1 FROM merchant_store_support_profile WHERE row_to_json(merchant_store_support_profile)::text LIKE ?
                    UNION ALL
                    SELECT 1 FROM delivery_external_courier_support_profile WHERE row_to_json(delivery_external_courier_support_profile)::text LIKE ?
                )
                """.trimIndent(),
                Boolean::class.java,
                "%$raw%",
                "%$raw%",
                "%$raw%",
            ) ?: false

        private fun seedCustomer() {
            jdbcTemplate.update(
                """
                INSERT INTO identity_customer_support_profile (
                    customer_id, display_name_ciphertext, display_name_key_version, display_name_aad_version,
                    masked_display_name, primary_phone_ciphertext, primary_phone_key_version,
                    primary_phone_aad_version, masked_primary_phone, created_at, updated_at, version
                ) VALUES (?, 'vault:v7:name', 7, 1, '김*현', 'vault:v7:old-phone', 7, 1,
                          '***-****-1111', ?, ?, 0)
                """.trimIndent(),
                CUSTOMER_ID,
                Timestamp.from(NOW),
                Timestamp.from(NOW),
            )
        }

        private fun seedStore() {
            jdbcTemplate.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                STORE_ID,
            )
            jdbcTemplate.update(
                """
                INSERT INTO merchant_store_support_profile (
                    store_id, legal_display_name_ciphertext, legal_display_name_key_version,
                    legal_display_name_aad_version, masked_display_name,
                    public_phone_ciphertext, public_phone_key_version, public_phone_aad_version, masked_public_phone,
                    support_email_ciphertext, support_email_key_version, support_email_aad_version, masked_support_email,
                    created_at, updated_at, version
                ) VALUES (?, 'vault:v7:legal', 7, 1, '빈*우', 'vault:v7:old-public-phone', 7, 1,
                          '***-****-2222', 'vault:v7:email', 7, 1, 's***@e***.com', ?, ?, 0)
                """.trimIndent(),
                STORE_ID,
                Timestamp.from(NOW),
                Timestamp.from(NOW),
            )
        }

        private fun seedCourier() {
            jdbcTemplate.update(
                """
                INSERT INTO delivery_external_courier_support_profile (
                    external_courier_id, provider_code,
                    provider_courier_reference_ciphertext, provider_courier_reference_key_version,
                    provider_courier_reference_aad_version, masked_provider_courier_reference,
                    display_name_ciphertext, display_name_key_version, display_name_aad_version, masked_display_name,
                    relay_email_ciphertext, relay_email_key_version, relay_email_aad_version, masked_relay_email,
                    created_at, updated_at, version
                ) VALUES (?, 'EXTERNAL_PROVIDER', 'vault:v7:reference', 7, 1, '***0001',
                          'vault:v7:display', 7, 1, '라*더', 'vault:v7:email', 7, 1,
                          'r***@e***.com', ?, ?, 0)
                """.trimIndent(),
                COURIER_ID,
                Timestamp.from(NOW),
                Timestamp.from(NOW),
            )
        }

        private companion object {
            val NOW: Instant = Instant.parse("2026-08-12T00:00:00Z")
            val CUSTOMER_ID: UUID = UUID.fromString("82000000-0000-0000-0000-000000000001")
            val STORE_ID: UUID = UUID.fromString("82000000-0000-0000-0000-000000000002")
            val COURIER_ID: UUID = UUID.fromString("82000000-0000-0000-0000-000000000003")
        }
    }

@TestConfiguration(proxyBeanMethods = false)
internal class OwnerProfileChangeTestConfiguration {
    @Bean
    @Primary
    fun recordingProfileCrypto(): RecordingProfileCrypto = RecordingProfileCrypto()
}

internal class RecordingProfileCrypto : PersonalDataCryptoPort, KeyedBlindIndexPort {
    private val ciphertextSequence = AtomicInteger()
    val decryptContexts = CopyOnWriteArrayList<PersonalDataEncryptionContext>()

    fun reset() {
        ciphertextSequence.set(0)
        decryptContexts.clear()
    }

    override fun encrypt(plaintext: ByteArray, context: PersonalDataEncryptionContext): EncryptedPersonalData =
        EncryptedPersonalData(
            "vault:v7:${context.field.name.lowercase()}-${ciphertextSequence.incrementAndGet()}",
            7,
            1,
        )

    override fun decrypt(encrypted: EncryptedPersonalData, context: PersonalDataEncryptionContext): ByteArray {
        decryptContexts += context
        return "+821055556666".toByteArray()
    }

    override fun rewrap(encrypted: EncryptedPersonalData, context: PersonalDataEncryptionContext): EncryptedPersonalData = encrypted

    override fun writeKeyVersion(): Int = 3

    override fun activeSearchKeyVersions(): Set<Int> = setOf(3)

    override fun generate(normalizedValue: NormalizedExactSearchValue, keyVersions: Set<Int>): List<BlindIndex> =
        keyVersions.sorted().map { BlindIndex(it, ByteArray(32) { 4 }) }
}

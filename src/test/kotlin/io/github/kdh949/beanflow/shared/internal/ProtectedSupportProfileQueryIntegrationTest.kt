package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.delivery.api.ExternalCourierSupportProfileQueryOperations
import io.github.kdh949.beanflow.identity.api.CustomerSupportProfileQueryOperations
import io.github.kdh949.beanflow.merchant.api.StoreSupportProfileQueryOperations
import io.github.kdh949.beanflow.shared.api.BlindIndex
import io.github.kdh949.beanflow.shared.api.ExactSearchCriterionType
import io.github.kdh949.beanflow.shared.api.ProtectedProfileExactQuery
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
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
internal class ProtectedSupportProfileQueryIntegrationTest
    @Autowired
    constructor(
        private val customers: CustomerSupportProfileQueryOperations,
        private val stores: StoreSupportProfileQueryOperations,
        private val couriers: ExternalCourierSupportProfileQueryOperations,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        private val digest = ByteArray(32) { 6 }
        private val customerId = UUID.fromString("10000000-0000-0000-0000-000000000001")
        private val storeId = UUID.fromString("20000000-0000-0000-0000-000000000001")
        private val courierId = UUID.fromString("30000000-0000-0000-0000-000000000001")

        @BeforeEach
        fun resetAndSeed() {
            jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    identity_customer_support_profile_exact_index,
                    merchant_store_support_profile_exact_index,
                    delivery_external_courier_support_profile_exact_index,
                    identity_customer_support_profile,
                    merchant_store_support_profile,
                    delivery_external_courier_support_profile,
                    merchant_store
                CASCADE
                """.trimIndent(),
            )
            seedCustomer()
            seedStore()
            seedCourier()
        }

        @Test
        fun `each owner returns only masked projections for the exact digest and version`() {
            val query = ProtectedProfileExactQuery(ExactSearchCriterionType.EMAIL, listOf(BlindIndex(3, digest)), 21)

            assertThat(customers.findByExactIndexes(query)).containsExactly(
                io.github.kdh949.beanflow.identity.api.MaskedCustomerSupportProfile(
                    customerId,
                    "홍*동",
                    "h***@e***.com",
                ),
            )
            assertThat(stores.findByExactIndexes(query)).containsExactly(
                io.github.kdh949.beanflow.merchant.api.MaskedStoreSupportProfile(
                    storeId,
                    "빈*우",
                    "s***@e***.com",
                ),
            )
            assertThat(couriers.findByExactIndexes(query)).containsExactly(
                io.github.kdh949.beanflow.delivery.api.MaskedExternalCourierSupportProfile(
                    courierId,
                    "라*더",
                    "r***@e***.com",
                ),
            )
        }

        @Test
        fun `different key version type or digest does not match`() {
            val wrongVersion = ProtectedProfileExactQuery(ExactSearchCriterionType.EMAIL, listOf(BlindIndex(4, digest)), 21)
            val wrongDigest = ProtectedProfileExactQuery(ExactSearchCriterionType.EMAIL, listOf(BlindIndex(3, ByteArray(32) { 7 })), 21)
            val wrongType = ProtectedProfileExactQuery(ExactSearchCriterionType.PHONE, listOf(BlindIndex(3, digest)), 21)

            listOf(wrongVersion, wrongDigest, wrongType).forEach { query ->
                assertThat(customers.findByExactIndexes(query)).isEmpty()
                assertThat(stores.findByExactIndexes(query)).isEmpty()
                assertThat(couriers.findByExactIndexes(query)).isEmpty()
            }
        }

        @Test
        fun `dual-version rotation lookup returns each matching owner only once`() {
            val nextDigest = ByteArray(32) { 8 }
            insertIndex("identity_customer_support_profile_exact_index", "customer_id", customerId, 4, nextDigest)
            insertIndex("merchant_store_support_profile_exact_index", "store_id", storeId, 4, nextDigest)
            insertIndex("delivery_external_courier_support_profile_exact_index", "external_courier_id", courierId, 4, nextDigest)
            val query =
                ProtectedProfileExactQuery(
                    ExactSearchCriterionType.EMAIL,
                    listOf(BlindIndex(3, digest), BlindIndex(4, nextDigest)),
                    21,
                )

            assertThat(customers.findByExactIndexes(query).map { it.customerId }).containsExactly(customerId)
            assertThat(stores.findByExactIndexes(query).map { it.storeId }).containsExactly(storeId)
            assertThat(couriers.findByExactIndexes(query).map { it.externalCourierId }).containsExactly(courierId)
        }

        private fun seedCustomer() {
            jdbcTemplate.update(
                """
                INSERT INTO identity_customer_support_profile (
                    customer_id, display_name_ciphertext, display_name_key_version, display_name_aad_version,
                    masked_display_name, primary_email_ciphertext, primary_email_key_version,
                    primary_email_aad_version, masked_primary_email, created_at, updated_at
                ) VALUES (?, 'vault:v7:display', 7, 1, '홍*동', 'vault:v7:email', 7, 1,
                          'h***@e***.com', ?, ?)
                """.trimIndent(),
                customerId,
                Timestamp.from(NOW),
                Timestamp.from(NOW),
            )
            insertIndex("identity_customer_support_profile_exact_index", "customer_id", customerId)
        }

        private fun seedStore() {
            jdbcTemplate.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                storeId,
            )
            jdbcTemplate.update(
                """
                INSERT INTO merchant_store_support_profile (
                    store_id, legal_display_name_ciphertext, legal_display_name_key_version,
                    legal_display_name_aad_version, masked_display_name,
                    support_email_ciphertext, support_email_key_version, support_email_aad_version,
                    masked_support_email, created_at, updated_at
                ) VALUES (?, 'vault:v7:legal', 7, 1, '빈*우', 'vault:v7:email', 7, 1,
                          's***@e***.com', ?, ?)
                """.trimIndent(),
                storeId,
                Timestamp.from(NOW),
                Timestamp.from(NOW),
            )
            insertIndex("merchant_store_support_profile_exact_index", "store_id", storeId)
        }

        private fun seedCourier() {
            jdbcTemplate.update(
                """
                INSERT INTO delivery_external_courier_support_profile (
                    external_courier_id, provider_code,
                    provider_courier_reference_ciphertext, provider_courier_reference_key_version,
                    provider_courier_reference_aad_version,
                    display_name_ciphertext, display_name_key_version, display_name_aad_version, masked_display_name,
                    relay_email_ciphertext, relay_email_key_version, relay_email_aad_version, masked_relay_email,
                    created_at, updated_at
                ) VALUES (?, 'EXTERNAL_PROVIDER', 'vault:v7:reference', 7, 1,
                          'vault:v7:display', 7, 1, '라*더', 'vault:v7:email', 7, 1,
                          'r***@e***.com', ?, ?)
                """.trimIndent(),
                courierId,
                Timestamp.from(NOW),
                Timestamp.from(NOW),
            )
            insertIndex(
                "delivery_external_courier_support_profile_exact_index",
                "external_courier_id",
                courierId,
            )
        }

        private fun insertIndex(
            table: String,
            idColumn: String,
            subjectId: UUID,
            keyVersion: Int = 3,
            blindIndex: ByteArray = digest,
        ) {
            jdbcTemplate.update(
                """
                INSERT INTO $table ($idColumn, criterion_type, index_key_version, blind_index, created_at)
                VALUES (?, 'EMAIL', ?, ?, ?)
                """.trimIndent(),
                subjectId,
                keyVersion,
                blindIndex,
                Timestamp.from(NOW),
            )
        }

        private companion object {
            val NOW: Instant = Instant.parse("2026-08-11T00:00:00Z")
        }
    }

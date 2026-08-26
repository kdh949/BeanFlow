package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.merchant.api.ReplaceMenuDisplayContentCommand
import io.github.kdh949.beanflow.merchant.api.ReplaceStoreCustomerDisplayCommand
import io.github.kdh949.beanflow.merchant.api.StoreOperatingDay
import io.github.kdh949.beanflow.operations.internal.AuditRecordService
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.reset
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.sql.Timestamp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("proves display owner writes roll back when append-only Audit persistence fails")
@SpringBootTest(properties = ["spring.main.allow-bean-definition-overriding=true"])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class MerchantDisplayContentAuditRollbackIntegrationTest(
    @Autowired private val service: MerchantDisplayContentService,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val passwords: CustomerPasswordSecurity,
) {
    @MockitoBean(name = "auditRecordService")
    private lateinit var audits: AuditRecordService

    @BeforeEach
    fun cleanDatabase() {
        jdbc.execute(
            """
            TRUNCATE TABLE identity_store_membership, identity_merchant_account,
                merchant_store_operating_hours, merchant_store_customer_display_profile,
                merchant_menu, merchant_store CASCADE
            """.trimIndent(),
        )
        reset(audits)
        doThrow(DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Audit persistence failed"))
            .`when`(audits)
            .appendAll(anyList())
    }

    @Test
    fun `Store profile and all seven hours roll back when Audit append fails`() {
        val storeId = seedStore()
        val actorId = seedActor(storeId, "OWNER")

        assertThatThrownBy {
            service.replaceProfile(
                actorId,
                ReplaceStoreCustomerDisplayCommand(
                    storeId = storeId,
                    expectedVersion = 0,
                    addressLine = "서울시 성동구 연무장길 1",
                    directionsHint = null,
                    timezone = "Asia/Seoul",
                    operatingDays = completeWeek(),
                ),
            )
        }.isInstanceOf(DomainFailure::class.java)
            .extracting("code")
            .isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)

        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_store_customer_display_profile", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_store_operating_hours", Long::class.java)).isZero()
    }

    @Test
    fun `Menu content and Menu version roll back when Audit append fails`() {
        val storeId = seedStore()
        val menuId = seedMenu(storeId)
        val actorId = seedActor(storeId, "STAFF")

        assertThatThrownBy {
            service.replaceMenu(
                actorId,
                ReplaceMenuDisplayContentCommand(storeId, menuId, 0, "커피", "고소한 카페라떼"),
            )
        }.isInstanceOf(DomainFailure::class.java)
            .extracting("code")
            .isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)

        val menu = jdbc.queryForMap("SELECT display_category, public_description, version FROM merchant_menu WHERE id = ?", menuId)
        assertThat(menu["display_category"]).isNull()
        assertThat(menu["public_description"]).isNull()
        assertThat(menu["version"]).isEqualTo(0L)
    }

    private fun completeWeek(): List<StoreOperatingDay> =
        DayOfWeek.entries.map { day ->
            if (day == DayOfWeek.SUNDAY) {
                StoreOperatingDay(day, true, null, null)
            } else {
                StoreOperatingDay(day, false, LocalTime.of(9, 0), LocalTime.of(18, 0))
            }
        }

    private fun seedStore(): UUID =
        UUID.randomUUID().also { storeId ->
            jdbc.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                storeId,
            )
        }

    private fun seedMenu(storeId: UUID): UUID =
        UUID.randomUUID().also { menuId ->
            jdbc.update(
                "INSERT INTO merchant_menu (id, store_id, name, base_price_krw, available, version) " +
                    "VALUES (?, ?, 'Latte', 5000, true, 0)",
                menuId,
                storeId,
            )
        }

    private fun seedActor(
        storeId: UUID,
        role: String,
    ): UUID =
        UUID.randomUUID().also { actorId ->
            jdbc.update(
                """
                INSERT INTO identity_merchant_account
                    (id, login_id, password_hash, credential_version, display_name, state,
                     temporary_password_expires_at, password_changed_at, locked_until,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, 0, 'Display Merchant', 'ACTIVE', NULL, ?, NULL, ?, ?, 0)
                """.trimIndent(),
                actorId,
                "rb.${UUID.randomUUID().toString().replace("-", "").take(24)}",
                passwords.encode("merchant-current-password-2026"),
                Timestamp.from(NOW),
                Timestamp.from(NOW.minusSeconds(1)),
                Timestamp.from(NOW),
            )
            jdbc.update(
                """
                INSERT INTO identity_store_membership
                    (id, actor_id, store_id, membership_role, status, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, 0)
                """.trimIndent(),
                UUID.randomUUID(),
                actorId,
                storeId,
                role,
                Timestamp.from(NOW),
                Timestamp.from(NOW),
            )
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyList(): List<T> {
        org.mockito.Mockito.anyList<T>()
        return emptyList()
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-25T00:00:00Z")
    }
}

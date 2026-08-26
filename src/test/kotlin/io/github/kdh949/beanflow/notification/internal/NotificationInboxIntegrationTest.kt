package io.github.kdh949.beanflow.notification.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.notification.api.RequestCustomerCancellationAcceptedNotificationCommand
import io.github.kdh949.beanflow.tamperSignedCursorSignature
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("notification ownership and atomic persistence require committed PostgreSQL state")
@SpringBootTest(
    properties = [
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.notification.inbox-cleanup.initial-delay-ms=3600000",
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
    ],
)
internal class NotificationInboxIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val objectMapper: ObjectMapper,
        private val inboxService: NotificationInboxService,
        private val deliveryService: NotificationDeliveryService,
        private val meterRegistry: MeterRegistry,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)

        @BeforeEach
        fun cleanDatabase() {
            dropFailureTriggers()
            jdbcTemplate.execute(
                "TRUNCATE TABLE notification_customer_preference, notification_inbox_item, notification_delivery CASCADE",
            )
        }

        @AfterEach
        fun removeFailureTriggers() = dropFailureTriggers()

        @Test
        fun `summary and list expose only the session customer inbox`() {
            val customerId = UUID.randomUUID()
            val otherCustomerId = UUID.randomUUID()
            val ownId = insertItem(customerId, NOW, "own")
            insertItem(otherCustomerId, NOW.plusSeconds(1), "other")
            val summaryBefore = readMetric("summary", "succeeded")
            val listBefore = readMetric("list", "succeeded")

            mockMvc
                .perform(get("/api/v1/me/notification-summary").with(customerJwt(customerId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.hasUnread").value(true))

            mockMvc
                .perform(get("/api/v1/me/notifications").with(customerJwt(customerId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].notificationId").value(ownId.toString()))
                .andExpect(jsonPath("$.items[0].title").value("알림 own"))
                .andExpect(jsonPath("$.items[0].classification").value("TRANSACTIONAL"))
                .andExpect(jsonPath("$.items[0].target.type").value("NONE"))
                .andExpect(jsonPath("$.items[0].target.reference").doesNotExist())
                .andExpect(jsonPath("$.items[0].orderId").doesNotExist())
                .andExpect(jsonPath("$.items[0].deliveryState").doesNotExist())

            mockMvc.perform(get("/api/v1/me/notifications")).andExpect(status().isUnauthorized)
            mockMvc
                .perform(
                    get("/api/v1/me/notifications")
                        .with(jwt().jwt { it.subject(customerId.toString()) }.authorities(SimpleGrantedAuthority("ROLE_MERCHANT"))),
                ).andExpect(status().isForbidden)
            assertThat(readMetric("summary", "succeeded") - summaryBefore).isEqualTo(1.0)
            assertThat(readMetric("list", "succeeded") - listBefore).isEqualTo(1.0)
        }

        @Test
        fun `signed cursor has no gap or duplicate and rejects another customer or tampering`() {
            val customerId = UUID.randomUUID()
            val ids =
                listOf(
                    insertItem(customerId, NOW.plusSeconds(2), "third"),
                    insertItem(customerId, NOW.plusSeconds(1), "second"),
                    insertItem(customerId, NOW, "first"),
                )
            val firstPage =
                mockMvc
                    .perform(get("/api/v1/me/notifications").param("limit", "2").with(customerJwt(customerId)))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.items.length()").value(2))
                    .andExpect(jsonPath("$.page.nextCursor").isString)
                    .andReturn()
            val firstJson = objectMapper.readTree(firstPage.response.contentAsString)
            val cursor = requireNotNull(firstJson.path("page").path("nextCursor").stringValue())
            val pageIds = mutableListOf<UUID>()
            firstJson.path("items").forEach { pageIds += UUID.fromString(it.path("notificationId").stringValue()) }

            val secondPage =
                mockMvc
                    .perform(
                        get("/api/v1/me/notifications")
                            .param("limit", "2")
                            .param("cursor", cursor)
                            .with(customerJwt(customerId)),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.page.nextCursor").doesNotExist())
                    .andReturn()
            objectMapper.readTree(secondPage.response.contentAsString).path("items").forEach {
                pageIds += UUID.fromString(it.path("notificationId").stringValue())
            }
            assertThat(pageIds).containsExactlyElementsOf(ids)

            mockMvc
                .perform(
                    get("/api/v1/me/notifications")
                        .param("cursor", cursor)
                        .with(customerJwt(UUID.randomUUID())),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            mockMvc
                .perform(
                    get("/api/v1/me/notifications")
                        .param("cursor", tamperSignedCursorSignature(cursor))
                        .with(customerJwt(customerId)),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }

        @Test
        fun `read command is owner scoped strict and idempotent`() {
            val customerId = UUID.randomUUID()
            val notificationId = insertItem(customerId, NOW, "read")
            val updatedBefore = commandMetric("mark_read", "updated")
            val replayedBefore = commandMetric("mark_read", "replayed")

            repeat(2) {
                mockMvc
                    .perform(
                        patch("/api/v1/me/notifications/{notificationId}", notificationId)
                            .with(customerJwt(customerId))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"read":true}"""),
                    ).andExpect(status().isNoContent)
            }
            val firstReadAt =
                jdbcTemplate.queryForObject(
                    "SELECT read_at FROM notification_inbox_item WHERE id = ?",
                    Timestamp::class.java,
                    notificationId,
                )
            assertThat(firstReadAt).isNotNull()
            mockMvc
                .perform(get("/api/v1/me/notification-summary").with(customerJwt(customerId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.hasUnread").value(false))

            mockMvc
                .perform(
                    patch("/api/v1/me/notifications/{notificationId}", notificationId)
                        .with(customerJwt(UUID.randomUUID()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"read":true}"""),
                ).andExpect(status().isNotFound)

            listOf("""{"read":false}""", "{}", """{"read":true,"unknown":1}""").forEach { body ->
                mockMvc
                    .perform(
                        patch("/api/v1/me/notifications/{notificationId}", notificationId)
                            .with(customerJwt(customerId))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body),
                    ).andExpect(status().isBadRequest)
            }
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT read_at FROM notification_inbox_item WHERE id = ?",
                    Timestamp::class.java,
                    notificationId,
                ),
            ).isEqualTo(firstReadAt)
            assertThat(commandMetric("mark_read", "updated") - updatedBefore).isEqualTo(1.0)
            assertThat(commandMetric("mark_read", "replayed") - replayedBefore).isEqualTo(1.0)
        }

        @Test
        fun `marketing preference defaults off and PUT fully replaces it`() {
            val customerId = UUID.randomUUID()

            mockMvc
                .perform(get("/api/v1/me/notification-preferences").with(customerJwt(customerId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.marketingOptIn").value(false))

            listOf(true, false).forEach { value ->
                mockMvc
                    .perform(
                        put("/api/v1/me/notification-preferences")
                            .with(customerJwt(customerId))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"marketingOptIn":$value}"""),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.marketingOptIn").value(value))
            }
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT version FROM notification_customer_preference WHERE customer_id = ?",
                    Long::class.java,
                    customerId,
                ),
            ).isEqualTo(1)
        }

        @Test
        fun `cleanup deletes at most one hundred items at the ninety day boundary`() {
            val customerId = UUID.randomUUID()
            repeat(101) { index -> insertItem(customerId, NOW.minus(90, ChronoUnit.DAYS).minusSeconds(index.toLong()), "due-$index") }
            val futureId = insertItem(customerId, NOW.minus(90, ChronoUnit.DAYS).plusNanos(1_000), "future")

            assertThat(inboxService.purgeExpired(NOW)).isEqualTo(100)
            assertThat(countItems()).isEqualTo(2)
            assertThat(inboxService.purgeExpired(NOW)).isEqualTo(1)
            assertThat(countItems()).isEqualTo(1)
            assertThat(jdbcTemplate.queryForObject("SELECT id FROM notification_inbox_item", UUID::class.java)).isEqualTo(futureId)
        }

        @Test
        fun `inbox or delivery insert failure rolls back both source rows`() {
            val createdBefore = inboxCreateMetric("TRANSACTIONAL", "created")
            listOf("notification_inbox_item", "notification_delivery").forEach { target ->
                installInsertFailure(target)
                val command =
                    RequestCustomerCancellationAcceptedNotificationCommand(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1,
                        NOW,
                        "atomic-$target",
                    )

                assertThatThrownBy { transactions.execute { deliveryService.requestAccepted(command) } }
                    .isInstanceOf(DataAccessException::class.java)
                assertThat(countItems()).isZero()
                assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification_delivery", Int::class.java)).isZero()
                dropFailureTriggers()
            }
            assertThat(inboxCreateMetric("TRANSACTIONAL", "created")).isEqualTo(createdBefore)
        }

        private fun insertItem(
            customerId: UUID,
            createdAt: Instant,
            suffix: String,
        ): UUID {
            val id = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO notification_inbox_item (
                    id, customer_id, logical_source, order_id, classification, template,
                    title, body, target_type, target_reference, read_at, created_at, retention_expires_at
                ) VALUES (?, ?, ?, ?, 'TRANSACTIONAL', 'ORDER_READY', ?, ?, 'NONE', NULL, NULL, ?, ?)
                """.trimIndent(),
                id,
                customerId,
                "test:$customerId:$suffix",
                UUID.randomUUID(),
                "알림 $suffix",
                "고객에게 안전하게 표시되는 본문입니다.",
                Timestamp.from(createdAt),
                Timestamp.from(createdAt.plus(90, ChronoUnit.DAYS)),
            )
            return id
        }

        private fun installInsertFailure(table: String) {
            jdbcTemplate.execute(
                """
                CREATE OR REPLACE FUNCTION fail_notification_inbox_test_insert() RETURNS trigger AS ${'$'}${'$'}
                BEGIN
                    RAISE EXCEPTION 'forced notification persistence failure';
                END;
                ${'$'}${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            )
            jdbcTemplate.execute(
                "CREATE TRIGGER fail_notification_inbox_test BEFORE INSERT ON $table " +
                    "FOR EACH ROW EXECUTE FUNCTION fail_notification_inbox_test_insert()",
            )
        }

        private fun dropFailureTriggers() {
            listOf("notification_inbox_item", "notification_delivery").forEach { table ->
                jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_notification_inbox_test ON $table")
            }
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_notification_inbox_test_insert()")
        }

        private fun countItems(): Int =
            requireNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM notification_inbox_item", Int::class.java))

        private fun readMetric(
            operation: String,
            outcome: String,
        ): Double =
            meterRegistry
                .find("beanflow.notification.inbox.read.count")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .counter()
                ?.count() ?: 0.0

        private fun commandMetric(
            operation: String,
            outcome: String,
        ): Double =
            meterRegistry
                .find("beanflow.notification.inbox.command.count")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .counter()
                ?.count() ?: 0.0

        private fun inboxCreateMetric(
            classification: String,
            outcome: String,
        ): Double =
            meterRegistry
                .find("beanflow.notification.inbox.create.count")
                .tag("classification", classification)
                .tag("outcome", outcome)
                .counter()
                ?.count() ?: 0.0

        private fun customerJwt(customerId: UUID) =
            jwt().jwt { it.subject(customerId.toString()) }.authorities(SimpleGrantedAuthority("ROLE_CUSTOMER"))

        private companion object {
            val NOW: Instant = Instant.parse("2026-08-26T04:00:00Z")
        }
    }

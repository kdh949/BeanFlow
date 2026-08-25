package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.ordering.internal.OrderCreationDatabaseFixture
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest(
    properties = [
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
    ],
)
internal class AuditRetentionPolicyIntegrationTest
    @Autowired
    constructor(
        private val operations: AuditRecordOperations,
        private val service: AuditRecordService,
        private val transactionTemplate: TransactionTemplate,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        @BeforeEach
        fun cleanDatabase() = OrderCreationDatabaseFixture.clean(jdbcTemplate)

        @AfterEach
        fun dropDeleteFailureTrigger() {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_audit_delete_failure ON operations_audit_record")
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_reject_audit_delete()")
        }

        @Test
        fun `financial and pii audits snapshot five year and two year policies`() {
            val occurredAt =
                ZonedDateTime.of(2024, 2, 29, 12, 0, 0, 0, ZoneId.of("Asia/Seoul")).toInstant()
            transactionTemplate.executeWithoutResult {
                operations.appendAll(
                    listOf(
                        command(AuditCategory.FINANCIAL_TRANSACTION, "PARTIAL_REFUND_REQUESTED", occurredAt),
                        command(AuditCategory.PII_ACCESS, "SUPPORT_PII_ACCESS_RECORDED", occurredAt),
                    ),
                )
            }

            val snapshots =
                jdbcTemplate.queryForList(
                    """
                    SELECT audit_category, retention_class, retention_policy_version_id, retention_expires_at
                      FROM operations_audit_record
                     ORDER BY audit_category
                    """.trimIndent(),
                )
            assertThat(snapshots.map { it["audit_category"] })
                .containsExactly("FINANCIAL_TRANSACTION", "PII_ACCESS")
            assertThat(snapshots.map { it["retention_class"] })
                .containsExactly("FINANCIAL_AUDIT", "PII_ACCESS_AUDIT")
            assertThat(snapshots.map { it["retention_policy_version_id"] }).doesNotContainNull()
            assertThat(snapshots.map { (it["retention_expires_at"] as java.sql.Timestamp).toInstant() })
                .containsExactly(
                    Instant.parse("2029-02-28T03:00:00Z"),
                    Instant.parse("2026-02-28T03:00:00Z"),
                )

            val piiExpiry = Instant.parse("2026-02-28T03:00:00Z")
            assertThat(service.purgeDue(piiExpiry.minusNanos(1_000), 10).deletedCount).isZero()
            assertThat(service.purgeDue(piiExpiry, 10).deletedCount).isOne()
            assertThat(service.purgeDue(piiExpiry.plusNanos(1_000), 10).deletedCount).isZero()
            assertThat(count()).isOne()
        }

        @Test
        fun `every audit category uses its policy expiry as the exact deletion boundary`() {
            val occurredAt =
                ZonedDateTime.of(2024, 2, 29, 12, 0, 0, 0, ZoneId.of("Asia/Seoul")).toInstant()
            val actions =
                mapOf(
                    AuditCategory.FINANCIAL_TRANSACTION to "PARTIAL_REFUND_REQUESTED",
                    AuditCategory.ORDER_AND_FULFILLMENT to "STOCK_RESERVED",
                    AuditCategory.SETTLEMENT_AND_DISPUTE to "SETTLEMENT_ADJUSTMENT_CREATED",
                    AuditCategory.SECURITY_AND_PERMISSION to "OPERATOR_PERMISSION_GRANTED",
                    AuditCategory.OPERATIONS_POLICY to "EXPIRED_BENEFIT_POLICY_READ",
                    AuditCategory.PII_ACCESS to "SUPPORT_PII_ACCESS_RECORDED",
                )

            actions.forEach { (category, action) ->
                transactionTemplate.executeWithoutResult {
                    operations.appendAll(listOf(command(category, action, occurredAt)))
                }
                val retentionYears = if (category == AuditCategory.PII_ACCESS) 2 else 5
                val expectedExpiry = occurredAt.atZone(ZoneId.of("Asia/Seoul")).plusYears(retentionYears.toLong()).toInstant()
                val storedExpiry =
                    jdbcTemplate
                        .queryForObject(
                            "SELECT retention_expires_at FROM operations_audit_record",
                            java.sql.Timestamp::class.java,
                        )?.toInstant()

                assertThat(storedExpiry).describedAs(category.name).isEqualTo(expectedExpiry)
                assertThat(service.purgeDue(expectedExpiry.minusNanos(1_000), 1).deletedCount)
                    .describedAs("%s before boundary", category)
                    .isZero()
                assertThat(service.purgeDue(expectedExpiry, 1).deletedCount)
                    .describedAs("%s at boundary", category)
                    .isOne()
                assertThat(count()).isZero()
            }
        }

        @Test
        fun `raw pii summary keys are rejected`() {
            listOf("email", "phoneNumber", "streetAddress", "fullName", "birthDate", "rawPii").forEach { key ->
                assertThatThrownBy {
                    transactionTemplate.executeWithoutResult {
                        operations.appendAll(
                            listOf(
                                command(
                                    AuditCategory.PII_ACCESS,
                                    "SUPPORT_PII_ACCESS_RECORDED",
                                    afterSummary = mapOf(key to "raw-value"),
                                ),
                            ),
                        )
                    }
                }.isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.INVALID_REQUEST)
                }
            }
            assertThat(count()).isZero()
        }

        @Test
        fun `missing policy head fails closed and rolls back the whole mixed category batch`() {
            assertThatThrownBy {
                transactionTemplate.executeWithoutResult {
                    jdbcTemplate.update("DELETE FROM operations_retention_policy_head WHERE category = 'PII_ACCESS'")
                    operations.appendAll(
                        listOf(
                            command(AuditCategory.FINANCIAL_TRANSACTION, "PARTIAL_REFUND_REQUESTED"),
                            command(AuditCategory.PII_ACCESS, "SUPPORT_PII_ACCESS_RECORDED"),
                        ),
                    )
                }
            }.isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
            }

            assertThat(count()).isZero()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM operations_retention_policy_head WHERE category = 'PII_ACCESS'",
                    Long::class.java,
                ),
            ).isOne()
        }

        @Test
        fun `invalid policy shape fails closed before an audit row is inserted`() {
            assertThatThrownBy {
                transactionTemplate.executeWithoutResult {
                    jdbcTemplate.execute(
                        "ALTER TABLE operations_retention_policy_head " +
                            "DROP CONSTRAINT fk_retention_policy_head_version_category",
                    )
                    jdbcTemplate.update(
                        "UPDATE operations_retention_policy_head SET policy_version_id = 1 WHERE category = 'PII_ACCESS'",
                    )
                    operations.appendAll(
                        listOf(command(AuditCategory.PII_ACCESS, "SUPPORT_PII_ACCESS_RECORDED")),
                    )
                }
            }.isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
            }

            assertThat(count()).isZero()
        }

        @Test
        fun `unknown action and category mismatch reject the complete append batch`() {
            assertThatThrownBy {
                transactionTemplate.executeWithoutResult {
                    operations.appendAll(
                        listOf(
                            command(AuditCategory.FINANCIAL_TRANSACTION, "PARTIAL_REFUND_REQUESTED"),
                            command(AuditCategory.PII_ACCESS, "STOCK_RESERVED"),
                        ),
                    )
                }
            }.isInstanceOf(DataIntegrityViolationException::class.java)

            assertThat(count()).isZero()
        }

        @Test
        fun `retention worker failure keeps due rows for an explicit retry`() {
            transactionTemplate.executeWithoutResult {
                operations.appendAll(
                    listOf(
                        command(
                            AuditCategory.FINANCIAL_TRANSACTION,
                            "PARTIAL_REFUND_REQUESTED",
                            Instant.parse("2010-01-01T00:00:00Z"),
                        ),
                    ),
                )
            }
            jdbcTemplate.execute(
                """
                CREATE FUNCTION test_reject_audit_delete() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN
                    RAISE EXCEPTION 'test audit delete failure';
                END;
                ${'$'}${'$'}
                """.trimIndent(),
            )
            jdbcTemplate.execute(
                "CREATE TRIGGER test_audit_delete_failure BEFORE DELETE ON operations_audit_record " +
                    "FOR EACH ROW EXECUTE FUNCTION test_reject_audit_delete()",
            )

            assertThatThrownBy { service.purgeDue(Instant.parse("2026-01-01T00:00:00Z"), 10) }
                .isInstanceOf(RuntimeException::class.java)
            assertThat(count()).isOne()

            dropDeleteFailureTrigger()
            assertThat(service.purgeDue(Instant.parse("2026-01-01T00:00:00Z"), 10).deletedCount).isOne()
            assertThat(count()).isZero()
        }

        private fun command(
            category: AuditCategory,
            action: String,
            occurredAt: Instant = Instant.parse("2026-08-01T00:00:00Z"),
            afterSummary: Map<String, String> = mapOf("state" to "RECORDED"),
        ) = AppendAuditRecordCommand(
            actorId = UUID.randomUUID().toString(),
            actorType = AuditActorType.SYSTEM,
            category = category,
            action = action,
            targetType = "S10_TEST",
            targetId = UUID.randomUUID(),
            occurredAt = occurredAt,
            reason = "S10_TEST",
            beforeSummary = emptyMap(),
            afterSummary = afterSummary,
            correlationId = UUID.randomUUID().toString(),
            sourceReference = "s10:${UUID.randomUUID()}",
        )

        private fun count(): Long = jdbcTemplate.queryForObject("SELECT count(*) FROM operations_audit_record", Long::class.java)!!
    }

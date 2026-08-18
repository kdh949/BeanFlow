package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.operations.api.OperatorPermission
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
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("uses a database failure trigger that aborts the current transaction")
@SpringBootTest(
    properties = [
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
    ],
)
internal class BenefitPolicyIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val grantLifecycle: OperatorPermissionGrantLifecycle,
        private val meterRegistry: MeterRegistry,
    ) {
        private val actorId = UUID.fromString("10000000-0000-0000-0000-000000000001")

        @BeforeEach
        fun cleanSecurityState() {
            dropAuditFailureTrigger()
            jdbcTemplate.execute("TRUNCATE TABLE operations_operator_permission_grant, operations_audit_record")
            jdbcTemplate.execute(
                """
                UPDATE operations_expired_benefit_policy_head head
                   SET policy_version = seed.policy_version,
                       version = 0
                  FROM (
                      SELECT trigger, benefit_type, min(policy_version) AS policy_version
                        FROM operations_expired_benefit_policy_version
                       GROUP BY trigger, benefit_type
                  ) seed
                 WHERE seed.trigger = head.trigger
                   AND seed.benefit_type = head.benefit_type
                """.trimIndent(),
            )
        }

        @AfterEach
        fun cleanupFailureTrigger() = dropAuditFailureTrigger()

        @Test
        fun `empty database migration seeds exactly five allowed immutable policy heads`() {
            val heads =
                jdbcTemplate.queryForList(
                    """
                    SELECT head.trigger, head.benefit_type, version.mode, version.compensation_validity_days
                      FROM operations_expired_benefit_policy_head head
                      JOIN operations_expired_benefit_policy_version version
                        ON version.policy_version = head.policy_version
                       AND version.trigger = head.trigger
                       AND version.benefit_type = head.benefit_type
                     ORDER BY head.trigger, head.benefit_type
                    """.trimIndent(),
                )

            assertThat(heads).hasSize(5)
            assertThat(heads.map { it["trigger"] to it["benefit_type"] })
                .containsExactly(
                    "CUSTOMER_CANCELLATION" to "COUPON",
                    "CUSTOMER_CANCELLATION" to "POINTS",
                    "PARTIAL_REFUND" to "POINTS",
                    "STORE_REJECTION" to "COUPON",
                    "STORE_REJECTION" to "POINTS",
                )
            assertThat(
                heads.single { it["trigger"] == "PARTIAL_REFUND" }["mode"],
            ).isEqualTo("COMPENSATE_WITH_NEW_ISSUANCE")
            assertThat(
                heads.single { it["trigger"] == "PARTIAL_REFUND" }["compensation_validity_days"],
            ).isEqualTo(30)
            assertThat(
                heads.filter { it["trigger"] == "CUSTOMER_CANCELLATION" }.map { it["mode"] },
            ).containsOnly("PRESERVE_ORIGINAL_EXPIRY")
            assertThat(
                heads
                    .filter { it["trigger"] == "CUSTOMER_CANCELLATION" }
                    .map { it["compensation_validity_days"] },
            ).containsOnly(30)
            assertThat(
                heads.filter { it["trigger"] == "STORE_REJECTION" }.map { it["mode"] },
            ).containsOnly("COMPENSATE_WITH_NEW_ISSUANCE")

            val version = currentVersion("STORE_REJECTION", "COUPON")
            assertThatThrownBy {
                jdbcTemplate.update(
                    "UPDATE operations_expired_benefit_policy_version SET reason = 'MUTATED' WHERE policy_version = ?",
                    version,
                )
            }.isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy {
                jdbcTemplate.update(
                    "DELETE FROM operations_expired_benefit_policy_version WHERE policy_version = ?",
                    version,
                )
            }.isInstanceOf(DataAccessException::class.java)
        }

        @Test
        fun `forbidden partial refund coupon key is rejected by DB and API without version or audit`() {
            grant(OperatorPermission.EXPIRED_BENEFIT_POLICY_WRITE)
            val versionsBefore = count("operations_expired_benefit_policy_version")
            val auditsBefore = count("operations_audit_record")

            assertThatThrownBy {
                jdbcTemplate.update(
                    """
                    INSERT INTO operations_expired_benefit_policy_version (
                        trigger, benefit_type, mode, compensation_validity_days,
                        effective_at, updated_by, reason
                    ) VALUES ('PARTIAL_REFUND', 'COUPON', 'PRESERVE_ORIGINAL_EXPIRY', 30, now(), ?, 'FORBIDDEN')
                    """.trimIndent(),
                    actorId,
                )
            }.isInstanceOf(DataIntegrityViolationException::class.java)

            mockMvc
                .perform(
                    patch("/api/v1/operations/policies/expired-benefit-restoration/PARTIAL_REFUND/COUPON")
                        .with(operatorJwt(actorId))
                        .header("Idempotency-Key", "forbidden-key-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                              "expectedPolicyVersionId": 1,
                              "mode": "PRESERVE_ORIGINAL_EXPIRY",
                              "compensationValidityDays": 30,
                              "reason": "No coupon restoration"
                            }
                            """.trimIndent(),
                        ),
                ).andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

            assertThat(count("operations_expired_benefit_policy_version")).isEqualTo(versionsBefore)
            assertThat(count("operations_audit_record")).isEqualTo(auditsBefore)
        }

        @Test
        fun `keyed patch appends one version replays it and rejects stale CAS`() {
            grant(OperatorPermission.EXPIRED_BENEFIT_POLICY_WRITE)
            val expected = currentVersion("CUSTOMER_CANCELLATION", "COUPON")
            val key = "policy-cas-replay-${UUID.randomUUID()}"

            val first =
                patchPolicy("CUSTOMER_CANCELLATION", "COUPON", key, expected, "Future cancellation policy")
                    .andExpect(status().isOk)
                    .andReturn()
            val firstVersion =
                tools.jackson.databind.json.JsonMapper
                    .builder()
                    .build()
                    .readTree(first.response.contentAsString)["policyVersionId"]
                    .longValue()

            patchPolicy("CUSTOMER_CANCELLATION", "COUPON", key, expected, "Future cancellation policy")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.policyVersionId").value(firstVersion))

            patchPolicy(
                "CUSTOMER_CANCELLATION",
                "COUPON",
                "stale-cas-${UUID.randomUUID()}",
                expected,
                "Stale request",
            ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"))

            assertThat(currentVersion("CUSTOMER_CANCELLATION", "COUPON")).isEqualTo(firstVersion)
            assertThat(
                queryCount(
                    "SELECT count(*) FROM operations_expired_benefit_policy_version WHERE idempotency_key = ?",
                    key,
                ),
            ).isEqualTo(1)
            assertThat(
                queryCount(
                    "SELECT count(*) FROM operations_audit_record WHERE action = 'EXPIRED_BENEFIT_POLICY_CHANGED'",
                ),
            ).isEqualTo(1)
        }

        @Test
        fun `GET reason and access audit are an atomic commit gate`() {
            grant(OperatorPermission.EXPIRED_BENEFIT_POLICY_READ)

            mockMvc
                .perform(get("/api/v1/operations/policies/expired-benefit-restoration").with(operatorJwt(actorId)))
                .andExpect(status().isBadRequest)
            mockMvc
                .perform(
                    get("/api/v1/operations/policies/expired-benefit-restoration")
                        .with(operatorJwt(actorId))
                        .header("X-Access-Reason", "   "),
                ).andExpect(status().isBadRequest)
            assertThat(readAuditCount()).isZero()

            mockMvc
                .perform(
                    get("/api/v1/operations/policies/expired-benefit-restoration")
                        .with(operatorJwt(actorId))
                        .header("X-Access-Reason", " Support review "),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(5))
            assertThat(readAuditCount()).isEqualTo(1)

            installAuditFailureTrigger("EXPIRED_BENEFIT_POLICY_READ")
            mockMvc
                .perform(
                    get("/api/v1/operations/policies/expired-benefit-restoration")
                        .with(operatorJwt(actorId))
                        .header("X-Access-Reason", "Persistence failure proof"),
                ).andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
            assertThat(readAuditCount()).isEqualTo(1)
        }

        @Test
        fun `audit persistence failure rolls back policy version and head`() {
            grant(OperatorPermission.EXPIRED_BENEFIT_POLICY_WRITE)
            val expected = currentVersion("STORE_REJECTION", "POINTS")
            val versionsBefore = count("operations_expired_benefit_policy_version")
            installAuditFailureTrigger("EXPIRED_BENEFIT_POLICY_CHANGED")

            patchPolicy(
                "STORE_REJECTION",
                "POINTS",
                "rollback-${UUID.randomUUID()}",
                expected,
                "Rollback on audit failure",
            ).andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))

            assertThat(currentVersion("STORE_REJECTION", "POINTS")).isEqualTo(expected)
            assertThat(count("operations_expired_benefit_policy_version")).isEqualTo(versionsBefore)
        }

        @Test
        fun `policy and grant metrics expose closed non-sensitive tags only`() {
            grant(OperatorPermission.EXPIRED_BENEFIT_POLICY_READ)
            mockMvc
                .perform(
                    get("/api/v1/operations/policies/expired-benefit-restoration")
                        .with(operatorJwt(actorId))
                        .header("X-Access-Reason", "Metric contract proof"),
                ).andExpect(status().isOk)

            grant(OperatorPermission.EXPIRED_BENEFIT_POLICY_WRITE)
            val expected = currentVersion("PARTIAL_REFUND", "POINTS")
            patchPolicy(
                "PARTIAL_REFUND",
                "POINTS",
                "metric-contract-${UUID.randomUUID()}",
                expected,
                "Metric contract proof",
            ).andExpect(status().isOk)

            assertTagKeys("beanflow.operations.permission.authorization", "permission", "outcome")
            assertTagKeys("beanflow.operations.policy.read.count", "outcome")
            assertTagKeys(
                "beanflow.operations.benefit_policy.change.count",
                "trigger",
                "benefit_type",
                "mode",
                "outcome",
            )
            assertTagKeys("beanflow.operations.permission.grant.revoke.count", "permission", "outcome")
            assertTagKeys("beanflow.operations.permission.bootstrap.count", "action", "outcome")
        }

        private fun patchPolicy(
            trigger: String,
            benefitType: String,
            key: String,
            expectedVersion: Long,
            reason: String,
        ) = mockMvc.perform(
            patch("/api/v1/operations/policies/expired-benefit-restoration/{trigger}/{benefitType}", trigger, benefitType)
                .with(operatorJwt(actorId))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "expectedPolicyVersionId": $expectedVersion,
                      "mode": "COMPENSATE_WITH_NEW_ISSUANCE",
                      "compensationValidityDays": 45,
                      "reason": "$reason"
                    }
                    """.trimIndent(),
                ),
        )

        private fun grant(permission: OperatorPermission) {
            val result =
                grantLifecycle.apply(
                    OperatorPermissionBootstrapCommand(
                        action = OperatorPermissionBootstrapAction.GRANT,
                        actorId = actorId,
                        permission = permission,
                        reason = "Test grant",
                        evidenceReference = "test-evidence",
                        correlationId = UUID.randomUUID().toString(),
                        now = Instant.parse("2026-08-01T00:00:00Z"),
                    ),
                    VerifiedReleasePrincipal("issuer=test|subject=release|audience=test|deploymentRun=run-1"),
                )
            assertThat(result).isEqualTo(OperatorPermissionBootstrapResult.APPLIED)
        }

        private fun operatorJwt(actor: UUID) =
            jwt()
                .jwt {
                    it
                        .subject(actor.toString())
                        .claim("roles", listOf("PLATFORM_OPERATOR"))
                }.authorities(SimpleGrantedAuthority("ROLE_PLATFORM_OPERATOR"))

        private fun currentVersion(
            trigger: String,
            benefitType: String,
        ): Long =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    """
                    SELECT policy_version
                      FROM operations_expired_benefit_policy_head
                     WHERE trigger = ? AND benefit_type = ?
                    """.trimIndent(),
                    Long::class.java,
                    trigger,
                    benefitType,
                ),
            )

        private fun count(table: String): Long = queryCount("SELECT count(*) FROM $table")

        private fun queryCount(
            sql: String,
            vararg args: Any,
        ): Long = requireNotNull(jdbcTemplate.queryForObject(sql, Long::class.java, *args))

        private fun readAuditCount(): Long =
            queryCount("SELECT count(*) FROM operations_audit_record WHERE action = 'EXPIRED_BENEFIT_POLICY_READ'")

        private fun assertTagKeys(
            metricName: String,
            vararg expectedKeys: String,
        ) {
            val meters = meterRegistry.meters.filter { it.id.name == metricName }
            assertThat(meters).isNotEmpty()
            assertThat(
                meters
                    .map { meter ->
                        meter.id.tags
                            .map { it.key }
                            .toSet()
                    }.toSet(),
            ).containsOnly(expectedKeys.toSet())
        }

        private fun installAuditFailureTrigger(action: String) {
            jdbcTemplate.execute(
                """
                CREATE OR REPLACE FUNCTION test_reject_operations_audit()
                RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN
                    IF NEW.action = '$action' THEN
                        RAISE EXCEPTION 'injected audit persistence failure';
                    END IF;
                    RETURN NEW;
                END
                ${'$'}${'$'}
                """.trimIndent(),
            )
            jdbcTemplate.execute(
                """
                CREATE TRIGGER test_reject_operations_audit
                BEFORE INSERT ON operations_audit_record
                FOR EACH ROW EXECUTE FUNCTION test_reject_operations_audit()
                """.trimIndent(),
            )
        }

        private fun dropAuditFailureTrigger() {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_reject_operations_audit ON operations_audit_record")
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_reject_operations_audit()")
        }
    }

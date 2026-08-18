package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualExpiryRule
import io.github.kdh949.beanflow.operations.api.PointAccrualIssuerType
import io.github.kdh949.beanflow.operations.api.PointAccrualRoundingMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest
internal class OrdinaryPointAccrualPolicyBootstrapTest
    @Autowired
    constructor(
        private val lifecycle: OrdinaryPointAccrualPolicyBootstrapLifecycle,
        private val headRepository: OrdinaryPointAccrualPolicyHeadJpaRepository,
        private val versionRepository: OrdinaryPointAccrualPolicyVersionJpaRepository,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        @BeforeEach
        fun removeInitialState() {
            dropAuditFailureTrigger()
            headRepository.deleteAllInBatch()
            jdbcTemplate.update("DELETE FROM operations_audit_record WHERE action = 'POINT_ACCRUAL_POLICY_BOOTSTRAPPED'")
        }

        @AfterEach
        fun cleanupFailureTrigger() = dropAuditFailureTrigger()

        @Test
        fun `verified first bootstrap atomically creates one global version head and audit`() {
            val command = command()

            val first = lifecycle.apply(command, VerifiedReleasePrincipal("issuer=test|subject=release|run=42"))
            val replay = lifecycle.apply(command, VerifiedReleasePrincipal("issuer=test|subject=release|run=42"))

            assertThat(first).isEqualTo(OrdinaryPointAccrualPolicyBootstrapResult.APPLIED)
            assertThat(replay).isEqualTo(OrdinaryPointAccrualPolicyBootstrapResult.POLICY_ALREADY_INITIALIZED)
            val head = headRepository.findAll().single()
            val version = versionRepository.findById(head.policyVersionId).orElseThrow()
            assertThat(version.accrualRateBps).isEqualTo(250)
            assertThat(version.issuerReference).isEqualTo("platform:verified")
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM operations_audit_record WHERE action = 'POINT_ACCRUAL_POLICY_BOOTSTRAPPED'",
                    Long::class.java,
                ),
            ).isOne()
        }

        @Test
        fun `invalid command and audit failure leave no global head or partial version`() {
            val principal = VerifiedReleasePrincipal("issuer=test|subject=release|run=43")
            val versionsBefore = versionRepository.count()

            assertThat(lifecycle.apply(command(issuerReference = " "), principal))
                .isEqualTo(OrdinaryPointAccrualPolicyBootstrapResult.INVALID_INPUT)
            assertThat(headRepository.count()).isZero()

            installAuditFailureTrigger()
            assertThat(lifecycle.apply(command(), principal))
                .isEqualTo(OrdinaryPointAccrualPolicyBootstrapResult.DEPENDENCY_UNAVAILABLE)
            assertThat(headRepository.count()).isZero()
            assertThat(versionRepository.count()).isEqualTo(versionsBefore)
        }

        @Test
        fun `CLI rejects unverifiable identity without invoking apply or exposing policy inputs`() {
            val standardOut = ByteArrayOutputStream()
            val standardError = ByteArrayOutputStream()
            var invoked = false

            val exit =
                OrdinaryPointAccrualPolicyBootstrapCli.execute(
                    arguments = emptyArray(),
                    environment = bootstrapEnvironment(),
                    standardOut = PrintStream(standardOut),
                    standardError = PrintStream(standardError),
                    apply = { _, _ ->
                        invoked = true
                        OrdinaryPointAccrualPolicyBootstrapResult.APPLIED
                    },
                )

            assertThat(exit).isEqualTo(3)
            assertThat(invoked).isFalse()
            assertThat(standardOut.toString()).isEmpty()
            assertThat(standardError.toString())
                .contains("result=IDENTITY_VERIFICATION_FAILED")
                .doesNotContain("platform:secret", "250", "secret reason", "/missing/token")
        }

        private fun command(issuerReference: String = "platform:verified") =
            OrdinaryPointAccrualPolicyBootstrapCommand(
                accrualRateBps = 250,
                roundingMode = PointAccrualRoundingMode.HALF_UP,
                issuerType = PointAccrualIssuerType.PLATFORM,
                issuerReference = issuerReference,
                expiryRule = OrdinaryPointAccrualExpiryRule.EXACT_DURATION_FROM_COMPLETION,
                validityDays = 365,
                reason = "Verified initial ordinary accrual policy",
                evidenceReference = "release-evidence:42",
                correlationId = "release-run:42",
                now = Instant.parse("2026-08-01T00:00:00Z"),
            )

        private fun bootstrapEnvironment(): Map<String, String> =
            mapOf(
                "BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_RATE_BPS" to "250",
                "BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_ROUNDING_MODE" to "HALF_UP",
                "BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_ISSUER_TYPE" to "PLATFORM",
                "BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_ISSUER_REFERENCE" to "platform:secret",
                "BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_EXPIRY_RULE" to "EXACT_DURATION_FROM_COMPLETION",
                "BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_VALIDITY_DAYS" to "365",
                "BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_REASON" to "secret reason",
                "BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_EVIDENCE_REFERENCE" to "secret evidence",
                "BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_CORRELATION_ID" to "release-run:43",
                "BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_TOKEN_FILE" to "/missing/token",
                "BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_JWK_SET_FILE" to "/missing/jwks",
                "BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_ISSUER" to "https://issuer.example",
                "BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_AUDIENCE" to "beanflow-bootstrap",
                "BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_ALLOWED_SUBJECTS" to "release-workload",
                "BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_DEPLOYMENT_RUN_CLAIM" to "deployment_run",
            )

        private fun installAuditFailureTrigger() {
            jdbcTemplate.execute(
                """
                CREATE OR REPLACE FUNCTION fail_point_accrual_bootstrap_audit() RETURNS trigger AS ${'$'}${'$'}
                BEGIN
                    IF NEW.action = 'POINT_ACCRUAL_POLICY_BOOTSTRAPPED' THEN
                        RAISE EXCEPTION 'forced point accrual bootstrap audit failure';
                    END IF;
                    RETURN NEW;
                END;
                ${'$'}${'$'} LANGUAGE plpgsql;
                CREATE TRIGGER fail_point_accrual_bootstrap_audit
                    BEFORE INSERT ON operations_audit_record
                    FOR EACH ROW EXECUTE FUNCTION fail_point_accrual_bootstrap_audit();
                """.trimIndent(),
            )
        }

        private fun dropAuditFailureTrigger() {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_point_accrual_bootstrap_audit ON operations_audit_record")
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_point_accrual_bootstrap_audit()")
        }
    }
